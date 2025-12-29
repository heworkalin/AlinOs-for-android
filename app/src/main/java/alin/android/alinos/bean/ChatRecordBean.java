package alin.android.alinos.bean;

public class ChatRecordBean {
    private int id;
    private int sessionId;
    private int msgType;
    private String sender;
    private String content;
    private long sendTime;

    // 新增：带5个参数的构造器（匹配 ChatActivity 第153、164行调用）
    public ChatRecordBean(int sessionId, int msgType, String sender, String content, long sendTime) {
        this.sessionId = sessionId;
        this.msgType = msgType;
        this.sender = sender;
        this.content = content;
        this.sendTime = sendTime;
    }

    // 保留原有的无参构造器（可选，但建议保留）
    public ChatRecordBean() {}

    // 原有的 getter/setter 方法（不变）
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getSessionId() { return sessionId; }
    public void setSessionId(int sessionId) { this.sessionId = sessionId; }
    public int getMsgType() { return msgType; }
    public void setMsgType(int msgType) { this.msgType = msgType; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public long getSendTime() { return sendTime; }
    public void setSendTime(long sendTime) { this.sendTime = sendTime; }
}