package alin.android.alinos.log;

/** 日志输出目标（借鉴 Timber 的 Tree 分发思想）。 */
public interface LogSink {

    /** 接收一条日志。实现必须自行捕获异常，不得向调用方抛出。 */
    void log(LogRecord record);

    /** 清空该 Sink 的所有内容（内存缓冲 / 文件）。 */
    void clear();

    /** 名称，便于诊断。 */
    String name();
}
