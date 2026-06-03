package alin.android.alinos.net.openai.model;

import org.json.JSONObject;

/**
 * Audio Transcription (STT) 请求体。
 * POST /v1/audio/transcriptions
 */
public class AudioTranscriptionRequest {
    public String file;               // 音频文件路径或 base64
    public String model = "whisper-1";
    public String language;           // ISO-639-1（可选，自动检测）
    public String prompt;             // 引导词（可选）
    public String responseFormat = "json";  // json / text / srt / verbose_json / vtt
    public double temperature = 0;    // 0 ~ 1
    public String timestampGranularities; // word / segment（仅 verbose_json）

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("model", model);
            if (language != null) json.put("language", language);
            if (prompt != null) json.put("prompt", prompt);
            json.put("response_format", responseFormat);
            json.put("temperature", temperature);
            if (timestampGranularities != null) json.put("timestamp_granularities[]", timestampGranularities);
        } catch (Exception ignored) {}
        return json;
    }
}
