package alin.android.alinos.localshell;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.UnixShellEnvironment;

import java.io.File;
import java.util.HashMap;

/**
 * Shell environment setup for LocalShell sessions.
 */
public class LocalShellEnvironment extends UnixShellEnvironment implements IShellEnvironment {

    // ProotMod flag, referenced by TermuxShellUtils for proot injection
    public static boolean ProotMod = false;

    /**
     * 供 {@link #setupShellCommandArguments} 使用（该方法没有 Context 参数）。
     * 由 {@code LocalShellService.createTermuxSession} 构造时传入。
     */
    private final Context mContext;

    public LocalShellEnvironment() {
        this(null);
    }

    public LocalShellEnvironment(Context context) {
        super();
        this.mContext = context;
    }

    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {
        // Delegate to setupShellCommandEnvironment for consistent env vars
        return setupShellCommandEnvironment(currentPackageContext, null);
    }

    @Override
    public HashMap<String, String> setupShellCommandEnvironment(Context context, ExecutionCommand executionCommand) {
        HashMap<String, String> environment = new HashMap<>();

        // Android system properties
        environment.put("ANDROID_ROOT", System.getenv("ANDROID_ROOT") != null ? System.getenv("ANDROID_ROOT") : "/system");
        environment.put("ANDROID_DATA", System.getenv("ANDROID_DATA") != null ? System.getenv("ANDROID_DATA") : "/data");
        environment.put("ANDROID_ART_ROOT", System.getenv("ANDROID_ART_ROOT") != null ? System.getenv("ANDROID_ART_ROOT") : "/apex/com.android.art");
        environment.put("ANDROID_I18N_ROOT", System.getenv("ANDROID_I18N_ROOT") != null ? System.getenv("ANDROID_I18N_ROOT") : "/apex/com.android.i18n");
        environment.put("ANDROID_TZDATA_ROOT", System.getenv("ANDROID_TZDATA_ROOT") != null ? System.getenv("ANDROID_TZDATA_ROOT") : "/apex/com.android.tzdata");

        // Standard variables
        environment.put("HOME", LocalShellConstants.HOME_DIR_PATH);
        environment.put("LANG", "en_US.UTF-8");
        environment.put("PREFIX", LocalShellConstants.PREFIX_DIR_PATH);
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        environment.put("TMPDIR", LocalShellConstants.TMP_DIR_PATH);
        environment.put("SHELL", LocalShellConstants.SHELL_PATH);

        // PATH
        String path = LocalShellConstants.BIN_DIR_PATH ;
        environment.put("PATH", path);

        // LD_LIBRARY_PATH
        environment.put("LD_LIBRARY_PATH", LocalShellConstants.LIB_DIR_PATH);

        // LD_PRELOAD for termux-exec if it exists
        // 注意：Proot 模式下必须禁用！termux-exec 会把 /usr/bin/env 等路径改写成
        // termux 前缀，而容器内不存在该路径，proot 会崩：
        //   execve("/usr/bin/env"): No such file or directory
        //   proot error: can't chmod '.../proot_tmp/proot-...'
        File termuxExec = new File(LocalShellConstants.TERMUX_EXEC_LD_PRELOAD_PATH);
        if (!ProotMod && termuxExec.exists()) {
            environment.put("LD_PRELOAD", LocalShellConstants.TERMUX_EXEC_LD_PRELOAD_PATH);
        } else {
            environment.remove("LD_PRELOAD");
        }

        // Proot 模式：proot / proot-loader / tar 均伪装成 lib*.so 放在 nativeLibraryDir，
        // 启动 proot 依赖这些进程级环境变量。
        // 由 LocalShellTestActivity 孵化容器 session 时临时置 ProotMod=true。
        if (ProotMod && context != null) {
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;

            File loader = new File(nativeDir, "libproot-loader.so");
            if (loader.exists()) {
                environment.put("PROOT_LOADER", loader.getAbsolutePath());
            }

            File tmp = new File(context.getFilesDir(), "proot_tmp");
            tmp.mkdirs();
            environment.put("PROOT_TMP_DIR", canonicalPath(tmp));

            // l2s 必须位于 rootfs 内部（proot 官方测试用 ${ROOTFS}/.l2s）；
            // 放 rootfs 外会让伪硬链接在 canonicalize 阶段被判成 ENOENT。
            File l2s = alin.android.alinos.proot.ProotContainerManager.l2sDir(context);
            l2s.mkdirs();
            environment.put("PROOT_L2S_DIR", canonicalPath(l2s));
            // 关闭 f2fs workaround，原因见 ProotContainerManager。
            environment.put("PROOT_F2FS_WORKAROUND", "0");

            // 容器内环境：统一从 ProotContainerManager.CONTAINER_ENV 引入，
            // 单一数据源，避免两处维护不一致。
            // 否则 PATH 还是宿主路径，login shell 的 /etc/profile 里连 `id` 都找不到，
            // PATH 无法被修正，导致 ls/id/groups 全部 command not found。
            alin.android.alinos.proot.ProotContainerManager.applyContainerEnv(context, environment);
        }

        return environment;
    }

    @Override
    public String[] setupShellCommandArguments(String executable, String[] arguments) {
        // Proot 模式：注入式启动（参考项目早期 termux-shared/TermuxShellUtils 的做法）。
        // 只有点击「容器」按钮孵化成内部终端时才置 ProotMod=true；
        // 默认终端（addNewSession）不注入。
        if (ProotMod && mContext != null) {
            return alin.android.alinos.proot.ProotContainerManager
                    .wrapWithProot(mContext, executable, arguments);
        }
        // 普通本地环境：不注入 proot
        return com.termux.shared.shell.ShellUtils.setupShellCommandArguments(executable, arguments);
    }

    @Override
    public String getDefaultWorkingDirectoryPath() {
        return LocalShellConstants.HOME_DIR_PATH;
    }

    @Override
    public String getDefaultBinPath() {
        return LocalShellConstants.BIN_DIR_PATH;
    }

    /** 解析软链接，返回真实路径；失败则退回绝对路径。 */
    private static String canonicalPath(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    public static void init(Context context) {
        // Initialize environment - create necessary directories
        new File(LocalShellConstants.HOME_DIR_PATH).mkdirs();
        new File(LocalShellConstants.TMP_DIR_PATH).mkdirs();
    }

    public static void writeEnvironmentToFile(Context context) {
        // Write environment to a file for shell scripts to source
    }
}
