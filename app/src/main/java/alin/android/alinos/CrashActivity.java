package alin.android.alinos;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import alin.android.alinos.log.CrashCapture;

/**
 * 崩溃承接界面。
 *
 * <p>当 App 任一界面发生未捕获异常时，由 {@link CrashCapture} 启动本页，
 * 直接展示异常详情（不再无声闪退）。用户可复制 / 分享，或查看全部日志、退出应用。
 */
public class CrashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 崩溃承接页必须尽量简单，避免自身再抛异常
        try {
            setContentView(R.layout.activity_crash);
            if (getSupportActionBar() != null) {
                getSupportActionBar().hide();
            }

            final String text = resolveCrashText();

            TextView tvText = findViewById(R.id.tv_crash_text);
            tvText.setText(text);

            Button btnCopy = findViewById(R.id.btn_copy);
            Button btnShare = findViewById(R.id.btn_share);
            Button btnLogs = findViewById(R.id.btn_view_logs);
            Button btnExit = findViewById(R.id.btn_exit);

            btnCopy.setOnClickListener(v -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("AlinOs crash", text));
                        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
                }
            });

            btnShare.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_SUBJECT, "AlinOs 崩溃报告");
                    intent.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(intent, "分享崩溃报告"));
                } catch (Exception e) {
                    Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show();
                }
            });

            btnLogs.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(this, LogActivity.class));
                } catch (Exception ignored) {
                }
            });

            btnExit.setOnClickListener(v -> finishAffinityAndExit());
        } catch (Throwable t) {
            // 兜底：直接结束，避免死循环
            finish();
        }
    }

    private String resolveCrashText() {
        String fromIntent = getIntent() == null ? null : getIntent().getStringExtra("crash_text");
        if (fromIntent != null && !fromIntent.isEmpty()) return fromIntent;
        String cached = CrashCapture.getLastCrashText();
        return cached != null ? cached : "（无异常详情）";
    }

    private void finishAffinityAndExit() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            }
            finishAffinity();
        } catch (Throwable ignored) {
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    @Override
    public void onBackPressed() {
        // 拦截返回：崩溃页需要一个明确的选择
        finishAffinityAndExit();
    }
}
