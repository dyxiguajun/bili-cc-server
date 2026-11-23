package dev.xigua.bilicc;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Consumer;

abstract class Screen extends Activity {
    final Handler ui = new Handler(Looper.getMainLooper());
    private Future<?> task;
    private int revision;

    BiliApp app() {
        return BiliApp.from(this);
    }

    <T> void runTask(Callable<T> action, Consumer<T> success, Consumer<Exception> failure) {
        cancelTask();
        int started = revision;
        task = app().workers.submit(() -> {
            try {
                T value = action.call();
                ui.post(() -> {
                    if (started == revision && !isFinishing() && !isDestroyed()) success.accept(value);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (started == revision && !isFinishing() && !isDestroyed()) failure.accept(error);
                });
            }
        });
    }

    void cancelTask() {
        revision++;
        if (task != null) task.cancel(true);
    }

    @Override
    protected void onDestroy() {
        cancelTask();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
