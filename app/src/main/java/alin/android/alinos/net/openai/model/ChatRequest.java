package alin.android.alinos.net.openai.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Chat Completion 请求体。
 * 对应 POST /v1/chat/completions
 */
public class ChatRequest {
    public String model;
    public List<Message> messages = new ArrayList<>();
    public double temperature = 0.7;
    public int maxTokens = 1024;
    public boolean stream = true;
    public JSONArray tools;       // function-calling tools
    public String toolChoice;     // "auto" / "none" / specific
    public JSONObject streamOptions; // { include_usage: true }

    public static class Message {
        public String role;       // system / user / assistant / tool
        public String content;
        public String name;       // optional
        public String toolCallId;
        public List<ToolCall> toolCalls;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("role", role);
                if (content != null) json.put("content", content);
                if (name != null) json.put("name", name);
                if (toolCallId != null) json.put("tool_call_id", toolCallId);
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    JSONArray arr = new JSONArray();
                    for (ToolCall tc : toolCalls) arr.put(tc.toJson());
                    json.put("tool_calls", arr);
                }
            } catch (Exception ignored) {}
            return json;
        }
    }

    public static class ToolCall {
        public String id;
        public String type = "function";
        public FunctionCall function;

        public static class FunctionCall {
            public String name;
            public String arguments; // JSON string
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("type", type);
                if (function != null) {
                    JSONObject f = new JSONObject();
                    f.put("name", function.name);
                    f.put("arguments", function.arguments);
                    json.put("function", f);
                }
            } catch (Exception ignored) {}
            return json;
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("model", model);
            json.put("temperature", temperature);
            json.put("max_tokens", maxTokens);
            json.put("stream", stream);
            JSONArray msgs = new JSONArray();
            for (Message m : messages) msgs.put(m.toJson());
            json.put("messages", msgs);
            if (tools != null) json.put("tools", tools);
            if (toolChoice != null) json.put("tool_choice", toolChoice);
            if (streamOptions != null) json.put("stream_options", streamOptions);
        } catch (Exception ignored) {}
        return json;
    }
}
