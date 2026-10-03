package alin.android.alinos.voice.engine.sherpa;

import alin.android.alinos.log.AlinLog;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;

import alin.android.alinos.voice.engine.ITtsEngine;

/**
 * sherpa-onnx TTS 引擎 — 真实实现。
 * 使用 vits-melo-tts-zh_en 模型。
 * 输出 PCM 16-bit 22050Hz 单声道原始音频。
 */
public class SherpaTtsEngine implements ITtsEngine {

    private static final String TAG = "SherpaTts";
    private OfflineTts mTts;
    private boolean mReady = false;

    @Override
    public void init(File modelDir, Callback cb) {
        mReady = false;
        new Thread(() -> {
            try {
                if (!modelDir.exists() || !modelDir.isDirectory()) {
                    throw new Exception("TTS 模型目录不存在: " + modelDir);
                }

                // 打印目录树，便于排查
                logDir(modelDir, "");

                String modelFile = findOnnx(modelDir);
                String tokensFile = findFile(modelDir, "tokens.txt");
                String lexiconFile = findFile(modelDir, "lexicon.txt");

                AlinLog.d(TAG, "model: " + modelFile);
                AlinLog.d(TAG, "tokens: " + tokensFile);
                AlinLog.d(TAG, "lexicon: " + lexiconFile);

                if (modelFile == null || tokensFile == null) {
                    throw new Exception("缺少模型文件: " + modelDir);
                }

                OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
                vits.setModel(modelFile);
                vits.setTokens(tokensFile);
                if (lexiconFile != null) vits.setLexicon(lexiconFile);

                OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
                modelConfig.setVits(vits);
                modelConfig.setNumThreads(2);
                modelConfig.setDebug(false);

                OfflineTtsConfig config = new OfflineTtsConfig();
                config.setModel(modelConfig);

                mTts = new OfflineTts(null, config);
                mReady = true;

                AlinLog.d(TAG, "TTS 模型加载成功: " + modelDir);
                cb.onAudio(null); // 就绪信号
            } catch (Exception e) {
                AlinLog.e(TAG, "TTS 初始化失败", e);
                cb.onError(e.getMessage());
            }
        }).start();
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

        new Thread(() -> {
            try {
                AlinLog.d(TAG, "合成: " + text + " speed=" + speed);
                int sid = 0;
                GeneratedAudio audio = mTts.generate(text, sid, speed);
                float[] samples = audio.getSamples();
                int sampleRate = audio.getSampleRate();

                AlinLog.d(TAG, "合成完成: samples=" + samples.length
                        + " sampleRate=" + sampleRate);

                // 转为 16-bit PCM 字节数组 + WAV 头
                byte[] wav = toWav(samples, sampleRate);
                cb.onAudio(wav);
            } catch (Exception e) {
                AlinLog.e(TAG, "合成失败", e);
                cb.onError(e.getMessage());
            }
        }).start();
    }

    @Override
    public void release() {
        mReady = false;
        if (mTts != null) {
            mTts.release();
            mTts = null;
        }
        AlinLog.d(TAG, "TTS 释放");
    }

    @Override public boolean isReady() { return mReady; }
    @Override public String getName() { return "sherpa-onnx TTS"; }

    /** 递归查找任意 .onnx 文件（模型文件名因版本不同而异） */
    private String findOnnx(File dir) {
        File[] fs = dir.listFiles(); if (fs == null) return null;
        for (File f : fs) if (f.isFile() && f.getName().endsWith(".onnx")) return f.getAbsolutePath();
        for (File f : fs) if (f.isDirectory()) { String r = findOnnx(f); if (r != null) return r; }
        return null;
    }

    /** 打印目录树到 logcat */
    private void logDir(File dir, String prefix) {
        File[] fs = dir.listFiles(); if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { AlinLog.d(TAG, prefix + "[" + f.getName() + "]"); logDir(f, prefix + "  "); }
            else AlinLog.d(TAG, prefix + f.getName() + " (" + f.length() + " bytes)");
        }
    }

    /** 在目录中查找指定文件 */
    private String findFile(File dir, String name) {
        File f = new File(dir, name);
        if (f.exists()) return f.getAbsolutePath();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) {
                    String found = findFile(c, name);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    /** float[] PCM 转 16-bit WAV */
    private byte[] toWav(float[] samples, int sampleRate) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int dataSize = samples.length * 2;
            int fileSize = 44 + dataSize;

            // WAV header
            writeLE(out, "RIFF".getBytes());
            writeLE(out, fileSize - 8);
            writeLE(out, "WAVE".getBytes());
            writeLE(out, "fmt ".getBytes());
            writeLE(out, 16);           // PCM
            writeLE(out, (short) 1);    // format
            writeLE(out, (short) 1);    // channels (mono)
            writeLE(out, sampleRate);
            writeLE(out, sampleRate * 2); // byte rate
            writeLE(out, (short) 2);    // block align
            writeLE(out, (short) 16);   // bits per sample
            writeLE(out, "data".getBytes());
            writeLE(out, dataSize);

            // PCM data
            for (float s : samples) {
                short pcm = (short) Math.max(-32768, Math.min(32767, s * 32767));
                out.write(pcm & 0xFF);
                out.write((pcm >> 8) & 0xFF);
            }

            return out.toByteArray();
        } catch (Exception e) {
            AlinLog.e(TAG, "WAV 转换失败", e);
            return null;
        }
    }

    private void writeLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private void writeLE(ByteArrayOutputStream out, short v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private void writeLE(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }
}
