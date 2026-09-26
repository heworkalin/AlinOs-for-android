package alin.android.alinos.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 模型列表动态刷新器 —— 轮询 provider 的 models 端点，把「当前可用模型」写入
 * {@link ModelRegistry} 的动态 overlay。
 *
 * <p>与 pi-ai 的 {@code fetchModels} 机制对应：
 * <ul>
 *   <li>OpenAI 兼容：{@code GET {baseUrl}/v1/models} + {@code Authorization: Bearer <key>}；</li>
 *   <li>Anthropic：{@code GET https://api.anthropic.com/v1/models} + {@code x-api-key}；</li>
 *   <li>Google：{@code GET .../v1beta/models?key=<key>}。</li>
 * </ul>
 *
 * <p>静态基线里已有的模型保留其定价；动态新发现的模型先以「无定价」入库。
 */
public final class ModelRefresher {

    private static final String TAG = "ModelRefresher";

    /** 刷新结果。 */
    public static final class Result {
        public final String providerId;
        public final int count;
        public final String error;

        Result(String providerId, int count, String error) {
            this.providerId = providerId;
            this.count = count;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    private ModelRefresher() {
    }

    private static OkHttpClient client() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 刷新单个 provider 的模型列表。
     *
     * @param baseUrl 用户配置的服务器地址（可为空，则用 provider 默认）
     * @param apiType provider 使用的协议（决定 endpoints 与鉴权头）
     * @param apiKey  API Key（可为空）
     */
    public static Result refresh(Context ctx, String providerId, String baseUrl,
                                 String apiType, String apiKey) {
        try {
            List<ModelInfo> models;
            if ("anthropic-messages".equals(apiType)) {
                models = fetchAnthropic(firstNonEmpty(baseUrl, "https://api.anthropic.com"), apiKey);
            } else if ("google-generative-ai".equals(apiType)) {
                models = fetchGoogle(firstNonEmpty(baseUrl,
                        "https://generativelanguage.googleapis.com"), apiKey);
            } else {
                models = fetchOpenAiCompatible(firstNonEmpty(baseUrl, defaultBaseUrl(providerId)),
                        apiKey, apiType == null ? "openai-completions" : apiType);
            }

            long now = System.currentTimeMillis();
            ModelRegistry registry = ModelRegistry.get(ctx);
            registry.setDynamic(providerId, models, now);
            registry.persist(ctx);
            Log.d(TAG, "刷新 " + providerId + " 成功: " + models.size() + " 个模型");
            return new Result(providerId, models.size(), null);
        } catch (Exception e) {
            Log.w(TAG, "刷新 " + providerId + " 失败: " + e.getMessage());
            return new Result(providerId, 0, String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------------
    // 各协议实现
    // ---------------------------------------------------------------------

    /** OpenAI 兼容：{"data":[{"id":"..."}]} */
    private static List<ModelInfo> fetchOpenAiCompatible(String baseUrl, String apiKey, String api)
            throws IOException {
        String url = appendPath(baseUrl, "/models");
        Request.Builder rb = new Request.Builder().url(url).get()
                .addHeader("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            rb.addHeader("Authorization", "Bearer " + apiKey);
        }
        JSONObject json = getJson(rb.build());
        JSONArray data = json.optJSONArray("data");
        if (data == null) data = json.optJSONArray("models");
        return parseOpenAiLike(data, api, json);
    }

    /** Anthropic：{"data":[{"id":"...","display_name":"..."}]} */
    private static List<ModelInfo> fetchAnthropic(String baseUrl, String apiKey) throws IOException {
        String url = appendPath(baseUrl, "/v1/models?limit=1000");
        Request.Builder rb = new Request.Builder().url(url).get()
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            rb.addHeader("x-api-key", apiKey);
        }
        JSONObject json = getJson(rb.build());
        JSONArray data = json.optJSONArray("data");
        List<ModelInfo> out = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                if (id.isEmpty()) continue;
                out.add(build(id, m.optString("display_name", id), "anthropic-messages",
                        baseUrl, 0, 0, false));
            }
        }
        return out;
    }

    /** Google：{"models":[{"name":"models/gemini-...","displayName":...}]} */
    private static List<ModelInfo> fetchGoogle(String baseUrl, String apiKey) throws IOException {
        String url = appendPath(baseUrl, "/v1beta/models");
        if (apiKey != null && !apiKey.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + "key=" + apiKey;
        }
        JSONObject json = getJson(new Request.Builder().url(url).get()
                .addHeader("Accept", "application/json").build());
        JSONArray arr = json.optJSONArray("models");
        List<ModelInfo> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name", "");
                if (name.startsWith("models/")) name = name.substring("models/".length());
                if (name.isEmpty()) continue;
                out.add(build(name, m.optString("displayName", name), "google-generative-ai",
                        baseUrl,
                        m.optInt("inputTokenLimit", 0),
                        m.optInt("outputTokenLimit", 0),
                        false));
            }
        }
        return out;
    }

    private static List<ModelInfo> parseOpenAiLike(JSONArray data, String api, JSONObject root) {
        List<ModelInfo> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", m.optString("name", ""));
            if (id.isEmpty()) continue;
            out.add(build(id, m.optString("name", id), api, "",
                    m.optInt("context_length", m.optInt("contextWindow", 0)),
                    m.optInt("max_output_tokens", 0),
                    false));
        }
        return out;
    }

    private static ModelInfo build(String id, String name, String api, String baseUrl,
                                   int contextWindow, int maxTokens, boolean reasoning) {
        return new ModelInfo("", id, name, api, baseUrl, contextWindow, maxTokens,
                reasoning, "text", null);
    }

    // ---------------------------------------------------------------------
    // HTTP 工具
    // ---------------------------------------------------------------------

    private static JSONObject getJson(Request request) throws IOException {
        try (Response resp = client().newCall(request).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + preview(body));
            }
            return new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("响应不是合法 JSON: " + e.getMessage());
        }
    }

    private static String preview(String s) {
        if (s == null) return "";
        s = s.trim().replace("\n", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /** 拼接 models 端点，兼容 baseUrl 已带 /v1 的情况。 */
    private static String appendPath(String baseUrl, String path) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) return path;
        if (!b.startsWith("http")) b = "https://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (path.startsWith("/v1/") && b.endsWith("/v1")) {
            return b + path.substring(3);
        }
        if (path.equals("/models") && b.endsWith("/v1")) {
            return b + "/models";
        }
        if (b.endsWith("/v1") && path.startsWith("/v1/")) {
            return b + path.substring(3);
        }
        return b + path;
    }

    private static String firstNonEmpty(String a, String b) {
        return (a == null || a.trim().isEmpty()) ? b : a.trim();
    }

    private static String defaultBaseUrl(String providerId) {
        switch (providerId == null ? "" : providerId) {
            case "deepseek": return "https://api.deepseek.com";
            case "openai": return "https://api.openai.com";
            case "anthropic": return "https://api.anthropic.com";
            case "google": return "https://generativelanguage.googleapis.com";
            default: return "";
        }
    }
}
