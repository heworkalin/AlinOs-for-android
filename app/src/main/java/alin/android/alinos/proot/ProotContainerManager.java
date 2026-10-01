package alin.android.alinos.proot;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    public static final String[] CONTAINER_ENV = {
            "HOME=/root",
            "USER=root",
            "HOSTNAME=localhost",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "LANG=C.UTF-8",
            "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    };

    /**
     * 容器登录初始化脚本，写入 {@code /etc/profile.d/001-alinos-login.sh}。
     *
     * <p>参考 tmoe 官方的引入方式（{@code /etc/profile.d/001_login.sh}），但只保留
     * 干净、稳定的部分：加载 {@code /etc/profile.d/permanent/*} 用户环境脚本。
     * <b>不含</b> tmoe 的 VNC / 临时启动项，也<b>不</b> source entrypoint（即不强制 cd ~）。
     *
     * <p>{@code bash -l} 会经 /etc/profile 自动 source 本文件。
     */
    private static final String LOGIN_SCRIPT =
            "# AlinOs login initialization (adapted from tmoe-linux environment/login)\n"
            + "# 加载 /etc/profile.d/permanent/ 下的用户环境脚本；不强制 cd ~。\n"
            + "if [ -d /etc/profile.d/permanent ]; then\n"
            + "    for i in /etc/profile.d/permanent/*; do\n"
            + "        [ -f \"$i\" ] || continue\n"
            + "        chmod a+rx \"$i\" 2>/dev/null\n"
            + "        . \"$i\"\n"
            + "    done\n"
            + "fi\n"
            + "unset i\n";

    /** 把 {@link #CONTAINER_ENV} 写入给定环境表，并清掉宿主专有变量。
     *
     * <p>单一数据源：一次性执行（{@code buildCommand}）与注入式孵化
     * （{@code LocalShellEnvironment}）都从这里取，避免两处不一致。
     */
    public static void applyContainerEnv(java.util.Map<String, String> env) {
        if (env == null) return;
        for (String kv : CONTAINER_ENV) {
            int i = kv.indexOf('=');
            if (i > 0) env.put(kv.substring(0, i), kv.substring(i + 1));
        }
        // 宿主（termux/Android）专有变量对容器无意义，必须移除
        env.remove("LD_PRELOAD");
        env.remove("LD_LIBRARY_PATH");
        env.remove("PREFIX");
    }

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
        // 确保 /proc 时间伪文件（uptime/stat）存在并由守护线程持续维护
        FakeProcMaintainer.get().ensure(rootfs);

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

    /** 拼接完整的 proot 命令行（一次性执行：env -i + bash -lc）。 */
    private static List<String> buildCommand(File rootfs, File proot, String command, String cwd) {
        List<String> cmd = prootPrelude(rootfs, proot, cwd);
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        for (String e : CONTAINER_ENV) cmd.add(e);
        cmd.add("/bin/bash");
        cmd.add("-lc");
        cmd.add(command == null || command.trim().isEmpty() ? "true" : command);
        return cmd;
    }

    /**
     * proot 的起始参数（不含最终要执行的命令）。
     *
     * <p>参考项目早期 {@code termux-shared/TermuxShellUtils.setupShellCommandArguments}
     * 的 proot 注入模式：把 proot 前缀拼在用户 executable 之前，而不是把可执行换成 proot。
     */
    private static List<String> prootPrelude(File rootfs, File proot, String cwd) {
        ensureLoginScript(rootfs);
        List<String> cmd = new ArrayList<>();
        cmd.add(proot.getAbsolutePath());
        cmd.add("--root-id");          // 伪造 uid=0
        cmd.add("--link2symlink");     // 硬链接 → 符号链接
        cmd.add("--kill-on-exit");     // Android 必须，避免 tracee 残留
        cmd.add("--pwd=" + ProotPathMapper.normalize(
                cwd == null || cwd.trim().isEmpty() ? ProotPathMapper.DEFAULT_CWD : cwd.trim()));
        cmd.add("--rootfs=" + rootfs.getAbsolutePath());
        //修复Error, do this: mount -t proc proc \/proc
        cmd.add("-b /proc:/proc");
        // 时间信息由 FakeProcMaintainer 动态伪造后在此覆盖。
        // 注意：绝不能直接 "-b /proc/uptime:/proc/uptime" —— Android hidepid 下
        // proot tracee 无权读取，会刷 "can't sanitize binding ... Permission denied"，
        // 且绑定失败后容器内 ps 仍报 "Unable to get system boot time"。
        appendFakeProcMounts(rootfs, cmd);
        return cmd;
    }

    /**
     * 把 {@code usr/local/etc/tmoe-linux/proot_proc} 下的伪造文件逐个挂到 {@code /proc/<name>}。
     *
     * <p>来源：assets/proot_proc.tar.xz（由 ProotContainerTestActivity 初始化时解包）。
     * {@code .tmoe-container.<name>} 前缀表示运行时从宿主 /proc 抓取的数据。
     *
     * <p>由 {@link FakeProcMaintainer} 接管的 {@code uptime}/{@code stat} 等已生成正式
     * 文件，此时跳过对应的 {@code .tmoe-container.*}，避免同名挂载顺序不确定。
     */
    private static void appendFakeProcMounts(File rootfs, List<String> cmd) {
        File procDir = new File(rootfs, "usr/local/etc/tmoe-linux/proot_proc");
        File[] files = procDir.listFiles();
        if (files == null) return;

        // 正式文件名集合（非 .tmoe-container.* 前缀），这些优先。
        java.util.Set<String> plain = new java.util.HashSet<>();
        for (File f : files) {
            if (f.isFile() && !f.getName().startsWith(".tmoe-container.")) {
                plain.add(f.getName());
            }
        }
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (name.startsWith(".tmoe-container.")) {
                String alias = name.substring(".tmoe-container.".length());
                // 正式文件已由 FakeProcMaintainer 接管，跳过重复项
                if (plain.contains(alias)) continue;
                name = alias;
            }
            cmd.add("-b");
            cmd.add(f.getAbsolutePath() + ":/proc/" + name);
        }
    }

    /**
     * 注入式启动：把任意 executable / arguments 包进 proot 容器。
     *
     * <p>供 {@code LocalShellEnvironment.setupShellCommandArguments} 在
     * {@code ProotMod} 为 true 时调用。这样终端只需传普通的 {@code /bin/bash -l}，
     * 由本方法自动为它在容器内执行（与项目早期 TermuxShellUtils 的做法一致）。
     *
     * @return 完整 argv；第一个元素为 proot 可执行文件路径
     */
    public static String[] wrapWithProot(Context ctx, String executable, String[] arguments) {
        File rootfs = containerDir(ctx);
        File proot = new File(new File(ctx.getApplicationInfo().nativeLibraryDir), "libproot.so");
        FakeProcMaintainer.get().ensure(rootfs);

        List<String> cmd = prootPrelude(rootfs, proot, ProotPathMapper.DEFAULT_CWD);
        cmd.add(executable == null || executable.trim().isEmpty() ? "/bin/bash" : executable);
        if (arguments != null) {
            for (String a : arguments) cmd.add(a);
        }
        return cmd.toArray(new String[0]);
    }

    /**
     * 确保容器内登录初始化脚本存在（幂等）。
     *
     * <p>同时创建空的 {@code /etc/profile.d/permanent/} 目录，供用户放置永久环境脚本。
     */
    private static void ensureLoginScript(File rootfs) {
        File profileD = new File(rootfs, "etc/profile.d");
        if (!profileD.isDirectory() && !profileD.mkdirs()) return;
        // 空目录，用户可往里放永久环境脚本
        new File(profileD, "permanent").mkdirs();

        File script = new File(profileD, "001-alinos-login.sh");
        // 不存在才写入；已存在则保持原样（不校验内容、不覆盖）。
        if (script.exists()) return;
        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(LOGIN_SCRIPT.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w("ProotContainerManager", "write login script failed: " + e);
            return;
        }
        script.setReadable(true, false);
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
