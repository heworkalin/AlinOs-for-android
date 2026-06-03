package alin.android.alinos.net.openai.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Image / Moderation / Model 等通用响应模型。
 */
public class CommonResponses {

    /** Image 生成响应 */
    public static class ImageResponse {
        public long created;
        public List<ImageData> data = new ArrayList<>();

        public static class ImageData {
            public String url;         // URL 模式
            public String b64Json;     // b64_json 模式
            public String revisedPrompt; // dall-e-3 修订后的 prompt
        }

        public static ImageResponse fromJson(JSONObject json) {
            ImageResponse resp = new ImageResponse();
            resp.created = json.optLong("created");
            JSONArray arr = json.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.optJSONObject(i);
                    if (d == null) continue;
                    ImageData id = new ImageData();
                    id.url = d.optString("url");
                    id.b64Json = d.optString("b64_json");
                    id.revisedPrompt = d.optString("revised_prompt");
                    resp.data.add(id);
                }
            }
            return resp;
        }
    }

    /** Model 列表项 */
    public static class ModelInfo {
        public String id;
        public String object;
        public long created;
        public String ownedBy;

        public static ModelInfo fromJson(JSONObject json) {
            ModelInfo m = new ModelInfo();
            m.id = json.optString("id");
            m.object = json.optString("object");
            m.created = json.optLong("created");
            m.ownedBy = json.optString("owned_by");
            return m;
        }
    }

    /** Models 列表响应 */
    public static class ModelListResponse {
        public String object;
        public List<ModelInfo> data = new ArrayList<>();

        public static ModelListResponse fromJson(JSONObject json) {
            ModelListResponse resp = new ModelListResponse();
            resp.object = json.optString("object");
            JSONArray arr = json.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.optJSONObject(i);
                    if (d != null) resp.data.add(ModelInfo.fromJson(d));
                }
            }
            return resp;
        }
    }

    /** Moderation 请求 */
    public static class ModerationRequest {
        public String input;    // 要审核的文本
        public String model = "omni-moderation-latest"; // 或 text-moderation-latest / text-moderation-stable

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("input", input);
                json.put("model", model);
            } catch (Exception ignored) {}
            return json;
        }
    }

    /** Moderation 响应 */
    public static class ModerationResponse {
        public String id;
        public String model;
        public List<ModerationResult> results = new ArrayList<>();

        public static class ModerationResult {
            public boolean flagged;
            public CategoryScores categoryScores;

            public static class CategoryScores {
                public double harassment;
                public double harassmentThreatening;
                public double hate;
                public double hateThreatening;
                public double selfHarm;
                public double selfHarmIntent;
                public double selfHarmInstructions;
                public double sexual;
                public double sexualMinors;
                public double violence;
                public double violenceGraphic;
            }
        }

        public static ModerationResponse fromJson(JSONObject json) {
            ModerationResponse resp = new ModerationResponse();
            resp.id = json.optString("id");
            resp.model = json.optString("model");
            JSONArray arr = json.optJSONArray("results");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r == null) continue;
                    ModerationResult mr = new ModerationResult();
                    mr.flagged = r.optBoolean("flagged");
                    JSONObject cs = r.optJSONObject("category_scores");
                    if (cs != null) {
                        mr.categoryScores = new ModerationResult.CategoryScores();
                        mr.categoryScores.harassment = cs.optDouble("harassment");
                        mr.categoryScores.harassmentThreatening = cs.optDouble("harassment/threatening");
                        mr.categoryScores.hate = cs.optDouble("hate");
                        mr.categoryScores.hateThreatening = cs.optDouble("hate/threatening");
                        mr.categoryScores.selfHarm = cs.optDouble("self-harm");
                        mr.categoryScores.selfHarmIntent = cs.optDouble("self-harm/intent");
                        mr.categoryScores.selfHarmInstructions = cs.optDouble("self-harm/instructions");
                        mr.categoryScores.sexual = cs.optDouble("sexual");
                        mr.categoryScores.sexualMinors = cs.optDouble("sexual/minors");
                        mr.categoryScores.violence = cs.optDouble("violence");
                        mr.categoryScores.violenceGraphic = cs.optDouble("violence/graphic");
                    }
                    resp.results.add(mr);
                }
            }
            return resp;
        }
    }

    /** 文件信息 */
    public static class FileInfo {
        public String id;
        public String object;
        public long bytes;
        public long createdAt;
        public String filename;
        public String purpose;

        public static FileInfo fromJson(JSONObject json) {
            FileInfo f = new FileInfo();
            f.id = json.optString("id");
            f.object = json.optString("object");
            f.bytes = json.optLong("bytes");
            f.createdAt = json.optLong("created_at");
            f.filename = json.optString("filename");
            f.purpose = json.optString("purpose");
            return f;
        }
    }

    /** 删除确认响应 */
    public static class DeletionStatus {
        public String id;
        public String object;
        public boolean deleted;

        public static DeletionStatus fromJson(JSONObject json) {
            DeletionStatus ds = new DeletionStatus();
            ds.id = json.optString("id");
            ds.object = json.optString("object");
            ds.deleted = json.optBoolean("deleted");
            return ds;
        }
    }
}
