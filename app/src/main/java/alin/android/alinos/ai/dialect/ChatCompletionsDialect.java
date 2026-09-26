package alin.android.alinos.ai.dialect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import alin.android.alinos.ai.Usage;

/**
 * OpenAI Chat Completions 协议（{@code POST /v1/chat/completions}）。
 *
 * <p>兼容：OpenAI、DeepSeek、Groq、OpenRouter、Together、Ollama、llama.cpp、
 * one-api/new-api 网关等所有 OpenAI 风格服务端。
 */
public class ChatCompletionsDialect implements ApiDialect {

    public static final String ID = "openai-completions";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.openai.com";
    }

    @Override
    public String buildUrl(String baseUrl, String modelId, boolean stream) {
        return UrlBuilder.endpoint(baseUrl,
                "https://api.openai.com", "/v1", "/chat/completions");
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Accept", "text/event-stream");
        h.put("Cache-Control", "no-cache");
        h.put("Accept-Encoding", "identity");
        h.put("Authorization", "Bearer " + (apiKey == null || apiKey.isEmpty() ? "empty" : apiKey));
        return h;
    }

    @Override
    public String buildBody(String modelId, JSONArray messages, JSONArray tools,
                            int maxTokens, double temperature, boolean stream) {
        JSONObject body = new JSONObject();
        try {
            body.put("model", modelId);
            body.put("messages", messages == null ? new JSONArray() : messages);
            if (temperature >= 0) body.put("temperature", temperature);
            if (maxTokens > 0) body.put("max_tokens", maxTokens);
            body.put("stream", stream);
            if (stream) {
                body.put("stream_options", new JSONObject().put("include_usage", true));
            }
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
        } catch (Exception ignored) {
        }
        return body.toString();
    }

    @Override
    public List<StreamEvent> parse(String data, ParseState state) {
        List<StreamEvent> events = new ArrayList<>();
        JSONObject chunk;
        try {
            chunk = new JSONObject(data);
        } catch (Exception e) {
            return events;
        }

        // usage 帧（stream_options.include_usage）
        JSONObject usageObj = chunk.optJSONObject("usage");
        if (usageObj != null) {
            events.add(StreamEvent.usage(parseUsage(usageObj)));
        }

        JSONArray choices = chunk.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return events;

        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return events;

        String finish = choice.optString("finish_reason", "");
        if (!finish.isEmpty() && !"null".equals(finish)) {
            state.stopReason = "tool_calls".equals(finish) ? "tool_use" : finish;
        }

        JSONObject delta = choice.optJSONObject("delta");
        if (delta == null) delta = choice.optJSONObject("message");
        if (delta == null) return events;

        // 思考内容（DeepSeek reasoning_content / 部分网关的 reasoning）
        String reasoning = delta.optString("reasoning_content", "");
        if (reasoning.isEmpty()) reasoning = delta.optString("reasoning", "");
        if (!reasoning.isEmpty()) {
            events.add(StreamEvent.think(reasoning));
        }

        // 正文
        String content = delta.optString("content", "");
        if (!content.isEmpty() && !"null".equals(content)) {
            events.add(StreamEvent.text(content));
        }

        // 工具调用分片
        JSONArray toolCalls = delta.optJSONArray("tool_calls");
        if (toolCalls != null) {
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject tc = toolCalls.optJSONObject(i);
                if (tc == null) continue;
                int index = tc.optInt("index", i);
                ToolCallAccumulator acc = state.toolCall(index);
                if (tc.has("id")) {
                    String id = tc.optString("id", "");
                    if (!id.isEmpty()) acc.id = id;
                }
                JSONObject fn = tc.optJSONObject("function");
                String argsDelta = "";
                if (fn != null) {
                    String name = fn.optString("name", "");
                    if (!name.isEmpty()) acc.name = name;
                    argsDelta = fn.optString("arguments", "");
                }
                if (!argsDelta.isEmpty() && !"null".equals(argsDelta)) {
                    acc.args.append(argsDelta);
                }
                events.add(StreamEvent.toolCall(acc.id, acc.name, argsDelta));
            }
        }

        if (!"".equals(state.stopReason) && state.stopReason != null
                && state.toolCalls.isEmpty()) {
            events.add(StreamEvent.done(state.stopReason));
        }
        return events;
    }

    @Override
    public boolean isDone(String data, ParseState state) {
        return "[DONE]".equals(data);
    }

    private static Usage parseUsage(JSONObject u) {
        Usage usage = new Usage();
        usage.input = u.optLong("prompt_tokens", u.optLong("input_tokens", 0));
        usage.output = u.optLong("completion_tokens", u.optLong("output_tokens", 0));
        JSONObject details = u.optJSONObject("prompt_tokens_details");
        if (details != null) {
            usage.cacheRead = details.optLong("cached_tokens", 0);
        }
        if (usage.cacheRead == 0) {
            usage.cacheRead = u.optLong("cached_tokens", 0);
        }
        JSONObject completionDetails = u.optJSONObject("completion_tokens_details");
        if (completionDetails != null) {
            usage.reasoning = completionDetails.optLong("reasoning_tokens", 0);
        }
        if (usage.cacheRead > 0 && usage.input >= usage.cacheRead) {
            usage.input -= usage.cacheRead;
        }
        return usage;
    }
}
