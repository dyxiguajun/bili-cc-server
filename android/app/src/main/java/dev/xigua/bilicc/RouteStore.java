package dev.xigua.bilicc;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.InetAddresses;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RouteStore {
    static final int MAX_ROUTES = 6;

    static final class Route {
        final String name;
        final HttpUrl url;
        final InetAddress address;

        Route(String name, String url, String ip) {
            this.name = name.trim();
            this.url = HttpUrl.get(url.trim());
            if (this.name.isEmpty() || this.name.length() > 40 || !this.url.isHttps()
                    || !this.url.encodedPath().equals("/") || this.url.query() != null
                    || this.url.fragment() != null || !this.url.username().isEmpty()
                    || !this.url.password().isEmpty()) {
                throw new IllegalArgumentException("需要名称和 HTTPS 根地址。");
            }
            this.address = ip.trim().isEmpty() ? null : InetAddresses.parseNumericAddress(ip.trim());
        }

        String ip() {
            return address == null ? "" : address.getHostAddress();
        }
    }

    private final Context context;
    private final SharedPreferences settings;

    RouteStore(Context context) {
        this.context = context;
        settings = context.getSharedPreferences("connection_settings", Context.MODE_PRIVATE);
    }

    List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        String json = settings.getString("routes", null);
        try {
            if (json == null) {
                try (BufferedReader asset = new BufferedReader(new InputStreamReader(
                        context.getAssets().open("routes.json"), StandardCharsets.UTF_8))) {
                    StringBuilder contents = new StringBuilder();
                    String line;
                    while ((line = asset.readLine()) != null) contents.append(line);
                    json = contents.toString();
                }
            }
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < Math.min(list.length(), MAX_ROUTES); i++) {
                JSONObject row = list.getJSONObject(i);
                routes.add(new Route(row.getString("name"), row.getString("url"), row.optString("ip")));
            }
        } catch (Exception invalid) {
            routes.clear();
        }
        return routes;
    }

    boolean backgroundShare() {
        return settings.getBoolean("background_share", true);
    }

    boolean save(List<Route> routes, boolean backgroundShare) throws JSONException {
        JSONArray list = new JSONArray();
        for (Route route : routes) {
            list.put(new JSONObject().put("name", route.name).put("url", route.url.toString()).put("ip", route.ip()));
        }
        return settings.edit().putString("routes", list.toString())
                .putBoolean("background_share", backgroundShare).commit();
    }
}
