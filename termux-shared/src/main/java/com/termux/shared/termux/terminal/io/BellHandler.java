package com.termux.shared.termux.terminal.io;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.termux.shared.logger.Logger;

public class BellHandler {
    private static BellHandler instance = null;
    private static final Object lock = new Object();

    private static final String LOG_TAG = "BellHandler";

    public static BellHandler getInstance(Context context) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    Context appContext = context.getApplicationContext();
                    Vibrator vibrator = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
                    instance = new BellHandler(appContext, vibrator);
                }
            }
        }

        return instance;
    }

    private static final long DURATION = 50;
    private static final long MIN_PAUSE = 3 * DURATION;

    private final Context mContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBell = 0;
    private final Runnable bellRunnable;

    private BellHandler(Context context, final Vibrator vibrator) {
        mContext = context.getApplicationContext();
        bellRunnable = new Runnable() {
            @Override
            public void run() {
                if (vibrator != null && checkVibratePermission()) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(DURATION, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            vibrator.vibrate(DURATION);
                        }
                    } catch (Exception e) {
                        // Issue on samsung devices on android 8
                        // java.lang.NullPointerException: Attempt to read from field 'android.os.VibrationEffect com.android.server.VibratorService$Vibration.mEffect' on a null object reference
                        Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run vibrator", e);
                    }
                }
            }
        };
    }

    /**
     * 检查振动权限（AppOps）
     * @return true 如果允许振动，否则 false
     */
    private boolean checkVibratePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                AppOpsManager appOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
                if (appOps == null) {
                    return false;
                }

                int mode;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 使用字符串（直接写死真实值，避免隐藏API报错）
                    String opStr = "android:vibrate";
                    mode = appOps.unsafeCheckOpNoThrow(opStr, Process.myUid(), mContext.getPackageName());
                } else {
                    // Android 6.0 ~ 9 使用整型 OP_VIBRATE = 3
                    int opVibrate = 3;
                    mode = appOps.checkOpNoThrow(String.valueOf(opVibrate), Process.myUid(), mContext.getPackageName());
                }

                if (mode != AppOpsManager.MODE_ALLOWED) {
                    Logger.logVerbose(LOG_TAG, "Vibrate permission denied by AppOps (mode=" + mode + ")");
                    return false;
                }
            } catch (Exception e) {
                Logger.logDebug(LOG_TAG, "Error checking vibrate AppOps permission: " + e.getMessage());
                return true;
            }
        }
        // API < 23 直接返回允许
        return true;
    }
    public synchronized void doBell() {
        long now = now();
        long timeSinceLastBell = now - lastBell;

        if (timeSinceLastBell < 0) {
            // there is a next bell pending; don't schedule another one
        } else if (timeSinceLastBell < MIN_PAUSE) {
            // there was a bell recently, schedule the next one
            handler.postDelayed(bellRunnable, MIN_PAUSE - timeSinceLastBell);
            lastBell = lastBell + MIN_PAUSE;
        } else {
            // the last bell was long ago, do it now
            bellRunnable.run();
            lastBell = now;
        }
    }

    private long now() {
        return SystemClock.uptimeMillis();
    }
}
