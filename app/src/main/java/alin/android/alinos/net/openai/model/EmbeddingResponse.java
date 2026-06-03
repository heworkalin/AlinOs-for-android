package alin.android.alinos.net.openai.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Embeddings 响应。
 */
public class EmbeddingResponse {
    public String object;
    public List<EmbeddingData> data = new ArrayList<>();
    public String model;
    public Usage usage;

    public static class EmbeddingData {
        public String object;
        public int index;
        public List<Float> embedding = new ArrayList<>();
    }

    public static class Usage {
        public int promptTokens;
        public int totalTokens;
    }

    public static EmbeddingResponse fromJson(JSONObject json) {
        EmbeddingResponse resp = new EmbeddingResponse();
        resp.object = json.optString("object");
        resp.model = json.optString("model");

        JSONArray arr = json.optJSONArray("data");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i);
                if (d == null) continue;
                EmbeddingData ed = new EmbeddingData();
                ed.object = d.optString("object");
                ed.index = d.optInt("index");
                JSONArray emb = d.optJSONArray("embedding");
                if (emb != null) {
                    for (int j = 0; j < emb.length(); j++) {
                        ed.embedding.add((float) emb.optDouble(j));
                    }
                }
                resp.data.add(ed);
            }
        }

        JSONObject u = json.optJSONObject("usage");
        if (u != null) {
            resp.usage = new Usage();
            resp.usage.promptTokens = u.optInt("prompt_tokens");
            resp.usage.totalTokens = u.optInt("total_tokens");
        }
        return resp;
    }
}
