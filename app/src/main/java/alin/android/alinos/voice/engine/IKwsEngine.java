package alin.android.alinos.voice.engine;

import java.io.File;

/**
 * KWS 关键词唤醒引擎接口。
 */
public interface IKwsEngine {

    /**
     * 初始化引擎，加载 KWS 模型并注册唤醒词。
     *
     * @param modelDir  模型目录（zipformer KWS 包，tar 解压后带一层子目录亦可）
     * @param keyword   唤醒词（中文）
     * @param threshold 检测阈值 0.0~1.0（越大越不容易误唤醒）
     */
    void init(File modelDir, String keyword, float threshold, Callback cb);

    /**
     * 一次性检测一段完整 PCM（16kHz/16bit/单声道）。
     * 内部自动补尾部静音并做流式解码，用于"录音→判定"类测试。
     * 命中时回调 onDetected(keyword, 1.0)，未命中时 onDetected("", 0.0)。
     */
    void detectPcm(byte[] pcm, Callback cb);

    /** 开始实时检测，需配合 AudioRecord 持续 feed 音频（常驻监听场景） */
    void start(Callback cb);

    /** 投喂 PCM 音频帧（常驻监听场景，内部流式解码并触发 onDetected） */
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
