package alin.android.alinos.proot;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按文件串行化变更操作（对齐 pi 的 {@code withFileMutationQueue}）。
 *
 * <p>同一文件的 write / edit 会串行执行，避免并发读-改-写互相覆盖。
 * 锁按宿主真实路径区分，用完即释放。
 */
public final class FileMutationQueue {

    private FileMutationQueue() {
    }

    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    /** 可能抛受检异常的操作。 */
    public interface Action<T> {
        T run() throws Exception;
    }

    public static <T> T run(File file, Action<T> action) throws Exception {
        String key;
        try {
            key = file.getCanonicalPath();
        } catch (Exception e) {
            key = file.getAbsolutePath();
        }
        ReentrantLock lock = LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.run();
        } finally {
            lock.unlock();
            // 没有等待者时清理，避免 map 无限增长（轻微竞争可接受）。
            if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                LOCKS.remove(key, lock);
            }
        }
    }
}
