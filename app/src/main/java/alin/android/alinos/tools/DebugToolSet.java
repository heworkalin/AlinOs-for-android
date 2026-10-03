package alin.android.alinos.tools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import alin.android.alinos.log.AlinLog;
import alin.android.alinos.log.LogLevel;
import alin.android.alinos.log.LogRecord;

/**
 * 调试工具集（DEBUG）。
 *
 * <p>让 MCP 客户端能读取 App 运行态与统一日志。默认仅在调试模式暴露，
 * 当前先注册为 {@link ToolMeta.Scope#DEBUG}（不喂给 AI 的常规对话）。
 */
public class DebugToolSet {

    private DebugToolSet() {
    }

    public static void register(final Context ctx) {
        ToolRegistry.register("debug_logs",
                "Read recent unified app logs. Supports level threshold and tag/keyword filtering.",
                ToolMeta.params(
                        ToolMeta.param("lines", "int", false, "200", "Maximum records to return"),
                        ToolMeta.param("level", "string", false, "DEBUG",
                                "Minimum level: VERBOSE/DEBUG/INFO/WARN/ERROR/CRASH"),
                        ToolMeta.param("tag", "string", false, "", "Filter by tag substring"),
                        ToolMeta.param("keyword", "string", false, "", "Filter by message substring")),
                p -> {
                    LogLevel min = LogLevel.parse(p.optString("level", "DEBUG"), LogLevel.DEBUG);
                    List<LogRecord> records = AlinLog.query(
                            min, p.optString("tag", ""), p.optString("keyword", ""),
                            p.optInt("lines", 200));
                    JSONObject o = ToolMeta.ok();
                    o.put("count", records.size());
                    o.put("min_level", min.name());
                    JSONArray arr = new JSONArray();
                    for (LogRecord r : records) arr.put(r.toJson());
                    o.put("logs", arr);
                    return o;
                },
                ToolMeta.Scope.DEBUG, ToolMeta.Category.SYSTEM);

        ToolRegistry.register("debug_last_crash",
                "Return the most recent uncaught crash report, if any.",
                new ToolMeta.Param[0],
                p -> {
                    String text = alin.android.alinos.log.CrashCapture.getLastCrashText();
                    if (text == null || text.isEmpty()) {
                        return ToolMeta.error("no crash recorded in this process");
                    }
                    JSONObject o = ToolMeta.ok();
                    o.put("time", alin.android.alinos.log.CrashCapture.getLastCrashTime());
                    o.put("report", text);
                    return o;
                },
                ToolMeta.Scope.DEBUG, ToolMeta.Category.SYSTEM);

        ToolRegistry.register("debug_runtime",
                "Report basic runtime info: pid, log level, sink state.",
                new ToolMeta.Param[0],
                p -> {
                    JSONObject o = ToolMeta.ok();
                    o.put("pid", android.os.Process.myPid());
                    o.put("log_initialized", AlinLog.isInit());
                    o.put("min_level", AlinLog.getMinLevel().name());
                    o.put("log_dir", AlinLog.logDir() == null ? "" : AlinLog.logDir().getAbsolutePath());
                    return o;
                },
                ToolMeta.Scope.DEBUG, ToolMeta.Category.SYSTEM);
    }
}
