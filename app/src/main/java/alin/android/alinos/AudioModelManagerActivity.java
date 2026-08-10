package alin.android.alinos;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.InputStream;

import alin.android.alinos.voice.AudioService;
import alin.android.alinos.voice.ModelDownloadManager;

/**
 * 音频模型管理 — 纯下载/删除/切换。
 */
public class AudioModelManagerActivity extends AppCompatActivity
        implements ModelDownloadManager.DownloadListener {

    private TextView tvAsrStatus, tvAsrSize;
    private Button btnAsrSwitch, btnAsrDownload, btnAsrDelete;

    private TextView tvTtsStatus, tvTtsSize;
    private Button btnTtsSwitch, btnTtsDownload, btnTtsDelete;

    private TextView tvKwsStatus, tvKwsSize;
    private Button btnKwsDownload, btnKwsDelete;

    private TextView tvSpeakerStatus, tvSpeakerSize;
    private Button btnSpeakerDownload, btnSpeakerDelete;

    private TextView tvStorage;
    private Button btnDeleteAll, btnImport;

    private AudioService mAudio;
    private ModelDownloadManager mDl;
    private AlertDialog mProgressDialog;
    private TextView mProgressText;
    private boolean mDownloading;

    private static final String[] ASR_KEYS = {"paraformer", "sensevoice", "whisper"};
    private static final String[] ASR_NAMES = {"Paraformer (70MB)", "SenseVoice (200MB)", "Whisper (100MB)"};
    private static final String[] TTS_KEYS = {"melo", "aishell", "xiaoya"};
    private static final String[] TTS_NAMES = {"MeloTTS 中英双语", "AIShell3 标准中文", "Piper 小雅 女声"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        mAudio = AudioService.getInstance(this);
        mDl = new ModelDownloadManager(this, this);
        buildUi();
        refreshAll();
    }

    @Override
    protected void onResume() { super.onResume(); if (!mDownloading) refreshAll(); }

    @Override
    protected void onPause() { super.onPause(); dismissProgress(); }

    // ==================== UI ====================

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.LinearLayout r = new android.widget.LinearLayout(this);
        r.setOrientation(android.widget.LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(48), dp(16), dp(16));

        r.addView(t("⚙️ 音频模型管理", 18, 0xFF333333, true));
        r.addView(space(16));

        // ASR
        r.addView(t("📦 ASR 模型（识别声音）", 15, 0xFF333333, true));
        tvAsrStatus = t("", 13, 0xFF999999, false); r.addView(tvAsrStatus);
        tvAsrSize = t("", 12, 0xFF999999, false); r.addView(tvAsrSize);
        r.addView(row(btnAsrSwitch = btnSm("切换模型", 0xFF2196F3),
                btnAsrDownload = btnSm("下载", 0xFFFF9800), btnAsrDelete = btnSm("删除", 0xFFF44336)));
        r.addView(space(16));

        // TTS
        r.addView(t("🔊 TTS 模型（文字转语音）", 15, 0xFF333333, true));
        tvTtsStatus = t("", 13, 0xFF999999, false); r.addView(tvTtsStatus);
        tvTtsSize = t("", 12, 0xFF999999, false); r.addView(tvTtsSize);
        r.addView(row(btnTtsSwitch = btnSm("切换模型", 0xFF2196F3),
                btnTtsDownload = btnSm("下载", 0xFFFF9800), btnTtsDelete = btnSm("删除", 0xFFF44336)));
        r.addView(space(16));

        // KWS
        r.addView(t("🗣️ KWS 模型（语音唤醒）", 15, 0xFF333333, true));
        tvKwsStatus = t("", 13, 0xFF999999, false); r.addView(tvKwsStatus);
        tvKwsSize = t("", 12, 0xFF999999, false); r.addView(tvKwsSize);
        r.addView(row(btnKwsDownload = btnSm("下载", 0xFFFF9800), btnKwsDelete = btnSm("删除", 0xFFF44336)));
        r.addView(space(16));

        // Speaker
        r.addView(t("🔐 声纹模型", 15, 0xFF333333, true));
        tvSpeakerStatus = t("", 13, 0xFF999999, false); r.addView(tvSpeakerStatus);
        tvSpeakerSize = t("", 12, 0xFF999999, false); r.addView(tvSpeakerSize);
        r.addView(row(btnSpeakerDownload = btnSm("下载", 0xFFFF9800), btnSpeakerDelete = btnSm("删除", 0xFFF44336)));
        r.addView(space(16));

        // Storage
        r.addView(t("💾 存储", 15, 0xFF333333, true));
        tvStorage = t("", 13, 0xFF999999, false); r.addView(tvStorage);
        r.addView(row(btnImport = btnSm("导入外部模型", 0xFF2196F3), btnDeleteAll = btnSm("删除所有模型", 0xFFF44336)));

        sv.addView(r);
        setContentView(sv);

        // events
        btnAsrSwitch.setOnClickListener(v -> showAsrSwitchDialog());
        btnAsrDownload.setOnClickListener(v -> downloadAsr());
        btnAsrDelete.setOnClickListener(v -> confirmDelete("asr/" + asrKey()));
        btnTtsSwitch.setOnClickListener(v -> showTtsSwitchDialog());
        btnTtsDownload.setOnClickListener(v -> downloadTts());
        btnTtsDelete.setOnClickListener(v -> confirmDelete("tts/" + ttsKey()));
        btnKwsDownload.setOnClickListener(v -> startDownload("kws_mobile", "KWS"));
        btnKwsDelete.setOnClickListener(v -> confirmDelete("kws"));
        btnSpeakerDownload.setOnClickListener(v -> startDownload("speaker", "声纹"));
        btnSpeakerDelete.setOnClickListener(v -> confirmDelete("speaker"));
        btnDeleteAll.setOnClickListener(v -> confirmDeleteAll());
        btnImport.setOnClickListener(v -> openImportPicker());
    }

    // ==================== Refresh ====================

    private void refreshAll() { refreshAsr(); refreshTts(); refreshKws(); refreshSpeaker(); refreshStorage(); }

    private void refreshAsr() {
        String k = asrKey();
        boolean ok = mAudio.isAsrInstalled(k);
        tvAsrStatus.setText((ok ? "✅ " : "○ ") + ASR_NAMES[asrIdx()]);
        tvAsrStatus.setTextColor(ok ? 0xFF4CAF50 : 0xFFFF9800);
        tvAsrSize.setText("大小: " + (ok ? dirSize(mAudio.getAsrModelDir(k)) : "未下载"));
        btnAsrDelete.setVisibility(ok ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void refreshTts() {
        String k = ttsKey();
        boolean ok = mAudio.isTtsInstalled(k);
        tvTtsStatus.setText((ok ? "✅ " : "○ ") + TTS_NAMES[ttsIdx()]);
        tvTtsStatus.setTextColor(ok ? 0xFF4CAF50 : 0xFFFF9800);
        tvTtsSize.setText("大小: " + (ok ? dirSize(mAudio.getTtsModelDir(k)) : "未下载"));
        btnTtsDelete.setVisibility(ok ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void refreshKws() {
        boolean ok = mAudio.isKwsInstalled();
        tvKwsStatus.setText((ok ? "✅" : "○") + " KWS (3.3MB)");
        tvKwsStatus.setTextColor(ok ? 0xFF4CAF50 : 0xFFFF9800);
        tvKwsSize.setText("大小: " + (ok ? dirSize(mAudio.getKwsModelDir()) : "未下载"));
        btnKwsDelete.setVisibility(ok ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void refreshSpeaker() {
        boolean ok = new File(mAudio.getSpeakerModelDir(), "campplus.onnx").exists();
        tvSpeakerStatus.setText((ok ? "✅" : "○") + " CAM++ (27MB)");
        tvSpeakerStatus.setTextColor(ok ? 0xFF4CAF50 : 0xFFFF9800);
        tvSpeakerSize.setText("大小: " + (ok ? dirSize(mAudio.getSpeakerModelDir()) : "未下载"));
        btnSpeakerDelete.setVisibility(ok ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void refreshStorage() {
        new Thread(() -> {
            long total = dirSizeRaw(mAudio.getModelDir());
            runOnUiThread(() -> tvStorage.setText("总占用: " + fmtSize(total)));
        }).start();
    }

    // ==================== Switch ====================

    private String prefStr(String key, String def) {
        return getSharedPreferences("voice_settings", MODE_PRIVATE).getString(key, def);
    }

    private int asrIdx() { String c = prefStr("voice_asr_offline_model", "paraformer");
        for (int i = 0; i < ASR_KEYS.length; i++) if (ASR_KEYS[i].equals(c)) return i; return 0; }
    private String asrKey() { return ASR_KEYS[asrIdx()]; }

    private int ttsIdx() { String c = prefStr("voice_tts_model", "melo");
        for (int i = 0; i < TTS_KEYS.length; i++) if (TTS_KEYS[i].equals(c)) return i; return 0; }
    private String ttsKey() { return TTS_KEYS[ttsIdx()]; }

    private void showAsrSwitchDialog() {
        new AlertDialog.Builder(this).setTitle("切换 ASR 模型").setItems(ASR_NAMES, (d, w) -> {
            getSharedPreferences("voice_settings", MODE_PRIVATE).edit()
                    .putString("voice_asr_offline_model", ASR_KEYS[w]).apply();
            refreshAsr();
        }).setNegativeButton("取消", null).show();
    }

    private void showTtsSwitchDialog() {
        new AlertDialog.Builder(this).setTitle("切换 TTS 模型").setItems(TTS_NAMES, (d, w) -> {
            getSharedPreferences("voice_settings", MODE_PRIVATE).edit()
                    .putString("voice_tts_model", TTS_KEYS[w]).apply();
            refreshTts();
        }).setNegativeButton("取消", null).show();
    }

    // ==================== Download / Delete ====================

    private void downloadAsr() {
        if (mAudio.isAsrInstalled(asrKey())) { Toast.makeText(this, "已安装，请先删除", Toast.LENGTH_SHORT).show(); return; }
        startDownload(asrKey(), "ASR " + ASR_NAMES[asrIdx()]);
    }

    private void downloadTts() {
        if (mAudio.isTtsInstalled(ttsKey())) { Toast.makeText(this, "已安装，请先删除", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("下载: " + TTS_NAMES[ttsIdx()])
                .setMessage("将下载 TTS 模型文件。").setPositiveButton("下载", (d, w) -> {
                    mDl.downloadTtsModel(ttsKey());
                    Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("取消", null).show();
    }

    private void startDownload(String modelKey, String modelName) {
        new AlertDialog.Builder(this).setTitle("下载 " + modelName)
                .setMessage("将从网络下载模型文件。").setPositiveButton("下载", (d, w) -> doDownload(modelKey)).setNegativeButton("取消", null).show();
    }

    private void doDownload(String modelKey) {
        mDownloading = true;
        switch (modelKey) {
            case "kws_mobile": mDl.downloadKwsModel("kws_mobile"); break;
            case "speaker": mDl.downloadSpeakerModel(); break;
            case "paraformer": mDl.downloadAsrModel("paraformer"); break;
            case "sensevoice": mDl.downloadAsrModel("sensevoice"); break;
            case "whisper": mDl.downloadAsrModel("whisper"); break;
        }
    }

    private void confirmDelete(String path) {
        new AlertDialog.Builder(this).setTitle("删除模型").setMessage("确定删除？")
                .setPositiveButton("删除", (d, w) -> { mDl.deleteModel(path); refreshAll(); }).setNegativeButton("取消", null).show();
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this).setTitle("⚠️ 删除所有模型")
                .setMessage("将永久删除全部已下载的语音模型文件！\n\n删除后需要重新下载。确定删除？")
                .setPositiveButton("确认删除", (d, w) -> {
                    mDl.clearAllCache(); refreshAll();
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("取消", null).show();
    }

    // ==================== Import ====================

    private void openImportPicker() {
        startActivityForResult(Intent.createChooser(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), "选择模型"), 1001);
    }

    @Override
    protected void onActivityResult(int rc, int result, Intent data) {
        super.onActivityResult(rc, result, data);
        if (rc == 1001 && result == RESULT_OK && data != null && data.getData() != null) importModel(data.getData());
    }

    private void importModel(Uri uri) {
        String n = uri.getPath(); if (n == null) return; n = n.substring(n.lastIndexOf('/') + 1);
        String[] targets = {"ASR", "TTS", "KWS", "声纹", "VAD"}, dirs = {"asr", "tts", "kws", "speaker", "vad"};
        new AlertDialog.Builder(this).setTitle("导入到...").setItems(targets, (d, w) -> {
            new Thread(() -> {
                try {
                    File td = new File(mAudio.getModelDir(), dirs[w]); td.mkdirs();
                    File tmp = new File(getCacheDir(), "import_" + System.currentTimeMillis());
                    InputStream in = getContentResolver().openInputStream(uri);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                    byte[] b = new byte[8192]; int l;
                    while ((l = in.read(b)) > 0) out.write(b, 0, l); in.close(); out.close();
                    if (mDl.extractArchive(tmp, td)) {
                        runOnUiThread(() -> { tmp.delete(); refreshAll(); Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show(); });
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, "解压失败", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        }).setNegativeButton("取消", null).show();
    }

    // ==================== DownloadListener ====================

    @Override public void onDownloadStarted(String n) { runOnUiThread(() -> showProgress(n)); }
    @Override public void onDownloadProgress(String n, int p, long d, long t) {
        runOnUiThread(() -> { if (mProgressDialog != null) mProgressText.setText(n + ": " + p + "% (" + fmtSize(d) + ")"); });
    }
    @Override public void onDownloadSuccess(String n, File dir) {
        mDownloading = false; runOnUiThread(() -> { dismissProgress(); refreshAll(); Toast.makeText(this, "下载完成", Toast.LENGTH_SHORT).show(); });
    }
    @Override public void onDownloadFailed(String n, String e) {
        mDownloading = false; runOnUiThread(() -> { dismissProgress(); Toast.makeText(this, "下载失败: " + e, Toast.LENGTH_LONG).show(); });
    }

    private void showProgress(String name) {
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.VERTICAL); l.setPadding(40, 20, 40, 20);
        mProgressText = new TextView(this); mProgressText.setTextSize(14); mProgressText.setText("下载中: " + name + "..."); l.addView(mProgressText);
        mProgressDialog = new AlertDialog.Builder(this).setTitle("模型下载").setView(l).setCancelable(false)
                .setNegativeButton("取消下载", (d, w) -> { mDownloading = false; Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show(); }).create();
        mProgressDialog.show();
    }

    private void dismissProgress() { if (mProgressDialog != null && mProgressDialog.isShowing()) mProgressDialog.dismiss(); }

    // ==================== utils ====================

    private String dirSize(File d) { return fmtSize(dirSizeRaw(d)); }
    private long dirSizeRaw(File d) { long s = 0; if (d != null && d.isDirectory()) { File[] fs = d.listFiles(); if (fs != null) for (File f : fs) s += f.isDirectory() ? dirSizeRaw(f) : f.length(); } return s; }
    private String fmtSize(long b) { if (b < 1024) return b + " B"; if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0); return String.format("%.1f MB", b / (1024.0 * 1024.0)); }

    private TextView t(String text, int sp, int color, boolean bold) {
        TextView tv = new TextView(this); tv.setText(text); tv.setTextSize(sp); tv.setTextColor(color);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD); return tv;
    }

    private Button btnSm(String text, int color) {
        Button b = new Button(this); b.setText(text); b.setTextColor(0xFFFFFFFF); b.setTextSize(12);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, dp(40), 1);
        lp.setMargins(2, 0, 2, 0); b.setLayoutParams(lp); return b;
    }

    private android.widget.LinearLayout row(Button... btns) {
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        for (Button b : btns) l.addView(b);
        return l;
    }

    private android.view.View space(int h) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }
}
