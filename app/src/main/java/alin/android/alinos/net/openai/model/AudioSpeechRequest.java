package alin.android.alinos.net.openai.model;

import org.json.JSONObject;

/**
 * Audio Speech (TTS) 请求体。
 * POST /v1/audio/speech
 */
public class AudioSpeechRequest {
    public String model = "tts-1";    // tts-1 / tts-1-hd
    public String input;              // 要合成的文本（最大4096字符）
    public String voice = "alloy";    // alloy / echo / fable / onyx / nova / shimmer
    public String responseFormat = "mp3";  // mp3 / opus / aac / flac / wav / pcm
    public double speed = 1.0;        // 0.25 ~ 4.0

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("model", model);
            json.put("input", input);
            json.put("voice", voice);
            json.put("response_format", responseFormat);
            json.put("speed", speed);
        } catch (Exception ignored) {}
        return json;
    }
}
