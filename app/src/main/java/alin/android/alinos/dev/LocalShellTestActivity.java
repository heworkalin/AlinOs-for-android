package alin.android.alinos.dev;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManager;
import android.widget.Toast;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import alin.android.alinos.localshell.LocalShellService;
import alin.android.alinos.tools.AmApkInstaller;

/**
 * Standalone terminal activity bound to LocalShellService.
 * Extends TermuxActivity to reuse all UI and view clients.
 * Only overrides service binding and session management.
 */
public class LocalShellTestActivity extends TermuxActivity {

    private static final String LOG_TAG = "LocalShellTestActivity";

    @Override
    protected Intent createServiceIntent() {
        return new Intent(this, LocalShellService.class);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        LocalShellService.LocalBinder localBinder = (LocalShellService.LocalBinder) binder;
        mTermuxService = localBinder.service;

        setTermuxSessionsListView();

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (isVisible()) {
                setupEnvironmentIfNeeded(() -> {
                    AmApkInstaller.installIfNeeded(LocalShellTestActivity.this);
                    if (mTermuxService == null) return;
                    try {
                        getTermuxTerminalSessionClient().addNewSession(false, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished
                    }
                });
            } else {
                finishActivityIfNotFinishing();
            }
        } else {
            getTermuxTerminalSessionClient().setCurrentSession(
                getTermuxTerminalSessionClient().getCurrentStoredSessionOrLast());
        }

        mTermuxService.setTermuxTerminalSessionClient(getTermuxTerminalSessionClient());
        // Force screen refresh after session client is wired up
        if (getCurrentSession() != null) {
            getTerminalView().onScreenUpdated();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");
        finishActivityIfNotFinishing();
    }

    // ── Environment setup (TAR-based, replaces TermuxInstaller) ──

    private void setupEnvironmentIfNeeded(Runnable whenDone) {
        String prefixPath = alin.android.alinos.localshell.LocalShellConstants.PREFIX_DIR_PATH;
        java.io.File prefixDir = new java.io.File(prefixPath);
        java.io.File bashFile = new java.io.File(prefixDir, "bin/bash");

        Logger.logInfo(LOG_TAG, "Checking environment at: " + prefixPath);
        Logger.logInfo(LOG_TAG, "  prefix exists: " + prefixDir.isDirectory() + ", bash exists: " + bashFile.exists());

        if (prefixDir.isDirectory() && bashFile.exists()) {
            Logger.logInfo(LOG_TAG, "Environment already installed, skipping extraction");
            whenDone.run();
            return;
        }

        Logger.logInfo(LOG_TAG, "Environment not found, starting TAR extraction...");
        try { Toast.makeText(this, "正在安装环境，请稍候...", Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}

        // Show loading dialog (compat-safe)
        final android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setMessage("正在解压环境文件 (约60MB)...");
        progressDialog.setCancelable(false);
        progressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
        try { progressDialog.show(); } catch (Exception ignored) {}

        new Thread(() -> {
            try {
                String libDir = getApplicationInfo().nativeLibraryDir;
                Logger.logInfo(LOG_TAG, "Native library dir: " + libDir);

                java.io.File libTar = new java.io.File(libDir, "libtar.so");
                Logger.logInfo(LOG_TAG, "libtar path: " + libTar.getAbsolutePath() + ", exists: " + libTar.exists());

                String abi = android.os.Build.SUPPORTED_ABIS[0];
                Logger.logInfo(LOG_TAG, "Device ABI: " + abi);

                // tar.gz files are in assets/ (not jniLibs — some build systems won't package multi-dot .so files)
                String tarGzName = null;
                if (abi.startsWith("arm64")) {
                    tarGzName = "files.default.aarch64.tar.gz.so";
                } else if (abi.startsWith("armeabi")) {
                    tarGzName = "files.default.arm.tar.gz.so";
                } else if (abi.startsWith("x86_64")) {
                    tarGzName = "files.default.x86_64.tar.gz.so";
                } else if (abi.startsWith("x86")) {
                    tarGzName = "files.default.i686.tar.gz.so";
                } else {
                    Logger.logError(LOG_TAG, "Unsupported ABI: " + abi);
                    runOnUiThread(() -> { try { progressDialog.dismiss(); } catch (Exception ignored) {} whenDone.run(); });
                    return;
                }
                // Debug: list available assets
                String[] assetList = getAssets().list("");
                if (assetList != null) {
                    Logger.logInfo(LOG_TAG, "Assets count: " + assetList.length);
                    for (String a : assetList) {
                        if (a.startsWith("files.default")) {
                            Logger.logInfo(LOG_TAG, "  asset: " + a);
                        }
                    }
                }

                // Copy tar.gz from assets to temp file (libtar needs a real file path)
                java.io.File cacheDir = getCacheDir();
                java.io.File tmpTarGz = new java.io.File(cacheDir, tarGzName);
                Logger.logInfo(LOG_TAG, "Copying asset " + tarGzName + " to temp: " + tmpTarGz.getAbsolutePath());
                long copyStart = System.currentTimeMillis();
                java.io.InputStream in = getAssets().open(tarGzName);
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmpTarGz);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                in.close();
                out.close();
                long copyMs = System.currentTimeMillis() - copyStart;
                Logger.logInfo(LOG_TAG, "Copied " + tmpTarGz.length() + " bytes in " + (copyMs / 1000) + "s");

                String destDir = getApplicationContext().getFilesDir().getParent();
                Logger.logInfo(LOG_TAG, "Extracting to: " + destDir);

                long startTime = System.currentTimeMillis();
                String[] extractCmd = {libTar.getAbsolutePath(), "-xf", tmpTarGz.getAbsolutePath(), "-C", destDir};
                Logger.logInfo(LOG_TAG, "Running: " + String.join(" ", extractCmd));

                Process extractProc = Runtime.getRuntime().exec(extractCmd);
                int extractExit = extractProc.waitFor();
                long elapsed = System.currentTimeMillis() - startTime;
                Logger.logInfo(LOG_TAG, "Extraction exit=" + extractExit + ", elapsed=" + (elapsed / 1000) + "s");

                if (extractExit != 0) {
                    tmpTarGz.delete();
                    Logger.logError(LOG_TAG, "Extraction failed with exit code " + extractExit);
                    runOnUiThread(() -> {
                        try { progressDialog.dismiss(); } catch (Exception ignored) {}
                        try { Toast.makeText(LocalShellTestActivity.this, "环境安装失败 (exit=" + extractExit + ")", Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                        whenDone.run();
                    });
                    return;
                }

                tmpTarGz.delete();
                Logger.logInfo(LOG_TAG, "Temp file deleted");

                // Fix permissions
                String binDir = alin.android.alinos.localshell.LocalShellConstants.BIN_DIR_PATH;
                Logger.logInfo(LOG_TAG, "Applying chmod 755 to: " + binDir);
                Process chmodProc = Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "-R", "755", binDir});
                int chmodExit = chmodProc.waitFor();
                Logger.logInfo(LOG_TAG, "chmod exit=" + chmodExit);

                // Create directories
                String homeDir = alin.android.alinos.localshell.LocalShellConstants.HOME_DIR_PATH;
                String tmpDir = alin.android.alinos.localshell.LocalShellConstants.TMP_DIR_PATH;
                new java.io.File(homeDir).mkdirs();
                new java.io.File(tmpDir).mkdirs();
                Logger.logInfo(LOG_TAG, "Created home=" + homeDir + ", tmp=" + tmpDir);

                // Verify bash exists
                Logger.logInfo(LOG_TAG, "Verification: bash exists=" + bashFile.exists() + ", bin count=" + (new java.io.File(binDir).list() != null ? new java.io.File(binDir).list().length : -1));

                runOnUiThread(() -> {
                    try { progressDialog.dismiss(); } catch (Exception ignored) {}
                    try { Toast.makeText(LocalShellTestActivity.this, "环境安装完成", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                    whenDone.run();
                });
                return;
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "TAR extraction error", e);
                runOnUiThread(() -> {
                    try { progressDialog.dismiss(); } catch (Exception ignored) {}
                    try { Toast.makeText(LocalShellTestActivity.this, "环境安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                    whenDone.run();
                });
            }
        }).start();
    }
}
