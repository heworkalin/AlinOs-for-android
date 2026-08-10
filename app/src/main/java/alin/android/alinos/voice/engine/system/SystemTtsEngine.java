package alin.android.alinos.voice.engine.system;

import android.content.Context;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import alin.android.alinos.voice.engine.ITtsEngine;

/**
 * Android 系统 TTS 引擎。
 * 参照 TextToSpeechActivity 的完整边界处理：
 * - 引擎可用性检测（默认引擎 → 已安装引擎列表 → 无引擎引导）
 * - 语言自动检测（中文/英文/混合）
 * - 语言包缺失回退
 * - QUEUE_FLUSH 避免队列堆积
 * - 暂停/停止/释放完整生命周期
 */
public class SystemTtsEngine implements ITtsEngine {

    private static final String TAG = "SystemTts";

    private TextToSpeech mTts;
    private boolean mReady = false;
    private final Context mCtx;
    private Callback mCurrentCallback;

    private static final float DEFAULT_SPEECH_RATE = 1.0f;
    private static final float DEFAULT_PITCH = 1.0f;
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]");

    public SystemTtsEngine(Context ctx) {
        this.mCtx = ctx.getApplicationContext();
    }

    @Override
    public void init(File modelDir, Callback cb) {
        Log.d(TAG, "检查系统 TTS 引擎...");

        // 先检测是否有可用引擎
        String defaultEngine = null;
        List<TextToSpeech.EngineInfo> engineList = null;
        try {
            TextToSpeech probe = new TextToSpeech(mCtx, null);
            defaultEngine = probe.getDefaultEngine();
            if (defaultEngine == null || defaultEngine.isEmpty()) {
                engineList = probe.getEngines();
            }
            probe.shutdown();
        } catch (Exception e) {
            Log.e(TAG, "检测 TTS 引擎失败", e);
            cb.onError("无法检测 TTS 引擎");
            return;
        }

        if (defaultEngine == null && (engineList == null || engineList.isEmpty())) {
            cb.onError("NO_ENGINE");
            return;
        }

        // 初始化
        mTts = new TextToSpeech(mCtx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                mTts.setSpeechRate(DEFAULT_SPEECH_RATE);
                mTts.setPitch(DEFAULT_PITCH);

                int langResult = mTts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if (langResult == TextToSpeech.LANG_MISSING_DATA
                        || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "中文不支持，切英文");
                    mTts.setLanguage(Locale.ENGLISH);
                }

                mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {
                        Log.d(TAG, "朗读开始: " + id);
                    }

                    @Override public void onDone(String id) {
                        Log.d(TAG, "朗读完成: " + id);
                        if (mCurrentCallback != null) mCurrentCallback.onAudio(new byte[0]);
                        mCurrentCallback = null;
                    }

                    @Override public void onError(String id) {
                        Log.e(TAG, "朗读错误: " + id);
                        if (mCurrentCallback != null) mCurrentCallback.onError("朗读异常");
                        mCurrentCallback = null;
                    }
                });

                mReady = true;
                Log.d(TAG, "系统 TTS 就绪");
                cb.onAudio(null); // 信号：就绪
            } else {
                Log.e(TAG, "TTS 初始化失败, status=" + status);
                cb.onError("TTS 初始化失败，code=" + status);
            }
        });
    }

    @Override
    public void synthesize(String text, float speed, Callback cb) {
        if (!mReady || mTts == null) {
            cb.onError("引擎未初始化");
            return;
        }

        if (text == null || text.trim().isEmpty()) {
            cb.onError("文本为空");
            return;
        }

        mCurrentCallback = cb;
        mTts.setSpeechRate(speed);

        // 自动检测语言
        Locale locale = CHINESE_PATTERN.matcher(text).find()
                ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;

        int langResult = mTts.setLanguage(locale);
        if (langResult == TextToSpeech.LANG_MISSING_DATA) {
            cb.onError(locale == Locale.SIMPLIFIED_CHINESE ? "缺少中文语音数据" : "缺少英文语音数据");
            mCurrentCallback = null;
            return;
        } else if (langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 回退到另一种语言
            Locale fallback = locale == Locale.SIMPLIFIED_CHINESE ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
            mTts.setLanguage(fallback);
        }

        int result = mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "tts_" + System.currentTimeMillis());
        if (result != TextToSpeech.SUCCESS) {
            cb.onError("朗读请求失败");
            mCurrentCallback = null;
        }
    }

    /** 停止当前朗读 */
    public void stop() {
        if (mTts != null) mTts.stop();
        mCurrentCallback = null;
    }

    @Override
    public void release() {
        mReady = false;
        mCurrentCallback = null;
        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
            mTts = null;
        }
        Log.d(TAG, "系统 TTS 已释放");
    }

    @Override public boolean isReady() { return mReady; }

    @Override public String getName() { return "Android 系统 TTS"; }

    /** 打开系统 TTS 设置/安装页面（供 UI 层调用） */
    public static void openTtsSettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                ctx.startActivity(new Intent("android.settings.TEXT_TO_SPEECH_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                            .setData(android.net.Uri.parse("market://details?id=com.google.android.tts"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e3) {
                    Log.e(TAG, "无法打开 TTS 设置");
                }
            }
        }
    }
}
