package alin.android.alinos.ai.dialect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import alin.android.alinos.ai.Usage;

/**
 * OpenAI Responses 协议（{@code POST /v1/responses}）。
 *
 * <p>与 Chat Completions 的关键差异：
 * <ul>
 *   <li>请求体用 {@code instructions} + {@code input} 数组，而不是 {@code messages}；</li>
 *   <li>工具 schema 是<b>扁平</b>的：{@code {type:"function", name, parameters}}，没有 {@code function} 包裹；</li>
 *   <li>流事件按 {@code response.*} 命名，例如 {@code response.output_text.delta}；</li>
 *   <li>工具调用通过 {@code response.output_item.added} +
 *       {@code response.function_call_arguments.delta} 上报。</li>
 * </ul>
 */
public class ResponsesDialect implements ApiDialect {

    public static final String ID = "openai-responses";

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
        return UrlBuilder.endpoint(baseUrl, "https://api.openai.com", "/v1", "/responses");
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
            StringBuilder instructions = new StringBuilder();
            JSONArray input = toInput(messages, instructions);

            body.put("model", modelId);
            if (instructions.length() > 0) {
                body.put("instructions", instructions.toString().trim());
            }
            body.put("input", input);
            if (temperature >= 0) body.put("temperature", temperature);
            if (maxTokens > 0) body.put("max_output_tokens", maxTokens);
            body.put("stream", stream);

            JSONArray rsTools = toTools(tools);
            if (rsTools.length() > 0) {
                body.put("tools", rsTools);
                body.put("tool_choice", "auto");
            }
        } catch (Exception ignored) {
        }
        return body.toString();
    }

    // ---------------------------------------------------------------------
    // 消息 / 工具转换
    // ---------------------------------------------------------------------

    private static JSONArray toInput(JSONArray messages, StringBuilder instructions) {
        JSONArray input = new JSONArray();
        if (messages == null) return input;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null) continue;
            try {
                appendInputItem(input, msg, instructions);
            } catch (Exception ignored) {
            }
        }
        return input;
    }

    private static void appendInputItem(JSONArray input, JSONObject msg, StringBuilder instructions)
            throws org.json.JSONException {
        String role = msg.optString("role", "user");
        String content = msg.optString("content", "");

        if ("system".equals(role) || "developer".equals(role)) {
            if (!content.isEmpty() && !"null".equals(content)) {
                instructions.append(content).append("\n\n");
            }
            return;
        }

        if ("tool".equals(role)) {
            input.put(new JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", msg.optString("tool_call_id", ""))
                    .put("output", content == null ? "" : content));
            return;
        }

        if ("assistant".equals(role)) {
            if (content != null && !content.isEmpty() && !"null".equals(content)) {
                input.put(new JSONObject()
                        .put("role", "assistant")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "output_text")
                                .put("text", content))));
            }
            JSONArray toolCalls = msg.optJSONArray("tool_calls");
            if (toolCalls != null) {
                for (int j = 0; j < toolCalls.length(); j++) {
                    JSONObject tc = toolCalls.optJSONObject(j);
                    if (tc == null) continue;
                    JSONObject fn = tc.optJSONObject("function");
                    if (fn == null) continue;
                    input.put(new JSONObject()
                            .put("type", "function_call")
                            .put("call_id", tc.optString("id", ""))
                            .put("name", fn.optString("name", ""))
                            .put("arguments", fn.optString("arguments", "{}")));
                }
            }
            return;
        }

        input.put(new JSONObject()
                .put("role", "user")
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "input_text")
                        .put("text", content == null ? "" : content))));
    }

    /** OpenAI tools（嵌套 function） → Responses tools（扁平）。 */
    public static JSONArray toTools(JSONArray openAiTools) {
        JSONArray out = new JSONArray();
        if (openAiTools == null) return out;
        for (int i = 0; i < openAiTools.length(); i++) {
            JSONObject t = openAiTools.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("type", "function");
                o.put("name", fn.optString("name", ""));
                o.put("description", fn.optString("description", ""));
                JSONObject params = fn.optJSONObject("parameters");
                o.put("parameters", params == null ? new JSONObject() : params);
                if (fn.has("strict")) o.put("strict", fn.optBoolean("strict", false));
                out.put(o);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // 流解析
    // ---------------------------------------------------------------------

    @Override
    public List<StreamEvent> parse(String data, ParseState state) {
        List<StreamEvent> events = new ArrayList<>();
        JSONObject ev;
        try {
            ev = new JSONObject(data);
        } catch (Exception e) {
            return events;
        }

        String type = ev.optString("type", "");

        if ("response.output_text.delta".equals(type)) {
            String delta = ev.optString("delta", "");
            if (!delta.isEmpty()) events.add(StreamEvent.text(delta));
            return events;
        }

        if ("response.reasoning_summary_text.delta".equals(type)
                || "response.reasoning_text.delta".equals(type)) {
            String delta = ev.optString("delta", "");
            if (!delta.isEmpty()) events.add(StreamEvent.think(delta));
            return events;
        }

        if ("response.output_item.added".equals(type)) {
            JSONObject item = ev.optJSONObject("item");
            if (item != null && "function_call".equals(item.optString("type", ""))) {
                int index = state.toolCalls.size();
                ToolCallAccumulator acc = state.toolCall(index);
                acc.id = firstNonEmpty(item.optString("call_id", ""), item.optString("id", ""));
                acc.name = item.optString("name", "");
                events.add(StreamEvent.toolCall(acc.id, acc.name, ""));
            }
            return events;
        }

        if ("response.function_call_arguments.delta".equals(type)) {
            String itemId = ev.optString("item_id", "");
            String delta = ev.optString("delta", "");
            ToolCallAccumulator acc = findByItemId(state, itemId);
            if (acc == null) {
                acc = state.toolCall(state.toolCalls.size());
            }
            if (delta != null && !delta.isEmpty()) acc.args.append(delta);
            events.add(StreamEvent.toolCall(acc.id, acc.name, delta));
            return events;
        }

        if ("response.completed".equals(type)) {
            JSONObject resp = ev.optJSONObject("response");
            if (resp != null) {
                JSONObject usage = resp.optJSONObject("usage");
                if (usage != null) events.add(StreamEvent.usage(parseUsage(usage)));
            }
            state.stopReason = "stop";
            events.add(StreamEvent.done(state.stopReason));
            return events;
        }

        if ("response.failed".equals(type) || "error".equals(type)) {
            JSONObject err = ev.optJSONObject("error");
            String msg = err != null ? err.optString("message", "") : ev.optString("message", "");
            events.add(StreamEvent.error(msg.isEmpty() ? "responses api error" : msg));
            return events;
        }

        if ("response.incomplete".equals(type)) {
            state.stopReason = "length";
            events.add(StreamEvent.done("length"));
            return events;
        }

        return events;
    }

    @Override
    public boolean isDone(String data, ParseState state) {
        return data.contains("\"response.completed\"")
                || data.contains("\"response.failed\"")
                || "[DONE]".equals(data);
    }

    private static ToolCallAccumulator findByItemId(ParseState state, String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        for (ToolCallAccumulator a : state.toolCalls.values()) {
            if (itemId.equals(a.id)) return a;
        }
        return null;
    }

    private static String firstNonEmpty(String a, String b) {
        return (a == null || a.isEmpty()) ? b : a;
    }

    private static Usage parseUsage(JSONObject u) {
        Usage usage = new Usage();
        usage.input = u.optLong("input_tokens", 0);
        usage.output = u.optLong("output_tokens", 0);
        JSONObject details = u.optJSONObject("input_tokens_details");
        if (details != null) {
            usage.cacheRead = details.optLong("cached_tokens", 0);
            if (usage.cacheRead > 0 && usage.input >= usage.cacheRead) {
                usage.input -= usage.cacheRead;
            }
        }
        JSONObject outDetails = u.optJSONObject("output_tokens_details");
        if (outDetails != null) {
            usage.reasoning = outDetails.optLong("reasoning_tokens", 0);
        }
        return usage;
    }
}
