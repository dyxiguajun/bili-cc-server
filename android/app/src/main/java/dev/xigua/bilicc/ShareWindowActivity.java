package dev.xigua.bilicc;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ShareWindowActivity extends Screen {
    private String video;
    private String pendingCopy;
    private TextView status;
    private LinearLayout actions;
    private LinearLayout panel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        video = VideoLinks.normalize(getIntent().getStringExtra(ShareActivity.LINK));
        if (video == null) {
            finish();
            return;
        }
        FrameLayout overlay = new FrameLayout(this);
        panel = Ui.panel(this);
        status = Ui.text(this, "正在提取字幕…", 16, Ui.TEXT, true);
        actions = Ui.row(this);
        Ui.weight(actions, Ui.button(this, "取消", false, view -> finish()), 0);
        Ui.add(panel, status, 0);
        Ui.add(panel, actions, 6);
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        placement.setMargins(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 52));
        overlay.addView(panel, placement);
        panel.setVisibility(View.INVISIBLE);
        setContentView(overlay);
        ui.postDelayed(() -> panel.setVisibility(View.VISIBLE), 350);
        runTask(() -> ShareJobService.requireText(app().api.extract(app().settings.routes(), video, null)),
                text -> {
                    pendingCopy = text;
                    if (hasWindowFocus()) copyAndReturn();
                }, this::showProblem);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) copyAndReturn();
    }

    private void copyAndReturn() {
        if (pendingCopy == null || isFinishing()) return;
        try {
            Ui.copy(this, pendingCopy);
            pendingCopy = null;
            finish();
        } catch (Exception error) {
            pendingCopy = null;
            showProblem(error);
        }
    }

    private void showProblem(Exception error) {
        panel.setVisibility(View.VISIBLE);
        status.setText(Ui.message(error));
        actions.removeAllViews();
        boolean login = error instanceof BiliClient.Failure && ((BiliClient.Failure) error).code.equals("login_required");
        Ui.weight(actions, Ui.button(this, login ? "去登录" : "打开 Bili CC", true, view -> {
            startActivity(new Intent(this, MainActivity.class).putExtra(ShareActivity.LINK, video)
                    .putExtra(MainActivity.OPEN_LOGIN, login));
            finish();
        }), 0);
        Ui.weight(actions, Ui.button(this, "关闭", false, view -> finish()), 7);
    }
}
