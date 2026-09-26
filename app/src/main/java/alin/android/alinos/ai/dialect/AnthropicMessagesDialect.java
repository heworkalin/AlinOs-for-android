package alin.android.alinos.ai.dialect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import alin.android.alinos.ai.Usage;

/**
 * Anthropic Messages 协议（{@code POST /v1/messages}）。
 *
 * <p>与 OpenAI 的关键差异：
 * <ul>
 *   <li>鉴权用 {@code x-api-key} + {@code anthropic-version}；</li>
 *   <li>system 是顶层 {@code system} 字段，不在 messages 里；</li>
 *   <li>工具 schema 用 {@code input_schema}，不是 {@code parameters}；</li>
 *   <li>工具结果作为 {@code user} 消息里的 {@code tool_result} 内容块；</li>
 *   <li>流事件为 {@code content_block_start / content_block_delta / message_delta}；
 *       工具参数在 {@code input_json_delta.partial_json} 中分片给出；</li>
 *   <li>{@code max_tokens} 是<b>必填</b>。</li>
 * </ul>
 */
public class AnthropicMessagesDialect implements ApiDialect {

    public static final String ID = "anthropic-messages";
    private static final String API_VERSION = "2023-06-01";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.anthropic.com";
    }

    @Override
    public String buildUrl(String baseUrl, String modelId, boolean stream) {
        return UrlBuilder.endpoint(baseUrl, "https://api.anthropic.com", "/v1", "/messages");
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Accept", "text/event-stream");
        h.put("anthropic-version", API_VERSION);
        h.put("x-api-key", apiKey == null ? "" : apiKey);
        return h;
    }

    @Override
    public String buildBody(String modelId, JSONArray messages, JSONArray tools,
                            int maxTokens, double temperature, boolean stream) {
        JSONObject body = new JSONObject();
        try {
            StringBuilder system = new StringBuilder();
            JSONArray msgs = toMessages(messages, system);

            body.put("model", modelId);
            if (system.length() > 0) body.put("system", system.toString().trim());
            body.put("messages", msgs);
            // Anthropic 要求 max_tokens 必填
            body.put("max_tokens", maxTokens > 0 ? maxTokens : 4096);
            if (temperature >= 0) body.put("temperature", temperature);
            body.put("stream", stream);

            JSONArray at = toTools(tools);
            if (at.length() > 0) {
                body.put("tools", at);
            }
        } catch (Exception ignored) {
        }
        return body.toString();
    }

    // ---------------------------------------------------------------------
    // 消息 / 工具转换
    // ---------------------------------------------------------------------

    private static JSONArray toMessages(JSONArray messages, StringBuilder system) {
        JSONArray out = new JSONArray();
        if (messages == null) return out;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null) continue;
            try {
                appendMessage(out, msg, system);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void appendMessage(JSONArray out, JSONObject msg, StringBuilder system)
            throws org.json.JSONException {
        String role = msg.optString("role", "user");
        String content = msg.optString("content", "");

        if ("system".equals(role) || "developer".equals(role)) {
            if (!content.isEmpty() && !"null".equals(content)) {
                system.append(content).append("\n\n");
            }
            return;
        }

        if ("tool".equals(role)) {
            JSONObject block = new JSONObject();
            block.put("type", "tool_result");
            block.put("tool_use_id", msg.optString("tool_call_id", ""));
            block.put("content", content == null ? "" : content);
            out.put(new JSONObject().put("role", "user")
                    .put("content", new JSONArray().put(block)));
            return;
        }

        if ("assistant".equals(role)) {
            JSONArray blocks = new JSONArray();
            if (content != null && !content.isEmpty() && !"null".equals(content)) {
                blocks.put(new JSONObject().put("type", "text").put("text", content));
            }
            JSONArray toolCalls = msg.optJSONArray("tool_calls");
            if (toolCalls != null) {
                for (int j = 0; j < toolCalls.length(); j++) {
                    JSONObject tc = toolCalls.optJSONObject(j);
                    if (tc == null) continue;
                    JSONObject fn = tc.optJSONObject("function");
                    if (fn == null) continue;
                    JSONObject block = new JSONObject();
                    block.put("type", "tool_use");
                    block.put("id", tc.optString("id", ""));
                    block.put("name", fn.optString("name", ""));
                    block.put("input", parseArgs(fn.optString("arguments", "{}")));
                    blocks.put(block);
                }
            }
            if (blocks.length() > 0) {
                out.put(new JSONObject().put("role", "assistant").put("content", blocks));
            }
            return;
        }

        out.put(new JSONObject().put("role", "user")
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "text")
                        .put("text", content == null ? "" : content))));
    }

    private static JSONObject parseArgs(String s) {
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** OpenAI tools（parameters） → Anthropic tools（input_schema）。 */
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
                o.put("name", fn.optString("name", ""));
                o.put("description", fn.optString("description", ""));
                JSONObject params = fn.optJSONObject("parameters");
                o.put("input_schema", params == null ? emptySchema() : params);
                out.put(o);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static JSONObject emptySchema() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "object");
            o.put("properties", new JSONObject());
        } catch (Exception ignored) {
        }
        return o;
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

        if ("message_start".equals(type)) {
            JSONObject message = ev.optJSONObject("message");
            if (message != null) {
                JSONObject usage = message.optJSONObject("usage");
                if (usage != null) events.add(StreamEvent.usage(parseUsage(usage, true)));
            }
            return events;
        }

        if ("content_block_start".equals(type)) {
            JSONObject block = ev.optJSONObject("content_block");
            if (block != null && "tool_use".equals(block.optString("type", ""))) {
                int index = ev.optInt("index", state.toolCalls.size());
                ToolCallAccumulator acc = state.toolCall(index);
                acc.id = block.optString("id", "");
                acc.name = block.optString("name", "");
                events.add(StreamEvent.toolCall(acc.id, acc.name, ""));
            }
            return events;
        }

        if ("content_block_delta".equals(type)) {
            int index = ev.optInt("index", 0);
            JSONObject delta = ev.optJSONObject("delta");
            if (delta == null) return events;
            String dtype = delta.optString("type", "");

            if ("text_delta".equals(dtype)) {
                String text = delta.optString("text", "");
                if (!text.isEmpty()) events.add(StreamEvent.text(text));
            } else if ("thinking_delta".equals(dtype)) {
                String text = delta.optString("thinking", "");
                if (!text.isEmpty()) events.add(StreamEvent.think(text));
            } else if ("input_json_delta".equals(dtype)) {
                String partial = delta.optString("partial_json", "");
                ToolCallAccumulator acc = state.toolCall(index);
                if (!partial.isEmpty()) acc.args.append(partial);
                events.add(StreamEvent.toolCall(acc.id, acc.name, partial));
            }
            return events;
        }

        if ("message_delta".equals(type)) {
            JSONObject delta = ev.optJSONObject("delta");
            if (delta != null) {
                String stop = delta.optString("stop_reason", "");
                if (!stop.isEmpty() && !"null".equals(stop)) {
                    state.stopReason = "tool_use".equals(stop) ? "tool_use" : stop;
                }
            }
            JSONObject usage = ev.optJSONObject("usage");
            if (usage != null) events.add(StreamEvent.usage(parseUsage(usage, false)));
            return events;
        }

        if ("message_stop".equals(type)) {
            if (state.stopReason == null) {
                state.stopReason = state.toolCalls.isEmpty() ? "stop" : "tool_use";
            }
            events.add(StreamEvent.done(state.stopReason));
            return events;
        }

        if ("error".equals(type)) {
            JSONObject err = ev.optJSONObject("error");
            String msg = err != null ? err.optString("message", "") : "";
            events.add(StreamEvent.error(msg.isEmpty() ? "anthropic error" : msg));
            return events;
        }

        return events;
    }

    @Override
    public boolean isDone(String data, ParseState state) {
        return data.contains("\"message_stop\"") || data.contains("\"error\"");
    }

    /**
     * Anthropic 的 usage 分散在 message_start（input）与 message_delta（output）。
     *
     * @param fromStart true 表示这是 message_start 里的初始 usage
     */
    private static Usage parseUsage(JSONObject u, boolean fromStart) {
        Usage usage = new Usage();
        if (fromStart) {
            usage.input = u.optLong("input_tokens", 0);
            usage.cacheRead = u.optLong("cache_read_input_tokens", 0);
            usage.cacheWrite = u.optLong("cache_creation_input_tokens", 0);
            JSONObject creation = u.optJSONObject("cache_creation");
            if (creation != null) {
                usage.cacheWrite1h = creation.optLong("ephemeral_1h_input_tokens", 0);
            }
        } else {
            usage.output = u.optLong("output_tokens", 0);
            // message_delta 里也可能带完整的 input 统计
            long in = u.optLong("input_tokens", 0);
            if (in > 0) usage.input = in;
            long cr = u.optLong("cache_read_input_tokens", 0);
            if (cr > 0) usage.cacheRead = cr;
            long cw = u.optLong("cache_creation_input_tokens", 0);
            if (cw > 0) usage.cacheWrite = cw;
        }
        return usage;
    }
}
