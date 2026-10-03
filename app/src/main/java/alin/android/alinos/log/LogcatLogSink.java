package alin.android.alinos.log;

import android.util.Log;

/**
 * Logcat Sink：把统一日志转发到 Android logcat（adb 可见），兼容既有调试习惯。
 *
 * <p><b>等级跟随 UI</b>：内存 / 文件始终全量记录（避免切等级后丢历史），
 * 但转发的 logcat 输出按 {@link AlinLog#getMinLevel()} 过滤 ——
 * 界面选择什么等级，adb 就看到什么等级。
 */
public class LogcatLogSink implements LogSink {

    private static final String DEFAULT_TAG = "AlinOs";

    private final boolean enabled;

    public LogcatLogSink(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void log(LogRecord record) {
        if (!enabled || record == null) return;
        // adb / logcat 受 UI 等级调控
        if (!record.level.allows(AlinLog.getMinLevel())) return;
        String tag = record.tag.isEmpty() ? DEFAULT_TAG : record.tag;
        String msg = record.message;
        try {
            switch (record.level) {
                case VERBOSE: Log.v(tag, msg); break;
                case DEBUG:   Log.d(tag, msg); break;
                case INFO:    Log.i(tag, msg); break;
                case WARN:    Log.w(tag, msg); break;
                case ERROR:
                case CRASH:   Log.e(tag, msg); break;
                default:      Log.d(tag, msg); break;
            }
        } catch (Exception ignored) {
            // 日志系统自身不得抛出
        }
    }

    @Override
    public void clear() {
        // logcat 无法清空，忽略
    }

    @Override
    public String name() {
        return "logcat";
    }
}
