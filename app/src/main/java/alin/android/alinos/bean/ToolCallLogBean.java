package alin.android.alinos.bean;

/**
 * 工具调用日志实体。
 * 每次 AI 发起工具调用的完整记录，供 UI 回溯和调试审计使用。
 */
public class ToolCallLogBean {
    private int id;
    private int sessionId;
    private int recordId;        // 关联的聊天记录ID（可选）
    private String toolName;
    private String toolCallId;   // LLM 返回的唯一 ID
    private String arguments;    // 入参 JSON
    private String result;       // 出参 JSON
    private String status;       // success / error / timeout
    private String errorMessage;
    private long durationMs;
    private long createdAt;

    public ToolCallLogBean() {}

    public ToolCallLogBean(int sessionId, String toolName, String toolCallId,
                           String arguments, long createdAt) {
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.arguments = arguments;
        this.createdAt = createdAt;
        this.status = "pending";
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getSessionId() { return sessionId; }
    public void setSessionId(int sessionId) { this.sessionId = sessionId; }

    public int getRecordId() { return recordId; }
    public void setRecordId(int recordId) { this.recordId = recordId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
