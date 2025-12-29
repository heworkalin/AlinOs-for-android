package alin.android.alinos.manager;

import android.content.Context;

/**
 * ADB Shell命令执行器标准接口
 * 定义：初始化、执行ADB命令、释放资源的标准化行为
 */
public interface IAdbShellExecutor {
    /**
     * 初始化执行器
     * @param context 上下文
     */
    void init(Context context);

    /**
     * 执行ADB Shell命令（自动完成Shizuku检测-激活-授权流程）
     * @param command ADB Shell命令数组（如{"getprop", "ro.product.model"}）
     * @param onSuccess 成功回调（参数：命令执行结果）
     * @param onFail 失败回调（参数：失败原因）
     */
    void executeAdbCommand(String[] command, OnExecuteSuccess onSuccess, OnExecuteFail onFail);

    /**
     * 释放资源
     */
    void release();

    // 成功回调接口（携带命令执行结果）
    interface OnExecuteSuccess {
        void onSuccess(String result);
    }

    // 失败回调接口（携带失败原因）
    interface OnExecuteFail {
        void onFail(String errorMsg);
    }
}