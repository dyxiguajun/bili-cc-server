package dev.xigua.bilicc;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.Future;

public final class ShareJobService extends JobService {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private JobParameters active;
    private Future<?> task;

    @Override
    public boolean onStartJob(JobParameters params) {
        long received = params.getTransientExtras().getLong(ShareActivity.STARTED, 0);
        String video = params.getTransientExtras().getString(ShareActivity.LINK);
        if (video == null || received == 0) return false;
        if (SystemClock.elapsedRealtime() - received > 8_000) {
            Ui.toast(this, "后台提取未及时启动。可在设置中使用兼容窗口。");
            return false;
        }
        active = params;
        BiliApp app = BiliApp.from(this);
        task = app.workers.submit(() -> {
            try {
                BiliClient.Reply reply = app.api.extract(app.settings.routes(), video, null);
                ui.post(() -> complete(params, reply, null));
            } catch (Exception error) {
                ui.post(() -> complete(params, null, error));
            }
        });
        return true;
    }

    private void complete(JobParameters params, BiliClient.Reply reply, Exception error) {
        if (active != params) return;
        active = null;
        task = null;
        try {
            if (error != null) throw error;
            Ui.copy(this, requireText(reply));
        } catch (Exception failure) {
            Ui.toast(this, Ui.message(failure));
        } finally {
            jobFinished(params, false);
        }
    }

    static String requireText(BiliClient.Reply reply) throws BiliClient.Failure {
        String status = reply.data.optString("status", "");
        String text = reply.data.optString("text", "");
        if ("ok".equals(status) && !text.isEmpty()) return text;
        if ("login_required".equals(status)) {
            throw new BiliClient.Failure(status, "请打开 Bili CC 登录 B 站后再分享。");
        }
        if ("no_subtitle".equals(status)) {
            throw new BiliClient.Failure(status, "当前分 P 没有可提取的字幕。");
        }
        throw new BiliClient.Failure(status, "暂未获取到字幕。");
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (active == params) {
            active = null;
            if (task != null) task.cancel(true);
            task = null;
        }
        return false;
    }
}
