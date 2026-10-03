package alin.android.alinos.log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 日志格式化工具。 */
public final class LogFormat {

    private LogFormat() {
    }

    private static final ThreadLocal<SimpleDateFormat> TIME_FMT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };

    private static final ThreadLocal<SimpleDateFormat> FILE_FMT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };

    /** 界面展示用的短时间。 */
    public static String time(long ms) {
        return TIME_FMT.get().format(new Date(ms));
    }

    /** 文件落盘用的完整时间。 */
    public static String fileTime(long ms) {
        return FILE_FMT.get().format(new Date(ms));
    }

    /** 文件名时间戳（yyyyMMdd_HHmmss）。 */
    public static String stamp(long ms) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(ms));
    }
}
