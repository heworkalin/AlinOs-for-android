package alin.android.alinos.net.openai.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Chat Completion 响应。
 */
public class ChatResponse {
    public String id;
    public String object;
    public long created;
    public String model;
    public List<Choice> choices = new ArrayList<>();
    public Usage usage;

    public static class Choice {
        public int index;
        public Message message;
        public Message delta;       // streaming
        public String finishReason;
    }

    public static class Message {
        public String role;
        public String content;
        public List<ChatRequest.ToolCall> toolCalls;
    }

    public static class Usage {
        public int promptTokens;
        public int completionTokens;
        public int totalTokens;
    }

    public static ChatResponse fromJson(JSONObject json) {
        ChatResponse resp = new ChatResponse();
        resp.id = json.optString("id");
        resp.object = json.optString("object");
        resp.created = json.optLong("created");
        resp.model = json.optString("model");

        JSONArray choices = json.optJSONArray("choices");
        if (choices != null) {
            for (int i = 0; i < choices.length(); i++) {
                JSONObject c = choices.optJSONObject(i);
                if (c == null) continue;
                Choice choice = new Choice();
                choice.index = c.optInt("index");
                choice.finishReason = c.optString("finish_reason");
                // message (non-streaming)
                JSONObject msg = c.optJSONObject("message");
                if (msg != null) {
                    choice.message = new Message();
                    choice.message.role = msg.optString("role");
                    choice.message.content = msg.optString("content");
                }
                // delta (streaming)
                JSONObject delta = c.optJSONObject("delta");
                if (delta != null) {
                    choice.delta = new Message();
                    choice.delta.role = delta.optString("role");
                    choice.delta.content = delta.optString("content");
                }
                resp.choices.add(choice);
            }
        }

        JSONObject u = json.optJSONObject("usage");
        if (u != null) {
            resp.usage = new Usage();
            resp.usage.promptTokens = u.optInt("prompt_tokens");
            resp.usage.completionTokens = u.optInt("completion_tokens");
            resp.usage.totalTokens = u.optInt("total_tokens");
        }
        return resp;
    }
}
