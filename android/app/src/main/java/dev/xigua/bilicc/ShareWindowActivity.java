package dev.xigua.bilicc;

import android.os.Bundle;
import android.widget.Toast;

public final class ShareWindowActivity extends Screen {
    private Toast progress;
    private Runnable pendingResult;
    private final Runnable showProgress = () -> {
        if (!hasWindowFocus() || isFinishing()) return;
        progress = Toast.makeText(this, "正在提取中文字幕…", Toast.LENGTH_SHORT);
        progress.show();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String video = VideoLinks.normalize(getIntent().getStringExtra(ShareActivity.LINK));
        if (video == null) {
            finish();
            return;
        }
        ui.postDelayed(showProgress, 650);
        runTask(() -> ShareJobService.requireText(app().api.extractChinese(app().settings.routes(), video)),
                text -> whenFocused(() -> copyAndReturn(text)),
                error -> whenFocused(() -> notifyAndFinish(Ui.message(error))));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) deliverResult();
    }

    private void whenFocused(Runnable result) {
        pendingResult = result;
        deliverResult();
    }

    private void deliverResult() {
        if (pendingResult == null || !hasWindowFocus() || isFinishing()) return;
        Runnable result = pendingResult;
        pendingResult = null;
        result.run();
    }

    private void copyAndReturn(String text) {
        try {
            Ui.copyToClipboard(this, text);
            notifyAndFinish("中文字幕已复制");
        } catch (BiliClient.Failure error) {
            notifyAndFinish(Ui.message(error));
        }
    }

    private void notifyAndFinish(String message) {
        cancelProgress();
        Ui.toast(this, message);
        ui.postDelayed(this::finish, 350);
    }

    private void cancelProgress() {
        ui.removeCallbacks(showProgress);
        if (progress != null) {
            progress.cancel();
            progress = null;
        }
    }

    @Override
    protected void onDestroy() {
        cancelProgress();
        super.onDestroy();
    }
}
