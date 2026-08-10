package alin.android.alinos.voice.engine.sherpa;

import android.util.Log;

import java.io.File;

import alin.android.alinos.voice.engine.IKwsEngine;

/** sherpa-onnx KWS 唤醒引擎 */
public class SherpaKwsEngine implements IKwsEngine {

    private static final String TAG = "SherpaKws";
    private boolean mReady = false;

    @Override
    public void init(File modelDir, String keyword, float threshold, Callback cb) {
        mReady = false;
        new Thread(() -> {
            try {
                if (!modelDir.exists() || !modelDir.isDirectory()) {
                    throw new Exception("KWS 模型目录不存在: " + modelDir);
                }
                // TODO: KeywordSpotterConfig + KeywordSpotter
                Log.d(TAG, "KWS 模型验证通过, keyword=" + keyword + ", threshold=" + threshold);
                mReady = true;
                cb.onListening();
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        }).start();
    }

    @Override public void start(Callback cb) { cb.onListening(); }

    @Override
    public void feedPcm(byte[] pcm) {
        // TODO: mSpotter.acceptWaveform(pcm, pcm.length)
    }

    @Override public void stop() {}
    @Override public void release() { mReady = false; }
    @Override public boolean isReady() { return mReady; }
    @Override public String getName() { return "sherpa-onnx KWS"; }
}
