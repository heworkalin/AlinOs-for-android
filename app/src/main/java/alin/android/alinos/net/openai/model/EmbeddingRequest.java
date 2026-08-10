package alin.android.alinos.net.openai.model;

import org.json.JSONObject;

/**
 * Embeddings 请求体。
 * POST /v1/embeddings
 */
public class EmbeddingRequest {
    public String model = "text-embedding-3-small";
    public String input;              // 文本或 token 数组
    public String encodingFormat = "float";  // float / base64
    public Integer dimensions;        // 输出维度（可选，仅 text-embedding-3 支持）
    public String user;               // 终端用户标识（可选）

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("model", model);
            json.put("input", input);
            json.put("encoding_format", encodingFormat);
            if (dimensions != null) json.put("dimensions", dimensions);
            if (user != null) json.put("user", user);
        } catch (Exception ignored) {}
        return json;
    }
}
