package alin.android.alinos;

import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import alin.android.alinos.log.AlinLog;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import alin.android.alinos.voice.AudioService;
import alin.android.alinos.voice.AppConfigStore;
import alin.android.alinos.voice.engine.ITtsEngine;
import alin.android.alinos.voice.engine.system.SystemTtsEngine;

/**
 * TTS 文字转语音测试。
 * 只做一件事：输文字 → 合成 → 播放。
 */
public class TtsTestActivity extends AppCompatActivity {

    private Spinner spEngine, spTtsModel;
    private EditText etText;
    private SeekBar sbSpeed;
    private TextView tvSpeed;
    private Button btnSpeak, btnStop, btnTtsSettings, btnSaveConfig;
    private TextView tvStatus;

    private ITtsEngine mEngine;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private AppConfigStore mStore;
    private List<AppConfigStore.Model> mCustomTtsModels = new ArrayList<>();

    private static final String[] ENGINE_KEYS = {"system", "sherpa"};
    private static final String[] ENGINE_NAMES = {"Android 系统 TTS", "sherpa-onnx TTS"};
    private static final String[] TTS_MODEL_KEYS = {"melo", "aishell", "xiaoya"};
    private static final String[] TTS_MODEL_NAMES = {"MeloTTS 中英双语", "AIShell3 标准中文", "Piper 小雅 女声"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        buildUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAudio();
        if (mEngine != null) mEngine.release();
    }

    private void buildUi() {
        int dp = (int) getResources().getDisplayMetrics().density;
        int pad = 16 * dp;

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        root.addView(t("🔊 TTS 文字转语音测试", 18, 0xFF333333, true));
        root.addView(space(12 * dp));

        // 引擎选择
        root.addView(t("实现方式：", 14, 0xFF666666, false));
        spEngine = new Spinner(this);
        spEngine.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ENGINE_NAMES));
        spEngine.setSelection(0); // 默认系统 TTS
        root.addView(spEngine);
        root.addView(space(4 * dp));

        // 系统 TTS 设置入口（仅系统引擎时显示，可让用户自行配置系统 TTS 服务）
        btnTtsSettings = btn("⚙️ 系统 TTS 设置（更换引擎/下载语音包/调语速）", 0xFF607D8B);
        btnTtsSettings.setMinHeight(48 * dp);
        root.addView(btnTtsSettings);
        root.addView(space(4 * dp));
        btnTtsSettings.setOnClickListener(v ->
                SystemTtsEngine.openTtsSettings(TtsTestActivity.this));

        // TTS 模型选择（sherpa 时显示）：内置 + 自定义
        mStore = AudioService.getInstance(this).getConfigStore();
        spTtsModel = new Spinner(this);
        mCustomTtsModels.clear();
        for (AppConfigStore.Model m : mStore.getModels("tts")) {
            if (!m.builtin) mCustomTtsModels.add(m);
        }
        List<String> ttsModelNames = new ArrayList<>(Arrays.asList(TTS_MODEL_NAMES));
        for (AppConfigStore.Model m : mCustomTtsModels) ttsModelNames.add("自定义: " + m.name);
        spTtsModel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ttsModelNames));
        spTtsModel.setVisibility(android.view.View.GONE);
        root.addView(spTtsModel);
        root.addView(space(12 * dp));

        // 输入文字
        etText = new EditText(this);
        etText.setHint("请输入要合成的文字...");
        etText.setMinHeight(80 * dp);
        etText.setGravity(android.view.Gravity.TOP);
        root.addView(etText);
        root.addView(space(12 * dp));

        // 语速
        root.addView(t("语速：", 14, 0xFF666666, false));
        android.widget.LinearLayout sr = new android.widget.LinearLayout(this);
        sr.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        sbSpeed = new SeekBar(this);
        sbSpeed.setMax(100);
        sbSpeed.setProgress(50);
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        sbSpeed.setLayoutParams(slp);
        sr.addView(sbSpeed);
        tvSpeed = t("1.0x", 14, 0xFF2196F3, true);
        sr.addView(tvSpeed);
        root.addView(sr);
        sbSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float speed = 0.5f + p / 100f * 1.5f;
                tvSpeed.setText(String.format("%.1fx", speed));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        // 打开时加载上次保存的语速
        try {
            float savedSpeed = Float.parseFloat(mStore.getConfig("tts_speed", "1.0"));
            sbSpeed.setProgress((int) ((savedSpeed - 0.5f) / 1.5f * 100f));
        } catch (NumberFormatException ignored) {}
        root.addView(space(12 * dp));

        // 按钮
        btnSaveConfig = btn("💾 保存配置", 0xFF009688);
        btnSaveConfig.setMinHeight(48 * dp);
        root.addView(btnSaveConfig);
        root.addView(space(6 * dp));

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnSpeak = btn("▶ 合成播放", 0xFF4CAF50);
        android.widget.LinearLayout.LayoutParams b1 = new android.widget.LinearLayout.LayoutParams(0, 48 * dp, 1);
        b1.setMargins(0, 0, 4 * dp, 0);
        btnSpeak.setLayoutParams(b1);
        btnRow.addView(btnSpeak);
        btnStop = btn("⏹ 停止", 0xFFF44336);
        android.widget.LinearLayout.LayoutParams b2 = new android.widget.LinearLayout.LayoutParams(0, 48 * dp, 1);
        b2.setMargins(4 * dp, 0, 0, 0);
        btnStop.setLayoutParams(b2);
        btnRow.addView(btnStop);
        root.addView(btnRow);
        root.addView(space(8 * dp));

        tvStatus = t("状态：未初始化", 13, 0xFF999999, false);
        root.addView(tvStatus);

        sv.addView(root);
        setContentView(sv);

        spEngine.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                spTtsModel.setVisibility(pos == 1 ? android.view.View.VISIBLE : android.view.View.GONE);
                // 系统 TTS 才显示设置入口
                btnTtsSettings.setVisibility(pos == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        spTtsModel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // 打开时加载上次配置（引擎/模型）
        String savedEngine = mStore.getConfig("tts_engine", "system");
        for (int i = 0; i < ENGINE_KEYS.length; i++) {
            if (ENGINE_KEYS[i].equals(savedEngine)) { spEngine.setSelection(i); break; }
        }
        String savedTtsModel = mStore.getConfig("tts_model", "melo");
        for (int i = 0; i < ttsModelNames.size(); i++) {
            if (i < TTS_MODEL_KEYS.length) {
                if (TTS_MODEL_KEYS[i].equals(savedTtsModel)) spTtsModel.setSelection(i);
            } else {
                AppConfigStore.Model m = mCustomTtsModels.get(i - TTS_MODEL_KEYS.length);
                if (m.name.equals(savedTtsModel)) spTtsModel.setSelection(i);
            }
        }

        // 事件
        btnSpeak.setOnClickListener(v -> doSynthesize());
        btnStop.setOnClickListener(v -> {
            if (mEngine instanceof SystemTtsEngine) ((SystemTtsEngine) mEngine).stop();
            stopAudio();
        });
        btnSaveConfig.setOnClickListener(v -> saveConfig());
    }

    /** 保存当前配置（引擎 + 模型 + 语速）到数据库，下次打开自动加载 */
    private void saveConfig() {
        mStore.setConfig("tts_engine", ENGINE_KEYS[spEngine.getSelectedItemPosition()]);
        mStore.setConfig("tts_model", ttsModelKeyFor(spTtsModel.getSelectedItemPosition()));
        float speed = 0.5f + sbSpeed.getProgress() / 100f * 1.5f;
        mStore.setConfig("tts_speed", String.valueOf(speed));
        Toast.makeText(this, "配置已保存：" + ENGINE_NAMES[spEngine.getSelectedItemPosition()]
                + " / " + ttsModelKeyFor(spTtsModel.getSelectedItemPosition())
                + " / " + String.format("%.1fx", speed), Toast.LENGTH_SHORT).show();
    }

    /** 模型下拉位置 → 配置 key（内置 key 或自定义模型名） */
    private String ttsModelKeyFor(int pos) {
        if (pos < TTS_MODEL_KEYS.length) return TTS_MODEL_KEYS[pos];
        return mCustomTtsModels.get(pos - TTS_MODEL_KEYS.length).name;
    }

    /** 当前选中模型的实际目录（内置 key 或自定义绑定的路径） */
    private File currentTtsModelDir(AudioService as) {
        int pos = spTtsModel.getSelectedItemPosition();
        if (pos < TTS_MODEL_KEYS.length) {
            return as.getTtsModelDir(TTS_MODEL_KEYS[pos]);
        }
        return new File(mCustomTtsModels.get(pos - TTS_MODEL_KEYS.length).path);
    }

    private void doSynthesize() {
        String text = etText.getText().toString().trim();
        if (text.isEmpty()) { Toast.makeText(this, "请输入文字", Toast.LENGTH_SHORT).show(); return; }

        String key = ENGINE_KEYS[spEngine.getSelectedItemPosition()];
        AudioService as = AudioService.getInstance(this);

        if ("sherpa".equals(key)) {
            File ttsDir = currentTtsModelDir(as);
            if (!ttsDir.exists() || !ttsDir.isDirectory()) {
                Toast.makeText(this, "TTS 模型未下载，请到模型管理页下载", Toast.LENGTH_LONG).show();
                return;
            }
        }

        float speed = 0.5f + sbSpeed.getProgress() / 100f * 1.5f;

        if ("system".equals(key)) {
            mEngine = new SystemTtsEngine(this);
        } else {
            mEngine = as.getTtsEngine();
        }

        btnSpeak.setEnabled(false);
        tvStatus.setText("初始化引擎...");

        mEngine.init(currentTtsModelDir(as), new ITtsEngine.Callback() {
            @Override
            public void onAudio(byte[] wav) {
                runOnUiThread(() -> {
                    if (!mEngine.isReady()) return;
                    tvStatus.setText("正在合成...");
                    mEngine.synthesize(text, speed, new ITtsEngine.Callback() {
                        @Override
                        public void onAudio(byte[] wav2) {
                            runOnUiThread(() -> {
                                tvStatus.setText("✅ 合成完成");
                                btnSpeak.setEnabled(true);
                                if (wav2 != null && wav2.length > 44) playAudio(wav2);
                            });
                        }
                        @Override
                        public void onError(String e) {
                            runOnUiThread(() -> { tvStatus.setText("❌ " + e); btnSpeak.setEnabled(true); });
                        }
                    });
                });
            }

            @Override
            public void onError(String e) {
                runOnUiThread(() -> {
                    btnSpeak.setEnabled(true);
                    if ("NO_ENGINE".equals(e)) {
                        tvStatus.setText("❌ 没有可用的 TTS 引擎");
                        new AlertDialog.Builder(TtsTestActivity.this)
                                .setTitle("需要 TTS 引擎")
                                .setMessage("当前设备没有文字转语音引擎。\n\n请安装一个 TTS 引擎（如 Google TTS）。")
                                .setPositiveButton("安装引擎", (d, w) ->
                                        SystemTtsEngine.openTtsSettings(TtsTestActivity.this))
                                .setNegativeButton("取消", null)
                                .setCancelable(false)
                                .show();
                    } else {
                        tvStatus.setText("❌ " + e);
                    }
                });
            }
        });
    }

    // ==================== 音频播放 ====================

    private AudioTrack mAudioTrack;

    private void playAudio(byte[] wav) {
        try {
            stopAudio();
            int offset = 44;
            int pcmSize = wav.length - offset;
            short[] shorts = new short[pcmSize / 2];
            for (int i = 0; i < shorts.length; i++) {
                shorts[i] = (short) ((wav[offset + i * 2] & 0xFF) | (wav[offset + i * 2 + 1] << 8));
            }

            int bufSize = AudioTrack.getMinBufferSize(22050,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(22050)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(bufSize, shorts.length * 2))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            mAudioTrack.write(shorts, 0, shorts.length);
            mAudioTrack.play();
            AlinLog.d("TtsTest", "播放 " + shorts.length + " samples @ 22050Hz");
        } catch (Exception e) {
            AlinLog.e("TtsTest", "播放失败", e);
        }
    }

    private void stopAudio() {
        if (mAudioTrack != null) {
            try { mAudioTrack.stop(); mAudioTrack.release(); } catch (Exception ignored) {}
            mAudioTrack = null;
        }
    }

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
