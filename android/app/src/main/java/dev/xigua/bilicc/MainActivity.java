package dev.xigua.bilicc;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.InputFilter;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Screen {
    private static final int LOGIN = 10;
    private static final int SAVE = 11;
    private EditText input;
    private TextView connections;
    private TextView status;
    private TextView title;
    private TextView subtitle;
    private Button extract;
    private Button track;
    private Button save;
    private Button copy;
    private static final String EMPTY_PREVIEW = "提取后的字幕会显示在这里。";
    private BiliClient.Reply result;
    private String resultLink;
    private boolean retryOnLogin;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildView();
        if (state != null) {
            input.setText(state.getString("video", ""));
            retryOnLogin = state.getBoolean("retryOnLogin");
        } else {
            handleShare(getIntent());
            if (app().settings.routes().isEmpty()) openSettings();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int count = app().settings.routes().size();
        connections.setText(count == 0 ? "请设置服务地址" : "已设置 " + count + " 条连接");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("video", input.getText().toString());
        out.putBoolean("retryOnLogin", retryOnLogin);
    }

    private void buildView() {
        LinearLayout page = Ui.page(this);
        LinearLayout toolbar = Ui.row(this);
        Ui.weight(toolbar, Ui.brand(this), 0);
        toolbar.addView(Ui.quiet(this, "B 站登录", view -> openLogin(false)));
        Button settings = Ui.quiet(this, "设置", view -> openSettings());
        settings.setContentDescription("连接设置");
        toolbar.addView(settings);
        Ui.add(page, toolbar, 0);

        Ui.add(page, Ui.heading(this, "B 站字幕提取", 30), 18);
        connections = Ui.text(this, "", 14, Ui.MUTED, false);
        Ui.add(page, connections, 4);

        LinearLayout form = Ui.panel(this);
        input = Ui.field(this, "B 站链接、短链接或 BV 号", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16_384)});
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit(null, true);
            return true;
        });
        Ui.labeledField(form, "视频链接", input, 0);
        extract = Ui.button(this, "提取并复制", true, view -> submit(null, true));
        Ui.add(form, extract, 8);
        status = Ui.text(this, "", 15, Ui.MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        Ui.add(form, status, 8);
        Ui.add(page, form, 16);

        LinearLayout output = Ui.panel(this);
        Ui.add(output, Ui.heading(this, "字幕预览", 19), 0);
        title = Ui.text(this, "", 15, Ui.ACCENT, true);
        title.setVisibility(View.GONE);
        Ui.add(output, title, 7);
        LinearLayout buttons = Ui.row(this);
        track = Ui.button(this, "切换字幕轨", false, view -> chooseTrack());
        track.setVisibility(View.GONE);
        copy = Ui.button(this, "复制字幕", false, view -> copyResult());
        save = Ui.quiet(this, "下载 SRT", view -> chooseDestination());
        Ui.weight(buttons, save, 0);
        Ui.weight(buttons, copy, 8);
        Ui.add(output, track, 7);
        Ui.add(output, buttons, 6);
        subtitle = Ui.text(this, EMPTY_PREVIEW, 16, Ui.MUTED, false);
        subtitle.setTextIsSelectable(true);
        subtitle.setLineSpacing(Ui.dp(this, 4), 1f);
        subtitle.setMinHeight(Ui.dp(this, 132));
        int padding = Ui.dp(this, 12);
        subtitle.setPadding(padding, padding, padding, padding);
        subtitle.setBackground(Ui.readingBackground(this));
        subtitle.setGravity(android.view.Gravity.CENTER);
        Ui.add(output, subtitle, 8);
        Ui.add(page, output, 12);
        Ui.add(page, Ui.text(this, "XiGua · Corvallis, Oregon, USA", 13, Ui.MUTED, false), 14);
        setResultActions(false);
    }

    private void handleShare(Intent intent) {
        String video = VideoLinks.normalize(intent.getStringExtra(ShareActivity.LINK));
        if (video == null) return;
        input.setText(video);
    }

    private void openLogin(boolean retry) {
        if (app().settings.routes().isEmpty()) {
            openSettings();
            return;
        }
        retryOnLogin = retry;
        startActivityForResult(new Intent(this, LoginActivity.class), LOGIN);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void submit(String trackId, boolean autoCopy) {
        String video = VideoLinks.normalize(input.getText());
        if (video == null) {
            showStatus("请输入 B 站视频链接或 BV 号。", true);
            return;
        }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        extract.setEnabled(false);
        if (trackId == null) {
            result = null;
            title.setVisibility(View.GONE);
            track.setVisibility(View.GONE);
            subtitle.setText("正在提取…");
            subtitle.setTextColor(getColor(Ui.MUTED));
            subtitle.setGravity(android.view.Gravity.CENTER);
        }
        setResultActions(false);
        track.setEnabled(false);
        showStatus("正在连接 NAS…", false);
        boolean chinese = trackId == null && getIntent().hasExtra(ShareActivity.LINK);
        runTask(() -> chinese ? app().api.extractChinese(app().settings.routes(), video)
                : app().api.extract(app().settings.routes(), video, trackId), reply -> {
            finishRequest();
            resultLink = video;
            display(reply, autoCopy);
        }, failure -> {
            finishRequest();
            processFailure(failure);
        });
    }

    private void finishRequest() {
        extract.setEnabled(true);
        track.setEnabled(true);
        setResultActions(result != null && "ok".equals(result.data.optString("status")));
        if (result == null) subtitle.setText(EMPTY_PREVIEW);
    }

    private void display(BiliClient.Reply reply, boolean autoCopy) {
        result = reply;
        JSONObject info = reply.data.optJSONObject("video");
        if (info != null) {
            String part = info.optString("part");
            title.setText(info.optString("title") + (part.isEmpty() ? "" : "\n" + part));
            title.setVisibility(View.VISIBLE);
        }
        JSONArray tracks = reply.data.optJSONArray("tracks");
        track.setVisibility(tracks != null && tracks.length() > 1 ? View.VISIBLE : View.GONE);
        try {
            String text = ShareJobService.requireText(reply);
            subtitle.setText(text);
            subtitle.setTextColor(getColor(Ui.TEXT));
            subtitle.setGravity(android.view.Gravity.START | android.view.Gravity.TOP);
            setResultActions(true);
            showSuccess("已提取字幕 · " + reply.route.name);
            if (autoCopy) copyResult();
        } catch (BiliClient.Failure error) {
            subtitle.setText("当前没有可预览的字幕。");
            subtitle.setTextColor(getColor(Ui.MUTED));
            subtitle.setGravity(android.view.Gravity.CENTER);
            setResultActions(false);
            processFailure(error);
        }
    }

    private void processFailure(Exception failure) {
        showStatus(Ui.message(failure), true);
        if (failure instanceof BiliClient.Failure) {
            String code = ((BiliClient.Failure) failure).code;
            if ("login_required".equals(code)) openLogin(true);
            if ("no_routes".equals(code)) openSettings();
        }
    }

    private void chooseTrack() {
        if (result == null) return;
        JSONArray tracks = result.data.optJSONArray("tracks");
        if (tracks == null || tracks.length() < 2) return;
        String[] names = new String[tracks.length()];
        String[] ids = new String[tracks.length()];
        int selected = -1;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject item = tracks.optJSONObject(i);
            if (item == null) return;
            names[i] = item.optString("label", "字幕");
            ids[i] = item.optString("id");
            if (ids[i].equals(result.data.optString("selected"))) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("字幕轨")
                .setSingleChoiceItems(names, selected, (dialog, index) -> {
                    dialog.dismiss();
                    input.setText(resultLink);
                    submit(ids[index], true);
                }).setNegativeButton("取消", null).show();
    }

    private void copyResult() {
        if (result == null) return;
        try {
            Ui.copy(this, ShareJobService.requireText(result));
            showSuccess("字幕已复制 · " + result.route.name);
        } catch (Exception failure) {
            showStatus(Ui.message(failure), true);
        }
    }

    private void chooseDestination() {
        if (result == null) return;
        JSONObject video = result.data.optJSONObject("video");
        String bvid = video == null ? "" : video.optString("bvid");
        String filename = bvid.matches("BV[A-Za-z0-9]{10}") ? bvid + "-P" + video.optInt("page", 1) : "Bili字幕";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-subrip").putExtra(Intent.EXTRA_TITLE, filename + ".srt");
        startActivityForResult(intent, SAVE);
    }

    private void setResultActions(boolean enabled) {
        copy.setEnabled(enabled);
        save.setEnabled(enabled);
    }

    private void showStatus(String value, boolean error) {
        status.setVisibility(View.VISIBLE);
        status.setTextColor(getColor(error ? Ui.ERROR : Ui.MUTED));
        status.setText(value);
    }

    private void showSuccess(String value) {
        status.setVisibility(View.VISIBLE);
        status.setTextColor(getColor(Ui.SUCCESS));
        status.setText(value);
    }

    @Override
    protected void onActivityResult(int request, int outcome, Intent data) {
        super.onActivityResult(request, outcome, data);
        if (request == LOGIN) {
            boolean retry = retryOnLogin;
            retryOnLogin = false;
            if (outcome == RESULT_OK && retry) submit(null, true);
        } else if (request == SAVE && outcome == RESULT_OK && data != null && result != null) {
            Uri destination = data.getData();
            if (destination == null) return;
            byte[] srt = result.data.optString("srt").getBytes(StandardCharsets.UTF_8);
            app().workers.submit(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new java.io.IOException("File unavailable");
                    out.write(srt);
                    ui.post(() -> { if (!isDestroyed()) showStatus("已保存 SRT。", false); });
                } catch (Exception unavailable) {
                    ui.post(() -> { if (!isDestroyed()) showStatus("保存失败，请选择其他位置。", true); });
                }
            });
        }
    }
}
