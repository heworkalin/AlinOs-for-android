package alin.android.alinos.voice.engine.sherpa;

import android.util.Log;

import java.io.File;

import alin.android.alinos.voice.engine.IAsrEngine;

/**
 * sherpa-onnx ASR 引擎实现。
 * 封装 OnlineRecognizer 的 init → recognize 生命周期。
 */
public class SherpaAsrEngine implements IAsrEngine {

    private static final String TAG = "SherpaAsrEngine";
    private boolean mReady = false;

    public SherpaAsrEngine() {
        try {
            System.loadLibrary("sherpa-onnx-jni");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "JNI 加载失败", e);
        }
    }

    @Override
    public void init(File modelDir, Callback cb) {
        mReady = false;
        new Thread(() -> {
            try {
                if (!modelDir.exists() || !modelDir.isDirectory()) {
                    throw new Exception("模型目录不存在: " + modelDir);
                }

                // ====== sherpa-onnx 实际调用（TODO: AAR 就绪后启用） ======
                // OnlineModelConfig modelConfig = new OnlineModelConfig();
                // modelConfig.setTokens(new File(modelDir, "tokens.txt").getAbsolutePath());
                //
                // OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
                // transducer.setEncoder(new File(modelDir, "encoder.onnx").getAbsolutePath());
                // transducer.setDecoder(new File(modelDir, "decoder.onnx").getAbsolutePath());
                // transducer.setJoiner(new File(modelDir, "joiner.onnx").getAbsolutePath());
                // modelConfig.setTransducer(transducer);
                //
                // OnlineRecognizerConfig config = new OnlineRecognizerConfig(modelConfig);
                // mRecognizer = new OnlineRecognizer(config);
                // ================================================

                Log.d(TAG, "模型验证通过: " + modelDir);
                mReady = true;
                cb.onResult("就绪");
            } catch (Exception e) {
                Log.e(TAG, "初始化失败", e);
                cb.onError(e.getMessage());
            }
        }).start();
    }

    @Override
    public void recognize(byte[] pcm, Callback cb) {
        if (!mReady) {
            cb.onError("引擎未初始化");
            return;
        }
        new Thread(() -> {
            try {
                // ====== sherpa-onnx 实际调用（TODO） ======
                // OnlineStream stream = mRecognizer.createStream();
                // stream.acceptWaveform(pcm, pcm.length);
                // mRecognizer.decode(stream);
                // String text = mRecognizer.getResult(stream).getText();
                // cb.onResult(text);
                // =========================================

                Thread.sleep(300);
                cb.onResult("[sherpa] 识别完成，音频 " + pcm.length + " 字节");
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        }).start();
    }

    @Override
    public void release() { mReady = false; }

    @Override
    public boolean isReady() { return mReady; }

    @Override
    public String getName() { return "sherpa-onnx"; }
}
