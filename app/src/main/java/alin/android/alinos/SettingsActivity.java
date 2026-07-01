package alin.android.alinos;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 设置页面 — 占位，后续实现配置管理等功能。
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        setContentView(R.layout.activity_settings);
    }
}
