package alin.android.alinos.tools;

/**
 * 工具调用卡片 UI 更新回调。
 * ToolCallCoordinator 在执行工具后通过此回调通知 ChatActivity 更新对应卡片的显示内容。
 */
public interface ToolCallCardCallback {
    /**
     * @param messageIndex 工具在 tool_calls 数组中的索引（从 0 开始）
     * @param cardJson     卡片 JSON 内容（含 toolName/args/request/response/log/status/duration）
     * @param isExecuting  是否仍在执行中
     */
    void onToolCallResult(int messageIndex, String cardJson, boolean isExecuting);
}
