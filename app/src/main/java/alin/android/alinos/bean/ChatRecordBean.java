package alin.android.alinos.bean;

import alin.android.alinos.utils.TokenEstimator;

public class ChatRecordBean {
    private int id;
    private int sessionId;
    private int msgType;
    private String sender;
    private String content;
    private long sendTime;

    // Token相关字段（新增）
    private int tokenCount = 0;           // 消息内容本身的token数
    private int promptTokens = 0;         // 整个prompt的token数（仅AI回复有意义）
    private int completionTokens = 0;     // AI回复的token数
    private int totalTokens = 0;          // 总token数

    // 用量与费用（新增）
    /** 完整用量 JSON（含 cost 明细）。 */
    private String usageJson;
    /** 本条消息累计费用（美元）。 */
    private double costTotal = 0;
    /** 本条消息使用的模型 id。 */
    private String modelId;
    /** 命中缓存的读取 token。 */
    private int cacheReadTokens = 0;
    /** 写入缓存的 token。 */
    private int cacheWriteTokens = 0;

    // 新增：带5个参数的构造器（匹配 ChatActivity 第153、164行调用）
    public ChatRecordBean(int sessionId, int msgType, String sender, String content, long sendTime) {
        this.sessionId = sessionId;
        this.msgType = msgType;
        this.sender = sender;
        this.content = content;
        this.sendTime = sendTime;
        // 新增：自动计算消息内容本身的token数
        this.tokenCount = alin.android.alinos.utils.TokenEstimator.estimateTokens(content);
    }

    // 新增：带Token参数的构造器
    public ChatRecordBean(int sessionId, int msgType, String sender, String content, long sendTime,
                         int tokenCount, int promptTokens, int completionTokens, int totalTokens) {
        this.sessionId = sessionId;
        this.msgType = msgType;
        this.sender = sender;
        this.content = content;
        this.sendTime = sendTime;
        this.tokenCount = tokenCount;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
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

    // Token相关字段的getter/setter（新增）
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public String getUsageJson() { return usageJson; }
    public void setUsageJson(String usageJson) { this.usageJson = usageJson; }

    public double getCostTotal() { return costTotal; }
    public void setCostTotal(double costTotal) { this.costTotal = costTotal; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public int getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(int cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }

    public int getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(int cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }

    /** 费用文本，例如 "$0.001234"。 */
    public String costText() {
        if (costTotal <= 0) return "";
        if (costTotal < 0.01) return String.format(java.util.Locale.US, "$%.6f", costTotal);
        return String.format(java.util.Locale.US, "$%.4f", costTotal);
    }

    /**
     * 获取当前消息的预估token数（优先使用云端返回的，没有则使用本地估算的）
     * 对于AI回复，返回completionTokens；对于用户消息，返回tokenCount
     */
    public int getEstimatedTokens() {
        if (msgType == 1 && completionTokens > 0) { // AI回复且有云端token数
            return completionTokens;
        } else if (tokenCount > 0) { // 有本地估算的token数
            return tokenCount;
        } else { // 都没有，重新估算
            return alin.android.alinos.utils.TokenEstimator.estimateTokens(content);
        }
    }
}