package dev.xigua.bilicc;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.json.JSONObject;

public final class LoginActivity extends Screen {
    private static final String PNG = "data:image/png;base64,";
    private TextView status;
    private ImageView image;
    private LinearLayout actions;
    private Button refresh;
    private Button logout;
    private List<RouteStore.Route> loginRoutes;
    private byte[] qr;
    private Uri official;
    private long deadline;
    private final Runnable poll = this::poll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = Ui.page(this);
        LinearLayout toolbar = Ui.row(this);
        toolbar.addView(Ui.quiet(this, "返回", view -> finish()));
        Ui.add(page, toolbar, 0);
        Ui.add(page, Ui.heading(this, "B 站登录", 30), 16);
        LinearLayout panel = Ui.panel(this);
        Ui.add(panel, Ui.text(this, "用哔哩哔哩 App 扫码并确认。只有一台手机时，可保存二维码，再从 B 站扫一扫的相册选项读取。", 15, Ui.MUTED, false), 0);

        image = new ImageView(this);
        image.setContentDescription("哔哩哔哩登录二维码");
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int available = getResources().getConfiguration().screenWidthDp - 72;
        int qrSize = Ui.dp(this, Math.min(224, Math.max(0, available)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(qrSize, qrSize);
        size.gravity = Gravity.CENTER_HORIZONTAL;
        size.topMargin = Ui.dp(this, 12);
        panel.addView(image, size);
        image.setVisibility(View.GONE);
        status = Ui.text(this, "正在检查登录状态…", 15, Ui.TEXT, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.add(panel, status, 9);

        actions = Ui.column(this);
        Ui.add(actions, Ui.button(this, "保存二维码到相册", true, view -> saveImage()), 0);
        Ui.add(actions, Ui.quiet(this, "打开 B 站登录页", view -> openOfficial()), 5);
        Ui.add(panel, actions, 8);
        actions.setVisibility(View.GONE);
        refresh = Ui.button(this, "刷新二维码", false, view -> startLogin());
        Ui.add(panel, refresh, 8);
        refresh.setVisibility(View.GONE);
        logout = Ui.button(this, "退出本机登录", false, view -> logout());
        Ui.add(panel, logout, 8);
        logout.setVisibility(View.GONE);
        Ui.add(page, panel, 16);

        runTask(() -> app().api.call(app().settings.routes(), "/api/session", null), reply -> {
            loginRoutes = BiliClient.routesForHost(reply.route, app().settings.routes());
            if (reply.data.optBoolean("signed_in")) signedIn(reply.route.name);
            else startLogin();
        }, this::showError);
    }

    private void startLogin() {
        ui.removeCallbacks(poll);
        displayQr(false);
        status.setTextColor(getColor(Ui.TEXT));
        status.setText("正在生成登录二维码…");
        refresh.setVisibility(View.GONE);
        logout.setVisibility(View.GONE);
        runTask(() -> app().api.call(loginRoutes == null ? app().settings.routes() : loginRoutes,
                "/api/login/start", new JSONObject()), reply -> {
            try {
                String src = reply.data.getString("image");
                if (!src.startsWith(PNG)) throw new IOException("Missing PNG");
                qr = Base64.decode(src.substring(PNG.length()), Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(qr, 0, qr.length);
                if (bitmap == null) throw new IOException("Invalid PNG");
                image.setImageBitmap(bitmap);
                official = Uri.parse(reply.data.getString("url"));
                deadline = SystemClock.elapsedRealtime() + 1000L * Math.max(10, Math.min(180, reply.data.optInt("expires_in")));
                loginRoutes = BiliClient.routesForHost(reply.route, app().settings.routes());
                status.setText("等待扫码 · " + reply.route.name);
                displayQr(true);
                ui.postDelayed(poll, 2400);
            } catch (Exception badReply) {
                showError(new BiliClient.Failure("qr", "无法读取登录二维码，请刷新。"));
            }
        }, this::showError);
    }

    private void poll() {
        if (SystemClock.elapsedRealtime() >= deadline) {
            showError(new BiliClient.Failure("expired", "二维码已过期，请刷新。"));
            return;
        }
        runTask(() -> app().api.call(loginRoutes, "/api/login/poll", new JSONObject()), reply -> {
            String state = reply.data.optString("status");
            if ("signed_in".equals(state)) {
                setResult(RESULT_OK);
                Ui.toast(this, "B 站登录成功");
                finish();
                return;
            }
            if ("expired".equals(state)) {
                showError(new BiliClient.Failure("expired", "二维码已过期，请刷新。"));
                return;
            }
            if ("waiting_confirm".equals(state)) status.setText("已扫码，请在哔哩哔哩 App 内确认。");
            if ("waiting_scan".equals(state)) status.setText("等待扫码 · " + reply.route.name);
            ui.postDelayed(poll, 2400);
        }, error -> {
            if (error instanceof BiliClient.Failure && "login_response".equals(((BiliClient.Failure) error).code)) {
                showError(error);
                return;
            }
            status.setText("暂时无法检查登录状态，正在重试。");
            ui.postDelayed(poll, 4800);
        });
    }

    private void signedIn(String route) {
        displayQr(false);
        status.setTextColor(getColor(Ui.SUCCESS));
        status.setText("已登录 B 站 · " + route);
        logout.setVisibility(View.VISIBLE);
    }

    private void logout() {
        logout.setEnabled(false);
        runTask(() -> app().api.call(loginRoutes, "/api/logout", new JSONObject()), reply -> {
            logout.setEnabled(true);
            logout.setVisibility(View.GONE);
            status.setTextColor(getColor(Ui.MUTED));
            status.setText("本机已退出登录。");
            refresh.setText("重新登录");
            refresh.setVisibility(View.VISIBLE);
        }, error -> {
            logout.setEnabled(true);
            status.setTextColor(getColor(Ui.ERROR));
            status.setText(Ui.message(error));
        });
    }

    private void openOfficial() {
        if (official == null || !"https".equals(official.getScheme())
                || !("account.bilibili.com".equals(official.getHost()) || "passport.bilibili.com".equals(official.getHost()))
                || official.getUserInfo() != null || official.getPort() != -1) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, official));
        } catch (android.content.ActivityNotFoundException missing) {
            Ui.toast(this, "无法打开登录页，请扫码或保存二维码。");
        }
    }

    private void saveImage() {
        byte[] png = qr;
        if (png == null) return;
        app().workers.submit(() -> {
            Uri file = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "Bili-CC-QR-" + System.currentTimeMillis() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Bili CC");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                file = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (file == null) throw new IOException("Album unavailable");
                try (OutputStream out = getContentResolver().openOutputStream(file)) {
                    if (out == null) throw new IOException("Image unavailable");
                    out.write(png);
                }
                ContentValues published = new ContentValues();
                published.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(file, published, null, null);
                ui.post(() -> Ui.toast(this, "二维码已保存到相册"));
            } catch (Exception error) {
                if (file != null) getContentResolver().delete(file, null, null);
                ui.post(() -> Ui.toast(this, "无法保存二维码。"));
            }
        });
    }

    private void displayQr(boolean visible) {
        image.setVisibility(visible ? View.VISIBLE : View.GONE);
        actions.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            image.setImageDrawable(null);
            qr = null;
            official = null;
        }
    }

    private void showError(Exception error) {
        ui.removeCallbacks(poll);
        displayQr(false);
        status.setTextColor(getColor(Ui.ERROR));
        status.setText(Ui.message(error));
        refresh.setVisibility(View.VISIBLE);
    }
}
