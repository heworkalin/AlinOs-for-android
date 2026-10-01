package alin.android.alinos.proot;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 容器环境数据源（放在 profile.d，用户可编辑）。
     *
     * <p>人：{@code bash -l} 会经 /etc/profile 自动 source 它；
     * AI：由 {@link #applyContainerEnv} / {@code buildCommand} 解析后动态追加。
     */
    private static final String ENV_FILE_REL = "etc/profile.d/000-alinos-env.sh";

    /** 环境文件不存在时写入的默认内容。 */
    private static final String DEFAULT_ENV_FILE =
            "export HOME=\"/root\"\n"
            + "export USER=\"root\"\n"
            + "export HOSTNAME=\"localhost\"\n"
            + "export TERM=\"xterm-256color\"\n"
            + "export TMPDIR=\"/tmp\"\n"
            + "export LANG=\"C.UTF-8\"\n"
            + "export SHELL=\"/bin/bash\"\n"
            + "export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\n";

    /**
     * 兜底：解析后仍缺失的关键变量，强制补上。
     * 保证 AI 工具调用时环境至少是可用的（PATH/HOME 等）。
     */
    private static final String[] REQUIRED_ENV = {
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
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
            + "# 幂等：同一会话内 permanent/* 只执行一次，\n"
            + "# 即使同时被 /etc/profile 和 ~/.profile 引用也不会重复跑。\n"
            + "if [ -z \"$ALINOS_LOGIN_LOADED\" ]; then\n"
            + "    ALINOS_LOGIN_LOADED=1\n"
            + "    export ALINOS_LOGIN_LOADED\n"
            + "    if [ -d /etc/profile.d/permanent ]; then\n"
            + "        for i in /etc/profile.d/permanent/*; do\n"
            + "            [ -f \"$i\" ] || continue\n"
            + "            chmod a+rx \"$i\" 2>/dev/null\n"
            + "            . \"$i\"\n"
            + "        done\n"
            + "    fi\n"
            + "    unset i\n"
            + "fi\n";

    /** 标记：用于判断 /root/.profile 是不是我们写/追加过的。 */
    private static final String ROOT_PROFILE_MARKER = "# AlinOs profile";

    /**
     * 追加/写入 {@code /root/.profile} 的片段。
     *
     * <p>不依赖 {@code /etc/profile} 是否 source {@code profile.d}：
     * login shell 一定会读 {@code ~/.profile}，这里主动把容器环境和永久脚本接上。
     * {@code 001} 已幂等，重复 source 无副作用。
     */
    private static final String DEFAULT_ROOT_PROFILE =
            ROOT_PROFILE_MARKER + "\n"
            + "# 由 AlinOs 写入：保证 login shell 加载容器环境与 ~/.bashrc\n"
            + "[ -r /etc/profile.d/000-alinos-env.sh ] && . /etc/profile.d/000-alinos-env.sh\n"
            + "[ -r /etc/profile.d/001-alinos-login.sh ] && . /etc/profile.d/001-alinos-login.sh\n"
            + "if [ -n \"$BASH_VERSION\" ] && [ -f \"$HOME/.bashrc\" ]; then\n"
            + "    . \"$HOME/.bashrc\"\n"
            + "fi\n"
            + ROOT_PROFILE_MARKER + " end\n";

    /**
     * 把容器环境写入给定环境表：解析环境文件 + 关键变量兜底 + 清掉宿主专有变量。
     *
     * <p>单一数据源：人由 {@code bash -l} 自动 source 环境文件，
     * AI 侧由本方法解析后动态追加，两边最终一致。
     */
    public static void applyContainerEnv(Context ctx, java.util.Map<String, String> env) {
        if (env == null) return;
        File rootfs = containerDir(ctx);
        ensureEnvFile(rootfs);
        for (String kv : loadContainerEnv(rootfs)) {
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
        // 用持久目录（getFilesDir）而非 cache：Android 会在后台/低存储时清空
        // cache，l2s 真身一旦被清，rootfs 内所有伪硬链接立即悬空并报 ENOENT。
        File tmp = tmpDir(ctx);
        File l2s = l2sDir(ctx);
        tmp.mkdirs();
        l2s.mkdirs();

        // 必须用规范化后的真实路径：Android 的 /data/user/0/... 是软链接，
        // 而 proot 用 O_NOFOLLOW 打开 PROOT_L2S_DIR/PROOT_TMP_DIR，
        // 碰到软链接会拒绝，导致 link2symlink 拿不到目录、中间路径变空。
        String tmpPath = canonicalPath(tmp);
        String l2sPath = canonicalPath(l2s);

        List<String> cmd = buildCommand(rootfs, proot, command, cwd);

        List<String> env = new ArrayList<>();
        if (loader.exists()) env.add("PROOT_LOADER=" + loader.getAbsolutePath());
        env.add("PROOT_TMP_DIR=" + tmpPath);
        env.add("PROOT_L2S_DIR=" + l2sPath);
        // 关闭 f2fs workaround：probe_f2fs_bug() 在 Android /data 上易误报，
        // 一旦启用，should_skip_file_access_due_to_f2fs_bug() 用 readdir
        // 判定文件是否存在，而 f2fs 目录项缓存会漏掉刚创建的文件，
        // 导致 chown/open 等对普通文件、l2s 伪硬链接直接返回 ENOENT。
        env.add("PROOT_F2FS_WORKAROUND=0");

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
        for (String e : loadContainerEnv(rootfs)) cmd.add(e);
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
        // SysV IPC（信号量/共享内存）：tmoe 默认开启，apt/dpkg 解压时依赖它，
        // 缺失会出现 "paste subprocess was killed by signal (Broken pipe)"、
        // "error setting ownership of '....dpkg-new': No such file or directory"
        cmd.add("--sysvipc");
        // -L：修正 lstat 对符号链接返回的 size（tmoe PROOT_L=true）。
        // dpkg 对含符号链接的包（如 perl 的 /usr/bin/perlthanks）靠 lstat 判断，
        // 缺 -L 时会把 .dpkg-new 处理错，报 ownership No such file or directory。
        cmd.add("-L");
        cmd.add("--pwd=" + ProotPathMapper.normalize(
                cwd == null || cwd.trim().isEmpty() ? ProotPathMapper.DEFAULT_CWD : cwd.trim()));
        cmd.add("--rootfs=" + rootfs.getAbsolutePath());
        // /dev：dpkg/apt 需要在 /dev/pts 下创建伪终端，否则报
        //   "Can not write log (Is /dev/pts mounted?) - posix_openpt (2: No such file or directory)"
        cmd.add("-b /dev:/dev");
        // Android 的 /dev 缺少以下标准节点，dpkg-deb 的 paste 子进程 / apt 依赖它们，
        // 缺失会导致 "paste subprocess was killed by signal (Broken pipe)"，
        // 进而 ".dpkg-new: No such file or directory"（chown 失败）。
        // 参考 tmoe 的 MOUNT_DEV 段。
        cmd.add("-b /proc/self/fd:/dev/fd");
        cmd.add("-b /proc/self/fd/0:/dev/stdin");
        cmd.add("-b /proc/self/fd/1:/dev/stdout");
        cmd.add("-b /proc/self/fd/2:/dev/stderr");
        cmd.add("-b /dev/urandom:/dev/random");
        cmd.add("-b /dev/null:/dev/tty0");
        cmd.add("-b " + new File(rootfs, "tmp").getAbsolutePath() + ":/dev/shm");
        //修复Error, do this: mount -t proc proc \/proc
        cmd.add("-b /proc:/proc");
        // /sys：部分工具（如 lscpu、某些安装脚本）会读取
        cmd.add("-b /sys:/sys");
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
        // 保证 /tmp 存在：缺失时 proot 的 canonicalize("/tmp/...") 会对 "tmp"
        // 组件返回 -ENOENT，随后所有 touch/open/chown 都报
        // "No such file or directory"（且 translate 日志没有 "->" 目标行）。
        new File(rootfs, "tmp").mkdirs();

        File profileD = new File(rootfs, "etc/profile.d");
        if (!profileD.isDirectory() && !profileD.mkdirs()) return;
        // 空目录，用户可往里放永久环境脚本
        new File(profileD, "permanent").mkdirs();

        File script = new File(profileD, "001-alinos-login.sh");
        // 内容一致才跳过；不一致就重写。旧版本写入的脚本如果不更新，
        // 后来新增的加载逻辑（如 /etc/profile.d/permanent/*）永远不会生效。
        byte[] wanted = LOGIN_SCRIPT.getBytes(StandardCharsets.UTF_8);
        byte[] current = null;
        if (script.exists()) {
            try {
                current = java.nio.file.Files.readAllBytes(script.toPath());
            } catch (IOException ignored) {
                // 读失败按“需要重写”处理
            }
        }
        if (current == null || !java.util.Arrays.equals(current, wanted)) {
            try (FileOutputStream out = new FileOutputStream(script)) {
                out.write(wanted);
            } catch (IOException e) {
                Log.w("ProotContainerManager", "write login script failed: " + e);
                return;
            }
            script.setReadable(true, false);
        }

        ensureRootProfile(rootfs);
    }

    /**
     * 保证 {@code /root/.profile} 会加载容器环境。
     *
     * <p>缺失则创建；已存在但没带标记则追加（不动原有内容）；带标记则跳过。
     */
    private static void ensureRootProfile(File rootfs) {
        File home = new File(rootfs, "root");
        if (!home.isDirectory() && !home.mkdirs()) return;

        File profile = new File(home, ".profile");
        byte[] wanted = DEFAULT_ROOT_PROFILE.getBytes(StandardCharsets.UTF_8);

        if (profile.exists()) {
            String text = readText(profile);
            if (text != null && text.contains(ROOT_PROFILE_MARKER)) return;
            try (FileOutputStream out = new FileOutputStream(profile, true)) {
                out.write("\n".getBytes(StandardCharsets.UTF_8));
                out.write(wanted);
            } catch (IOException e) {
                Log.w("ProotContainerManager", "append root .profile failed: " + e);
            }
            return;
        }

        try (FileOutputStream out = new FileOutputStream(profile)) {
            out.write(wanted);
        } catch (IOException e) {
            Log.w("ProotContainerManager", "write root .profile failed: " + e);
            return;
        }
        profile.setReadable(true, false);
    }

    /** 确保环境文件存在（不存在才写入默认内容，不覆盖用户改动）。 */
    private static void ensureEnvFile(File rootfs) {
        File envFile = new File(rootfs, ENV_FILE_REL);
        if (envFile.exists()) return;
        File parent = envFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(envFile)) {
            out.write(DEFAULT_ENV_FILE.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w("ProotContainerManager", "write env file failed: " + e);
            return;
        }
        envFile.setReadable(true, false);
    }

    /**
     * 最简单的环境解析链：解析环境文件 → KEY=VALUE 列表。
     *
     * <p>规则：跳过空行与 {@code #} 注释；去掉行首 {@code export}；
     * 只接受标准变量名；去掉成对引号。不做 shell 变量展开
     * （{@code ${...}} 原样保留，由 shell 处理）。
     */
    private static List<String> parseEnvFile(File f) {
        List<String> out = new ArrayList<>();
        String text = readText(f);
        if (text == null) return out;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;      // 空行 / 注释
            if (line.startsWith("export ")) line = line.substring(7).trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;                                      // 非赋值行
            String key = line.substring(0, eq).trim();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;       // 非标准变量名
            String val = line.substring(eq + 1).trim();
            if (val.length() >= 2) {
                char q = val.charAt(0);
                if ((q == '"' || q == '\'') && val.charAt(val.length() - 1) == q) {
                    val = val.substring(1, val.length() - 1);
                }
            }
            out.add(key + "=" + val);
        }
        return out;
    }

    /** 解析环境文件并做关键变量兜底，返回最终 KEY=VALUE 列表。 */
    private static List<String> loadContainerEnv(File rootfs) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String kv : parseEnvFile(new File(rootfs, ENV_FILE_REL))) {
            int i = kv.indexOf('=');
            if (i > 0) env.put(kv.substring(0, i), kv.substring(i + 1));
        }
        // 兜底：关键词变量缺失则强制补上，保证 AI 工具调用环境正常
        for (String kv : REQUIRED_ENV) {
            int i = kv.indexOf('=');
            if (i > 0) env.putIfAbsent(kv.substring(0, i), kv.substring(i + 1));
        }
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) list.add(e.getKey() + "=" + e.getValue());
        return list;
    }

    /** 读取文本文件；失败返回 null。 */
    private static String readText(File f) {
        if (f == null || !f.isFile() || !f.canRead()) return null;
        long len = f.length();
        if (len <= 0 || len > (1 << 20)) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) len];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * l2s（伪硬链接真身）目录。
     *
     * <p><b>必须位于 rootfs 内部</b>。proot 在 canonicalize 伪硬链接时，会把
     * 符号链接目标（l2s 里的 backing file）当作 guest 路径重新解析：
     * 只有目标在 rootfs 内，{@code detranslate_path()} 才能剥掉 rootfs 前缀,
     * 解析成功；放在 rootfs 外会直接返回 -ENOENT，导致 chown/open
     * 报 "No such file or directory"（参见 proot tests/test-8d3c07f5.sh，
     * 官方用 {@code ${ROOTFS}/.l2s}）。
     */
    public static File l2sDir(Context ctx) {
        return new File(containerDir(ctx), ".l2s");
    }

    /** proot 临时目录：放持久目录，避免 cache 被系统清空。 */
    public static File tmpDir(Context ctx) {
        return new File(ctx.getFilesDir(), "proot_tmp");
    }

    /** 解析软链接，返回真实路径；失败则退回绝对路径。 */
    public static String canonicalPath(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
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
