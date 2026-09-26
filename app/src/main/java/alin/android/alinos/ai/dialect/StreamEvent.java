package alin.android.alinos.ai.dialect;

import org.json.JSONObject;

/**
 * 统一的流事件模型 —— 把各家协议的 SSE 差异抹平成同一种事件。
 *
 * <p>对应 pi-ai 的 {@code AssistantMessageEventStream} 事件：
 * text / thinking / toolCall / usage / done / error。
 */
public class StreamEvent {

    public enum Type {
        /** 正文增量。 */
        TEXT,
        /** 思考（reasoning / thinking）增量。 */
        THINK,
        /** 工具调用分片（同一 id 的多片需要调用方累积）。 */
        TOOL_CALL,
        /** 用量与费用。 */
        USAGE,
        /** 本轮结束。 */
        DONE,
        /** 错误。 */
        ERROR
    }

    public final Type type;

    // TEXT / THINK / ERROR
    public String text;

    // TOOL_CALL
    public String toolCallId;
    public String toolName;
    /** 参数 JSON 的增量片段。 */
    public String argsDelta;

    // DONE
    /** 结束原因：stop / length / tool_use / error。 */
    public String stopReason;

    /** 原始 JSON（调试用）。 */
    public JSONObject raw;

    private StreamEvent(Type type) {
        this.type = type;
    }

    public static StreamEvent text(String s) {
        StreamEvent e = new StreamEvent(Type.TEXT);
        e.text = s;
        return e;
    }

    public static StreamEvent think(String s) {
        StreamEvent e = new StreamEvent(Type.THINK);
        e.text = s;
        return e;
    }

    public static StreamEvent toolCall(String id, String name, String argsDelta) {
        StreamEvent e = new StreamEvent(Type.TOOL_CALL);
        e.toolCallId = id;
        e.toolName = name;
        e.argsDelta = argsDelta;
        return e;
    }

    public static StreamEvent done(String stopReason) {
        StreamEvent e = new StreamEvent(Type.DONE);
        e.stopReason = stopReason;
        return e;
    }

    public static StreamEvent error(String message) {
        StreamEvent e = new StreamEvent(Type.ERROR);
        e.text = message;
        return e;
    }

    public static StreamEvent usage(alin.android.alinos.ai.Usage usage) {
        StreamEvent e = new StreamEvent(Type.USAGE);
        e.text = usage == null ? "" : usage.toJson().toString();
        return e;
    }
}
