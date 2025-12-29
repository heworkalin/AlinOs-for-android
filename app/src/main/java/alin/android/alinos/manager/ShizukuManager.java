package alin.android.alinos.manager;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

public class ShizukuManager implements Shizuku.OnRequestPermissionResultListener {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String SHIZUKU_PACKAGE_NAME = "rikka.shizuku";
    private static final String SUI_PACKAGE_NAME = "moe.shizuku.privileged.api";

    private static ShizukuManager instance;
    private Context mContext;
    private ConsentDialogManager consentManager;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private ShizukuManager(Context context) {
        this.mContext = context;
        this.consentManager = ConsentDialogManager.getInstance(mContext);
        Shizuku.addRequestPermissionResultListener(this);
    }

    public static synchronized ShizukuManager getInstance(Context context) {
        if (instance == null) {
            instance = new ShizukuManager(context);
        }
        return instance;
    }

    public void requestShizukuWithConsent(Runnable onSuccess) {
        if (mContext == null || consentManager == null) {
            showToast("初始化失败，无法执行操作");
            return;
        }

        // 1. 检测安装状态
        boolean isInstalled = checkShizukuInstalled();
        if (!isInstalled) {
            // 调用抽象弹窗方法：传入未安装的文案和行为
            showConsentDialog(
                    "Shizuku/Sui 未安装",
                    "高权限功能需要 Shizuku 或 Sui 支持，是否前往官网下载？",
                    this::openShizukuDownloadPage,
                    () -> showToast("未安装 Shizuku，高权限功能无法使用")
            );
            return;
        }

        // 2. 检测服务状态
        try {
            Shizuku.getBinder();
            int permission = Shizuku.checkSelfPermission();
            if (permission == PackageManager.PERMISSION_GRANTED) {
                executeCommandWithShizuku(onSuccess);
            } else {
                showToast("需要授予当前应用 Shizuku 权限");
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            }
        } catch (Exception e) {
            // 调用抽象弹窗方法：传入未激活的文案和行为（修正文案错误）
            showConsentDialog(
                    "Shizuku 服务未启动",
                    "请先打开 Shizuku 应用完成激活（需授予root/ADB权限），是否立即前往？",
                    this::openShizukuApp,
                    () -> showToast("未激活 Shizuku，高权限功能无法使用")
            );
            showToast("Shizuku 服务未启动，请先激活");
        }
    }

    // ====================== 抽象弹窗调用方法（核心：支持动态传参） ======================
    private void showConsentDialog(String title, String message, Runnable positiveAction, Runnable negativeAction) {
        consentManager.showConsentDialog(title, message, positiveAction, negativeAction);
    }

    // ====================== 原有逻辑（无修改，仅移除冗余的弹窗方法） ======================
    private boolean checkShizukuInstalled() {
        PackageManager pm = mContext.getPackageManager();
        return isPackageInstalled(pm, SHIZUKU_PACKAGE_NAME)
                || isPackageInstalled(pm, SUI_PACKAGE_NAME);
    }

    private boolean isPackageInstalled(PackageManager pm, String packageName) {
        try {
            pm.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return checkPackageByIntent(packageName);
        }
    }

    private boolean checkPackageByIntent(String packageName) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(packageName);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return mContext.getPackageManager().resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_UNINSTALLED_PACKAGES
        ) != null;
    }

    private void executeCommandWithShizuku(Runnable onSuccess) {
        new Thread(() -> {
            try {
                String[] command = {"getprop", "ro.product.model"};
                Process process = Runtime.getRuntime().exec(command);

                String result = new java.util.Scanner(process.getInputStream())
                        .useDelimiter("\\A").next();
                int exitCode = process.waitFor();

                mainHandler.post(() -> {
                    if (exitCode == 0) {
                        showToast("设备型号：" + result.trim());
                        if (onSuccess != null) {
                            onSuccess.run();
                        }
                    } else {
                        showToast("命令执行失败，退出码：" + exitCode);
                    }
                });
            } catch (java.util.NoSuchElementException e) {
                mainHandler.post(() -> showToast("命令无输出结果"));
            } catch (Exception e) {
                mainHandler.post(() -> showToast("命令执行异常：" + e.getMessage()));
            }
        }).start();
    }

    private void openShizukuDownloadPage() {
        String url = "https://shizuku.rikka.app/download/";
        Uri uri = Uri.parse(url);

        if (uri == null || !uri.isHierarchical() || !uri.isAbsolute()) {
            showToast("下载链接无效");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        if (intent.resolveActivity(mContext.getPackageManager()) != null) {
            mContext.startActivity(intent);
        } else {
            try {
                mContext.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                showToast("未找到浏览器应用");
                e.printStackTrace();
            }
        }
    }

    private void openShizukuApp() {
        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME);
            if (intent == null) {
                intent = mContext.getPackageManager().getLaunchIntentForPackage(SUI_PACKAGE_NAME);
            }

            if (intent != null) {
                mContext.startActivity(intent);
            } else {
                showToast("无法打开 Shizuku/Sui，请手动在应用列表中启动");
            }
        } catch (Exception e) {
            showToast("Shizuku/Sui 启动失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                showToast("Shizuku 权限申请成功");
                requestShizukuWithConsent(null);
            } else {
                showToast("Shizuku 权限申请失败，无法使用高权限功能");
            }
        }
    }

    private void showToast(String message) {
        mainHandler.post(() -> {
            if (mContext != null) {
                Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void release() {
        Shizuku.removeRequestPermissionResultListener(this);
        instance = null;
        mContext = null;
        consentManager = null;
    }
}