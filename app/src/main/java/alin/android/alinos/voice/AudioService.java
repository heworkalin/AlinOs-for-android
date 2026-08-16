package alin.android.alinos.voice;

import android.content.Context;
import android.util.Log;

import java.io.File;

import alin.android.alinos.voice.engine.IAsrEngine;
import alin.android.alinos.voice.engine.IKwsEngine;
import alin.android.alinos.voice.engine.ITtsEngine;
import alin.android.alinos.voice.engine.sherpa.SherpaAsrEngine;
import alin.android.alinos.voice.engine.sherpa.SherpaKwsEngine;
import alin.android.alinos.voice.engine.sherpa.SherpaTtsEngine;
import alin.android.alinos.voice.engine.system.SystemTtsEngine;
import alin.android.alinos.voice.engine.vosk.VoskAsrEngine;
import alin.android.alinos.voice.core.ModelResolver;

/**
 * 音频服务统一入口（单例）。
 * 负责管理所有引擎实例、模型路径、VAD 模型。
 */
public class AudioService {

    private static final String TAG = "AudioService";
    private static AudioService instance;

    private final Context appContext;

    // 配置/模型注册库
    private AppConfigStore mConfigStore;

    // 引擎实例（懒加载）
    private IAsrEngine mAsrEngine;
    private ITtsEngine mTtsEngine;
    private IKwsEngine mKwsEngine;

    // VAD 模型路径
    private File mVadModelFile;

    private AudioService(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        Log.d(TAG, "AudioService 初始化");
        // VAD 模型复制到后台执行
        new Thread(this::copyVadModelIfNeeded).start();
        // 扫描已下载模型 → 自动建立模型注册库（幂等）
        mConfigStore = AppConfigStore.getInstance(appContext);
        new Thread(() -> mConfigStore.scanAndBuild(getModelDir())).start();
    }

    /** 全局配置 + 模型注册库 */
    public AppConfigStore getConfigStore() {
        if (mConfigStore == null) mConfigStore = AppConfigStore.getInstance(appContext);
        return mConfigStore;
    }

    public static synchronized AudioService getInstance(Context ctx) {
        if (instance == null) instance = new AudioService(ctx);
        return instance;
    }

    // ==================== 引擎获取 ====================

    /** 获取 ASR 引擎 */
    public IAsrEngine getAsrEngine(String type) {
        if ("vosk".equals(type)) return new VoskAsrEngine();
        if (mAsrEngine == null) mAsrEngine = new SherpaAsrEngine();
        return mAsrEngine;
    }

    /** 获取 TTS 引擎 */
    public ITtsEngine getTtsEngine() {
        if (mTtsEngine == null) {
            mTtsEngine = new SherpaTtsEngine();
        }
        return mTtsEngine;
    }

    /** 获取 KWS 引擎 */
    public IKwsEngine getKwsEngine() {
        if (mKwsEngine == null) mKwsEngine = new SherpaKwsEngine(appContext);
        return mKwsEngine;
    }

    // ==================== 统一对外接口（自动读配置 → 加载 → 执行） ====================

    /**
     * ASR 统一接口：自动读取 configs 中保存的引擎/模型配置，自动加载引擎后识别 PCM。
     * 服务层可直接调用，无需关心模型路径与引擎初始化。
     */
    public void asrRecognizePcm(byte[] pcm, IAsrEngine.Callback cb) {
        String engine = getConfigStore().getConfig("asr_engine", "sherpa");
        IAsrEngine eng = getAsrEngine(engine);
        if (eng.isReady()) {
            eng.recognize(pcm, cb);
            return;
        }
        File dir = ModelResolver.resolveAsr(appContext, null);
        if (!dir.exists() || !dir.isDirectory()) {
            cb.onError("ASR 模型未配置，请到音频管理界面下载/导入");
            return;
        }
        eng.init(dir, new IAsrEngine.Callback() {
            @Override public void onResult(String s) {
                if (eng.isReady()) eng.recognize(pcm, cb);
                else cb.onError("ASR 加载未就绪");
            }
            @Override public void onError(String e) { cb.onError(e); }
        });
    }

    /**
     * TTS 统一接口：自动读取 configs 中保存的引擎/模型/语速配置，自动加载后合成语音。
     */
    public void ttsSynthesize(String text, float speed, ITtsEngine.Callback cb) {
        String engine = getConfigStore().getConfig("tts_engine", "system");
        ITtsEngine eng = "system".equals(engine) ? new SystemTtsEngine(appContext) : getTtsEngine();
        if (eng.isReady()) {
            eng.synthesize(text, speed, cb);
            return;
        }
        File dir = ModelResolver.resolveTts(appContext, null);
        eng.init(dir, new ITtsEngine.Callback() {
            @Override public void onAudio(byte[] wav) {
                if (eng.isReady()) eng.synthesize(text, speed, cb);
                else cb.onError("TTS 加载未就绪");
            }
            @Override public void onError(String e) { cb.onError(e); }
        });
    }

    /**
     * KWS 统一接口：自动读取 configs 中保存的唤醒词/阈值/模型配置，自动加载后检测唤醒词。
     */
    public void kwsDetectPcm(byte[] pcm, IKwsEngine.Callback cb) {
        String keyword = getConfigStore().getConfig("kws_keyword", "");
        float threshold = 0.25f;
        try {
            threshold = Float.parseFloat(getConfigStore().getConfig("kws_threshold", "0.25"));
        } catch (NumberFormatException ignored) {}

        IKwsEngine eng = getKwsEngine();
        if (eng.isReady()) {
            eng.detectPcm(pcm, cb);
            return;
        }
        if (keyword.isEmpty()) {
            cb.onError("KWS 未配置唤醒词，请先在 KWS 测试界面保存唤醒词");
            return;
        }
        File dir = ModelResolver.resolveKws(appContext);
        if (!dir.exists() || !dir.isDirectory()) {
            cb.onError("KWS 模型未配置，请到音频管理界面下载/导入");
            return;
        }
        eng.init(dir, keyword, threshold, new IKwsEngine.Callback() {
            @Override public void onDetected(String kw, float conf) { cb.onDetected(kw, conf); }
            @Override public void onListening() { eng.detectPcm(pcm, cb); }
            @Override public void onError(String e) { cb.onError(e); }
        });
    }

    // ==================== 模型路径 ====================

    /** 模型根目录：files/voice_models/ */
    public File getModelDir() {
        File dir = new File(appContext.getFilesDir(), "voice_models");
        dir.mkdirs();
        return dir;
    }

    /** ASR 模型目录 */
    public File getAsrModelDir(String modelKey) {
        return new File(getModelDir(), "asr/" + modelKey);
    }

    /** KWS 模型目录 */
    public File getKwsModelDir() {
        return new File(getModelDir(), "kws");
    }

    /** TTS 模型目录 */
    public File getTtsModelDir(String modelKey) {
        return new File(getModelDir(), "tts/" + modelKey);
    }

    /** 默认 TTS 模型目录（兼容） */
    public File getTtsModelDir() {
        return new File(getModelDir(), "tts");
    }

    /** 声纹模型目录 */
    public File getSpeakerModelDir() {
        return new File(getModelDir(), "speaker");
    }

    /** VAD 模型文件 */
    public File getVadModelFile() {
        return mVadModelFile;
    }

    // ==================== 模型状态 ====================

    public boolean isAsrInstalled(String modelKey) {
        File dir = getAsrModelDir(modelKey);
        return dir.exists() && dir.isDirectory() && dir.list() != null && dir.list().length > 0;
    }

    public boolean isKwsInstalled() {
        File dir = getKwsModelDir();
        return dir.exists() && dir.isDirectory() && dir.list() != null && dir.list().length > 0;
    }

    public boolean isTtsInstalled(String modelKey) {
        File dir = getTtsModelDir(modelKey);
        return dir.exists() && dir.isDirectory() && dir.list() != null && dir.list().length > 0;
    }

    /** Vosk 模型目录 */
    public File getVoskModelDir() {
        File dir = new File(getModelDir(), "vosk/vosk-model-small-cn-0.22");
        if (!dir.exists() || !dir.isDirectory() || dir.list() == null || dir.list().length < 5) {
            copyVoskFromAssets(dir);
        }
        return dir;
    }

    private void copyVoskFromAssets(File target) {
        target.getParentFile().mkdirs();
        try {
            copyAssetDir("vosk-model-small-cn-0.22", target);
            Log.d(TAG, "Vosk 模型已从 assets 复制到 " + target);
        } catch (Exception e) {
            Log.e(TAG, "Vosk 模型复制失败", e);
        }
    }

    private void copyAssetDir(String assetPath, File target) throws Exception {
        String[] files = appContext.getAssets().list(assetPath);
        if (files == null || files.length == 0) {
            // 是文件而非目录
            java.io.InputStream in = appContext.getAssets().open(assetPath);
            java.io.FileOutputStream out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[8192]; int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            in.close(); out.close();
            return;
        }
        target.mkdirs();
        for (String f : files) {
            copyAssetDir(assetPath + "/" + f, new File(target, f));
        }
    }

    public boolean isVoskInstalled() {
        File dir = new File(getModelDir(), "vosk/vosk-model-small-cn-0.22");
        return dir.exists() && dir.isDirectory();
    }

    // ==================== VAD 模型 ====================

    private void copyVadModelIfNeeded() {
        mVadModelFile = new File(getModelDir(), "vad/silero_vad.onnx");
        if (mVadModelFile.exists()) return;

        mVadModelFile.getParentFile().mkdirs();
        try {
            java.io.InputStream in = appContext.getAssets().open("silero_vad.onnx");
            java.io.FileOutputStream out = new java.io.FileOutputStream(mVadModelFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            in.close();
            out.close();
            Log.d(TAG, "VAD 模型已从 assets 复制到 " + mVadModelFile);
        } catch (Exception e) {
            Log.e(TAG, "VAD 模型复制失败", e);
        }
    }

    // ==================== 释放 ====================

    public void releaseAll() {
        if (mAsrEngine != null) { mAsrEngine.release(); mAsrEngine = null; }
        if (mTtsEngine != null) { mTtsEngine.release(); mTtsEngine = null; }
        if (mKwsEngine != null) { mKwsEngine.release(); mKwsEngine = null; }
    }
}
