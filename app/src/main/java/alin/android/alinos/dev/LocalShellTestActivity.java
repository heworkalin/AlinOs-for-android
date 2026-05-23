package alin.android.alinos.dev;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManager;

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
        java.io.File prefixDir = new java.io.File(
            alin.android.alinos.localshell.LocalShellConstants.PREFIX_DIR_PATH);
        if (prefixDir.isDirectory() && new java.io.File(prefixDir, "bin/bash").exists()) {
            whenDone.run();
            return;
        }

        // Show loading dialog
        android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setMessage("正在安装环境，请稍候...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(() -> {
            try {
                String libDir = getApplicationInfo().nativeLibraryDir;
                java.io.File libTar = new java.io.File(libDir, "libtar.so");
                String abi = android.os.Build.SUPPORTED_ABIS[0];
                String tarGzName = abi.startsWith("arm64") ? "files.default.aarch64.tar.gz.so"
                    : abi.startsWith("x86_64") ? "files.default.x86_64.tar.gz.so" : null;
                if (tarGzName == null) return;
                java.io.File tarGzFile = new java.io.File(libDir, tarGzName);
                String destDir = getApplicationContext().getFilesDir().getParent();

                Runtime.getRuntime().exec(new String[]{
                    libTar.getAbsolutePath(), "-xf", tarGzFile.getAbsolutePath(), "-C", destDir
                }).waitFor();

                // Fix permissions and create dirs
                String binDir = alin.android.alinos.localshell.LocalShellConstants.BIN_DIR_PATH;
                String homeDir = alin.android.alinos.localshell.LocalShellConstants.HOME_DIR_PATH;
                String tmpDir = alin.android.alinos.localshell.LocalShellConstants.TMP_DIR_PATH;
                Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "-R", "755", binDir}).waitFor();
                new java.io.File(homeDir).mkdirs();
                new java.io.File(tmpDir).mkdirs();
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                progressDialog.dismiss();
                whenDone.run();
            });
        }).start();
    }
}
