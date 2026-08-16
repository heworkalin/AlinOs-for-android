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
 *
 * 参照 TtsTestActivity（新版 TTS 测试页）对系统 TTS 的完整边界处理 + 增强安全性冗余：
 * - 引擎可用性检测：默认引擎 → 已安装引擎列表 → 无引擎回调错误（UI 层可用 openTtsSettings 引导安装）
 * - 重复 init 防护：先释放旧实例，避免泄漏
 * - 初始化状态码判定（SUCCESS / 失败）
 * - 中文语音包缺失/不支持 → 自动回退英文
 * - synthesize 全流程 try/catch + null 校验 + speed 参数钳制
 * - 动态语言识别（含中文 → 中文，纯英文 → 英文），语言包缺失明确报错
 * - QUEUE_FLUSH 清空队列避免堆积；speak 返回值检查
 * - UtteranceProgressListener 完整生命周期回调（开始/完成/错误）
 * - 回调清理：onDone/onError 后置空 mCurrentCallback，避免悬挂引用
 * - release 完整释放（stop + shutdown + 置 null）
 */
public class SystemTtsEngine implements ITtsEngine {

    private static final String TAG = "SystemTts";

    private TextToSpeech mTts;
    private boolean mReady = false;
    private final Context mCtx;
    private Callback mCurrentCallback;

    private static final float DEFAULT_SPEECH_RATE = 1.0f;
    private static final float DEFAULT_PITCH = 1.0f;
    private static final float MIN_SPEED = 0.5f;
    private static final float MAX_SPEED = 2.0f;
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]");

    public SystemTtsEngine(Context ctx) {
        this.mCtx = ctx.getApplicationContext();
    }

    @Override
    public void init(File modelDir, Callback cb) {
        Log.d(TAG, "检查系统 TTS 引擎...");

        // 重复 init 防护：先释放旧实例
        releaseInternal();

        // 第一步：引擎可用性检测（默认引擎 → 已安装引擎列表 → 无引擎）
        String defaultEngine = null;
        List<TextToSpeech.EngineInfo> engineList = null;
        try {
            TextToSpeech probe = new TextToSpeech(mCtx, null);
            try {
                defaultEngine = probe.getDefaultEngine();
                if (defaultEngine == null || defaultEngine.isEmpty()) {
                    engineList = probe.getEngines();
                }
            } finally {
                probe.shutdown();
            }
        } catch (Exception e) {
            Log.e(TAG, "检测 TTS 引擎失败", e);
            cb.onError("无法检测 TTS 引擎");
            return;
        }

        if ((defaultEngine == null || defaultEngine.isEmpty())
                && (engineList == null || engineList.isEmpty())) {
            Log.w(TAG, "未检测到任何 TTS 引擎");
            cb.onError("NO_ENGINE");
            return;
        }
        if (defaultEngine != null && !defaultEngine.isEmpty()) {
            Log.d(TAG, "✅ 使用默认 TTS 引擎: " + defaultEngine);
        } else {
            Log.d(TAG, "✅ 无默认引擎，使用已安装引擎（共 " + engineList.size() + " 个）");
        }

        // 第二步：初始化 + 自动配置
        try {
            mTts = new TextToSpeech(mCtx, status -> {
                if (status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS 初始化失败, status=" + status);
                    mReady = false;
                    if (mTts != null) { mTts.shutdown(); mTts = null; }
                    cb.onError("TTS 初始化失败，code=" + status);
                    return;
                }

                Log.d(TAG, "✅ TTS 初始化成功，应用默认配置");
                mTts.setSpeechRate(DEFAULT_SPEECH_RATE);
                mTts.setPitch(DEFAULT_PITCH);

                // 默认简体中文；缺失/不支持则回退英文
                int langResult = mTts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if (langResult == TextToSpeech.LANG_MISSING_DATA
                        || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "中文语音包缺失/不支持，自动回退英文");
                    mTts.setLanguage(Locale.ENGLISH);
                }

                // 进度监听：开始/完成/错误（回调可能来自 binder 线程，内部自行切换主线程）
                mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {
                        Log.d(TAG, "🔊 朗读开始: " + id);
                    }

                    @Override public void onDone(String id) {
                        Log.d(TAG, "✅ 朗读完成: " + id);
                        final Callback cb1 = mCurrentCallback;
                        mCurrentCallback = null;
                        if (cb1 != null) {
                            // 完成信号：空字节数组表示"播放结束"（与 onAudio(null) 的就绪信号区分）
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .post(() -> cb1.onAudio(new byte[0]));
                        }
                    }

                    @Override public void onError(String id) {
                        Log.e(TAG, "❌ 朗读错误: " + id);
                        final Callback cb1 = mCurrentCallback;
                        mCurrentCallback = null;
                        if (cb1 != null) {
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .post(() -> cb1.onError("朗读异常: " + id));
                        }
                    }
                });

                mReady = true;
                Log.d(TAG, "系统 TTS 就绪");
                cb.onAudio(null); // 就绪信号（与项目内其他 TTS 引擎一致）
            });
        } catch (Exception e) {
            Log.e(TAG, "创建 TTS 实例失败", e);
            mReady = false;
            cb.onError("创建 TTS 实例失败: " + e.getMessage());
        }
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

        try {
            // 若有未完成的朗读回调，先清理（避免回调悬挂）
            mCurrentCallback = cb;

            // 语速钳制在合法范围
            float clamped = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
            mTts.setSpeechRate(clamped);

            // 动态识别语言：含中文 → 中文，纯英文 → 英文
            Locale locale = CHINESE_PATTERN.matcher(text).find()
                    ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;

            int langResult = mTts.setLanguage(locale);
            if (langResult == TextToSpeech.LANG_MISSING_DATA) {
                String tip = locale == Locale.SIMPLIFIED_CHINESE ? "中文" : "英文";
                mCurrentCallback = null;
                cb.onError("缺少" + tip + "语音数据，请到系统设置下载语音包");
                return;
            } else if (langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 回退另一种语言
                Locale fallback = locale == Locale.SIMPLIFIED_CHINESE
                        ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
                Log.w(TAG, locale.getDisplayName() + " 不支持，回退 " + fallback.getDisplayName());
                mTts.setLanguage(fallback);
            }

            // QUEUE_FLUSH：清空旧队列，立即朗读
            int result = mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                    "tts_" + System.currentTimeMillis());
            if (result != TextToSpeech.SUCCESS) {
                mCurrentCallback = null;
                cb.onError("朗读请求失败，code=" + result);
            }
        } catch (Exception e) {
            Log.e(TAG, "朗读异常", e);
            mCurrentCallback = null;
            cb.onError("朗读异常: " + e.getMessage());
        }
    }

    /** 停止当前朗读 */
    public void stop() {
        if (mTts != null) {
            try {
                mTts.stop();
            } catch (Exception e) {
                Log.w(TAG, "stop 异常", e);
            }
        }
        mCurrentCallback = null;
    }

    @Override
    public void release() {
        releaseInternal();
        Log.d(TAG, "系统 TTS 已释放");
    }

    /** 释放内部资源（init 重复调用与 release 共用） */
    private void releaseInternal() {
        mReady = false;
        mCurrentCallback = null;
        if (mTts != null) {
            try {
                mTts.stop();
                mTts.shutdown();
            } catch (Exception e) {
                Log.w(TAG, "释放 TTS 异常", e);
            }
            mTts = null;
        }
    }

    @Override public boolean isReady() { return mReady; }

    @Override public String getName() { return "Android 系统 TTS"; }

    /**
     * 打开系统 TTS 设置/安装页面（三级回退：安装页 → 设置 → 应用市场），供 UI 层调用。
     */
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
