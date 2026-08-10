package alin.android.alinos.voice.engine;

import java.io.File;

/**
 * TTS 文字转语音引擎接口。
 */
public interface ITtsEngine {

    void init(File modelDir, Callback cb);

    /** 合成语音，speed: 0.5~2.0 */
    void synthesize(String text, float speed, Callback cb);

    void release();
    boolean isReady();
    String getName();

    interface Callback {
        void onAudio(byte[] wavData);
        void onError(String error);
    }
}
