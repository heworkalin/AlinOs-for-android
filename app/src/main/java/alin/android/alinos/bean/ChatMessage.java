package alin.android.alinos.bean;

/**
 * 聊天消息数据模型
 * TYPE_USER=1, TYPE_AI=2（与 ChatActivity 内部类版本一致）
 * TYPE_THINK=3（AI 思考块）, TYPE_TOOL_CALL=4（工具调用卡片）
 */
public class ChatMessage {
    public static final int TYPE_USER = 1;       // 用户消息
    public static final int TYPE_AI = 2;         // AI消息
    public static final int TYPE_THINK = 3;      // AI思考块（可折叠）
    public static final int TYPE_TOOL_CALL = 4;  // 工具调用卡片（摘要/展开）

    public String content;                  // 消息内容（工具卡片时为JSON）
    public int type;                        // 消息类型
    public boolean isLoading;               // AI是否加载中 / 工具是否执行中

    public ChatMessage(String content, int type, boolean isLoading) {
        this.content = content;
        this.type = type;
        this.isLoading = isLoading;
    }
}
