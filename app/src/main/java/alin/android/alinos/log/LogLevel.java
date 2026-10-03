package alin.android.alinos.log;

/**
 * 日志等级。数值越大越严重，便于按等级阈值过滤。
 */
public enum LogLevel {
    VERBOSE(0, "V"),
    DEBUG(1, "D"),
    INFO(2, "I"),
    WARN(3, "W"),
    ERROR(4, "E"),
    CRASH(5, "C");

    /** 数值等级，用于阈值比较。 */
    public final int value;
    /** 单字符标记（对齐 logcat）。 */
    public final String shortName;

    LogLevel(int value, String shortName) {
        this.value = value;
        this.shortName = shortName;
    }

    /** 解析等级名（不区分大小写），失败回退 {@link #DEBUG}。 */
    public static LogLevel parse(String name, LogLevel fallback) {
        if (name == null) return fallback;
        switch (name.trim().toUpperCase()) {
            case "V": case "VERBOSE": return VERBOSE;
            case "D": case "DEBUG": return DEBUG;
            case "I": case "INFO": return INFO;
            case "W": case "WARN": case "WARNING": return WARN;
            case "E": case "ERROR": return ERROR;
            case "C": case "CRASH": case "FATAL": return CRASH;
            default: return fallback;
        }
    }

    public boolean allows(LogLevel min) {
        return this.value >= min.value;
    }
}
