package dev.xigua.bilicc;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

final class BiliClient {
    static final long ACTION_TIMEOUT_MS = 29_000;
    private static final int CONNECTION_PROBE_MS = 2_600;
    private static final int MAX_RESPONSE_BYTES = 9 * 1024 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    static final class Failure extends Exception {
        final String code;

        Failure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    static final class Reply {
        final RouteStore.Route route;
        final JSONObject data;

        Reply(RouteStore.Route route, JSONObject data) {
            this.route = route;
            this.data = data;
        }
    }

    private final OkHttpClient base;

    BiliClient(CookieJar sessions) {
        base = new OkHttpClient.Builder()
                .cookieJar(sessions)
                .connectTimeout(1_900, TimeUnit.MILLISECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .readTimeout(23, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .proxy(Proxy.NO_PROXY)
                .build();
    }

    Reply extract(List<RouteStore.Route> routes, String video, String track) throws Failure, JSONException {
        JSONObject body = new JSONObject().put("url", video);
        if (track != null) body.put("track", track);
        return call(routes, "/api/extract", body);
    }

    Reply extractChinese(List<RouteStore.Route> routes, String video) throws Failure, JSONException {
        return call(routes, "/api/extract", new JSONObject().put("url", video).put("language", "zh"));
    }

    Reply call(List<RouteStore.Route> routes, String path, JSONObject post) throws Failure {
        if (routes.isEmpty()) throw new Failure("no_routes", "请先设置一个 NAS 连接。");
        long deadline = SystemClock.elapsedRealtime() + ACTION_TIMEOUT_MS;
        boolean tlsError = false;
        boolean incompatible = false;
        for (RouteStore.Route route : routes) {
            if (Thread.currentThread().isInterrupted() || deadline <= SystemClock.elapsedRealtime()) break;
            OkHttpClient client = route.address == null ? base : base.newBuilder()
                    .dns(host -> host.equals(route.url.host()) ? Collections.singletonList(route.address) : base.dns().lookup(host))
                    .build();
            try {
                JSONObject health = request(client, route, "/healthz", null, deadline, CONNECTION_PROBE_MS);
                if (!"ok".equals(health.optString("status")) || health.optString("version").isEmpty()) {
                    incompatible = true;
                    continue;
                }
                JSONObject data = path.equals("/healthz") ? health
                        : request(client, route, path, post, deadline, ACTION_TIMEOUT_MS);
                return new Reply(route, data);
            } catch (SSLException rejected) {
                tlsError = true;
            } catch (JSONException invalid) {
                incompatible = true;
            } catch (IOException unreachable) {
                // A configured route is unreachable. Try the next HTTPS route.
            }
        }
        if (tlsError) throw new Failure("tls", "无法校验服务证书。请核对 HTTPS 域名和连接 IP。");
        if (incompatible) throw new Failure("server", "连接地址未返回 Bili CC 服务响应。");
        throw new Failure("network", "连不上 NAS。请检查局域网，或连接 Tailscale 后重试。");
    }

    static List<RouteStore.Route> routesForHost(RouteStore.Route first, List<RouteStore.Route> available) {
        List<RouteStore.Route> result = new ArrayList<>();
        result.add(first);
        for (RouteStore.Route route : available) {
            if (route.url.host().equals(first.url.host())
                    && !(route.url.equals(first.url) && route.ip().equals(first.ip()))) result.add(route);
        }
        return result;
    }

    private JSONObject request(OkHttpClient client, RouteStore.Route route, String path, JSONObject post,
                               long deadline, long maxMillis) throws IOException, JSONException, Failure {
        long timeLeft = Math.min(maxMillis, deadline - SystemClock.elapsedRealtime());
        if (timeLeft <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
        Request.Builder request = new Request.Builder().url(route.url.resolve(path))
                .header("Accept", "application/json").header("X-Bili-Client", "web");
        if (post != null) request.post(RequestBody.create(post.toString(), JSON));
        Call call = client.newCall(request.build());
        call.timeout().timeout(timeLeft, TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            MediaType type = response.body().contentType();
            if (type == null || !type.subtype().equals("json")) {
                if (response.code() >= 500) throw new IOException("Proxy unavailable");
                throw new JSONException("Unexpected response type");
            }
            JSONObject data;
            try (InputStream input = response.body().byteStream()) {
                int maxSize = path.equals("/api/extract") ? MAX_RESPONSE_BYTES : 262_144;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] chunk = new byte[8_192];
                int size;
                while ((size = input.read(chunk)) != -1) {
                    if (bytes.size() + size > maxSize) throw new Failure("result_too_large", "服务器返回的数据过大。");
                    bytes.write(chunk, 0, size);
                }
                data = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            }
            if (!response.isSuccessful()) {
                if (path.equals("/healthz")) throw new IOException("Service not healthy");
                String code = data.optString("status", "server");
                String message = data.optString("message", "字幕服务暂时不可用。");
                throw new Failure(code, message.substring(0, Math.min(300, message.length())));
            }
            return data;
        }
    }
}
