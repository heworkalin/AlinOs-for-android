package alin.android.alinos.net.openai.model;

import org.json.JSONObject;

/**
 * Image Generation 请求体。
 * POST /v1/images/generations
 */
public class ImageGenerationRequest {
    public String model = "dall-e-3";  // dall-e-2 / dall-e-3
    public String prompt;              // 图片描述（最大4000字符 dla-e-3，1000 dla-e-2）
    public int n = 1;                  // 生成数量（dall-e-3 仅支持1）
    public String quality = "standard"; // standard / hd（仅 dall-e-3）
    public String responseFormat = "url"; // url / b64_json
    public String size = "1024x1024";  // 256x256 / 512x512 / 1024x1024 (dall-e-2)
                                       // 1024x1024 / 1792x1024 / 1024x1792 (dall-e-3)
    public String style = "vivid";     // vivid / natural（仅 dall-e-3）
    public String user;                // 终端用户标识

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("model", model);
            json.put("prompt", prompt);
            json.put("n", n);
            if (quality != null) json.put("quality", quality);
            json.put("response_format", responseFormat);
            json.put("size", size);
            if (style != null) json.put("style", style);
            if (user != null) json.put("user", user);
        } catch (Exception ignored) {}
        return json;
    }
}
