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
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener;

/**
 * Shizuku实现的ADB Shell命令执行器
 * 遵循IAdbShellExecutor接口，完成Shizuku检测、授权与ADB命令执行
 */
public class ShizukuAdbShellExecutor implements IAdbShellExecutor, OnRequestPermissionResultListener {
    // 常量定义
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String SHIZUKU_PACKAGE = "rikka.shizuku";
    private static final String SUI_PACKAGE = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/";

    // 成员变量
    private Context mContext;
    private ConsentDialogManager mConsentManager;
    private Handler mMainHandler;
    // 临时存储命令与回调
    private String[] mCurrentCommand;
    private OnExecuteSuccess mOnSuccess;
    private OnExecuteFail mOnFail;

    @Override
    public void init(Context context) {
        this.mContext = context.getApplicationContext();
        this.mConsentManager = ConsentDialogManager.getInstance(mContext);
        this.mMainHandler = new Handler(Looper.getMainLooper());
        Shizuku.addRequestPermissionResultListener(this);
    }

    @Override
    public void executeAdbCommand(String[] command, OnExecuteSuccess onSuccess, OnExecuteFail onFail) {
        // 赋值命令与回调
        this.mCurrentCommand = command;
        this.mOnSuccess = onSuccess;
        this.mOnFail = onFail;

        // 前置校验
        if (mContext == null || mConsentManager == null) {
            notifyFail("执行器初始化失败");
            return;
        }
        if (mCurrentCommand == null || mCurrentCommand.length == 0) {
            notifyFail("ADB Shell命令不能为空");
            return;
        }

        // 步骤1：检测Shizuku/Sui是否安装
        if (!checkAppInstalled()) {
            showInstallDialog();
            return;
        }

        // 步骤2：检测Shizuku服务是否激活
        try {
            Shizuku.getBinder();
            checkPermissionAndExecute();
        } catch (Exception e) {
            showActivateDialog();
            notifyFail("Shizuku服务未启动，请先激活");
        }
    }

    /**
     * 检测Shizuku/Sui是否安装
     */
    private boolean checkAppInstalled() {
        PackageManager pm = mContext.getPackageManager();
        return isPackageExist(pm, SHIZUKU_PACKAGE) || isPackageExist(pm, SUI_PACKAGE);
    }

    /**
     * 检测单个包名是否存在
     */
    private boolean isPackageExist(PackageManager pm, String packageName) {
        try {
            pm.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return checkPackageByIntent(packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Intent降级检测（适配Android 11+包可见性）
     */
    private boolean checkPackageByIntent(String packageName) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(packageName);
        return mContext.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }

    /**
     * 检查Shizuku权限并执行命令
     */
    private void checkPermissionAndExecute() {
        try {
            int permission = Shizuku.checkSelfPermission();
            if (permission == PackageManager.PERMISSION_GRANTED) {
                runAdbCommand(); // 执行ADB命令
            } else {
                showToast("需要授予应用Shizuku权限");
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            }
        } catch (Exception e) {
            notifyFail("权限检查异常：" + e.getMessage());
        }
    }

    /**
     * 执行ADB Shell命令（核心逻辑）
     */
    private void runAdbCommand() {
        new Thread(() -> {
            try {
                // 执行传入的ADB Shell命令
                Process process = Runtime.getRuntime().exec(mCurrentCommand);
                // 读取命令输出结果
                String result = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A").next();
                int exitCode = process.waitFor();

                mMainHandler.post(() -> {
                    if (exitCode == 0) {
                        notifySuccess(result.trim());
                    } else {
                        notifyFail("命令执行失败，退出码：" + exitCode);
                    }
                });
            } catch (java.util.NoSuchElementException e) {
                notifySuccess(""); // 命令无输出时返回空
            } catch (Exception e) {
                notifyFail("命令执行异常：" + e.getMessage());
            }
        }).start();
    }

    /**
     * 未安装Shizuku的引导弹窗
     */
    private void showInstallDialog() {
        mConsentManager.showConsentDialog(
                "Shizuku/Sui未安装",
                "执行ADB命令需要Shizuku支持，是否前往下载？",
                this::openDownloadPage,
                () -> notifyFail("未安装Shizuku，无法执行ADB命令")
        );
    }

    /**
     * 未激活Shizuku的引导弹窗
     */
    private void showActivateDialog() {
        mConsentManager.showConsentDialog(
                "Shizuku服务未启动",
                "请先打开Shizuku完成激活（需root/ADB权限），是否立即前往？",
                this::openShizukuApp,
                () -> notifyFail("未激活Shizuku，无法执行ADB命令")
        );
    }

    /**
     * 打开Shizuku下载页
     */
    private void openDownloadPage() {
        Uri uri = Uri.parse(SHIZUKU_DOWNLOAD_URL);
        if (uri == null || !uri.isHierarchical()) {
            notifyFail("下载链接无效");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (intent.resolveActivity(mContext.getPackageManager()) != null) {
            mContext.startActivity(intent);
        } else {
            try {
                mContext.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                notifyFail("未找到浏览器应用");
            }
        }
    }

    /**
     * 打开Shizuku应用
     */
    private void openShizukuApp() {
        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (intent == null) intent = mContext.getPackageManager().getLaunchIntentForPackage(SUI_PACKAGE);

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            } else {
                notifyFail("无法打开Shizuku/Sui，请手动启动");
            }
        } catch (Exception e) {
            notifyFail("Shizuku启动失败：" + e.getMessage());
        }
    }

    /**
     * Shizuku权限申请回调
     */
    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                showToast("Shizuku权限申请成功");
                checkPermissionAndExecute();
            } else {
                notifyFail("Shizuku权限申请失败，无法执行ADB命令");
            }
        }
    }

    /**
     * 通知成功（主线程）
     */
    private void notifySuccess(String result) {
        if (mOnSuccess != null) {
            mMainHandler.post(() -> mOnSuccess.onSuccess(result));
        }
    }

    /**
     * 通知失败（主线程）
     */
    private void notifyFail(String errorMsg) {
        showToast(errorMsg);
        if (mOnFail != null) {
            mMainHandler.post(() -> mOnFail.onFail(errorMsg));
        }
    }

    /**
     * 主线程显示Toast
     */
    private void showToast(String message) {
        mMainHandler.post(() -> {
            if (mContext != null) {
                Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void release() {
        Shizuku.removeRequestPermissionResultListener(this);
        mContext = null;
        mConsentManager = null;
        mMainHandler = null;
        mCurrentCommand = null;
        mOnSuccess = null;
        mOnFail = null;
    }
}