package alin.android.alinos.manager;

import android.content.Context;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

public class ConsentDialogManager {
    private static ConsentDialogManager instance;
    // 注意：不再使用全局Application Context，直接使用传入的Context（需确保是Activity Context）
    private Context mContext;

    private ConsentDialogManager(Context context) {
        // 直接保存传入的Context（调用方需传入Activity实例，如MainActivity.this）
        this.mContext = context;
    }

    public static synchronized ConsentDialogManager getInstance(Context context) {
        if (instance == null) {
            instance = new ConsentDialogManager(context);
        }
        return instance;
    }

    // 带4个参数的弹窗方法（直接使用mContext创建AlertDialog）
    public void showConsentDialog(String title, String message, Runnable confirmAction, Runnable cancelAction) {
        // 关键：mContext是Activity Context，关联了AppCompat主题，不会崩溃
        new AlertDialog.Builder(mContext)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确认", (dialog, which) -> {
                    if (confirmAction != null) {
                        confirmAction.run();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    if (cancelAction != null) {
                        cancelAction.run();
                    }
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show(); // 第40行，之前崩溃的位置
    }

    // 保留无参方法（可选）
    public void showConsentDialog() {
        Toast.makeText(mContext, "显示权限同意弹窗", Toast.LENGTH_SHORT).show();
    }
}