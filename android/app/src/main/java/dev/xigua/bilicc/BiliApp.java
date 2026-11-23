package dev.xigua.bilicc;

import android.app.Application;
import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BiliApp extends Application {
    final ExecutorService workers = Executors.newFixedThreadPool(3);
    RouteStore settings;
    BiliClient api;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new RouteStore(this);
        api = new BiliClient(new Sessions(this));
    }

    static BiliApp from(Context context) {
        return (BiliApp) context.getApplicationContext();
    }
}
