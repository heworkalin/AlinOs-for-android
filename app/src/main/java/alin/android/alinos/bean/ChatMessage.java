package alin.android.alinos.bean;

/**
 * 聊天消息数据模型
 */
public class ChatMessage {

    public static final int TYPE_USER = 0;
    public static final int TYPE_AI = 1;

    private final int type;       // TYPE_USER 或 TYPE_AI
    private final String content; // Markdown 原文
    private long timestamp;

    public ChatMessage(int type, String content) {
        this.type = type;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    public int getType() { return type; }
    public String getContent() { return content; }
    public long getTimestamp() { return timestamp; }
}
