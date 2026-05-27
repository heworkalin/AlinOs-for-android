package alin.android.alinos;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import alin.android.alinos.dev.LocalShellTestActivity;

//AlinOs\app\src\main\java\alin\android\alinos\AgentConfigActivity.java
public class AgentConfigActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_config);

        // 找到工具测试中心卡片
        CardView cvDevTools = findViewById(R.id.cv_dev_tools);
        cvDevTools.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(AgentConfigActivity.this,
                        alin.android.alinos.dev.DevToolsActivity.class);
                startActivity(intent);
            }
        });

        // 找到本地 Shell 测试卡片
        CardView cvLocalShellTest = findViewById(R.id.cv_local_shell_test);
        cvLocalShellTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(AgentConfigActivity.this, LocalShellTestActivity.class);
                startActivity(intent);
            }
        });

    }
}