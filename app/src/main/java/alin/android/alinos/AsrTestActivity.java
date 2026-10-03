package alin.android.alinos;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import alin.android.alinos.log.AlinLog;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Looper;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import alin.android.alinos.voice.AudioService;
import alin.android.alinos.voice.AppConfigStore;
import alin.android.alinos.voice.engine.IAsrEngine;

/**
 * ASR 录音识别测试。
 * 只做一件事：加载模型 → 录音 → 停止 → 识别 → 看结果。
 */
public class AsrTestActivity extends AppCompatActivity {

    private static final int REQ_RECORD = 300;

    private Spinner spEngine, spModel;
    private Button btnLoad, btnRecord, btnStop, btnRecognize, btnSaveConfig;
    private TextView tvStatus, tvResult;

    private IAsrEngine mEngine;
    private AudioRecord mAudioRecord;
    private ByteArrayOutputStream mAudioBuf;
    private boolean mRecording;
    private Thread mRecThread;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private AppConfigStore mStore;
    private List<AppConfigStore.Model> mCustomAsrModels = new ArrayList<>();

    private static final String[] ENGINES = {"sherpa", "vosk"};
    private static final String[] ENGINE_NAMES = {"sherpa-onnx (paraformer等)", "Vosk (vosk-model-small-cn)"};
    private static final String[] MODELS = {"paraformer", "sensevoice", "whisper"};
    private static final String[] MODEL_NAMES = {"Paraformer (70MB, 中文)", "SenseVoice (200MB, 多语种)", "Whisper (100MB, 通用)"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        // 纯代码构建 UI
        buildUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        mHandler.removeCallbacksAndMessages(null);
    }

    private void buildUi() {
        int dp = (int) getResources().getDisplayMetrics().density;
        int pad = 16 * dp;

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        // 标题
        TextView title = t("🎤 ASR 录音识别测试", 18, 0xFF333333, true);
        root.addView(title);
        root.addView(space(12 * dp));

        AudioService as = AudioService.getInstance(this);
        mStore = as.getConfigStore();

        // 引擎选择
        root.addView(t("引擎：", 13, 0xFF666666, false));
        spEngine = new Spinner(this);
        spEngine.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ENGINE_NAMES));
        root.addView(spEngine);
        root.addView(space(4 * dp));

        // 模型选择（仅 sherpa 时显示）：内置 + 自定义
        root.addView(t("模型：", 13, 0xFF666666, false));
        spModel = new Spinner(this);
        mCustomAsrModels.clear();
        for (AppConfigStore.Model m : mStore.getModels("asr")) {
            if (!m.builtin) mCustomAsrModels.add(m);
        }
        List<String> modelNames = new ArrayList<>(Arrays.asList(MODEL_NAMES));
        for (AppConfigStore.Model m : mCustomAsrModels) modelNames.add("自定义: " + m.name);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, modelNames);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spModel.setAdapter(ad);
        root.addView(spModel);
        root.addView(space(12 * dp));

        // 加载模型 + 保存配置
        android.widget.LinearLayout cfgRow = new android.widget.LinearLayout(this);
        cfgRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnLoad = btn("🔄 加载模型", 0xFF2196F3);
        btnLoad.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, 48 * dp, 1));
        cfgRow.addView(btnLoad);
        btnSaveConfig = btn("💾 保存配置", 0xFF009688);
        btnSaveConfig.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, 48 * dp, 1));
        cfgRow.addView(btnSaveConfig);
        root.addView(cfgRow);
        root.addView(space(4 * dp));
        tvStatus = t("状态：等待加载", 13, 0xFF999999, false);
        root.addView(tvStatus);
        root.addView(space(12 * dp));

        // 录音
        android.widget.LinearLayout recRow = new android.widget.LinearLayout(this);
        recRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnRecord = btn("🎙 开始录音", 0xFF4CAF50);
        btnRecord.setEnabled(false);
        android.widget.LinearLayout.LayoutParams rp = new android.widget.LinearLayout.LayoutParams(0, 48 * dp, 1);
        rp.setMargins(0, 0, 4 * dp, 0);
        btnRecord.setLayoutParams(rp);
        recRow.addView(btnRecord);
        btnStop = btn("⏹ 停止", 0xFFF44336);
        btnStop.setEnabled(false);
        android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(0, 48 * dp, 1);
        sp.setMargins(4 * dp, 0, 0, 0);
        btnStop.setLayoutParams(sp);
        recRow.addView(btnStop);
        root.addView(recRow);
        root.addView(space(16 * dp));

        // 识别
        btnRecognize = btn("🔍 开始识别", 0xFFFF9800);
        btnRecognize.setEnabled(false);
        root.addView(btnRecognize);
        root.addView(space(8 * dp));
        tvResult = t("识别结果将显示在这里...", 14, 0xFF666666, false);
        tvResult.setMinHeight(60 * dp);
        tvResult.setBackgroundColor(0xFFF5F5F5);
        tvResult.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        root.addView(tvResult);

        sv.addView(root);
        setContentView(sv);

        spEngine.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                spModel.setVisibility(pos == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        spModel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // 打开时加载上次保存的配置
        String savedEngine = mStore.getConfig("asr_engine", "sherpa");
        for (int i = 0; i < ENGINES.length; i++) {
            if (ENGINES[i].equals(savedEngine)) { spEngine.setSelection(i); break; }
        }
        String savedModel = mStore.getConfig("asr_model", "paraformer");
        int modelPos = -1;
        for (int i = 0; i < modelNames.size(); i++) {
            if (i < MODELS.length) {
                if (MODELS[i].equals(savedModel)) modelPos = i;
            } else {
                AppConfigStore.Model m = mCustomAsrModels.get(i - MODELS.length);
                if (m.name.equals(savedModel)) modelPos = i;
            }
        }
        if (modelPos >= 0) spModel.setSelection(modelPos);

        // 事件
        btnLoad.setOnClickListener(v -> loadModel());
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnRecord.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> stopRecording());
        btnRecognize.setOnClickListener(v -> doRecognize());
    }

    /** 保存当前配置（引擎 + 模型）到数据库，下次打开自动加载 */
    private void saveConfig() {
        mStore.setConfig("asr_engine", ENGINES[spEngine.getSelectedItemPosition()]);
        mStore.setConfig("asr_model", modelKeyFor(spModel.getSelectedItemPosition()));
        Toast.makeText(this, "配置已保存：" + ENGINE_NAMES[spEngine.getSelectedItemPosition()]
                + " / " + modelKeyFor(spModel.getSelectedItemPosition()), Toast.LENGTH_SHORT).show();
    }

    // ==================== 模型 ====================

    /** 模型下拉位置 → 配置 key（内置 key 或自定义模型名） */
    private String modelKeyFor(int pos) {
        if (pos < MODELS.length) return MODELS[pos];
        return mCustomAsrModels.get(pos - MODELS.length).name;
    }

    private void loadModel() {
        AudioService as = AudioService.getInstance(this);
        String eng = ENGINES[spEngine.getSelectedItemPosition()];

        final File modelDir;
        if ("vosk".equals(eng)) {
            modelDir = as.getVoskModelDir();
        } else {
            int pos = spModel.getSelectedItemPosition();
            if (pos < MODELS.length) {
                modelDir = as.getAsrModelDir(MODELS[pos]);
            } else {
                // 自定义模型：直接用数据库绑定的路径
                AppConfigStore.Model m = mCustomAsrModels.get(pos - MODELS.length);
                modelDir = new File(m.path);
            }
        }

        if (!modelDir.exists() || !modelDir.isDirectory()) {
            Toast.makeText(this, "模型未下载，请到模型管理页下载", Toast.LENGTH_LONG).show();
            return;
        }

        mEngine = as.getAsrEngine(eng);
        btnLoad.setEnabled(false);
        btnLoad.setText("⏳ 加载中...");
        tvStatus.setText("加载 " + eng + "...");

        mEngine.init(modelDir, new IAsrEngine.Callback() {
            @Override
            public void onResult(String text) {
                runOnUiThread(() -> {
                    btnLoad.setText("✅ 已加载");
                    btnLoad.setEnabled(true);
                    tvStatus.setText("状态：✅ 就绪，可以录音");
                    tvStatus.setTextColor(0xFF4CAF50);
                    btnRecord.setEnabled(true);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnLoad.setText("🔄 重新加载");
                    btnLoad.setEnabled(true);
                    tvStatus.setText("状态：❌ " + error);
                    tvStatus.setTextColor(0xFFFF4444);
                });
            }
        });
    }

    // ==================== 录音 ====================

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
            return;
        }
        doStartRecording();
    }

    private void doStartRecording() {
        int minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "录音初始化失败", Toast.LENGTH_SHORT).show();
            return;
        }

        mAudioBuf = new ByteArrayOutputStream();
        mRecording = true;
        mAudioRecord.startRecording();
        btnRecord.setEnabled(false);
        btnStop.setEnabled(true);
        btnRecognize.setEnabled(false);
        tvStatus.setText("🔴 录音中...");
        tvStatus.setTextColor(0xFFFF4444);

        mRecThread = new Thread(() -> {
            byte[] buf = new byte[minBuf];
            while (mRecording && mAudioRecord != null) {
                int len = mAudioRecord.read(buf, 0, buf.length);
                if (len > 0) mAudioBuf.write(buf, 0, len);
            }
        });
        mRecThread.start();
    }

    private void stopRecording() {
        mRecording = false;
        if (mRecThread != null) { try { mRecThread.join(500); } catch (Exception ignored) {} mRecThread = null; }
        if (mAudioRecord != null) { try { mAudioRecord.stop(); mAudioRecord.release(); } catch (Exception ignored) {} mAudioRecord = null; }

        int bytes = mAudioBuf != null ? mAudioBuf.size() : 0;
        tvStatus.setText("⏹ 已停止，共 " + bytes + " 字节");
        tvStatus.setTextColor(0xFF999999);
        btnRecord.setEnabled(true);
        btnStop.setEnabled(false);
        btnRecognize.setEnabled(bytes > 0);
    }

    // ==================== 识别 ====================

    private void doRecognize() {
        if (mEngine == null || !mEngine.isReady()) {
            Toast.makeText(this, "请先加载模型", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mAudioBuf == null || mAudioBuf.size() == 0) {
            Toast.makeText(this, "请先录音", Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] pcm = mAudioBuf.toByteArray();
        AlinLog.d("AsrTest", "识别: " + pcm.length + " bytes, engine=" + mEngine.getName());
        btnRecognize.setEnabled(false);
        btnRecognize.setText("⏳ 识别中...");
        tvResult.setText("识别中...");

        mEngine.recognize(pcm, new IAsrEngine.Callback() {
            @Override
            public void onResult(String text) {
                runOnUiThread(() -> {
                    btnRecognize.setText("🔍 重新识别");
                    btnRecognize.setEnabled(true);
                    tvResult.setText(text);
                    tvResult.setTextColor(0xFF333333);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnRecognize.setText("🔍 重新识别");
                    btnRecognize.setEnabled(true);
                    tvResult.setText("❌ " + error);
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_RECORD && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            doStartRecording();
        } else {
            Toast.makeText(this, "需要录音权限", Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 工具 ====================

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
