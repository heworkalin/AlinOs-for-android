package alin.android.alinos;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import alin.android.alinos.dev.LocalShellTestActivity;
import alin.android.alinos.dev.SshTestActivity;

/**

 */
public class SettingsActivity extends AppCompatActivity implements View.OnClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        setContentView(R.layout.activity_settings);//布局文件



        // 初始化控件（匹配你的卡片式布局）
        CardView cvAiConfig = findViewById(R.id.cv_ai_config);//配置a i服务器的
        CardView cvMcpServer = findViewById(R.id.cv_mcp_server); // MCP 工具服务
        CardView cvDevTools = findViewById(R.id.cv_dev_tools); //,其实主要都是一个工具
        CardView cvTextToVoiceTest = findViewById(R.id.cv_text_to_voice_test); // 新增：文字转语音测试
        CardView cvLocalShell = findViewById(R.id.cv_local_shell);
        CardView cvSshConfig = findViewById(R.id.cv_ssh_config);

        // 设置点击事件
        cvAiConfig.setOnClickListener(this);
        cvMcpServer.setOnClickListener(this);
        cvDevTools.setOnClickListener(this);
        cvTextToVoiceTest.setOnClickListener(this);
        cvLocalShell.setOnClickListener(this);
        cvSshConfig.setOnClickListener(this);


    }
    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.cv_ai_config) {
            // 跳转到AI服务配置页面
            startActivity(new Intent(this, AiConfigActivity.class));
        } else if (id == R.id.cv_mcp_server) {
            // 跳转到 MCP 工具服务管理页
            startActivity(new Intent(this, McpServerActivity.class));
        } else if (id == R.id.cv_dev_tools) {
            // 跳转到工具测试界面
            startActivity(new Intent(this, alin.android.alinos.dev.DevToolsActivity.class));
        }else if (id == R.id.cv_text_to_voice_test) {
            // 跳转到文字转语音测试页面
            startActivity(new Intent(this, TextToSpeechActivity.class));
        }else if (id == R.id.cv_local_shell) {
            // 找到本地 Shell 卡片
            startActivity(new Intent(this,  LocalShellTestActivity.class));
        }else if (id == R.id.cv_ssh_config) {
            // 找到 SSH 卡片
            startActivity(new Intent(this, SshTestActivity.class));
        }

    }

}
