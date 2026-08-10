package alin.android.alinos.voice.engine;

import java.io.File;

/**
 * KWS 关键词唤醒引擎接口。
 */
public interface IKwsEngine {

    /**
     * @param modelDir  模型目录
     * @param keyword   唤醒词（中文）
     * @param threshold 检测阈值 0.0~1.0
     */
    void init(File modelDir, String keyword, float threshold, Callback cb);

    /** 开始实时检测，需配合 AudioRecord 持续 feed 音频 */
    void start(Callback cb);

    /** 投喂 PCM 音频帧 */
    void feedPcm(byte[] pcm);

    void stop();
    void release();
    boolean isReady();
    String getName();

    interface Callback {
        void onDetected(String keyword, float confidence);
        void onListening();
        void onError(String error);
    }
}
