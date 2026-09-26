package alin.android.alinos.proot;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Proot 容器管理器（Ubuntu 24.04 noble）。
 *
 * <p>职责：
 * <ul>
 *   <li>定位容器 rootfs（{@code files/containers/proot/ubuntu-noble_<arch>}）；</li>
 *   <li>拼接 proot 启动命令（含伪造 /proc 挂载、env -i、root-id）；</li>
 *   <li>单次执行命令并回收 stdout / stderr / exit code。</li>
 * </ul>
 *
 * <p>设计参考 tmoe 的 {@code share/container/proot/startup} 与项目早期的
 * {@code ProotBashTool}（一次性执行，不维持 PTY 会话）。
 *
 * <p>proot / loader / tar 均伪装成 {@code lib*.so} 放在
 * {@code nativeLibraryDir}，因为 Android 只允许在该目录下执行 ELF。
 */
public final class ProotContainerManager {

    /** 发行版标识（与 ProotContainerTestActivity 保持一致）。 */
    public static final String DISTRO = "ubuntu";
    public static final String CODENAME = "noble";

    /** 容器内固定环境变量：env -i 之后只注入这些，避免 Android 环境污染容器。 */
    private static final String[] CONTAINER_ENV = {
            "HOME=/root",
            "USER=root",
            "HOSTNAME=localhost",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "LANG=C.UTF-8",
            "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    };

    private ProotContainerManager() {
    }

    /** 单次执行结果。 */
    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timeout;

        public Result(int exitCode, String stdout, String stderr, boolean timeout) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.timeout = timeout;
        }

        public boolean ok() {
            return exitCode == 0 && !timeout;
        }
    }

    // ---------------------------------------------------------------------
    // 路径
    // ---------------------------------------------------------------------

    /** 设备 ABI → rootfs 架构名（与下载的 LXC 镜像命名一致）。 */
    public static String arch() {
        String abi = Build.SUPPORTED_ABIS[0];
        if (abi.startsWith("arm64")) return "arm64";
        if (abi.startsWith("armeabi")) return "armhf";
        if (abi.startsWith("x86_64")) return "amd64";
        if (abi.startsWith("x86")) return "i386";
        return "arm64";
    }

    /** 容器 rootfs 目录。 */
    public static File containerDir(Context ctx) {
        return new File(ctx.getFilesDir(),
                "containers/proot/" + DISTRO + "-" + CODENAME + "_" + arch());
    }

    /** 容器是否已解压就绪。 */
    public static boolean isReady(Context ctx) {
        return new File(containerDir(ctx), "etc/os-release").exists();
    }

    // ---------------------------------------------------------------------
    // 执行
    // ---------------------------------------------------------------------

    /**
     * 在容器内一次性执行命令。
     *
     * @param command   shell 命令（以 {@code bash -lc} 执行）
     * @param timeoutMs 超时毫秒；超时会强杀并返回 {@link Result#timeout}=true
     */
    public static Result exec(Context ctx, String command, long timeoutMs) {
        return exec(ctx, command, timeoutMs, ProotPathMapper.DEFAULT_CWD);
    }

    /** 在指定容器工作目录下执行命令（cwd 为容器内路径）。 */
    public static Result exec(Context ctx, String command, long timeoutMs, String cwd) {
        File rootfs = containerDir(ctx);
        if (!isReady(ctx)) {
            return new Result(-1, "", "container not deployed (missing etc/os-release): " + rootfs, false);
        }

        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        if (!proot.exists()) {
            return new Result(-1, "", "libproot.so not found: " + proot, false);
        }
        File loader = new File(nativeDir, "libproot-loader.so");
        File tmp = new File(ctx.getCacheDir(), "proot_tmp");
        File l2s = new File(ctx.getCacheDir(), "proot_l2s");
        tmp.mkdirs();
        l2s.mkdirs();

        List<String> cmd = buildCommand(rootfs, proot, command, cwd);

        List<String> env = new ArrayList<>();
        if (loader.exists()) env.add("PROOT_LOADER=" + loader.getAbsolutePath());
        env.add("PROOT_TMP_DIR=" + tmp.getAbsolutePath());
        env.add("PROOT_L2S_DIR=" + l2s.getAbsolutePath());

        Process p = null;
        try {
            final Process proc = Runtime.getRuntime().exec(
                    cmd.toArray(new String[0]),
                    env.toArray(new String[0]),
                    rootfs);
            p = proc;

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread tOut = new Thread(() -> drain(proc.getInputStream(), out), "proot-stdout");
            Thread tErr = new Thread(() -> drain(proc.getErrorStream(), err), "proot-stderr");
            tOut.start();
            tErr.start();

            boolean finished = p.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroy();
                p.waitFor(2, TimeUnit.SECONDS);
            }
            try { tOut.join(1500); } catch (InterruptedException ignored) { }
            try { tErr.join(1500); } catch (InterruptedException ignored) { }

            int exit = finished ? p.exitValue() : -1;
            return new Result(exit, out.toString(), err.toString(), !finished);
        } catch (Exception e) {
            return new Result(-1, "", "exec failed: " + e, false);
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    /** 拼接完整的 proot 命令行。 */
    private static List<String> buildCommand(File rootfs, File proot, String command, String cwd) {
        List<String> cmd = new ArrayList<>();
        cmd.add(proot.getAbsolutePath());
        cmd.add("--root-id");          // 伪造 uid=0
        cmd.add("--link2symlink");     // 硬链接 → 符号链接
        cmd.add("--kill-on-exit");     // Android 必须，避免 tracee 残留
        cmd.add("--pwd=" + ProotPathMapper.normalize(
                cwd == null || cwd.trim().isEmpty() ? ProotPathMapper.DEFAULT_CWD : cwd.trim()));
        cmd.add("--rootfs=" + rootfs.getAbsolutePath());

        // 伪造 /proc：默认启用（LXC 内部分程序依赖这些静态文件）
        appendFakeProcMounts(rootfs, cmd);

        cmd.add("/usr/bin/env");
        cmd.add("-i");
        for (String e : CONTAINER_ENV) cmd.add(e);
        cmd.add("/bin/bash");
        cmd.add("-lc");
        cmd.add(command == null || command.trim().isEmpty() ? "true" : command);
        return cmd;
    }

    /**
     * 把 {@code usr/local/etc/tmoe-linux/proot_proc} 下的伪造文件逐个挂到 {@code /proc/<name>}。
     *
     * <p>来源：assets/proot_proc.tar.xz（由 ProotContainerTestActivity 初始化时解包）。
     * {@code .tmoe-container.<name>} 前缀表示运行时从宿主 /proc 抓取的数据。
     */
    private static void appendFakeProcMounts(File rootfs, List<String> cmd) {
        File procDir = new File(rootfs, "usr/local/etc/tmoe-linux/proot_proc");
        File[] files = procDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (name.startsWith(".tmoe-container.")) {
                name = name.substring(".tmoe-container.".length());
            }
            cmd.add("-b");
            cmd.add(f.getAbsolutePath() + ":/proc/" + name);
        }
    }

    private static void drain(InputStream in, StringBuilder sb) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (sb) {
                    sb.append(line).append('\n');
                }
            }
        } catch (Exception ignored) {
        }
    }
}
