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

    public LocalShellEnvironment() {
        super();
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
        File termuxExec = new File(LocalShellConstants.TERMUX_EXEC_LD_PRELOAD_PATH);
        if (termuxExec.exists()) {
            environment.put("LD_PRELOAD", LocalShellConstants.TERMUX_EXEC_LD_PRELOAD_PATH);
        }

        return environment;
    }

    @Override
    public String[] setupShellCommandArguments(String executable, String[] arguments) {
        // Use the simple ShellUtils (no proot injection like TermuxShellUtils)
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

    public static void init(Context context) {
        // Initialize environment - create necessary directories
        new File(LocalShellConstants.HOME_DIR_PATH).mkdirs();
        new File(LocalShellConstants.TMP_DIR_PATH).mkdirs();
    }

    public static void writeEnvironmentToFile(Context context) {
        // Write environment to a file for shell scripts to source
    }
}
