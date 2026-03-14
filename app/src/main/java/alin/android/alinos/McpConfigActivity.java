package alin.android.alinos;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

public class McpConfigActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mcp_config);

        // 找到 Termux 配置卡片
        CardView cvTermuxConfig = findViewById(R.id.cv_termux_config);
        
        // 设置点击事件，跳转到 TermuxActivity
        cvTermuxConfig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    // 构建跳转到 Termux 主界面的 Intent
                    Intent termuxIntent = new Intent();
                    // 指定 Termux 主界面的完整类名
                    termuxIntent.setClassName(getPackageName(), "com.termux.app.TermuxActivity");
                    // 启动 Termux 界面
                    startActivity(termuxIntent);
                } catch (Exception e) {
                    // 容错处理：如果 TermuxActivity 不存在，给出提示（可选）
                    e.printStackTrace();
                    // 也可以添加 Toast 提示用户
                    // Toast.makeText(McpConfigActivity.this, "Termux 环境未配置", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}