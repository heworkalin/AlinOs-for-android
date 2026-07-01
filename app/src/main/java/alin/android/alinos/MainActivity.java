package alin.android.alinos;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import alin.android.alinos.manager.ConsentDialogManager;

import alin.android.alinos.manager.ShizukuManager;
import alin.android.alinos.localshell.LocalShellExecutor;


public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    // 控件
    private TextView tvAccessibilityStatus;
    private Switch swScreenShare;
    // 管理类
    private ShizukuManager shizukuManager;
    private ConsentDialogManager consentDialogManager;

    // 权限请求码
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 隐藏ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        // 状态栏透明，布局延伸到状态栏
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        setContentView(R.layout.activity_main);
        // 提前启动 LocalShellService 绑定，确保后续 create_session 调用时服务已就绪
        LocalShellExecutor.provideContext(this);

        // 初始化管理类
        shizukuManager = ShizukuManager.getInstance(this);
        consentDialogManager = ConsentDialogManager.getInstance(this);

        // 初始化控件（匹配你的卡片式布局）
        CardView cvAiConfig = findViewById(R.id.cv_ai_config);
        CardView cvAccessibility = findViewById(R.id.cv_accessibility);
        CardView cvAdb = findViewById(R.id.cv_adb); // 触发ADB（Shizuku替换）
        CardView cvScreenShare = findViewById(R.id.cv_screen_share);
        CardView cv_ChatActivity = findViewById(R.id.cv_ChatActivity); // 修改：跳转ChatActivity
        CardView cvOverlayTest = findViewById(R.id.cv_overlay_test); // 新增：悬浮窗测试
        CardView cvBackgroundKeep = findViewById(R.id.cv_background_keep); // 新增：后台保活
        CardView cvAgentConfig = findViewById(R.id.cv_agent_config); // Agent配置
        
        CardView cvTextToVoiceTest = findViewById(R.id.cv_text_to_voice_test); // 新增：文字转语音测试

        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status);
        swScreenShare = findViewById(R.id.sw_screen_share); // 屏幕共享开关

        // 初始化SharedPreferences
        SharedPreferences sp = getSharedPreferences("app_config", MODE_PRIVATE);

        // 设置点击事件
        cvAiConfig.setOnClickListener(this);
        cvAccessibility.setOnClickListener(this);
        cvAdb.setOnClickListener(this);
        cvScreenShare.setOnClickListener(this); // 卡片点击切换开关
        cv_ChatActivity.setOnClickListener(this); // 修改：跳转ChatActivity2
        cvOverlayTest.setOnClickListener(this);
        cvBackgroundKeep.setOnClickListener(this);
        cvAgentConfig.setOnClickListener(this);
       
        cvTextToVoiceTest.setOnClickListener(this);
        // 屏幕共享开关逻辑
        swScreenShare.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                initScreenShare(); // 初始化屏幕共享
                Toast.makeText(this, "屏幕共享全局模式已开启", Toast.LENGTH_SHORT).show();
            } else {
                releaseScreenShare(); // 释放资源
                Toast.makeText(this, "屏幕共享全局模式已关闭", Toast.LENGTH_SHORT).show();
            }
        });

        // 检测无障碍权限状态
        checkAccessibilityStatus();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.cv_ai_config) {
            // 跳转到AI服务配置页面
            startActivity(new Intent(this, AiConfigActivity.class));
        } else if (id == R.id.cv_accessibility) {
            // 跳转到无障碍权限设置页面
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        } else if (id == R.id.cv_adb) {
            // 触发ADB权限（Shizuku授权，带用户确认）
            shizukuManager.requestShizukuWithConsent(() -> {
                Toast.makeText(this, "Shizuku授权流程已发起", Toast.LENGTH_SHORT).show();
            });
        } else if (id == R.id.cv_screen_share) {
            // 点击卡片切换屏幕共享开关
            swScreenShare.setChecked(!swScreenShare.isChecked());
        }  else if (id == R.id.cv_ChatActivity ) {
            // 会话AI测试，跳转到ChatActivity2
            startActivity(new Intent(this, ChatActivity.class));
            //Toast.makeText(this, "已唤起AI助手会话", Toast.LENGTH_SHORT).show();
        
        } else if (id == R.id.cv_background_keep) {
            // 后台饱和配置（保活引导）
            guideBackgroundKeepAlive();
        } else if (id == R.id.cv_agent_config) {
            // 跳转到Agent配置页面
            startActivity(new Intent(this, AgentConfigActivity.class));
        }else if (id == R.id.cv_text_to_voice_test) {
            // 跳转到文字转语音测试页面
            startActivity(new Intent(this, TextToSpeechActivity.class));
        }

    }

    /**
     * 检测无障碍权限是否开启
     */
    private void checkAccessibilityStatus() {
        int accessibilityEnabled = 0;
        final String serviceName = getPackageName() + ".service.DeviceAccessibilityService";
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getApplicationContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.e("MainActivity", "获取无障碍服务状态失败", e);
        }

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    getApplicationContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null && settingValue.contains(serviceName)) {
                tvAccessibilityStatus.setText(getString(R.string.enabled));
                tvAccessibilityStatus.setTextColor(Color.parseColor("#00C851"));
                return;
            }
        }
        tvAccessibilityStatus.setText(getString(R.string.not_enabled));
        tvAccessibilityStatus.setTextColor(Color.parseColor("#FF4444"));
    }

    /**
     * 初始化屏幕共享（MediaProjection）
     */
    private void initScreenShare() {
        // 后续补充：申请MediaProjection权限，初始化全局截图/录屏
        // 此处先占位，确保开关逻辑完整
    }

    /**
     * 释放屏幕共享资源
     */
    private void releaseScreenShare() {
        // 后续补充：释放MediaProjection，避免内存泄漏
    }

    /**
     * 悬浮窗权限测试
     */

    /**
     * 引导后台保活配置
     */
    private void guideBackgroundKeepAlive() {
        consentDialogManager.showConsentDialog(
                "后台保活配置",
                "为避免AI助手被系统杀死，请将应用后台限制设为「无限制」，是否前往设置？",
                () -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                },
                () -> Toast.makeText(this, "未设置保活可能导致后台功能异常", Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到主界面，重新检测无障碍权限状态
        checkAccessibilityStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放悬浮窗资源
    }
}