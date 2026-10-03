package alin.android.alinos.voice.engine.vosk;

import alin.android.alinos.log.AlinLog;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;

import alin.android.alinos.voice.engine.IAsrEngine;

/**
 * Vosk ASR 引擎实现。
 * 参照原始 VoiceRecognitionActivity 的正确模式：
 * - JSONObject 解析（兼容任意空格）
 * - recognizer.reset() 避免状态残留
 */
public class VoskAsrEngine implements IAsrEngine {

    private static final String TAG = "VoskAsr";
    private static final float SAMPLE_RATE = 16000.0f;
    private Model mModel;
    private Recognizer mRecognizer;
    private boolean mReady;

    @Override
    public void init(File modelDir, Callback cb) {
        mReady = false;
        new Thread(() -> {
            try {
                if (!modelDir.exists() || !modelDir.isDirectory()) {
                    throw new Exception("Vosk 模型目录不存在: " + modelDir);
                }
                AlinLog.d(TAG, "加载 Vosk 模型: " + modelDir);
                mModel = new Model(modelDir.getAbsolutePath());
                mRecognizer = new Recognizer(mModel, SAMPLE_RATE);
                mReady = true;
                AlinLog.d(TAG, "Vosk 模型加载成功");
                cb.onResult("就绪");
            } catch (Exception e) {
                AlinLog.e(TAG, "Vosk 初始化失败", e);
                cb.onError(e.getMessage());
            }
        }).start();
    }

    @Override
    public void recognize(byte[] pcm, Callback cb) {
        if (!mReady || mRecognizer == null) {
            cb.onError("引擎未初始化");
            return;
        }
        new Thread(() -> {
            try {
                AlinLog.d(TAG, "开始识别: " + pcm.length + " bytes");
                // 关键：重置识别器状态
                mRecognizer.reset();

                boolean accepted = mRecognizer.acceptWaveForm(pcm, pcm.length);
                AlinLog.d(TAG, "acceptWaveForm: " + accepted);
                String json = accepted ? mRecognizer.getResult() : mRecognizer.getPartialResult();
                AlinLog.d(TAG, "原始结果: " + json);

                String text = parseText(json);
                AlinLog.d(TAG, "解析结果: " + text);
                cb.onResult(text.isEmpty() ? "(未识别到语音)" : text);
            } catch (Exception e) {
                AlinLog.e(TAG, "识别异常", e);
                cb.onError(e.getMessage());
            }
        }).start();
    }

    @Override
    public void release() {
        mReady = false;
        try { if (mRecognizer != null) mRecognizer.close(); } catch (Exception ignored) {}
        try { if (mModel != null) mModel.close(); } catch (Exception ignored) {}
        mRecognizer = null;
        mModel = null;
    }

    @Override public boolean isReady() { return mReady; }
    @Override public String getName() { return "Vosk"; }

    /** 用 JSONObject 解析（兼容任意空格） */
    private String parseText(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("text") && !obj.isNull("text")) return obj.getString("text");
            if (obj.has("partial") && !obj.isNull("partial")) return obj.getString("partial");
        } catch (Exception e) {
            AlinLog.w(TAG, "JSON 解析失败", e);
        }
        return "";
    }
}
