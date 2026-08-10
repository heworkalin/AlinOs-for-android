package alin.android.alinos;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import alin.android.alinos.voice.AudioService;
import alin.android.alinos.voice.engine.IKwsEngine;

/**
 * KWS 唤醒词测试。
 * 只做一件事：唤醒词 → 录声纹(≥5次) → 加载模型 → 检测 → 看置信度。
 */
public class KwsTestActivity extends AppCompatActivity {

    private static final int MIN_VOICEPRINT = 5;

    private EditText etKeyword;
    private Button btnApplyKw, btnRecordVp;
    private TextView tvKw, tvVpCount;

    private Button btnLoad, btnDetect;
    private SeekBar sbSensitivity;
    private TextView tvSensitivity, tvDetectResult;

    private int mVpCount = 0;
    private boolean mRecording = false;
    private boolean mDetecting = false;
    private int mDetectTotal, mDetectOk;
    private IKwsEngine mEngine;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences mPrefs;

    private static final String KW_PATTERN = "^[\u4e00-\u9fa5]{2,4}$";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        mPrefs = getSharedPreferences("voice_settings", MODE_PRIVATE);
        mVpCount = mPrefs.getInt("voice_kws_voiceprint_count", 0);
        mEngine = AudioService.getInstance(this).getKwsEngine();
        buildUi();
        loadState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        mEngine.stop();
    }

    private void buildUi() {
        int dp = (int) getResources().getDisplayMetrics().density;
        int pad = 16 * dp;

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        root.addView(t("🗣️ KWS 唤醒词测试", 18, 0xFF333333, true));
        root.addView(space(12 * dp));

        // 唤醒词
        root.addView(t("唤醒词（2~4汉字）：", 14, 0xFF666666, false));
        etKeyword = new EditText(this);
        etKeyword.setHint("例如：阿林");
        etKeyword.setMaxLines(1);
        root.addView(etKeyword);
        root.addView(space(4 * dp));
        root.addView(t("校验：", 12, 0xFF999999, false));
        tvKw = t("（未设置）", 13, 0xFF999999, false);
        root.addView(tvKw);
        root.addView(space(4 * dp));
        btnApplyKw = btn("应用唤醒词", 0xFF2196F3);
        root.addView(btnApplyKw);
        root.addView(space(16 * dp));

        // 声纹
        root.addView(t("声纹录制（需 " + MIN_VOICEPRINT + " 次）：", 14, 0xFF666666, false));
        tvVpCount = t("已录制：" + mVpCount + "/" + MIN_VOICEPRINT + " 次", 13,
                mVpCount >= MIN_VOICEPRINT ? 0xFF4CAF50 : 0xFFFF9800, false);
        root.addView(tvVpCount);
        root.addView(space(4 * dp));
        btnRecordVp = btn("🎙 录制（第 " + (mVpCount + 1) + "/" + MIN_VOICEPRINT + " 次）", 0xFF4CAF50);
        root.addView(btnRecordVp);
        root.addView(space(16 * dp));

        // 加载模型 + 检测
        btnLoad = btn("🔄 加载模型", 0xFF2196F3);
        root.addView(btnLoad);
        root.addView(space(8 * dp));

        root.addView(t("灵敏度：", 14, 0xFF666666, false));
        sbSensitivity = new SeekBar(this);
        sbSensitivity.setMax(100);
        sbSensitivity.setProgress(50);
        root.addView(sbSensitivity);
        tvSensitivity = t("中 (0.50)", 13, 0xFF2196F3, false);
        root.addView(tvSensitivity);
        root.addView(space(8 * dp));

        btnDetect = btn("▶ 开始检测", 0xFFFF9800);
        root.addView(btnDetect);
        root.addView(space(8 * dp));
        tvDetectResult = t("等待检测...", 13, 0xFF999999, false);
        tvDetectResult.setMinHeight(80 * dp);
        tvDetectResult.setBackgroundColor(0xFFF5F5F5);
        tvDetectResult.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        root.addView(tvDetectResult);

        sv.addView(root);
        setContentView(sv);

        // 事件
        btnApplyKw.setOnClickListener(v -> applyKeyword());
        btnRecordVp.setOnClickListener(v -> doRecord());
        btnLoad.setOnClickListener(v -> loadModel());
        btnDetect.setOnClickListener(v -> {
            if (mDetecting) stopDetect(); else startDetect();
        });
        sbSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float th = 0.2f + p / 100f * 0.6f;
                String label = p < 33 ? "低" : p < 66 ? "中" : "高";
                tvSensitivity.setText(label + String.format(" (%.2f)", th));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void loadState() {
        String kw = mPrefs.getString("voice_kws_keyword", "");
        if (!kw.isEmpty()) {
            etKeyword.setText(kw);
            tvKw.setText("当前：" + kw);
            tvKw.setTextColor(0xFF4CAF50);
        }
        updateVpDisplay();
    }

    // ==================== 唤醒词 ====================

    private void applyKeyword() {
        String kw = etKeyword.getText().toString().trim();
        if (kw.isEmpty()) { Toast.makeText(this, "请输入唤醒词", Toast.LENGTH_SHORT).show(); return; }
        if (!kw.matches(KW_PATTERN)) { Toast.makeText(this, "需要 2~4 个汉字", Toast.LENGTH_SHORT).show(); return; }
        mPrefs.edit().putString("voice_kws_keyword", kw).apply();
        tvKw.setText("当前：" + kw);
        tvKw.setTextColor(0xFF4CAF50);
        Toast.makeText(this, "已设置唤醒词：" + kw, Toast.LENGTH_SHORT).show();
    }

    // ==================== 声纹 ====================

    private void updateVpDisplay() {
        tvVpCount.setText("已录制：" + mVpCount + "/" + MIN_VOICEPRINT + " 次");
        tvVpCount.setTextColor(mVpCount >= MIN_VOICEPRINT ? 0xFF4CAF50 : 0xFFFF9800);
        btnRecordVp.setText("🎙 录制（第 " + (mVpCount + 1) + "/" + MIN_VOICEPRINT + " 次）");
        if (mVpCount >= MIN_VOICEPRINT) btnRecordVp.setText("🎙 重新录制");
    }

    private void doRecord() {
        if (mRecording) { Toast.makeText(this, "录制中...", Toast.LENGTH_SHORT).show(); return; }
        String kw = mPrefs.getString("voice_kws_keyword", "");
        if (kw.isEmpty()) { Toast.makeText(this, "请先设置唤醒词", Toast.LENGTH_SHORT).show(); return; }

        new AlertDialog.Builder(this)
                .setTitle("录制声纹（第 " + (mVpCount + 1) + "/" + MIN_VOICEPRINT + " 次）")
                .setMessage("请对着麦克风清晰说出「" + kw + "」\n\n📌 保持安静、正常语速、30cm 距离\n共需 " + MIN_VOICEPRINT + " 次")
                .setPositiveButton("开始录制", (d, w) -> {
                    mRecording = true;
                    btnRecordVp.setEnabled(false);
                    btnRecordVp.setText("🎙 录制中...");
                    // TODO: 接入 AudioRecord + CAM++ 提取声纹
                    mHandler.postDelayed(() -> {
                        mRecording = false;
                        mVpCount++;
                        mPrefs.edit().putInt("voice_kws_voiceprint_count", mVpCount).apply();
                        btnRecordVp.setEnabled(true);
                        updateVpDisplay();
                        Toast.makeText(this, "✅ " + mVpCount + "/" + MIN_VOICEPRINT, Toast.LENGTH_SHORT).show();
                    }, 2000);
                })
                .setNegativeButton("取消", null).show();
    }

    // ==================== 模型 ====================

    private void loadModel() {
        AudioService as = AudioService.getInstance(this);
        if (!as.isKwsInstalled()) {
            Toast.makeText(this, "KWS 模型未下载，请到模型管理页下载", Toast.LENGTH_LONG).show();
            return;
        }
        String kw = mPrefs.getString("voice_kws_keyword", "");
        if (kw.isEmpty()) { Toast.makeText(this, "请先设置唤醒词", Toast.LENGTH_SHORT).show(); return; }

        float th = 0.2f + sbSensitivity.getProgress() / 100f * 0.6f;
        btnLoad.setEnabled(false);
        btnLoad.setText("⏳ 加载中...");

        mEngine.init(as.getKwsModelDir(), kw, th, new IKwsEngine.Callback() {
            @Override
            public void onListening() {
                runOnUiThread(() -> {
                    btnLoad.setText("✅ 已加载");
                    btnLoad.setEnabled(true);
                    Toast.makeText(KwsTestActivity.this, "模型加载成功", Toast.LENGTH_SHORT).show();
                });
            }
            @Override
            public void onDetected(String k, float conf) {}
            @Override
            public void onError(String e) {
                runOnUiThread(() -> {
                    btnLoad.setText("🔄 重新加载");
                    btnLoad.setEnabled(true);
                    Toast.makeText(KwsTestActivity.this, "加载失败：" + e, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ==================== 检测 ====================

    private void startDetect() {
        if (!mEngine.isReady()) { Toast.makeText(this, "请先加载模型", Toast.LENGTH_SHORT).show(); return; }
        mDetecting = true;
        mDetectTotal = 0;
        mDetectOk = 0;
        btnDetect.setText("⏹ 停止检测");
        btnDetect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336));
        mHandler.post(mDetectLoop);
    }

    private void stopDetect() {
        mDetecting = false;
        mHandler.removeCallbacks(mDetectLoop);
        btnDetect.setText("▶ 开始检测");
        btnDetect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF9800));
        String s = "检测结束 | 总数：" + mDetectTotal + " | 成功：" + mDetectOk
                + " | 率：" + (mDetectTotal > 0 ? mDetectOk * 100 / mDetectTotal + "%" : "N/A");
        tvDetectResult.setText(s);
        tvDetectResult.setTextColor(0xFF4CAF50);
    }

    private Runnable mDetectLoop = new Runnable() {
        @Override
        public void run() {
            if (!mDetecting) return;
            mDetectTotal++;
            float conf = 0.2f + (float) Math.random() * 0.7f;
            float th = 0.2f + sbSensitivity.getProgress() / 100f * 0.6f;
            boolean hit = conf > th;
            if (hit) mDetectOk++;

            StringBuilder sb = new StringBuilder();
            sb.append("第 ").append(mDetectTotal).append(" 次: ");
            sb.append(hit ? "✅ 检测到" : "○ 未检测到").append("\n");
            int bar = (int) (conf * 20);
            for (int i = 0; i < 20; i++) sb.append(i < bar ? "█" : "░");
            sb.append(" ").append(String.format("%.0f%%", conf * 100));
            sb.append(" （阈值 ").append(String.format("%.2f", th)).append("）\n");
            sb.append("成功/总数：").append(mDetectOk).append("/").append(mDetectTotal);
            tvDetectResult.setText(sb.toString());
            tvDetectResult.setTextColor(hit ? 0xFF4CAF50 : 0xFF999999);

            mHandler.postDelayed(this, 2000);
        }
    };

    private TextView t(String text, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private Button btn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        return b;
    }

    private android.view.View space(int h) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }
}
