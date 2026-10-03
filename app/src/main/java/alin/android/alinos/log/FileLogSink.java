package alin.android.alinos.log;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文件 Sink：滚动落盘，供事后排查 / 导出分享 / MCP 读取。
 *
 * <p>策略：单个文件超过 {@code maxBytes} 时轮转，最多保留 {@code maxFiles} 个
 * （{@code alinos.log}、{@code alinos.log.1}、…）。写入在锁内完成，失败仅记录到 logcat。
 */
public class FileLogSink implements LogSink {

    private static final String TAG = "FileLogSink";

    private final File dir;
    private final File current;
    private final long maxBytes;
    private final int maxFiles;

    public FileLogSink(File dir, long maxBytes, int maxFiles) {
        this.dir = dir;
        this.maxBytes = maxBytes > 0 ? maxBytes : 2 * 1024 * 1024;
        this.maxFiles = maxFiles > 0 ? maxFiles : 3;
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        this.current = new File(dir, "alinos.log");
    }

    @Override
    public void log(LogRecord record) {
        if (record == null) return;
        String line = LogFormat.fileTime(record.timestamp) + " "
                + record.level.shortName + "/" + record.tag + "(" + record.pid + "): "
                + record.message
                + (record.throwable != null && !record.throwable.isEmpty()
                    ? "\n" + record.throwable : "")
                + "\n";
        synchronized (this) {
            try {
                rotateIfNeeded(line.getBytes(StandardCharsets.UTF_8).length);
                try (FileOutputStream fos = new FileOutputStream(current, true)) {
                    fos.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.w(TAG, "write failed: " + e.getMessage());
            }
        }
    }

    private void rotateIfNeeded(int incoming) {
        if (!current.exists()) return;
        if (current.length() + incoming <= maxBytes) return;
        // 删除最旧的一份，其余依次后移
        File oldest = new File(dir, "alinos.log." + (maxFiles - 1));
        if (oldest.exists()) {
            //noinspection ResultOfMethodCallIgnored
            oldest.delete();
        }
        for (int i = maxFiles - 2; i >= 1; i--) {
            File src = new File(dir, "alinos.log." + i);
            File dst = new File(dir, "alinos.log." + (i + 1));
            if (src.exists()) {
                //noinspection ResultOfMethodCallIgnored
                src.renameTo(dst);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        current.renameTo(new File(dir, "alinos.log.1"));
    }

    @Override
    public void clear() {
        synchronized (this) {
            //noinspection ResultOfMethodCallIgnored
            current.delete();
            for (int i = 1; i < maxFiles; i++) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, "alinos.log." + i).delete();
            }
        }
    }

    @Override
    public String name() {
        return "file";
    }

    public File currentFile() {
        return current;
    }

    public File dir() {
        return dir;
    }

    /** 读取当前日志文件末尾 n 行。 */
    public List<String> tail(int lines) {
        List<String> out = new ArrayList<>();
        if (!current.exists()) return out;
        synchronized (this) {
            try (RandomAccessFile raf = new RandomAccessFile(current, "r")) {
                long len = raf.length();
                int chunk = 8192;
                StringBuilder sb = new StringBuilder();
                long pos = len;
                while (pos > 0 && countLines(sb.toString()) <= lines) {
                    long readLen = Math.min(chunk, pos);
                    pos -= readLen;
                    byte[] buf = new byte[(int) readLen];
                    raf.seek(pos);
                    raf.readFully(buf);
                    sb.insert(0, new String(buf, StandardCharsets.UTF_8));
                }
                String[] all = sb.toString().split("\n", -1);
                int from = Math.max(0, all.length - lines);
                out.addAll(Arrays.asList(all).subList(from, all.length));
            } catch (IOException e) {
                Log.w(TAG, "tail failed: " + e.getMessage());
            }
        }
        return out;
    }

    private static int countLines(String s) {
        if (s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }
}
