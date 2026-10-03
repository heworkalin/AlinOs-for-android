package alin.android.alinos.log;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 全局崩溃承接器。
 *
 * <p>捕获未处理异常后：
 * <ol>
 *   <li>写入统一日志（{@link LogLevel#CRASH}）；</li>
 *   <li>缓存最近一次崩溃信息；</li>
 *   <li>在新进程中启动 {@code CrashActivity} 直接展示该异常，而不是无声闪退。</li>
 * </ol>
 *
 * <p>展示完成后由用户决定退出；随后仍会调用原始 handler 完成进程终止。
 */
public final class CrashCapture {

    private CrashCapture() {
    }

    /** 最近一次崩溃的展示文本。 */
    private static volatile String sLastCrashText;
    private static volatile long sLastCrashTime;

    public static String getLastCrashText() {
        return sLastCrashText;
    }

    public static long getLastCrashTime() {
        return sLastCrashTime;
    }

    /**
     * 处理崩溃：记录 + 拉起承接界面。
     *
     * @return 展示用文本（含设备与线程信息）
     */
    public static String handle(Context ctx, Thread thread, Throwable throwable) {
        String text = buildReport(thread, throwable);
        sLastCrashText = text;
        sLastCrashTime = System.currentTimeMillis();

        // 1) 统一日志落盘
        try {
            AlinLog.crash("CrashCapture", "Uncaught exception in " + thread.getName(), throwable);
        } catch (Throwable ignored) {
        }

        // 2) 拉起承接界面（独立任务栈；部分场景下 Android 不允许再启动 Activity）
        if (ctx != null) {
            try {
                Intent intent = new Intent(ctx, alin.android.alinos.CrashActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                intent.putExtra("crash_text", text);
                ctx.startActivity(intent);
            } catch (Throwable ignored) {
                // 无法启动界面时，至少已落盘
            }
        }
        return text;
    }

    /** 生成崩溃报告文本。 */
    public static String buildReport(Thread thread, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== App 崩溃 =====\n");
        sb.append("时间: ").append(LogFormat.fileTime(System.currentTimeMillis())).append('\n');
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("线程: ").append(thread.getName())
          .append(thread == Looper.getMainLooper().getThread() ? " (main/UI)" : "").append('\n');
        sb.append("类型: ").append(throwable.getClass().getName()).append('\n');
        sb.append("信息: ").append(String.valueOf(throwable.getMessage())).append("\n\n");
        sb.append("—— 堆栈 ——\n");
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            pw.flush();
            sb.append(sw.toString());
        } catch (Throwable t) {
            sb.append(String.valueOf(throwable));
        }
        return sb.toString();
    }
}
