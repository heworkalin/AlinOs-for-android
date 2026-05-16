package alin.android.alinos;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import alin.android.alinos.dev.ChatActivityDev;
import alin.android.alinos.dev.TermuxShellTestActivity;

//AlinOs\app\src\main\java\alin\android\alinos\AgentConfigActivity.java
public class AgentConfigActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_config);

        // 找到 Termux 配置卡片
        CardView cvTermuxConfig = findViewById(R.id.cv_termux_config);

        // 设置点击事件，跳转到 TermuxActivity
        cvTermuxConfig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent termuxIntent = new Intent();
                    termuxIntent.setClassName(getPackageName(), "com.termux.app.TermuxActivity");
                    startActivity(termuxIntent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        // 找到 Termux 测试卡片
        CardView cvTermuxTest = findViewById(R.id.cv_termux_test);
        cvTermuxTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(AgentConfigActivity.this, TermuxShellTestActivity.class);
                startActivity(intent);
            }
        });

        // 找到 Markdown + LaTeX 渲染测试卡片
        CardView cvMarkdownTest = findViewById(R.id.cv_markdown_test);
        cvMarkdownTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(AgentConfigActivity.this, ChatActivityDev.class);
                startActivity(intent);
            }
        });
    }
}