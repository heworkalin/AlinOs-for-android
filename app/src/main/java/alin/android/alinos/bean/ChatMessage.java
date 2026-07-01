package alin.android.alinos.bean;

/**
 * 聊天消息数据模型
 * TYPE_USER=1, TYPE_AI=2（与 ChatActivity 内部类版本一致）
 */
public class ChatMessage {
    public static final int TYPE_USER = 1;   // 用户消息
    public static final int TYPE_AI = 2;     // AI消息

    public String content;                  // 消息内容
    public int type;                        // 消息类型
    public boolean isLoading;               // AI是否加载中

    public ChatMessage(String content, int type, boolean isLoading) {
        this.content = content;
        this.type = type;
        this.isLoading = isLoading;
    }
}
