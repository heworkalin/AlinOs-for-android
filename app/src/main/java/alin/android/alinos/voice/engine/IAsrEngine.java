package alin.android.alinos.voice.engine;

import java.io.File;

/**
 * ASR 语音识别引擎接口。
 * 所有 ASR 实现（sherpa-onnx / vosk / 云端）必须实现此接口。
 */
public interface IAsrEngine {

    /**
     * 初始化引擎。
     * @param modelDir 模型文件目录
     * @param cb 回调
     */
    void init(File modelDir, Callback cb);

    /**
     * 识别一段 PCM 音频（16kHz, 16bit, 单声道）。
     * 调用前必须先 init 成功。
     */
    void recognize(byte[] pcm, Callback cb);

    /** 释放资源 */
    void release();

    /** 是否已就绪 */
    boolean isReady();

    /** 引擎名称 */
    String getName();

    interface Callback {
        void onResult(String text);
        void onError(String error);
    }
}
