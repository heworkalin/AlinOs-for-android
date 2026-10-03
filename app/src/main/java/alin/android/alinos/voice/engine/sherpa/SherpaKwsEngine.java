package alin.android.alinos.voice.engine.sherpa;

import android.content.Context;
import alin.android.alinos.log.AlinLog;

import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import alin.android.alinos.voice.engine.IKwsEngine;

/**
 * sherpa‑onnx KWS 唤醒引擎（KeywordSpotter，zipformer2 模型）。
 *
 * 兼容模型：sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20（带 metadata，支持中英文唤醒词）。
 * 模型包 tar 解压后带一层子目录，自动向下查找包含 tokens.txt 的目录；
 * encoder/decoder/joiner 文件名带日期/epoch 后缀，用前缀通配查找，并保证 chunk/int8 组合一致。
 *
 * 唤醒词经 KeywordTokenizer 转换为模型 token（phone+ppinyin），写入临时 keywords.txt，
 * 格式："x iǎo ài t óng x ué :1.0 #0.7 @小爱同学"。
 * 转换/校验失败时拒绝初始化并回调 onError，绝不把非法数据喂给 native 层。
 */
public class SherpaKwsEngine implements IKwsEngine {

    private static final String TAG = "SherpaKwsEngine";

    private final Context mContext;
    private final File mKeywordsFile;

    private KeywordSpotter mSpotter;
    private OnlineStream mStream;          // 常驻流式检测（start/feedPcm）
    private boolean mReady = false;
    private boolean mStarted = false;
    private String mKeyword = "";
    private float mThreshold = 0.25f;   // KWS 触发阈值（越低越易唤醒，官方默认 0.25）
    private KeywordTokenizer mTokenizer;

    public SherpaKwsEngine(Context context) {
        mContext = context.getApplicationContext();
        mKeywordsFile = new File(mContext.getCacheDir(), "kws_keywords.txt");
        try {
            System.loadLibrary("sherpa-onnx-jni");
        } catch (UnsatisfiedLinkError e) {
            AlinLog.e(TAG, "JNI库加载失败", e);
        }
    }

    @Override
    public void init(File modelDir, String keyword, float threshold, Callback cb) {
        mReady = false;
        mStarted = false;
        mKeyword = keyword == null ? "" : keyword;
        mThreshold = threshold;

        new Thread(() -> {
            try {
                // 重复 init 时先释放旧实例
                if (mSpotter != null) {
                    mSpotter.release();
                    mSpotter = null;
                }
                File dir = findModelDir(modelDir);
                if (dir == null) {
                    throw new Exception("未找到KWS模型文件（缺少 tokens.txt）：" + modelDir);
                }
                AlinLog.d(TAG, "使用模型目录: " + dir);

                // 唤醒词 → tokens（完整复刻 sherpa text2token，校验不过则拒绝初始化，绝不喂给 native）
                mTokenizer = new KeywordTokenizer(mContext);
                mTokenizer.load(dir);
                String keywordLine = mTokenizer.tokenize(mKeyword, 1.5f, mThreshold);
                writeKeywordsFile(keywordLine);
                AlinLog.d(TAG, "keywords: " + keywordLine);

                OnlineModelConfig modelConfig = new OnlineModelConfig();
                modelConfig.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
                modelConfig.setNumThreads(2);
                modelConfig.setModelType("zipformer2");

                OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
                transducer.setEncoder(findModelFile(dir, "encoder", true));
                transducer.setDecoder(findModelFile(dir, "decoder", false));
                transducer.setJoiner(findModelFile(dir, "joiner", true));
                modelConfig.setTransducer(transducer);

                KeywordSpotterConfig config = new KeywordSpotterConfig();
                config.setModelConfig(modelConfig);
                config.setKeywordsFile(mKeywordsFile.getAbsolutePath());
                config.setKeywordsScore(1.5f);
                config.setKeywordsThreshold(mThreshold);

                mSpotter = new KeywordSpotter(null, config);
                mReady = true;
                AlinLog.d(TAG, "KWS初始化完成, keyword=" + mKeyword + ", threshold=" + mThreshold);
                cb.onListening();
            } catch (Exception e) {
                AlinLog.e(TAG, "KWS初始化失败", e);
                cb.onError(e.getMessage());
            }
        }).start();
    }

    @Override
    public void detectPcm(byte[] pcm, Callback cb) {
        if (!mReady || mSpotter == null) {
            cb.onError("KWS引擎尚未就绪");
            return;
        }
        new Thread(() -> {
            OnlineStream stream = null;
            try {
                stream = mSpotter.createStream("");

                float[] samples = bytesToFloat(pcm);
                stream.acceptWaveform(samples, 16000);

                // 尾部补 0.66s 静音，让流式模型收尾
                float[] tail = new float[(int) (0.66f * 16000)];
                stream.acceptWaveform(tail, 16000);
                stream.inputFinished(); // 标记输入结束，驱动流式解码收尾

                // 循环解码，每次解码后立即取结果（与官方示例一致），命中后 reset 继续
                String hitKeyword = "";
                int guard = 0;
                while (mSpotter.isReady(stream) && guard++ < 1000) {
                    mSpotter.decode(stream);
                    KeywordSpotterResult r = mSpotter.getResult(stream);
                    String kw = r.getKeyword();
                    if (kw != null && !kw.isEmpty()) {
                        hitKeyword = kw;
                        mSpotter.reset(stream);
                    }
                }

                boolean hit = !hitKeyword.isEmpty();
                AlinLog.d(TAG, "检测结果: hit=" + hit + ", keyword=" + hitKeyword);
                cb.onDetected(hit ? hitKeyword : "", hit ? 1.0f : 0.0f);
            } catch (Exception e) {
                AlinLog.e(TAG, "检测异常", e);
                cb.onError(e.getMessage());
            } finally {
                if (stream != null) {
                    stream.release();
                }
            }
        }).start();
    }

    @Override
    public void start(Callback cb) {
        if (!mReady || mSpotter == null) {
            cb.onError("KWS引擎尚未就绪");
            return;
        }
        if (mStarted) {
            cb.onListening();
            return;
        }
        mStream = mSpotter.createStream("");
        mStarted = true;
        cb.onListening();
    }

    @Override
    public void feedPcm(byte[] pcm) {
        if (!mStarted || mSpotter == null || mStream == null) return;
        try {
            float[] samples = bytesToFloat(pcm);
            mStream.acceptWaveform(samples, 16000);
            mSpotter.decode(mStream);
            KeywordSpotterResult r = mSpotter.getResult(mStream);
            String kw = r.getKeyword();
            if (kw != null && !kw.isEmpty()) {
                AlinLog.d(TAG, "流式命中: " + kw);
                if (mCallback != null) {
                    final String hit = kw;
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(() -> mCallback.onDetected(hit, 1.0f));
                }
                mSpotter.reset(mStream);
            }
        } catch (Exception e) {
            AlinLog.e(TAG, "流式检测异常", e);
        }
    }

    private Callback mCallback;

    /** 供常驻监听场景注入回调（与 start(cb) 配合使用） */
    public void setStreamingCallback(Callback cb) {
        mCallback = cb;
    }

    @Override
    public void stop() {
        mStarted = false;
        if (mStream != null) {
            mStream.release();
            mStream = null;
        }
    }

    @Override
    public void release() {
        stop();
        mReady = false;
        if (mSpotter != null) {
            mSpotter.release();
            mSpotter = null;
        }
    }

    @Override
    public boolean isReady() {
        return mReady;
    }

    @Override
    public String getName() {
        return "sherpa‑onnx KWS";
    }

    // ==================== 工具 ====================

    /** 把已转换好的 keywords 行写入临时 keywords.txt（每行含 :score #threshold @原文） */
    private void writeKeywordsFile(String keywordLine) throws Exception {
        FileOutputStream fos = new FileOutputStream(mKeywordsFile);
        fos.write((keywordLine + "\n").getBytes(StandardCharsets.UTF_8));
        fos.close();
    }

    /**
     * 在 modelDir 及其子目录（最多两层）中查找包含 tokens.txt 的模型目录。
     * 多候选时：优先选含 en.phone 的（zh-en 新模型，与 sherpa-onnx 1.13.5 兼容），
     * 其次选最新修改的目录，避免选中旧格式模型导致 native 崩溃。
     */
    private File findModelDir(File root) {
        if (root == null || !root.exists() || !root.isDirectory()) return null;
        java.util.List<File> candidates = new java.util.ArrayList<>();
        if (new File(root, "tokens.txt").exists()) candidates.add(root);
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                if (new File(d, "tokens.txt").exists()) candidates.add(d);
                File[] sub = d.listFiles(File::isDirectory);
                if (sub != null) {
                    for (File s : sub) {
                        if (new File(s, "tokens.txt").exists()) candidates.add(s);
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> {
            boolean ea = new File(a, "en.phone").exists();
            boolean eb = new File(b, "en.phone").exists();
            if (ea != eb) return ea ? -1 : 1;
            return Long.compare(b.lastModified(), a.lastModified());
        });
        return candidates.get(0);
    }

    /**
     * 选择 KWS 模型文件：优先 chunk-16 组合，encoder/joiner 优先 int8，decoder 用 fp32。
     * 新模型包（kws-zipformer-zh-en-3M-2025-12-20）同时含 chunk-8/chunk-16 与 int8/fp32，
     * 必须保证 encoder/decoder/joiner 的 chunk 一致，否则原生层会报错/崩溃。
     */
    private String findModelFile(File dir, String prefix, boolean preferInt8) {
        File[] files = dir.listFiles((d, name) ->
                name.startsWith(prefix + "-") && name.endsWith(".onnx"));
        if (files == null || files.length == 0) return "";

        java.util.Arrays.sort(files, (a, b) -> {
            String na = a.getName(), nb = b.getName();
            // 1) chunk-16 优先于 chunk-8
            boolean a16 = na.contains("chunk-16"), b16 = nb.contains("chunk-16");
            if (a16 != b16) return a16 ? -1 : 1;
            // 2) int8 优先（仅 encoder/joiner 需要）
            boolean ai = na.contains(".int8.onnx"), bi = nb.contains(".int8.onnx");
            if (preferInt8 && ai != bi) return ai ? -1 : 1;
            if (!preferInt8 && ai != bi) return ai ? 1 : -1; // decoder 优先 fp32
            return na.compareTo(nb);
        });
        return files[0].getAbsolutePath();
    }

    /** 按前缀通配查找第一个 .onnx 文件（KWS 模型文件名带日期/epoch 后缀） */
    private String findByPrefix(File dir, String prefix) {
        File[] files = dir.listFiles((d, name) ->
                name.startsWith(prefix) && name.endsWith(".onnx"));
        if (files != null && files.length > 0) {
            return files[0].getAbsolutePath();
        }
        return "";
    }

    /** 16‑bit little‑endian PCM byte[] → float[]，范围 [-1.0 ~ 1.0] */
    private float[] bytesToFloat(byte[] bytes) {
        int sampleCount = bytes.length / 2;
        float[] out = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            short val = (short) (((bytes[i * 2 + 1] & 0xFF) << 8) | (bytes[i * 2] & 0xFF));
            out[i] = val / 32768.0f;
        }
        return out;
    }
}
