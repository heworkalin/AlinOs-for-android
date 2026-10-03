package alin.android.alinos.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存环形日志缓冲。供日志界面实时查看与 MCP 读取，容量可配。
 */
public class MemoryLogSink implements LogSink {

    private final int maxRecords;
    private final ArrayDeque<LogRecord> buffer;

    public MemoryLogSink(int maxRecords) {
        this.maxRecords = maxRecords > 0 ? maxRecords : 2000;
        this.buffer = new ArrayDeque<>(this.maxRecords);
    }

    @Override
    public synchronized void log(LogRecord record) {
        if (record == null) return;
        buffer.addLast(record);
        while (buffer.size() > maxRecords) {
            buffer.pollFirst();
        }
    }

    @Override
    public synchronized void clear() {
        buffer.clear();
    }

    @Override
    public String name() {
        return "memory";
    }

    /** 最近 n 条（按时间正序：旧→新）。 */
    public synchronized List<LogRecord> recent(int n) {
        List<LogRecord> all = new ArrayList<>(buffer);
        if (n <= 0 || n >= all.size()) return all;
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    /** 按条件查询（等级阈值 / tag / 关键字）。 */
    public synchronized List<LogRecord> query(LogLevel min, String tag, String keyword, int limit) {
        List<LogRecord> out = new ArrayList<>();
        String tagLower = tag == null ? "" : tag.toLowerCase();
        String kwLower = keyword == null ? "" : keyword.toLowerCase();
        for (LogRecord r : buffer) {
            if (min != null && !r.level.allows(min)) continue;
            if (!tagLower.isEmpty() && !r.tag.toLowerCase().contains(tagLower)) continue;
            if (!kwLower.isEmpty()
                    && !r.message.toLowerCase().contains(kwLower)
                    && !r.tag.toLowerCase().contains(kwLower)) continue;
            out.add(r);
        }
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<>(out.subList(out.size() - limit, out.size()));
        }
        return out;
    }

    public synchronized int size() {
        return buffer.size();
    }
}
