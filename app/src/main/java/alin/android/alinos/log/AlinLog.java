package alin.android.alinos.log;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 统一日志门面（借鉴 Timber 的 Tree/Sink 分发思想，自持实现）。
 *
 * <p>业务只调本类；底层挂多个 {@link LogSink}（内存 / 文件 / logcat），
 * 统一转发，便于日志界面展示与 MCP 读取。
 *
 * <p>用法（位置替换）：{@code Log.d(TAG, msg)} → {@code AlinLog.d(TAG, msg)}。
 */
public final class AlinLog {

    private static final String PREFS = "alinos_log";
    private static final String KEY_MIN_LEVEL = "min_level";
    private static final String KEY_FILE_ENABLED = "file_enabled";
    private static final String KEY_LOGCAT_ENABLED = "logcat_enabled";
    private static final String KEY_FILTER_SENSITIVE = "filter_sensitive";

    private AlinLog() {
    }

    // ---- 全局状态 -------------------------------------------------------

    private static volatile boolean sInit = false;
    /** 捕获等级：写入内存 / 文件的阈值（默认 VERBOSE，保证不丢日志）。 */
    private static volatile LogLevel sCaptureLevel = LogLevel.VERBOSE;
    /** 展示等级：日志界面 / MCP 读取时的默认过滤阈值（纯查看，不影响捕获）。 */
    private static volatile LogLevel sMinLevel = LogLevel.DEBUG;
    private static volatile boolean sFileEnabled = true;
    private static volatile boolean sLogcatEnabled = true;
    private static volatile boolean sFilterSensitive = true;

    private static final List<LogSink> sSinks = new CopyOnWriteArrayList<>();
    private static MemoryLogSink sMemory;
    private static FileLogSink sFile;

    private static int sPid = -1;
    private static SharedPreferences sPrefs;
    private static Context sAppContext;

    /** 界面监听器（实时刷新用）。 */
    public interface Listener {
        void onLog(LogRecord record);
    }

    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();

    // ---- 初始化 ---------------------------------------------------------

    /** 由 {@code AlinOsApp.onCreate()} 调用。幂等。 */
    public static synchronized void init(Context ctx) {
        if (sInit) return;
        Context app = ctx.getApplicationContext();
        sAppContext = app;
        sPrefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sPid = Process.myPid();

        // 恢复配置
        LogLevel saved = LogLevel.parse(sPrefs.getString(KEY_MIN_LEVEL, "DEBUG"), LogLevel.DEBUG);
        sMinLevel = saved;
        // 捕获等级固定为 VERBOSE：内存与文件始终记全量，等级仅用于查看过滤，
        // 这样用户切换查看等级时不会因之前的阈值而丢失低等级日志。
        sCaptureLevel = LogLevel.VERBOSE;
        sFileEnabled = sPrefs.getBoolean(KEY_FILE_ENABLED, true);
        sLogcatEnabled = sPrefs.getBoolean(KEY_LOGCAT_ENABLED, true);
        sFilterSensitive = sPrefs.getBoolean(KEY_FILTER_SENSITIVE, true);

        sMemory = new MemoryLogSink(2000);
        sSinks.add(sMemory);

        sFile = new FileLogSink(new File(app.getFilesDir(), "logs"), 2 * 1024 * 1024, 3);
        if (sFileEnabled) sSinks.add(sFile);

        if (sLogcatEnabled) sSinks.add(new LogcatLogSink(true));

        installCrashHandler();
        sInit = true;
        i("AlinLog", "日志系统初始化完成 (min=" + sMinLevel.name() + ")");
    }

    public static boolean isInit() {
        return sInit;
    }

    private static void ensureInit() {
        if (!sInit) {
            // 未初始化时退化为 logcat（避免丢失日志）
            sPid = Process.myPid();
            if (sMemory == null) sMemory = new MemoryLogSink(2000);
            if (!sSinks.contains(sMemory)) sSinks.add(sMemory);
            sInit = true;
        }
    }

    private static void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // 承接：记日志 + 拉起崩溃界面；不立即终止进程，让界面有机会展示。
            // 进程退出由用户在崩溃页显式触发（见 CrashActivity）。
            CrashCapture.handle(sAppContext, thread, throwable);
        });
    }

    // ---- 记录 API -------------------------------------------------------

    public static void v(String tag, String msg) { log(LogLevel.VERBOSE, tag, msg, null); }
    public static void v(String tag, String msg, Throwable t) { log(LogLevel.VERBOSE, tag, msg, t); }
    public static void d(String tag, String msg) { log(LogLevel.DEBUG, tag, msg, null); }
    public static void d(String tag, String msg, Throwable t) { log(LogLevel.DEBUG, tag, msg, t); }
    public static void i(String tag, String msg) { log(LogLevel.INFO, tag, msg, null); }
    public static void i(String tag, String msg, Throwable t) { log(LogLevel.INFO, tag, msg, t); }
    public static void w(String tag, String msg) { log(LogLevel.WARN, tag, msg, null); }
    public static void w(String tag, String msg, Throwable t) { log(LogLevel.WARN, tag, msg, t); }
    public static void e(String tag, String msg) { log(LogLevel.ERROR, tag, msg, null); }
    public static void e(String tag, String msg, Throwable t) { log(LogLevel.ERROR, tag, msg, t); }
    public static void crash(String tag, String msg, Throwable t) { log(LogLevel.CRASH, tag, msg, t); }

    /** 通用入口。 */
    public static void log(LogLevel level, String tag, String msg, Throwable t) {
        ensureInit();
        // 捕获阈值（默认 VERBOSE）决定是否落盘/入内存；展示等级不影响捕获。
        if (!level.allows(sCaptureLevel)) return;
        String message = sFilterSensitive ? SensitiveFilter.filter(msg) : msg;
        String stack = t == null ? null : stackToString(t);
        String thread = Thread.currentThread().getName();
        LogRecord record = new LogRecord(System.currentTimeMillis(), level, tag, message,
                stack, thread, sPid);
        for (LogSink sink : sSinks) {
            try {
                sink.log(record);
            } catch (Throwable ignored) {
                // 单个 Sink 失败不影响其它
            }
        }
        for (Listener l : sListeners) {
            try {
                l.onLog(record);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String stackToString(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch (Exception e) {
            return String.valueOf(t);
        }
    }

    // ---- 读取 API（日志界面 / MCP） -------------------------------------

    public static List<LogRecord> recent(int lines) {
        ensureInit();
        return sMemory == null ? new ArrayList<>() : sMemory.recent(lines);
    }

    public static List<LogRecord> query(LogLevel min, String tag, String keyword, int limit) {
        ensureInit();
        return sMemory == null ? new ArrayList<>() : sMemory.query(min, tag, keyword, limit);
    }

    public static FileLogSink fileSink() {
        return sFile;
    }

    public static File logDir() {
        return sFile == null ? null : sFile.dir();
    }

    // ---- 监听器 ---------------------------------------------------------

    public static void addListener(Listener l) {
        if (l != null && !sListeners.contains(l)) sListeners.add(l);
    }

    public static void removeListener(Listener l) {
        sListeners.remove(l);
    }

    // ---- 配置读写 -------------------------------------------------------

    public static LogLevel getMinLevel() { return sMinLevel; }

    public static void setMinLevel(LogLevel level) {
        if (level == null) return;
        sMinLevel = level;
        if (sPrefs != null) sPrefs.edit().putString(KEY_MIN_LEVEL, level.name()).apply();
    }

    public static boolean isFilterSensitive() { return sFilterSensitive; }

    public static void setFilterSensitive(boolean v) {
        sFilterSensitive = v;
        if (sPrefs != null) sPrefs.edit().putBoolean(KEY_FILTER_SENSITIVE, v).apply();
    }

    public static void clear() {
        for (LogSink sink : sSinks) {
            try {
                sink.clear();
            } catch (Throwable ignored) {
            }
        }
    }
}
