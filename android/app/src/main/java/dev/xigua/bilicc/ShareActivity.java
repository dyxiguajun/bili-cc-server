package dev.xigua.bilicc;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

public final class ShareActivity extends Activity {
    static final String LINK = "bili_video";
    static final String STARTED = "bili_share_started";
    static final int JOB = 1001;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (state == null && Intent.ACTION_SEND.equals(getIntent().getAction())) {
                String video = sharedVideo(getIntent());
                if (video == null) Ui.toast(this, "没有找到 B 站视频链接。");
                else dispatch(video);
            }
        } finally {
            finish();
        }
    }

    private String sharedVideo(Intent intent) {
        try {
            String video = VideoLinks.normalize(intent.getCharSequenceExtra(Intent.EXTRA_TEXT));
            return video != null ? video : VideoLinks.normalize(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private void dispatch(String video) {
        BiliApp app = BiliApp.from(this);
        if (app.settings.routes().isEmpty()) {
            startActivity(new Intent(this, MainActivity.class).putExtra(LINK, video));
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && app.settings.backgroundShare() && schedule(video)) return;
        startActivity(new Intent(this, ShareWindowActivity.class).putExtra(LINK, video));
    }

    private boolean schedule(String video) {
        if (Build.VERSION.SDK_INT < 31) return false;
        Bundle request = new Bundle();
        request.putString(LINK, video);
        request.putLong(STARTED, SystemClock.elapsedRealtime());
        try {
            JobScheduler scheduler = getSystemService(JobScheduler.class);
            JobInfo job = new JobInfo.Builder(JOB, new ComponentName(this, ShareJobService.class))
                    .setExpedited(true)
                    .setTransientExtras(request)
                    .build();
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
        } catch (IllegalStateException | IllegalArgumentException | SecurityException unavailable) {
            return false;
        }
    }
}
