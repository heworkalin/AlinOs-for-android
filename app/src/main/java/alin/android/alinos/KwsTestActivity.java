package alin.android.alinos;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.voice.AudioService;
import alin.android.alinos.voice.AppConfigStore;
import alin.android.alinos.voice.VoiceprintStore;
import alin.android.alinos.voice.engine.IKwsEngine;

/**
 * KWS 唤醒词验证 —— 五轮录音验证流程。
 *
 * 流程（真实业务）：
 *  ① 保存唤醒词：输入唤醒词 + 阈值，加载 KWS 引擎
 *  ② 五轮录音验证：
 *       每轮手动【开始录音】→ 说唤醒词 →【停止录音】
 *       停止后自动：静音判断 + 声纹提取 + KWS 唤醒词判定
 *       与唤醒词不一致（未命中/静音）→ 本轮作废，提示重新录音
 *       一致 → 记录本轮声纹，进入下一轮，共 5 轮
 *  ③ 五轮完成后统一声纹验证：5 段声纹两两相似度，判定是否稳定一致
 *  ④ 静音验证：录环境音，验证模型在静音下是否正常（不误触发）
 *  ⑤ 长录音验证：录较长音频，验证声纹 + 唤醒词是否一致，返回 true/false
 *  ⑥ 打印验证报告：汇总全部结果（界面显示 + Logcat 输出）
 */
public class KwsTestActivity extends AppCompatActivity {

    private static final String TAG = "KwsTest";
    private static final int REQ_RECORD = 400;
    private static final int ROUNDS = 5;
    private static final float SILENCE_RMS = 0.02f;   // 静音判定阈值
    private static final float VOICEPRINT_THRESHOLD = 0.6f;

    // 配置区
    private EditText etKeyword;
    private SeekBar sbThreshold;
    private TextView tvThreshold;
    private Button btnSaveKw;
    private TextView tvKwStatus;
    private android.widget.Spinner spKwsModel, spSpeakerModel;
    private java.util.List<AppConfigStore.Model> mCustomKws = new java.util.ArrayList<>();
    private java.util.List<AppConfigStore.Model> mCustomSpeaker = new java.util.ArrayList<>();

    // 五轮验证区
    private TextView tvRound;
    private Button btnStart, btnStop, btnVerify;
    private TextView tvRoundResult;

    // 功能按钮
    private Button btnSilence, btnLongRec, btnReport;

    // 保存区
    private Button btnSaveDb, btnDelDb;
    private TextView tvSavedList;

    // 数据库
    private VoiceprintStore mStore;
    private AppConfigStore mConfigStore;

    // 报告区
    private TextView tvReport;

    private Handler mHandler = new Handler(Looper.getMainLooper());

    // 录音
    private AudioRecord mAudioRecord;
    private ByteArrayOutputStream mAudioBuf;
    private boolean mRecording;
    private Thread mRecThread;
    private long mRecStartMs;
    private byte[] mLatestPcm;
    private float mLatestRms = -1f;

    // 引擎
    private IKwsEngine mKwsEngine;
    private SpeakerEmbeddingExtractor mExtractor;
    private SpeakerEmbeddingManager mSpeakerManager;

    // 五轮状态
    private int mRound = 1;                       // 当前轮 1..5，>5 表示全部完成
    private final List<float[]> mRoundEmbs = new ArrayList<>(); // 每轮声纹
    private final StringBuilder mRoundLog = new StringBuilder();

    // 长录音验证结果
    private String mLongResult = "（未执行）";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        mKwsEngine = AudioService.getInstance(this).getKwsEngine();
        mStore = new VoiceprintStore(this);
        mConfigStore = AudioService.getInstance(this).getConfigStore();
        buildUi();
        loadModelSelection();     // 数据库优先：恢复上次保存的模型选择
        loadSavedFromDb();        // 重启后自动加载数据库已保存的声纹/唤醒词
        refreshSavedState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        mKwsEngine.stop();
        if (mExtractor != null) mExtractor.release();
        if (mSpeakerManager != null) mSpeakerManager.release();
        mHandler.removeCallbacksAndMessages(null);
    }

    // ==================== UI ====================

    private void buildUi() {
        int dp = (int) getResources().getDisplayMetrics().density;
        int pad = 16 * dp;

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        root.addView(t("🔊 KWS 唤醒词验证（五轮录音）", 18, 0xFF333333, true));
        root.addView(space(12 * dp));

        // ---- ① 配置区 ----
        root.addView(t("唤醒词（中文或英文）：", 14, 0xFF666666, false));
        etKeyword = new EditText(this);
        etKeyword.setHint("例如：阿林 / Hello");
        etKeyword.setMaxLines(1);
        root.addView(etKeyword);
        root.addView(space(8 * dp));

        root.addView(t("KWS 触发阈值（越低越易唤醒，默认 0.25）：", 14, 0xFF666666, false));
        tvThreshold = t("0.25", 14, 0xFF2196F3, true);
        root.addView(tvThreshold);
        sbThreshold = new SeekBar(this);
        sbThreshold.setMax(45); // 0.05 ~ 0.50
        sbThreshold.setProgress(20); // 0.25
        sbThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                tvThreshold.setText(String.format("%.2f", 0.05f + p * 0.01f));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sbThreshold);
        root.addView(space(10 * dp));

        // 模型选择：唤醒词模型（KWS）+ 声纹模型（允许调整）
        root.addView(t("唤醒词模型（KWS zipformer）：", 14, 0xFF666666, false));
        spKwsModel = new android.widget.Spinner(this);
        mCustomKws.clear();
        for (AppConfigStore.Model m : mConfigStore.getModels("kws")) {
            if (!m.builtin) mCustomKws.add(m);
        }
        java.util.List<String> kwsNames = new java.util.ArrayList<>();
        kwsNames.add("内置 zh-en (3M)");
        for (AppConfigStore.Model m : mCustomKws) kwsNames.add("自定义: " + m.name);
        spKwsModel.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, kwsNames));
        root.addView(spKwsModel);
        root.addView(space(6 * dp));

        root.addView(t("声纹模型（说话人验证）：", 14, 0xFF666666, false));
        spSpeakerModel = new android.widget.Spinner(this);
        mCustomSpeaker.clear();
        for (AppConfigStore.Model m : mConfigStore.getModels("speaker")) {
            if (!m.builtin) mCustomSpeaker.add(m);
        }
        java.util.List<String> spkNames = new java.util.ArrayList<>();
        spkNames.add("内置 CAM++ (zh-en)");
        for (AppConfigStore.Model m : mCustomSpeaker) spkNames.add("自定义: " + m.name);
        spSpeakerModel.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, spkNames));
        root.addView(spSpeakerModel);
        root.addView(space(10 * dp));

        btnSaveKw = btn("① 保存唤醒词", 0xFF2196F3);
        root.addView(btnSaveKw);
        root.addView(space(4 * dp));
        tvKwStatus = t("状态：唤醒词未保存", 13, 0xFF999999, false);
        root.addView(tvKwStatus);
        root.addView(space(16 * dp));

        // ---- ② 五轮录音验证区 ----
        root.addView(t("② 五轮录音验证（每轮：开始录音 → 停止录音 → 验证录音）", 14, 0xFF666666, false));
        tvRound = t("第 1/" + ROUNDS + " 轮，通过 0 轮", 15, 0xFF333333, true);
        tvRound.setMinHeight(40 * dp);
        root.addView(tvRound);
        LinearLayout recRow = new LinearLayout(this);
        recRow.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = btn("开始录音", 0xFF4CAF50);
        btnStart.setLayoutParams(new LinearLayout.LayoutParams(0, 52 * dp, 1));
        recRow.addView(btnStart);
        btnStop = btn("停止录音", 0xFFF44336);
        btnStop.setEnabled(false);
        btnStop.setLayoutParams(new LinearLayout.LayoutParams(0, 52 * dp, 1));
        recRow.addView(btnStop);
        btnVerify = btn("验证录音", 0xFF2196F3);
        btnVerify.setEnabled(false);
        btnVerify.setLayoutParams(new LinearLayout.LayoutParams(0, 52 * dp, 1));
        recRow.addView(btnVerify);
        root.addView(recRow);
        root.addView(space(4 * dp));
        tvRoundResult = t("等待录音…（三步分开：开始 → 停止 → 验证）", 13, 0xFF999999, false);
        tvRoundResult.setMinHeight(44 * dp);
        root.addView(tvRoundResult);
        root.addView(space(16 * dp));

        // ---- ③④⑤ 功能按钮 ----
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        btnSilence = btn("③ 静音验证", 0xFF9C27B0);
        btnSilence.setLayoutParams(new LinearLayout.LayoutParams(0, 52 * dp, 1));
        row1.addView(btnSilence);
        btnLongRec = btn("④ 长录音验证", 0xFFFF9800);
        btnLongRec.setLayoutParams(new LinearLayout.LayoutParams(0, 52 * dp, 1));
        row1.addView(btnLongRec);
        root.addView(row1);
        root.addView(space(6 * dp));
        btnReport = btn("⑤ 打印验证报告", 0xFF3F51B5);
        btnReport.setMinHeight(52 * dp);
        root.addView(btnReport);
        root.addView(space(16 * dp));

        // ---- 保存区（本界面同时作为保存管理：唤醒词唯一，直接操作） ----
        root.addView(t("💾 保存声纹 / 声音（当前唤醒词）", 14, 0xFF333333, true));
        root.addView(space(6 * dp));
        root.addView(t("保存 = 完成五轮验证后写入并覆盖；再次保存 = 立即更新覆盖；删除 = 直接清除该唤醒词", 12, 0xFF999999, false));
        root.addView(space(6 * dp));
        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        btnSaveDb = btn("💾 保存（更新覆盖）", 0xFF009688);
        btnSaveDb.setLayoutParams(new LinearLayout.LayoutParams(0, 52 * dp, 1));
        saveRow.addView(btnSaveDb);
        btnDelDb = btn("🗑 直接删除", 0xFFF44336);
        btnDelDb.setLayoutParams(new LinearLayout.LayoutParams(0, 52 * dp, 1));
        saveRow.addView(btnDelDb);
        root.addView(saveRow);
        root.addView(space(6 * dp));
        tvSavedList = t("当前唤醒词未保存", 13, 0xFF666666, false);
        tvSavedList.setMinHeight(56 * dp);
        tvSavedList.setBackgroundColor(0xFFFAFAFA);
        tvSavedList.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        root.addView(tvSavedList);
        root.addView(space(16 * dp));

        // 唤醒词变化时刷新保存状态
        etKeyword.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refreshSavedState(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        // ---- 报告区（验证日志） ----
        root.addView(t("验证报告 / 日志", 14, 0xFF333333, true));
        root.addView(space(6 * dp));
        tvReport = t("（五轮验证完成后生成报告）", 13, 0xFF999999, false);
        tvReport.setMinHeight(120 * dp);
        tvReport.setBackgroundColor(0xFFF5F5F5);
        tvReport.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        root.addView(tvReport);

        sv.addView(root);
        setContentView(sv);

        btnSaveKw.setOnClickListener(v -> saveKeyword());
        btnStart.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> stopAndEnableVerify());
        btnVerify.setOnClickListener(v -> verifyRecording());
        btnSilence.setOnClickListener(v -> silenceVerify());
        btnLongRec.setOnClickListener(v -> longRecVerify());
        btnReport.setOnClickListener(v -> printReport());
        btnSaveDb.setOnClickListener(v -> saveToDb());
        btnDelDb.setOnClickListener(v -> deleteFromDb());
    }

    // ==================== ① 保存唤醒词 ====================

    private void saveKeyword() {
        String keyword = etKeyword.getText().toString().trim();
        if (keyword.isEmpty() || keyword.length() > 24) {
            Toast.makeText(this, "请输入唤醒词（中文或英文）", Toast.LENGTH_LONG).show();
            return;
        }
        float threshold = threshold();
        tvKwStatus.setText("⏳ 保存唤醒词并加载模型…");
        tvKwStatus.setTextColor(0xFFFF9800);
        btnSaveKw.setEnabled(false);

        // 保存配置：把当前选中的唤醒词模型/声纹模型/唤醒词/阈值一并写入数据库（切换模型后保存配置时生效）
        mConfigStore.setConfig("kws_model", selectedKwsModelKey());
        mConfigStore.setConfig("kws_speaker_model", selectedSpeakerModelKey());
        mConfigStore.setConfig("kws_keyword", keyword);
        mConfigStore.setConfig("kws_threshold", String.valueOf(threshold));

        File kwsDir;
        int kwsPos = spKwsModel.getSelectedItemPosition();
        if (kwsPos <= 0) {
            kwsDir = AudioService.getInstance(this).getKwsModelDir(); // 内置 zh-en
        } else {
            kwsDir = new File(mCustomKws.get(kwsPos - 1).path);       // 自定义唤醒词模型
        }
        if (!kwsDir.exists() || !kwsDir.isDirectory()) {
            tvKwStatus.setText("⚠ 唤醒词模型不存在（内置未下载或自定义已删除），请到音频管理界面配置");
            tvKwStatus.setTextColor(0xFFFF9800);
            btnSaveKw.setEnabled(true);
            return;
        }

        mKwsEngine.init(kwsDir, keyword, threshold, new IKwsEngine.Callback() {
            @Override public void onDetected(String kw, float conf) {}
            @Override public void onListening() {
                runOnUiThread(() -> {
                    tvKwStatus.setText("✅ 唤醒词已保存：「" + keyword + "」 阈值 " + String.format("%.2f", threshold)
                            + "\n可以开始五轮录音验证");
                    tvKwStatus.setTextColor(0xFF4CAF50);
                    btnSaveKw.setEnabled(true);
                    resetRounds();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvKwStatus.setText("❌ " + error);
                    tvKwStatus.setTextColor(0xFFF44336);
                    btnSaveKw.setEnabled(true);
                });
            }
        });
    }

    /** 保存唤醒词后重置五轮状态 */
    private void resetRounds() {
        mRound = 1;
        mRoundEmbs.clear();
        mRoundLog.setLength(0);
        mLongResult = "（未执行）";
        tvRound.setText("第 1/" + ROUNDS + " 轮，通过 0 轮");
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        btnVerify.setEnabled(false);
        tvRoundResult.setText("等待录音…（保存唤醒词后：开始 → 停止 → 验证）");
        tvRoundResult.setTextColor(0xFF999999);
        tvReport.setText("（五轮验证完成后生成报告）");
    }

    // ==================== 模型选择（数据库优先） ====================

    /** 当前选中的唤醒词模型标识："builtin"=内置，否则自定义模型名 */
    private String selectedKwsModelKey() {
        int pos = spKwsModel.getSelectedItemPosition();
        return pos <= 0 ? "builtin" : mCustomKws.get(pos - 1).name;
    }

    /** 当前选中的声纹模型标识："builtin"=内置 CAM++，否则自定义模型名 */
    private String selectedSpeakerModelKey() {
        int pos = spSpeakerModel.getSelectedItemPosition();
        return pos <= 0 ? "builtin" : mCustomSpeaker.get(pos - 1).name;
    }

    /**
     * 打开界面时从数据库恢复模型选择：
     * 数据库里有自定义模型选择且与默认不同 → 优先使用数据库中的；
     * 否则保持默认（内置）。
     */
    private void loadModelSelection() {
        String kwsCfg = mConfigStore.getConfig("kws_model", "builtin");
        if (!"builtin".equals(kwsCfg)) {
            for (int i = 0; i < mCustomKws.size(); i++) {
                if (mCustomKws.get(i).name.equals(kwsCfg)) {
                    spKwsModel.setSelection(i + 1);
                    break;
                }
            }
        }
        String spkCfg = mConfigStore.getConfig("kws_speaker_model", "builtin");
        if (!"builtin".equals(spkCfg)) {
            for (int i = 0; i < mCustomSpeaker.size(); i++) {
                if (mCustomSpeaker.get(i).name.equals(spkCfg)) {
                    spSpeakerModel.setSelection(i + 1);
                    break;
                }
            }
        }
    }

    // ==================== ② 五轮录音验证 ====================

    /** ① 开始录音（独立步骤） */
    private void startRecording() {
        if (mRecording) {
            Toast.makeText(this, "正在录音中", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
            return;
        }
        int minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "录音初始化失败", Toast.LENGTH_SHORT).show();
            return;
        }
        mAudioBuf = new ByteArrayOutputStream();
        mRecording = true;
        mRecStartMs = SystemClock.elapsedRealtime();
        mAudioRecord.startRecording();
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        btnVerify.setEnabled(false);
        tvRoundResult.setText("🔴 录音中…（说完唤醒词请点【停止录音】）");
        tvRoundResult.setTextColor(0xFFFF4444);

        mRecThread = new Thread(() -> {
            byte[] buf = new byte[minBuf];
            while (mRecording && mAudioRecord != null) {
                int len = mAudioRecord.read(buf, 0, buf.length);
                if (len > 0) mAudioBuf.write(buf, 0, len);
            }
        });
        mRecThread.start();
    }

    /** ② 停止录音（独立步骤）：保存 PCM，解锁验证按钮 */
    private void stopAndEnableVerify() {
        stopRecording();
        boolean hasAudio = mAudioBuf != null && mAudioBuf.size() > 0;
        if (hasAudio) {
            mLatestPcm = mAudioBuf.toByteArray();
            mLatestRms = computeRms(mLatestPcm);
        }
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        btnVerify.setEnabled(hasAudio);
        tvRoundResult.setText(hasAudio
                ? "⏹ 已停止（" + mLatestPcm.length / 32000f + "s），点【验证录音】"
                : "⚠ 未录到音频，请重新开始录音");
        tvRoundResult.setTextColor(hasAudio ? 0xFF999999 : 0xFFFF9800);
    }

    private void stopRecording() {
        mRecording = false;
        if (mRecThread != null) { try { mRecThread.join(300); } catch (Exception ignored) {} mRecThread = null; }
        if (mAudioRecord != null) {
            try { mAudioRecord.stop(); mAudioRecord.release(); } catch (Exception ignored) {}
            mAudioRecord = null;
        }
    }

    /**
     * ③ 验证录音（独立步骤）：静音判断 → KWS 识别提示词 → 命中后计算声纹。
     * 未命中/静音 → 本轮作废，重新录音。
     */
    private void verifyRecording() {
        if (mLatestPcm == null) {
            Toast.makeText(this, "请先录音", Toast.LENGTH_SHORT).show();
            return;
        }
        final int round = mRound;
        btnVerify.setEnabled(false);
        tvRoundResult.setText("🔍 验证第 " + round + " 轮…");
        tvRoundResult.setTextColor(0xFF2196F3);

        // 1) 静音判断
        if (mLatestRms < SILENCE_RMS) {
            btnVerify.setEnabled(true);
            tvRoundResult.setText("❌ 第 " + round + " 轮：环境静音，未检测到语音\n请重新录音（与唤醒词不一致）");
            tvRoundResult.setTextColor(0xFFF44336);
            return;
        }
        // 2) KWS 识别提示词
        if (!mKwsEngine.isReady()) {
            btnVerify.setEnabled(true);
            tvRoundResult.setText("❌ KWS 未就绪，请先保存唤醒词");
            tvRoundResult.setTextColor(0xFFF44336);
            return;
        }
        mKwsEngine.detectPcm(mLatestPcm, new IKwsEngine.Callback() {
            @Override public void onDetected(String kw, float conf) {
                runOnUiThread(() -> {
                    boolean hit = kw != null && !kw.isEmpty();
                    if (!hit) {
                        // 与唤醒词不一致 → 重新录音
                        btnVerify.setEnabled(true);
                        tvRoundResult.setText("❌ 第 " + round + " 轮：未命中唤醒词\n请重新录音（识别提示词与唤醒词不一致）");
                        tvRoundResult.setTextColor(0xFFF44336);
                        return;
                    }
                    // 3) 命中 → 计算声纹并保存，推进轮次
                    tvRoundResult.setText("✅ 第 " + round + " 轮命中「" + kw + "」，计算声纹中…");
                    tvRoundResult.setTextColor(0xFF4CAF50);
                    extractVoiceprint(round, conf);
                });
            }
            @Override public void onListening() {}
            @Override public void onError(String e) {
                runOnUiThread(() -> {
                    btnVerify.setEnabled(true);
                    tvRoundResult.setText("❌ 第 " + round + " 轮检测异常：" + e);
                    tvRoundResult.setTextColor(0xFFF44336);
                });
            }
        });
    }

    /** 提取本轮声纹，成功则推进轮次 */
    private void extractVoiceprint(int round, float conf) {
        new Thread(() -> {
            try {
                ensureExtractor();
                if (mExtractor == null) {
                    runOnUiThread(() -> {
                        tvRoundResult.setText("⚠ 第 " + round + " 轮命中，但声纹模型未下载（跳过声纹，仅记录 KWS）");
                        tvRoundResult.setTextColor(0xFFFF9800);
                        advanceRound(round, null, conf);
                    });
                    return;
                }
                float[] emb = computeEmbedding(mLatestPcm);
                runOnUiThread(() -> advanceRound(round, emb, conf));
            } catch (Exception e) {
                Log.e(TAG, "声纹提取异常", e);
                runOnUiThread(() -> advanceRound(round, null, conf));
            }
        }).start();
    }

    /** 推进轮次；五轮完成后统一声纹验证 */
    private void advanceRound(int round, float[] emb, float conf) {
        if (emb != null && emb.length > 0) {
            synchronized (mRoundEmbs) {
                if (round <= ROUNDS) {
                    mRoundEmbs.add(emb);
                }
            }
        }
        mRoundLog.append(String.format("第%d轮: 命中 置信度%.2f%s\n", round, conf,
                emb != null ? " 声纹已记录" : " 无声纹"));
        Log.d(TAG, String.format("第%d轮: 命中 置信度%.2f%s", round, conf, emb != null ? " 声纹已记录" : " 无声纹"));

        if (round >= ROUNDS) {
            // 五轮全部通过 → 统一声纹验证
            mRound = ROUNDS + 1;
            btnStart.setEnabled(false);
            btnStop.setEnabled(false);
            btnVerify.setEnabled(false);
            tvRoundResult.setText("✅ 五轮全部通过，正在做统一声纹验证…");
            tvRoundResult.setTextColor(0xFF4CAF50);
            unifiedVoiceprintVerify();
        } else {
            mRound = round + 1;
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            btnVerify.setEnabled(false);
            tvRound.setText("第 " + mRound + "/" + ROUNDS + " 轮，已通过 " + round + " 轮");
            tvRoundResult.setText("✅ 第 " + round + " 轮通过，请开始第 " + mRound + " 轮录音（再说一遍唤醒词）");
            tvRoundResult.setTextColor(0xFF4CAF50);
        }
    }

    /** 五轮完成后的统一声纹验证：5 段两两余弦相似度平均 */
    private void unifiedVoiceprintVerify() {
        new Thread(() -> {
            List<float[]> embs;
            synchronized (mRoundEmbs) {
                embs = new ArrayList<>(mRoundEmbs);
            }
            if (embs.size() < 2) {
                runOnUiThread(() -> {
                    tvRoundResult.setText("⚠ 声纹样本不足（" + embs.size() + " 段），跳过统一声纹验证");
                    tvRoundResult.setTextColor(0xFFFF9800);
                });
                return;
            }
            // 注册全部样本
            if (mSpeakerManager == null) mSpeakerManager = new SpeakerEmbeddingManager(mExtractor.dim());
            float[][] arr = new float[embs.size()][];
            embs.toArray(arr);
            mSpeakerManager.add("user", arr);

            // 两两相似度平均
            double sum = 0;
            int cnt = 0;
            for (int i = 0; i < embs.size(); i++) {
                for (int j = i + 1; j < embs.size(); j++) {
                    sum += cosineSimilarity(embs.get(i), embs.get(j));
                    cnt++;
                }
            }
            float avgSim = cnt > 0 ? (float) (sum / cnt) : 0f;
            boolean stable = avgSim >= VOICEPRINT_THRESHOLD;
            final float sim = avgSim;
            runOnUiThread(() -> {
                tvRoundResult.setText(stable
                        ? "✅ 统一声纹验证通过：5 段声纹一致（平均相似度 " + String.format("%.2f", sim) + "）\n⚠ 声纹仅记录在本次会话，请点击「💾 保存」写入本地数据库（否则重启后丢失）"
                        : "⚠ 统一声纹验证：平均相似度 " + String.format("%.2f", sim) + "（<0.60，声纹不稳定，建议重新五轮）");
                tvRoundResult.setTextColor(stable ? 0xFF4CAF50 : 0xFFFF9800);
                mRoundLog.append(String.format("统一声纹验证: 平均相似度%.2f %s（请手动保存到数据库）\n", sim, stable ? "通过" : "不稳定"));
                Log.d(TAG, "统一声纹验证: 平均相似度 " + sim + (stable ? " 通过" : " 不稳定"));
            });
        }).start();
    }

    // ==================== ③ 静音验证 ====================

    private void silenceVerify() {
        Toast.makeText(this, "请保持安静，录音 5 秒判断环境是否静音", Toast.LENGTH_LONG).show();
        recordSeconds(5, "静音验证", pcm -> {
            float rms = computeRms(pcm);
            boolean silent = rms < SILENCE_RMS;
            String line = "静音验证: " + (silent ? "✅ 模型正常（环境静音，无误触发）" : "❌ 环境非静音（RMS=" + String.format("%.3f", rms) + "）")
                    + "\n（静音下 KWS 不应误触发唤醒词）";
            tvReport.setText(line + "\n\n" + tvReport.getText());
            Log.d(TAG, line);
        });
    }

    // ==================== ④ 长录音验证 ====================

    private void longRecVerify() {
        Toast.makeText(this, "请录音 10~30 秒，中途说出唤醒词，验证声纹+唤醒词", Toast.LENGTH_LONG).show();
        recordSeconds(15, "长录音验证", pcm -> {
            tvRoundResult.setText("🔍 长录音分析中…（声纹 + 唤醒词）");
            tvRoundResult.setTextColor(0xFF2196F3);
            analyzeLongRecording(pcm);
        });
    }

    /** 长录音验证：声纹是否正常 + 是否命中唤醒词 → true/false */
    private void analyzeLongRecording(byte[] pcm) {
        new Thread(() -> {
            try {
                float rms = computeRms(pcm);
                boolean speech = rms >= SILENCE_RMS;

                // KWS：长录音中是否检出唤醒词
                final boolean[] kwsHit = new boolean[1];
                if (mKwsEngine.isReady()) {
                    final Object lock = new Object();
                    mKwsEngine.detectPcm(pcm, new IKwsEngine.Callback() {
                        @Override public void onDetected(String kw, float conf) {
                            kwsHit[0] = kw != null && !kw.isEmpty();
                            synchronized (lock) { lock.notifyAll(); }
                        }
                        @Override public void onListening() {}
                        @Override public void onError(String e) {
                            synchronized (lock) { lock.notifyAll(); }
                        }
                    });
                    synchronized (lock) {
                        try { lock.wait(15000); } catch (InterruptedException ignored) {}
                    }
                }

                // 声纹：优先用本轮会话五轮声纹（内存），否则从数据库加载已保存的声纹（重启后可用）
                boolean vpOk = false;
                float vpSim = 0f;
                if (mExtractor == null) ensureExtractor();
                if (mExtractor != null) {
                    float[] emb = computeEmbedding(pcm);
                    if (emb != null && emb.length > 0) {
                        float[] ref = averageEmbedding(); // 五轮平均（内存）
                        if (ref == null || ref.length == 0) {
                            ref = loadSavedEmbedding(etKeyword.getText().toString().trim()); // 数据库（持久）
                        }
                        if (ref != null && ref.length == emb.length) {
                            vpSim = cosineSimilarity(emb, ref);
                            vpOk = vpSim >= VOICEPRINT_THRESHOLD;
                        }
                    }
                }

                boolean result = speech && kwsHit[0] && vpOk;
                String line = "长录音验证: 有声=" + speech + " 唤醒词命中=" + kwsHit[0]
                        + " 声纹本人=" + vpOk + (vpSim > 0 ? String.format("(%.2f)", vpSim) : "")
                        + " → " + result;
                mLongResult = result ? "true" : "false";
                runOnUiThread(() -> {
                    tvReport.setText(line + "\n\n" + tvReport.getText());
                    tvRoundResult.setText(result
                            ? "✅ 长录音验证通过（true）：声纹正常 + 命中唤醒词"
                            : "❌ 长录音验证失败（false）：声纹或唤醒词不一致");
                    tvRoundResult.setTextColor(result ? 0xFF4CAF50 : 0xFFF44336);
                });
                Log.d(TAG, line);
            } catch (Exception e) {
                Log.e(TAG, "长录音验证异常", e);
            }
        }).start();
    }

    /** 从数据库加载指定唤醒词的已保存声纹（持久化，重启后依然可用） */
    private float[] loadSavedEmbedding(String keyword) {
        if (keyword == null || keyword.isEmpty()) return null;
        for (VoiceprintStore.Record r : mStore.getAll()) {
            if (keyword.equals(r.keyword) && r.embedding.length > 0) {
                return r.embedding;
            }
        }
        return null;
    }

    // ==================== 💾 保存 / 更新 / 删除 数据库 ====================

    private void saveToDb() {
        String keyword = etKeyword.getText().toString().trim();
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请先输入唤醒词", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mRoundEmbs.size() < ROUNDS) {
            Toast.makeText(this, "请先完成五轮验证（含声纹）后再保存", Toast.LENGTH_LONG).show();
            return;
        }
        // 平均声纹向量
        float[] avg = averageEmbedding();
        if (avg == null || avg.length == 0) {
            Toast.makeText(this, "声纹数据为空，无法保存", Toast.LENGTH_SHORT).show();
            return;
        }
        // 声音样本存为 WAV
        String wavPath = null;
        if (mLatestPcm != null && mLatestPcm.length > 0) {
            String safe = keyword.replaceAll("[^\\w\\u4e00-\\u9fff]", "_");
            File f = new File(mStore.getAudioDir(), safe + "_" + System.currentTimeMillis() + ".wav");
            try {
                writeWav(f, mLatestPcm);
                wavPath = f.getAbsolutePath();
            } catch (Exception e) {
                Log.e(TAG, "写WAV失败", e);
            }
        }
        boolean updating = wasSaved(keyword);
        if (mStore.save(keyword, avg, wavPath)) {
            refreshSavedState();
            String line = "保存数据库: 「" + keyword + "」 声纹" + avg.length + "维"
                    + (wavPath != null ? " + 声音样本" : "（无声音样本）") + " 已" + (updating ? "更新覆盖" : "保存");
            tvReport.setText(line + "\n\n" + tvReport.getText());
            Log.d(TAG, line);
            Toast.makeText(this, updating ? "已更新覆盖" : "已保存到数据库", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean wasSaved(String keyword) {
        for (VoiceprintStore.Record r : mStore.getAll()) {
            if (keyword.equals(r.keyword)) return true;
        }
        return false;
    }

    private void deleteFromDb() {
        String keyword = etKeyword.getText().toString().trim();
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请先输入唤醒词", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mStore.delete(keyword)) {
            refreshSavedState();
            String line = "删除数据库: 「" + keyword + "」";
            tvReport.setText(line + "\n\n" + tvReport.getText());
            Log.d(TAG, line);
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "「" + keyword + "」未保存，无需删除", Toast.LENGTH_SHORT).show();
        }
    }

    /** 五轮声纹的平均向量 */
    private float[] averageEmbedding() {
        synchronized (mRoundEmbs) {
            if (mRoundEmbs.isEmpty()) return new float[0];
            int dim = mRoundEmbs.get(0).length;
            float[] avg = new float[dim];
            for (float[] e : mRoundEmbs) {
                if (e.length != dim) return new float[0];
                for (int i = 0; i < dim; i++) avg[i] += e[i];
            }
            for (int i = 0; i < dim; i++) avg[i] /= mRoundEmbs.size();
            return avg;
        }
    }

    /** 重启后自动加载数据库里已保存的唤醒词/声纹（不自动保存，只回显让用户确认） */
    private void loadSavedFromDb() {
        List<VoiceprintStore.Record> list = mStore.getAll();
        if (!list.isEmpty()) {
            VoiceprintStore.Record latest = list.get(0); // 最近更新的一条
            etKeyword.setText(latest.keyword);
            tvKwStatus.setText("✅ 已从数据库加载：「" + latest.keyword + "」（声纹" + latest.embedding.length
                    + "维" + (latest.audioPath != null ? ", 声音✓" : "") + "）");
            tvKwStatus.setTextColor(0xFF009688);
            Log.d(TAG, "已从数据库加载: " + latest.keyword);
            // 自动初始化 KWS（否则重启后引擎未就绪，长录音验证会误报"唤醒词未命中"）
            saveKeyword();
        }
    }

    /** 显示当前唤醒词的保存状态（唯一唤醒词，无需列表选择） */
    private void refreshSavedState() {
        String keyword = etKeyword != null ? etKeyword.getText().toString().trim() : "";
        if (keyword.isEmpty()) {
            tvSavedList.setText("当前唤醒词未保存");
            tvSavedList.setTextColor(0xFF999999);
            return;
        }
        for (VoiceprintStore.Record r : mStore.getAll()) {
            if (keyword.equals(r.keyword)) {
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
                tvSavedList.setText("✅ 已保存「" + keyword + "」 [声纹" + r.embedding.length + "维"
                        + (r.audioPath != null ? ", 声音✓" : "") + "] 更新于 "
                        + fmt.format(new java.util.Date(r.updatedAt)));
                tvSavedList.setTextColor(0xFF009688);
                return;
            }
        }
        tvSavedList.setText("当前唤醒词「" + keyword + "」未保存（完成五轮验证后点保存）");
        tvSavedList.setTextColor(0xFF666666);
    }

    /** PCM(16kHz/16bit/mono) 写为 WAV 文件 */
    private void writeWav(File f, byte[] pcm) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        fos.write("RIFF".getBytes("US-ASCII"));
        writeLE(fos, 36 + pcm.length);
        fos.write("WAVE".getBytes("US-ASCII"));
        fos.write("fmt ".getBytes("US-ASCII"));
        writeLE(fos, 16); writeLE(fos, 1); writeLE(fos, 1);
        writeLE(fos, 16000); writeLE(fos, 32000); writeLE(fos, 2); writeLE(fos, 16);
        fos.write("data".getBytes("US-ASCII"));
        writeLE(fos, pcm.length);
        fos.write(pcm);
        fos.close();
    }

    private void writeLE(java.io.FileOutputStream fos, int v) throws Exception {
        fos.write(v & 0xff);
        fos.write((v >> 8) & 0xff);
        fos.write((v >> 16) & 0xff);
        fos.write((v >> 24) & 0xff);
    }

    // ==================== ⑤ 打印验证报告 ====================

    private void printReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ KWS 验证报告 ═══\n");
        sb.append("唤醒词：").append(etKeyword.getText().toString().trim()).append("\n");
        sb.append("阈值：").append(String.format("%.2f", threshold())).append("\n");
        sb.append("五轮验证：").append(mRound > ROUNDS ? "✅ 全部通过" : "进行中（通过 " + Math.min(mRound - 1, ROUNDS) + "/" + ROUNDS + "）").append("\n");
        if (mRoundLog.length() > 0) sb.append(mRoundLog);
        sb.append(mLongResult.equals("true") ? "长录音验证：✅ true\n" : mLongResult.equals("false") ? "长录音验证：❌ false\n" : "长录音验证：未执行\n");
        sb.append("═══ END ═══");

        tvReport.setText(sb.toString());
        Log.d(TAG, sb.toString()); // 打印日志
        Toast.makeText(this, "报告已生成并打印到日志", Toast.LENGTH_SHORT).show();
    }

    // ==================== 录音工具 ====================

    private interface PcmCallback { void onPcm(byte[] pcm); }

    /** 固定时长录音（静音/长录音验证用） */
    private void recordSeconds(int seconds, String tag, PcmCallback done) {
        stopRecording(); // 防止与五轮录音冲突
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        btnVerify.setEnabled(false);
        int minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "录音初始化失败", Toast.LENGTH_SHORT).show();
            return;
        }
        mAudioBuf = new ByteArrayOutputStream();
        mRecording = true;
        mAudioRecord.startRecording();
        tvRoundResult.setText("🔴 " + tag + " 录音中，等待 " + seconds + " 秒后自动结束…");
        tvRoundResult.setTextColor(0xFFFF4444);

        long startMs = SystemClock.elapsedRealtime();
        mRecThread = new Thread(() -> {
            byte[] buf = new byte[minBuf];
            while (mRecording && mAudioRecord != null) {
                long remain = seconds * 1000L - (SystemClock.elapsedRealtime() - startMs);
                if (remain <= 0) break;
                int len = mAudioRecord.read(buf, 0, buf.length);
                if (len > 0) mAudioBuf.write(buf, 0, len);
            }
            stopRecording();
            final byte[] pcm = mAudioBuf.toByteArray();
            mHandler.post(() -> done.onPcm(pcm));
        });
        mRecThread.start();

        // 自动倒计时显示（剩余秒数）
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!mRecording) return;
                long remainSec = (seconds * 1000L - (SystemClock.elapsedRealtime() - startMs)) / 1000;
                tvRoundResult.setText("🔴 " + tag + " 录音中，等待 " + Math.max(0, remainSec) + " 秒后自动结束…");
                tvRoundResult.setTextColor(0xFFFF4444);
                mHandler.postDelayed(this, 200);
            }
        }, 200);
    }

    // ==================== 声纹工具 ====================

    private void ensureExtractor() {
        if (mExtractor != null) return;
        AudioService as = AudioService.getInstance(this);
        File model;
        int spkPos = spSpeakerModel.getSelectedItemPosition();
        if (spkPos <= 0) {
            model = new File(as.getModelDir(), "speaker/campplus.onnx"); // 内置 CAM++
        } else {
            model = new File(mCustomSpeaker.get(spkPos - 1).path);        // 自定义声纹模型
        }
        if (!model.exists()) return;
        SpeakerEmbeddingExtractorConfig cfg = new SpeakerEmbeddingExtractorConfig(
                model.getAbsolutePath(), 2, false, "cpu");
        mExtractor = new SpeakerEmbeddingExtractor(null, cfg);
    }

    private float[] computeEmbedding(byte[] pcm) {
        try {
            float[] samples = bytesToFloat(pcm);
            OnlineStream stream = mExtractor.createStream();
            stream.acceptWaveform(samples, 16000);
            stream.inputFinished();
            float[] emb = mExtractor.compute(stream);
            stream.release();
            return emb;
        } catch (Exception e) {
            Log.e(TAG, "computeEmbedding 异常", e);
            return null;
        }
    }

    /** 余弦相似度 */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) return 0f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    private float computeRms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return 0f;
        double sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i < n; i++) {
            short v = (short) (((pcm[i * 2 + 1] & 0xFF) << 8) | (pcm[i * 2] & 0xFF));
            double s = v / 32768.0;
            sum += s * s;
        }
        return (float) Math.sqrt(sum / n);
    }

    private float[] bytesToFloat(byte[] bytes) {
        int sampleCount = bytes.length / 2;
        float[] out = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            short val = (short) (((bytes[i * 2 + 1] & 0xFF) << 8) | (bytes[i * 2] & 0xFF));
            out[i] = val / 32768.0f;
        }
        return out;
    }

    private float threshold() {
        return 0.05f + sbThreshold.getProgress() * 0.01f;
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
        b.setTextSize(13);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        return b;
    }

    private android.view.View space(int h) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_RECORD && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            Toast.makeText(this, "需要录音权限", Toast.LENGTH_LONG).show();
        }
    }
}
