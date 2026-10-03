package alin.android.alinos.voice.engine.sherpa;

import alin.android.alinos.log.AlinLog;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.io.File;

import alin.android.alinos.voice.engine.IAsrEngine;

/**
 * sherpa‑onnx ASR 引擎（离线/非流式模型）。
 *
 * 与 app 内置的离线模型下载（ModelDownloadManager.downloadAsrModel）匹配：
 *  - paraformer : model.int8.onnx  → OfflineParaformerModelConfig
 *  - sensevoice : model.int8.onnx  → OfflineSenseVoiceModelConfig
 *  - whisper    : encoder.onnx + decoder.onnx → OfflineWhisperModelConfig
 *
 * 模型 tar.bz2 解压后通常带一层子目录（如 sherpa-onnx-paraformer-zh-small-2024-03-09），
 * 这里会自动向下查找包含 tokens.txt 的目录，无需手动指定深层路径。
 *
 * assetManager 传 null → 走 newFromFile，读取磁盘文件，不依赖 assets。
 */
public class SherpaAsrEngine implements IAsrEngine {

    private static final String TAG = "SherpaAsrEngine";
    private boolean mReady = false;
    private OfflineRecognizer mRecognizer;

    public SherpaAsrEngine() {
        try {
            System.loadLibrary("sherpa-onnx-jni");
        } catch (UnsatisfiedLinkError e) {
            AlinLog.e(TAG, "JNI库加载失败", e);
        }
    }

    @Override
    public void init(File modelDir, Callback cb) {
        mReady = false;
        new Thread(() -> {
            try {
                File dir = findModelDir(modelDir);
                if (dir == null) {
                    throw new Exception("未找到模型文件（缺少 tokens.txt）：" + modelDir);
                }
                AlinLog.d(TAG, "使用模型目录: " + dir);

                OfflineModelConfig modelConfig = new OfflineModelConfig();
                modelConfig.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
                modelConfig.setNumThreads(2);

                String name = dir.getName().toLowerCase();
                if (name.contains("whisper")) {
                    OfflineWhisperModelConfig whisper = new OfflineWhisperModelConfig();
                    whisper.setEncoder(findIn(dir, "encoder.onnx", "encoder.int8.onnx"));
                    whisper.setDecoder(findIn(dir, "decoder.onnx", "decoder.int8.onnx"));
                    whisper.setLanguage("en");
                    whisper.setTask("transcribe");
                    modelConfig.setWhisper(whisper);
                    AlinLog.d(TAG, "识别到 whisper 模型: encoder=" + whisper.getEncoder());
                } else if (name.contains("sense")) {
                    OfflineSenseVoiceModelConfig sv = new OfflineSenseVoiceModelConfig();
                    sv.setModel(findIn(dir, "model.int8.onnx", "model.onnx"));
                    sv.setLanguage("auto");
                    sv.setUseInverseTextNormalization(true);
                    modelConfig.setSenseVoice(sv);
                    AlinLog.d(TAG, "识别到 sensevoice 模型: " + sv.getModel());
                } else {
                    // 默认按 paraformer 处理
                    OfflineParaformerModelConfig pf = new OfflineParaformerModelConfig();
                    pf.setModel(findIn(dir, "model.int8.onnx", "model.onnx"));
                    modelConfig.setParaformer(pf);
                    AlinLog.d(TAG, "识别到 paraformer 模型: " + pf.getModel());
                }

                OfflineRecognizerConfig config = new OfflineRecognizerConfig();
                config.setModelConfig(modelConfig);

                // 第一个参数传 null → newFromFile，读取本地磁盘文件，不走 assets
                mRecognizer = new OfflineRecognizer(null, config);

                AlinLog.d(TAG, "模型初始化完成: " + dir);
                mReady = true;
                cb.onResult("就绪");
            } catch (Exception e) {
                AlinLog.e(TAG, "初始化失败", e);
                cb.onError(e.getMessage());
            }
        }).start();
    }


    /**
     * 16‑bit little‑endian PCM byte[] → float[]，范围 [-1.0 ~ 1.0]
     */
    private float[] bytesToFloat(byte[] bytes) {
        int sampleCount = bytes.length / 2;
        float[] out = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            short val = (short) (((bytes[i * 2 + 1] & 0xFF) << 8) | (bytes[i * 2] & 0xFF));
            out[i] = val / 32768.0f;
        }
        return out;
    }


    @Override
    public void recognize(byte[] pcm, Callback cb) {
        if (!mReady || mRecognizer == null) {
            cb.onError("ASR引擎尚未初始化");
            return;
        }

        new Thread(() -> {
            OfflineStream stream = null;
            try {
                stream = mRecognizer.createStream();

                float[] pcmFloat = bytesToFloat(pcm);
                stream.acceptWaveform(pcmFloat, 16000);

                mRecognizer.decode(stream);

                String text = mRecognizer.getResult(stream).getText();
                cb.onResult(text);

            } catch (Exception e) {
                AlinLog.e(TAG, "识别异常", e);
                cb.onError(e.getMessage());
            } finally {
                if (stream != null) {
                    stream.release();
                }
            }
        }).start();
    }


    @Override
    public void release() {
        mReady = false;
        if (mRecognizer != null) {
            mRecognizer.release();
            mRecognizer = null;
        }
    }

    @Override
    public boolean isReady() {
        return mReady;
    }

    @Override
    public String getName() {
        return "sherpa‑onnx";
    }

    /**
     * 在 modelDir 及其子目录（最多两层）中查找包含 tokens.txt 的模型目录。
     */
    private File findModelDir(File root) {
        if (root == null || !root.exists() || !root.isDirectory()) {
            return null;
        }
        if (new File(root, "tokens.txt").exists()) {
            return root;
        }
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                if (new File(d, "tokens.txt").exists()) {
                    return d;
                }
                File[] sub = d.listFiles(File::isDirectory);
                if (sub != null) {
                    for (File s : sub) {
                        if (new File(s, "tokens.txt").exists()) {
                            return s;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 在目录中按候选文件名依次查找，返回第一个存在的文件的绝对路径；都不存在则返回空串。
     */
    private String findIn(File dir, String... names) {
        for (String n : names) {
            File f = new File(dir, n);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }
        return "";
    }
}
