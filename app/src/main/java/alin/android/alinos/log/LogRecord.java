package alin.android.alinos.log;

/**
 * 一条日志记录（不可变）。用于内存缓冲、界面展示与 MCP 读取。
 */
public class LogRecord {

    /** 记录时间（毫秒）。 */
    public final long timestamp;
    public final LogLevel level;
    public final String tag;
    public final String message;
    /** 关联异常堆栈（可空）。 */
    public final String throwable;
    /** 产生日志的线程名。 */
    public final String thread;
    /** 进程 pid。 */
    public final int pid;

    public LogRecord(long timestamp, LogLevel level, String tag, String message,
                     String throwable, String thread, int pid) {
        this.timestamp = timestamp;
        this.level = level;
        this.tag = tag == null ? "" : tag;
        this.message = message == null ? "" : message;
        this.throwable = throwable;
        this.thread = thread == null ? "" : thread;
        this.pid = pid;
    }

    public String getThrowable() {
        return throwable;
    }

    /** 格式化为单行文本：`时间 等级/TAG: 消息 (+堆栈)。 */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(LogFormat.time(timestamp)).append(' ')
          .append(level.shortName).append('/').append(tag).append(": ").append(message);
        if (throwable != null && !throwable.isEmpty()) {
            sb.append('\n').append(throwable);
        }
        return sb.toString();
    }

    /** 转为 JSON（供 MCP / 导出）。 */
    public org.json.JSONObject toJson() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("time", timestamp);
            o.put("time_text", LogFormat.time(timestamp));
            o.put("level", level.name());
            o.put("tag", tag);
            o.put("message", message);
            if (throwable != null) o.put("throwable", throwable);
            o.put("thread", thread);
            o.put("pid", pid);
        } catch (Exception ignored) {
        }
        return o;
    }
}
