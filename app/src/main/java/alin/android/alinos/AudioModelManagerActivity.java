package alin.android.alinos;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.voice.AppConfigStore;
import alin.android.alinos.voice.AudioService;
import alin.android.alinos.voice.ModelDownloadManager;

/**
 * 音频模型管理界面（重新规划）。
 *
 * 职责划分：
 *  - 只负责【内置模型下载】+【自定义模型导入/管理】，不再负责模型切换。
 *  - 模型切换（默认配置）由 ASR / TTS / KWS 测试界面的【💾 保存配置】设置，此处仅展示当前默认。
 *
 * 内置下载：ASR(paraformer/sensevoice/whisper)、TTS(melo/aishell/xiaoya)、KWS、声纹、VAD。
 *
 * 自定义模型：
 *  - 导入包必须是压缩包（tar.bz2 / tar.gz / zip），非压缩包拒绝。
 *  - 导入后存放在 files/voice_models/custom/<类型>/<名称>/，自动注册到数据库。
 *  - 【长按】自定义模型条目 → 弹对话框：重新设置类型（ASR/TTS/KWS）或删除；
 *    改类型会同步移动目录并更新数据库注册。
 *  - 兼容性说明：模型必须符合 sherpa-onnx 1.13.5 规范，否则加载失败。
 */
public class AudioModelManagerActivity extends AppCompatActivity
        implements ModelDownloadManager.DownloadListener {

    private static final int REQ_IMPORT = 1001;

    private AudioService mAudio;
    private ModelDownloadManager mDl;
    private AppConfigStore mStore;

    private AlertDialog mProgressDialog;
    private TextView mProgressText;
    private boolean mDownloading;

    private LinearLayout mCustomList;
    private TextView tvDefaultConfig, tvCustomHint;
    private final java.util.Map<String, Button> mDlButtons = new java.util.HashMap<>();

    // 内置模型（下载区展示）
    private static final String[] ASR_KEYS = {"paraformer", "sensevoice", "whisper"};
    private static final String[] ASR_NAMES = {"Paraformer (70MB)", "SenseVoice (200MB)", "Whisper (100MB)"};
    private static final String[] TTS_KEYS = {"melo", "aishell", "xiaoya"};
    private static final String[] TTS_NAMES = {"MeloTTS 中英", "AIShell3 中文", "Piper 小雅"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll(); // 每次回到本页都重新读取目录状态
    }

    // ==================== UI ====================

    private void buildUi() {
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        mAudio = AudioService.getInstance(this);
        mDl = new ModelDownloadManager(this, this);
        mStore = mAudio.getConfigStore();
        buildUiImpl();
        refreshAll();
    }

    private void buildUiImpl() {
        int dp = (int) getResources().getDisplayMetrics().density;
        int pad = 16 * dp;

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        // 右上角 Help
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView titleTv = t("🗂️ 音频模型管理", 18, 0xFF333333, true);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1));
        titleRow.addView(titleTv);
        Button btnHelp = new Button(this);
        btnHelp.setText("❓ Help");
        btnHelp.setTextSize(12);
        btnHelp.setTextColor(0xFFFFFFFF);
        btnHelp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF607D8B));
        btnHelp.setLayoutParams(new LinearLayout.LayoutParams(dp(88), dp(40)));
        btnHelp.setOnClickListener(v -> showHelp());
        titleRow.addView(btnHelp);
        root.addView(titleRow);
        root.addView(space(4 * dp));
        root.addView(t("模型切换请到 ASR / TTS / KWS 测试界面点【💾 保存配置】设置默认，本页只负责下载与导入", 12, 0xFF999999, false));
        root.addView(space(8 * dp));

        // 当前默认配置（来自测试界面保存的 configs）
        root.addView(t("当前默认配置", 14, 0xFF333333, true));
        tvDefaultConfig = t("ASR: - / TTS: - / KWS: -", 13, 0xFF2196F3, false);
        root.addView(tvDefaultConfig);
        root.addView(space(16 * dp));

        // ---- 内置模型下载区 ----
        root.addView(t("⬇️ 内置模型下载（预设）", 14, 0xFF333333, true));
        root.addView(space(6 * dp));

        root.addView(t("语音识别 ASR", 13, 0xFF666666, false));
        for (int i = 0; i < ASR_KEYS.length; i++) {
            final int idx = i;
            root.addView(modelRow("asr_" + ASR_KEYS[i], "ASR-" + ASR_KEYS[i],
                    v -> startDownload("asr_" + ASR_KEYS[idx], "ASR"),
                    v -> deleteModelDir(dirFor("asr_" + ASR_KEYS[idx]), "ASR-" + ASR_KEYS[idx])));
        }
        root.addView(space(8 * dp));

        root.addView(t("文字转语音 TTS", 13, 0xFF666666, false));
        for (int i = 0; i < TTS_KEYS.length; i++) {
            final int idx = i;
            root.addView(modelRow("tts_" + TTS_KEYS[i], "TTS-" + TTS_KEYS[i],
                    v -> startDownload("tts_" + TTS_KEYS[idx], "TTS"),
                    v -> deleteModelDir(dirFor("tts_" + TTS_KEYS[idx]), "TTS-" + TTS_KEYS[idx])));
        }
        root.addView(space(8 * dp));

        root.addView(t("唤醒 KWS", 13, 0xFF666666, false));
        root.addView(modelRow("kws", "KWS (zh-en 3M)",
                v -> startDownload("kws_mobile", "KWS"),
                v -> deleteModelDir(dirFor("kws"), "KWS")));
        root.addView(space(8 * dp));

        root.addView(t("声纹 / VAD", 13, 0xFF666666, false));
        root.addView(modelRow("speaker", "声纹 CAM++",
                v -> startDownload("speaker", "声纹"),
                v -> deleteModelDir(dirFor("speaker"), "声纹")));
        root.addView(modelRow("vad", "VAD silero",
                v -> startDownload("vad", "VAD"),
                v -> deleteModelDir(dirFor("vad"), "VAD")));
        root.addView(space(16 * dp));

        // ---- 自定义模型区 ----
        root.addView(t("📦 自定义模型（导入 / 长按配置）", 14, 0xFF333333, true));
        root.addView(space(4 * dp));
        tvCustomHint = t("⚠ 导入包必须是压缩包（.tar.bz2/.tar.gz/.zip）；模型须符合 sherpa-onnx 1.13.5 规范（ASR: tokens.txt+onnx / TTS: VITS / KWS: zipformer2 metadata），否则加载失败。导入后【长按】可设置类型或删除。", 12, 0xFFFF9800, false);
        root.addView(tvCustomHint);
        root.addView(space(6 * dp));

        Button btnImport = btn("➕ 导入自定义模型（压缩包）", 0xFF2196F3);
        root.addView(btnImport);
        btnImport.setOnClickListener(v -> openImportPicker());
        root.addView(space(6 * dp));

        mCustomList = new LinearLayout(this);
        mCustomList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mCustomList);
        root.addView(space(16 * dp));

        // ---- 存储占用 ----
        root.addView(t("💾 存储占用", 14, 0xFF333333, true));
        final TextView tvStorage = t("计算中...", 13, 0xFF666666, false);
        root.addView(tvStorage);
        new Thread(() -> {
            long total = dirSizeRaw(mAudio.getModelDir());
            final String s = fmtSize(total);
            runOnUiThread(() -> tvStorage.setText("模型目录占用: " + s));
        }).start();

        sv.addView(root);
        setContentView(sv);
    }

    /** 一行内置模型：名称 + 下载 + 删除 */
    /** 一行内置模型：名称 + 下载(读取目录状态) + 删除 */
    private LinearLayout modelRow(String key, String name, android.view.View.OnClickListener dl, android.view.View.OnClickListener del) {
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(13);
        tv.setTextColor(0xFF444444);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1));
        row.addView(tv);

        Button bDl = new Button(this);
        bDl.setText("下载");
        bDl.setTextSize(12);
        bDl.setTextColor(0xFFFFFFFF);
        bDl.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
        bDl.setLayoutParams(new LinearLayout.LayoutParams(dp(88), dp(38)));
        bDl.setOnClickListener(dl);
        row.addView(bDl);
        mDlButtons.put(key, bDl); // 记录引用，供刷新已下载状态

        Button bDel = new Button(this);
        bDel.setText("删除");
        bDel.setTextSize(12);
        bDel.setTextColor(0xFFFFFFFF);
        bDel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336));
        bDel.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(38)));
        bDel.setOnClickListener(del);
        row.addView(bDel);

        return row;
    }

    /** 自定义模型列表（含长按配置） */
    private void refreshCustomList() {
        mCustomList.removeAllViews();
        List<AppConfigStore.Model> customs = new ArrayList<>();
        for (AppConfigStore.Model m : mStore.getModels("asr")) if (!m.builtin) customs.add(m);
        for (AppConfigStore.Model m : mStore.getModels("tts")) if (!m.builtin) customs.add(m);
        for (AppConfigStore.Model m : mStore.getModels("kws")) if (!m.builtin) customs.add(m);

        if (customs.isEmpty()) {
            mCustomList.addView(t("（暂无自定义模型，点击上方导入）", 13, 0xFF999999, false));
            return;
        }
        for (AppConfigStore.Model m : customs) {
            mCustomList.addView(customRow(m));
        }
    }

    /** 自定义模型一行：名称(类型) —— 长按弹配置对话框 */
    private LinearLayout customRow(AppConfigStore.Model m) {
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(m.name + "  (" + typeName(m.type) + ")");
        tv.setTextSize(13);
        tv.setTextColor(0xFF444444);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1));
        row.addView(tv);

        row.setOnClickListener(null);
        row.setOnLongClickListener(v -> {
            showCustomMenu(m);
            return true;
        });
        row.setBackgroundColor(0xFFFAFAFA);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, 2, 0, 2);
        row.setLayoutParams(lp);
        return row;
    }

    /** 长按自定义模型 → 配置对话框（设置类型 / 删除 / 说明） */
    private void showCustomMenu(AppConfigStore.Model m) {
        final String[] types = {"ASR 语音识别", "TTS 文字转语音", "KWS 关键词唤醒"};
        final String[] typeKeys = {"asr", "tts", "kws"};
        new AlertDialog.Builder(this)
                .setTitle("自定义模型：「" + m.name + "」")
                .setMessage("当前类型：" + typeName(m.type) + "\n路径：" + m.path
                        + "\n\n长按可重新设置模型类型，目录会同步移动到 custom/<类型>/ 并更新数据库。")
                .setItems(types, (d, w) -> {
                    if (typeKeys[w].equals(m.type)) {
                        Toast.makeText(this, "已是该类型", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    changeCustomType(m, typeKeys[w]);
                })
                .setNegativeButton("🗑 删除", (d, w) -> deleteCustomModel(m))
                .setNeutralButton("取消", null)
                .show();
    }

    /** 修改自定义模型类型：移动目录 + 更新数据库注册 */
    private void changeCustomType(AppConfigStore.Model m, String newType) {
        new Thread(() -> {
            try {
                File src = new File(m.path);
                if (src.exists()) {
                    File dst = new File(mAudio.getModelDir(), "custom/" + newType + "/" + src.getName());
                    if (!dst.getParentFile().exists()) dst.getParentFile().mkdirs();
                    if (!src.renameTo(dst)) {
                        runOnUiThread(() -> Toast.makeText(this, "移动目录失败", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    // 更新注册（同 path 幂等：新路径插入，旧路径删除）
                    mStore.registerModel(newType, m.name, m.name, dst.getAbsolutePath(), false, m.meta);
                } else {
                    // 目录不存在：仅更新注册
                    mStore.registerModel(newType, m.name, m.name, m.path, false, m.meta);
                }
                mStore.removeModel(m.id);
                runOnUiThread(() -> {
                    refreshAll();
                    Toast.makeText(this, "已设为 " + typeName(newType) + " 并重新注册", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "设置类型失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void deleteCustomModel(AppConfigStore.Model m) {
        new Thread(() -> {
            File dir = new File(m.path);
            if (dir.exists()) deleteRecursive(dir);
            mStore.removeModel(m.id);
            runOnUiThread(() -> {
                refreshAll();
                Toast.makeText(this, "已删除「" + m.name + "」", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // ==================== 导入（必须压缩包） ====================

    private void openImportPicker() {
        startActivityForResult(Intent.createChooser(
                new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),
                "选择模型压缩包"), REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int rc, int result, Intent data) {
        super.onActivityResult(rc, result, data);
        if (rc == REQ_IMPORT && result == RESULT_OK && data != null && data.getData() != null) {
            importCustom(data.getData());
        }
    }

    private void importCustom(Uri uri) {
        String raw = uri.getPath();
        if (raw == null) return;
        String fileName = raw.substring(raw.lastIndexOf('/') + 1).toLowerCase();

        // 1) 必须压缩包
        boolean isArchive = fileName.endsWith(".tar.bz2") || fileName.endsWith(".tbz2")
                || fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")
                || fileName.endsWith(".zip");
        if (!isArchive) {
            Toast.makeText(this, "导入包必须是压缩包（.tar.bz2 / .tar.gz / .zip）", Toast.LENGTH_LONG).show();
            return;
        }

        // 2) 兼容性提醒
        new AlertDialog.Builder(this)
                .setTitle("导入自定义模型")
                .setMessage("将导入：\n" + fileName + "\n\n"
                        + "⚠ 兼容性提醒：\n"
                        + "· 模型须符合 sherpa-onnx 1.13.5 规范\n"
                        + "· ASR：需 tokens.txt + .onnx\n"
                        + "· TTS：需 VITS 模型结构\n"
                        + "· KWS：需 zipformer2 结构（带 metadata）\n"
                        + "类型不符 / 版本不符可能导致加载失败，请自行查阅模型资料。\n\n"
                        + "导入后长按模型条目可重新设置类型。")
                .setPositiveButton("继续导入", (d, w) -> {
                    final String[] types = {"ASR 语音识别", "TTS 文字转语音", "KWS 关键词唤醒"};
                    final String[] typeKeys = {"asr", "tts", "kws"};
                    new AlertDialog.Builder(this).setTitle("导入为哪种类型？")
                            .setItems(types, (d2, idx) -> doImport(uri, fileName, typeKeys[idx]))
                            .setNegativeButton("取消", null).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 解压到 custom/<type>/<name>/ 并注册数据库 */
    private void doImport(Uri uri, String fileName, String type) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.isEmpty()) base = "custom_" + System.currentTimeMillis();
        final String name = base;
        final String typeKey = type;

        new Thread(() -> {
            try {
                File td = new File(mAudio.getModelDir(), "custom/" + typeKey + "/" + name);
                td.mkdirs();
                File tmp = new File(getCacheDir(), "import_" + System.currentTimeMillis());
                InputStream in = getContentResolver().openInputStream(uri);
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                byte[] b = new byte[8192]; int l;
                while ((l = in.read(b)) > 0) out.write(b, 0, l);
                in.close(); out.close();

                boolean ok = mDl.extractArchive(tmp, td);
                tmp.delete();
                if (ok) {
                    mStore.registerModel(typeKey, name, name, td.getAbsolutePath(), false,
                            "自定义导入: " + fileName);
                    runOnUiThread(() -> {
                        refreshAll();
                        Toast.makeText(this, "导入成功：「" + name + "」(" + typeName(typeKey) + ")，长按可重新配置", Toast.LENGTH_LONG).show();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "解压失败，压缩包可能损坏", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ==================== ❓ Help（右上角） ====================

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("❓ 模型帮助")
                .setMessage(
                        "【KWS 其他语种】\n"
                        + "默认唤醒词模型为 zh-en 3M（中英）。其他语种需自行寻找/训练 sherpa-onnx 兼容的 KWS 模型，\n"
                        + "训练好后压缩打包 → 导入自定义模型 → 长按设为 KWS 类型即可加载。\n\n"
                        + "【识别方式】\n"
                        + "· 静音识别：环境静音判断（RMS），静音时不触发唤醒\n"
                        + "· 声纹识别：说话人验证（campplus），先确认是否本人再触发\n\n"
                        + "【唤醒词模型推荐下载】\n"
                        + "sherpa-onnx 官方 kws-models：\n"
                        + "https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models\n"
                        + "推荐：sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20（中英）\n\n"
                        + "【声纹模型推荐】\n"
                        + "sherpa-onnx speaker-recongition-models：\n"
                        + "https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models\n"
                        + "推荐：3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx（中英）\n\n"
                        + "【导入要求】\n"
                        + "必须是压缩包（.tar.bz2/.tar.gz/.zip），且符合 sherpa-onnx 1.13.5 规范，否则加载失败。")
                .setPositiveButton("打开官方下载页", (d, w) -> openBrowser(
                        "https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models"))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 下载 ====================

    private void startDownload(String key, String label) {
        if (mDownloading) { Toast.makeText(this, "已有下载任务", Toast.LENGTH_SHORT).show(); return; }
        mDownloading = true;
        if (key.startsWith("asr_")) mDl.downloadAsrModel(key.substring(4));
        else if (key.startsWith("tts_")) mDl.downloadTtsModel(key.substring(4));
        else if ("kws_mobile".equals(key)) mDl.downloadKwsModel("kws_mobile");
        else if ("speaker".equals(key)) mDl.downloadSpeakerModel();
        else if ("vad".equals(key)) mDl.downloadVadModel();
        mDownloading = false;
    }

    private void deleteModelDir(File dir, String label) {
        if (dir == null || !dir.exists()) { Toast.makeText(this, label + " 未下载", Toast.LENGTH_SHORT).show(); return; }
        deleteRecursive(dir);
        refreshAll();
        Toast.makeText(this, "已删除 " + label, Toast.LENGTH_SHORT).show();
    }

    // ==================== 刷新 ====================

    private void refreshAll() {
        refreshDefaultConfig();
        refreshCustomList();
        refreshDownloadStates();
    }

    /** key → 模型目录/文件（读取目录判定是否已下载） */
    private File dirFor(String key) {
        if (key.startsWith("asr_")) return mAudio.getAsrModelDir(key.substring(4));
        if (key.startsWith("tts_")) return mAudio.getTtsModelDir(key.substring(4));
        if ("kws".equals(key)) return mAudio.getKwsModelDir();
        if ("speaker".equals(key)) return mAudio.getSpeakerModelDir();
        if ("vad".equals(key)) return mAudio.getVadModelFile();
        return null;
    }

    /** 读取目录：已下载 → 绿色「✅ 已下载」禁用下载；未下载 → 显示「下载」 */
    private void refreshDownloadStates() {
        for (java.util.Map.Entry<String, Button> e : mDlButtons.entrySet()) {
            File dir = dirFor(e.getKey());
            boolean ok = dir != null && dir.exists();
            Button b = e.getValue();
            if (ok) {
                b.setText("✅ 已下载");
                b.setEnabled(false);
                b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF9E9E9E));
            } else {
                b.setText("下载");
                b.setEnabled(true);
                b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
            }
        }
    }

    /** 展示测试界面保存的默认配置（只读） */
    private void refreshDefaultConfig() {
        String asr = mStore.getConfig("asr_model", "-");
        String tts = mStore.getConfig("tts_model", "-");
        String kws = mStore.getConfig("kws_keyword", "-");
        tvDefaultConfig.setText("ASR: " + asr + "  |  TTS: " + tts + "  |  KWS: " + kws);
    }

    // ==================== DownloadListener ====================

    @Override public void onDownloadStarted(String n) {
        runOnUiThread(() -> showProgress(n));
    }
    @Override public void onDownloadProgress(String n, int p, long d, long t) {
        runOnUiThread(() -> { if (mProgressDialog != null) mProgressText.setText(n + ": " + p + "% (" + fmtSize(d) + ")"); });
    }
    @Override public void onDownloadSuccess(String n, File dir) {
        runOnUiThread(() -> {
            dismissProgress();
            // 下载完成 → 扫描注册到数据库（绑定路径）
            mStore.scanAndBuild(mAudio.getModelDir());
            refreshAll();
            Toast.makeText(this, "下载完成，已绑定到数据库", Toast.LENGTH_SHORT).show();
        });
    }
    @Override public void onDownloadFailed(String n, String e) {
        runOnUiThread(() -> { dismissProgress(); Toast.makeText(this, "下载失败: " + e, Toast.LENGTH_LONG).show(); });
    }

    private void showProgress(String name) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); l.setPadding(40, 20, 40, 20);
        mProgressText = new TextView(this);
        mProgressText.setTextSize(14);
        mProgressText.setText("下载中: " + name + "...");
        l.addView(mProgressText);
        mProgressDialog = new AlertDialog.Builder(this).setTitle("模型下载").setView(l).setCancelable(false).create();
        mProgressDialog.show();
    }

    private void dismissProgress() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) mProgressDialog.dismiss();
    }

    // ==================== 工具 ====================

    private String typeName(String type) {
        if ("asr".equals(type)) return "ASR 语音识别";
        if ("tts".equals(type)) return "TTS 文字转语音";
        if ("kws".equals(type)) return "KWS 关键词唤醒";
        return type;
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        f.delete();
    }

    private long dirSizeRaw(File d) {
        long s = 0;
        if (d != null && d.isDirectory()) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) s += f.isDirectory() ? dirSizeRaw(f) : f.length();
        }
        return s;
    }

    private String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024.0));
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

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
}
