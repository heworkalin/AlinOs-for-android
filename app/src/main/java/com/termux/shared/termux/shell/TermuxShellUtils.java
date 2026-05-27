package com.termux.shared.termux.shell;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.file.filesystem.FileTypes;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public class TermuxShellUtils {

    private static final String LOG_TAG = "TermuxShellUtils";

    /**
     * Setup shell command arguments for the execute. The file interpreter may be prefixed to
     * command arguments if needed.
     */
    @NonNull
    public static String[] setupShellCommandArguments(@NonNull String executable, @Nullable String[] arguments) {
        // The file to execute may either be:
        // - An elf file, in which we execute it directly.
        // - A script file without shebang, which we execute with our standard shell $PREFIX/bin/sh instead of the
        //   system /system/bin/sh. The system shell may vary and may not work at all due to LD_LIBRARY_PATH.
        // - A file with shebang, which we try to handle with e.g. /bin/foo -> $PREFIX/bin/foo.
        String interpreter = null;
        try {
            File file = new File(executable);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        // Elf file, do nothing.
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        // Try to parse shebang.
                        StringBuilder builder = new StringBuilder();
                        for (int i = 2; i < bytesRead; i++) {
                            char c = (char) buffer[i];
                            if (c == ' ' || c == '\n') {
                                if (builder.length() == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    String shebangExecutable = builder.toString();
                                    if (shebangExecutable.startsWith("/usr") || shebangExecutable.startsWith("/bin")) {
                                        String[] parts = shebangExecutable.split("/");
                                        String binary = parts[parts.length - 1];
                                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + binary;
                                    }
                                    break;
                                }
                            } else {
                                builder.append(c);
                            }
                        }
                    } else {
                        // No shebang and no ELF, use standard shell.
                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                    }
                }
            }
        } catch (IOException e) {
            // Ignore.
        }

        List<String> result = new ArrayList<>();
        if (interpreter != null) result.add(interpreter);
        result.add(executable);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

    /**
     * Clear the termux tmp directory.
     */
    public static void clearTermuxTMPDIR(boolean clearAll) {
        String tmpDirPath = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH;
        if (tmpDirPath == null) return;
        Logger.logDebug(LOG_TAG, "clearTermuxTMPDIR called, clearAll=" + clearAll);
        File tmpDir = new File(tmpDirPath);
        if (tmpDir.isDirectory()) {
            File[] files = tmpDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        file.delete();
                    }
                }
            }
        }
    }

}
