package alin.android.alinos.proot;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * /proc 时间信息伪造器（Java 版，动态维护）。
 *
 * <p><b>背景：</b>Android 的 /proc 带 hidepid，proot tracee 读不到宿主
 * {@code /proc/uptime}、{@code /proc/stat}，导致容器内 {@code ps} 报
 * {@code Unable to get system boot time}。tmoe 与 proot-distro 的做法都是
 * <b>安装时写死一份静态伪文件</b>（btime / uptime 永不变），只能"读得到"，
 * 不能保证"时间对"。
 *
 * <p><b>本类：</b>在容器启动时用 Android 单调时钟生成一份带真实 btime 的
 * {@code stat} 与随真实时间推进的 {@code uptime}，并开一个守护线程持续刷新，
 * 让容器内的 {@code ps}、{@code top}、{@code uptime}、{@code who} 等始终拿到
 * 合理的时间。
 *
 * <p><b>写入方式：</b>必须原地 truncate+write（{@link RandomAccessFile}），
 * 不能"临时文件 + rename"。proot 的 {@code --bind} 在启动时记录源路径，运行中
 * 按路径打开；rename 会换掉 inode，导致 proot 后续打开失败。
 */
public final class FakeProcMaintainer {

    private static final String TAG = "FakeProcMaintainer";

    /** 容器内伪文件目录（与 assets/proot_proc.tar.xz 解包位置一致）。 */
    private static final String PROC_SUBDIR = "usr/local/etc/tmoe-linux/proot_proc";

    /** uptime 刷新周期。 */
    private static final long UPTIME_REFRESH_MS = 1000L;
    /** btime 重算周期（系统时间可能被 NTP 校正）。 */
    private static final long STAT_REFRESH_MS = 60_000L;

    /** 由本类生成、需要与 .tmoe-container.* 去重的文件名（仅真正生成的两个）。 */
    private static final String[] OWNED = {
            "uptime", "stat",
    };

    /**
     * 内置 stat 模板（来自 proot-distro 的 _FAKE_STAT，8 核）。
     * 仅在本地既无 stat 也无 .tmoe-container.stat 时兜底。
     */
    private static final String DEFAULT_STAT_TEMPLATE =
            "cpu  1957 0 2877 93280 262 342 254 87 0 0\n"
            + "cpu0 31 0 226 12027 82 10 4 9 0 0\n"
            + "cpu1 45 0 664 11144 21 263 233 12 0 0\n"
            + "cpu2 494 0 537 11283 27 10 3 8 0 0\n"
            + "cpu3 359 0 234 11723 24 26 5 7 0 0\n"
            + "cpu4 295 0 268 11772 10 12 2 12 0 0\n"
            + "cpu5 270 0 251 11833 15 3 1 10 0 0\n"
            + "cpu6 430 0 520 11386 30 8 1 12 0 0\n"
            + "cpu7 30 0 172 12108 50 8 1 13 0 0\n"
            + "intr 127541 38 290 0 0 0 0 4 0 1 0 0 25329 258 0 5777 277 0 0 0\n"
            + "ctxt 140223\n"
            + "processes 772\n"
            + "procs_running 2\n"
            + "procs_blocked 0\n"
            + "softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n";

    private static volatile FakeProcMaintainer sInstance;

    /** 保护 ensure / 线程启停。 */
    private final Object lock = new Object();

    private File procDir;
    private volatile boolean running;
    private Thread worker;

    private volatile long btimeSec;
    /** 不含 btime 行的 stat 主体（cpu/intr/.../softirq）。 */
    private volatile String statBody;

    private FakeProcMaintainer() {
    }

    /** 单例（不需要 Context，rootfs 由 {@link #ensure(File)} 传入）。 */
    public static FakeProcMaintainer get() {
        FakeProcMaintainer inst = sInstance;
        if (inst == null) {
            synchronized (FakeProcMaintainer.class) {
                inst = sInstance;
                if (inst == null) {
                    inst = new FakeProcMaintainer();
                    sInstance = inst;
                }
            }
        }
        return inst;
    }

    /** 便捷重载，忽略 Context（保持调用点统一）。 */
    public static FakeProcMaintainer get(android.content.Context ctx) {
        return get();
    }

    /**
     * 确保伪文件已生成、维护线程已启动。
     *
     * <p>幂等：同 rootfs 重复调用只会刷新一次时间，不会重复起线程。
     *
     * @param rootfs 容器 rootfs 目录（{@code .../containers/proot/ubuntu-noble_<arch>}）
     */
    public void ensure(File rootfs) {
        if (rootfs == null) return;
        File dir = new File(rootfs, PROC_SUBDIR);
        synchronized (lock) {
            this.procDir = dir;
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "cannot create proot_proc dir: " + dir);
                return;
            }
            dedupe();
            this.statBody = loadStatBody(dir);
            this.btimeSec = computeBtimeSec();

            writeUptime();
            writeStat();

            if (!running) {
                running = true;
                worker = new Thread(this::loop, "fake-proc-maintainer");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    /** 停止维护线程（一般不需要；进程结束自然停止）。 */
    public void stop() {
        synchronized (lock) {
            running = false;
            if (worker != null) {
                worker.interrupt();
                worker = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private void loop() {
        long lastStat = SystemClock.elapsedRealtime();
        while (running) {
            try {
                writeUptime();
                long now = SystemClock.elapsedRealtime();
                if (now - lastStat >= STAT_REFRESH_MS) {
                    long b = computeBtimeSec();
                    if (b != btimeSec) {
                        btimeSec = b;
                        writeStat();
                    }
                    lastStat = now;
                }
                Thread.sleep(UPTIME_REFRESH_MS);
            } catch (InterruptedException e) {
                if (!running) return;
                // 被无关中断，继续
            } catch (Exception e) {
                Log.w(TAG, "maintain loop error: " + e);
            }
        }
    }

    /** btime = 当前 epoch 秒 - 开机至今秒数（elapsedRealtime 含休眠，单调）。 */
    private static long computeBtimeSec() {
        return System.currentTimeMillis() / 1000L - SystemClock.elapsedRealtime() / 1000L;
    }

    /** 删除会与正式文件重复绑定的 .tmoe-container.* 快照。 */
    private void dedupe() {
        if (procDir == null) return;
        for (String name : OWNED) {
            File dup = new File(procDir, ".tmoe-container." + name);
            if (dup.exists() && !dup.delete()) {
                Log.w(TAG, "cannot delete duplicate: " + dup);
            }
        }
    }

    /** 读取现有 stat（正式文件优先，其次 .tmoe-container.stat）作为模板并剥掉 btime 行。 */
    private static String loadStatBody(File dir) {
        String raw = readText(new File(dir, "stat"));
        if (raw == null || raw.trim().isEmpty()) {
            raw = readText(new File(dir, ".tmoe-container.stat"));
        }
        if (raw == null || raw.trim().isEmpty()) {
            raw = DEFAULT_STAT_TEMPLATE;
        }
        StringBuilder sb = new StringBuilder(raw.length() + 32);
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("btime ")) continue; // 由本类动态生成
            if (t.isEmpty() && sb.length() == 0) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void writeUptime() {
        File dir = procDir;
        if (dir == null) return;
        double up = SystemClock.elapsedRealtime() / 1000.0;
        String text = String.format(Locale.US, "%.2f %.2f\n", up, up);
        writeInPlace(new File(dir, "uptime"), text);
    }

    private void writeStat() {
        File dir = procDir;
        String body = statBody;
        if (dir == null || body == null) return;
        String text = body + "btime " + btimeSec + "\n";
        writeInPlace(new File(dir, "stat"), text);
    }

    /**
     * 原地覆盖写：seek(0) → write → setLength。
     *
     * <p>不换 inode，proot 已建立的 bind 源路径持续可读。
     */
    private static void writeInPlace(File f, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            raf.seek(0);
            raf.write(data);
            raf.setLength(data.length);
        } catch (IOException e) {
            Log.w(TAG, "write failed: " + f + " " + e);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String readText(File f) {
        if (f == null || !f.isFile() || !f.canRead()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[Math.max(64, (int) Math.min(f.length(), 1 << 16))];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
