package alin.android.alinos;

import android.app.Application;

import alin.android.alinos.log.AlinLog;

/**
 * 应用入口：初始化统一日志系统等全局基础设施。
 *
 * <p>注意：不做任何耗时操作，保持启动轻量。
 */
public class AlinOsApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AlinLog.init(this);
    }
}
