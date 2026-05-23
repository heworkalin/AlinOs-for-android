package alin.android.alinos.localshell;

import com.termux.shared.termux.TermuxConstants;

/**
 * Path constants for LocalShell environment.
 */
public class LocalShellConstants {

    public static final String FILES_DIR_PATH;
    public static final String PREFIX_DIR_PATH;
    public static final String BIN_DIR_PATH;
    public static final String LIB_DIR_PATH;
    public static final String ETC_DIR_PATH;
    public static final String TMP_DIR_PATH;
    public static final String HOME_DIR_PATH;
    public static final String VAR_DIR_PATH;

    public static final String SHELL_PATH = "/system/bin/sh";
    public static final String TERMUX_EXEC_PATH;
    public static final String TERMUX_EXEC_LD_PRELOAD_PATH;

    static {
        String filesDir = TermuxConstants.TERMUX_FILES_DIR_PATH;
        // Custom build uses files/default as prefix (not files/usr like standard Termux)
        String prefixDir = filesDir + "/default";

        FILES_DIR_PATH = filesDir;
        PREFIX_DIR_PATH = prefixDir;
        BIN_DIR_PATH = prefixDir + "/bin";
        LIB_DIR_PATH = prefixDir + "/lib";
        ETC_DIR_PATH = prefixDir + "/etc";
        TMP_DIR_PATH = prefixDir + "/tmp";
        HOME_DIR_PATH = filesDir + "/home";
        VAR_DIR_PATH = prefixDir + "/var";

        TERMUX_EXEC_PATH = BIN_DIR_PATH + "/termux-exec";
        TERMUX_EXEC_LD_PRELOAD_PATH = LIB_DIR_PATH + "/libtermux-exec.so";
    }

    private LocalShellConstants() {}
}
