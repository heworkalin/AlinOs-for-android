package alin.android.alinos.ai.dialect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * AI 协议方言（dialect）—— 对齐 pi-ai 的 {@code api} 概念。
 *
 * <p>每种协议实现：请求地址、鉴权头、请求体、以及流式事件解析。
 * 已实现：
 * <ul>
 *   <li>{@code openai-completions} —— OpenAI / DeepSeek / Groq / OpenRouter / Ollama 等；</li>
 *   <li>{@code openai-responses} —— OpenAI Responses API；</li>
 *   <li>{@code anthropic-messages} —— Anthropic Messages API。</li>
 * </ul>
 */
public interface ApiDialect {

    /** 协议 id，如 {@code openai-completions}。 */
    String id();

    /** 默认 base URL（用户未填写时使用）。 */
    String defaultBaseUrl();

    /** 构建请求 URL。 */
    String buildUrl(String baseUrl, String modelId, boolean stream);

    /** 构建鉴权等请求头。 */
    Map<String, String> headers(String apiKey);

    /**
     * 构建请求体。
     *
     * @param messages OpenAI 风格的消息数组（dialect 负责转换为自家格式）
     * @param tools    工具定义（OpenAI tools 格式，dialect 负责转换）
     */
    String buildBody(String modelId, JSONArray messages, JSONArray tools,
                     int maxTokens, double temperature, boolean stream);

    /**
     * 解析一行 SSE 数据。
     *
     * @param data     已剥离 {@code data:} 前缀的内容（不含 [DONE]）
     * @param state    跨行的累积状态（工具调用分片等）
     * @return 事件列表；返回空列表表示该帧不产生事件
     */
    List<StreamEvent> parse(String data, ParseState state);

    /** 是否为「流结束」标记（如 OpenAI 的 [DONE]、Anthropic 的 message_stop）。 */
    boolean isDone(String data, ParseState state);

    /** 跨帧累积状态。 */
    class ParseState {
        /** 工具调用累积：index → {id, name, args}。 */
        public final java.util.Map<Integer, ToolCallAccumulator> toolCalls =
                new java.util.LinkedHashMap<>();
        /** 是否已经发出过 usage 事件。 */
        public boolean usageEmitted;
        /** 结束原因。 */
        public String stopReason;

        public ToolCallAccumulator toolCall(int index) {
            ToolCallAccumulator a = toolCalls.get(index);
            if (a == null) {
                a = new ToolCallAccumulator();
                toolCalls.put(index, a);
            }
            return a;
        }
    }

    /** 单个工具调用的累积器。 */
    class ToolCallAccumulator {
        public String id;
        public String name;
        public final StringBuilder args = new StringBuilder();

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id == null ? "" : id);
                o.put("name", name == null ? "" : name);
                o.put("arguments", args.toString());
            } catch (Exception ignored) {
            }
            return o;
        }
    }
}
