package alin.android.alinos.tools;

/**
 * 工具调用卡片 UI 更新回调。
 * ToolCallCoordinator 在执行工具后通过此回调通知 ChatActivity 更新对应卡片的显示内容。
 * 递归工具调用时，通过 onNewPlaceholder 动态创建新的占位消息。
 */
public interface ToolCallCardCallback {
    /**
     * 工具执行结果回调（更新已有卡片）。
     *
     * @param messageIndex 工具在当前批次 tool_calls 中的索引（从 0 开始），
     *                     对应 ChatActivity 中 capturedStartPos + messageIndex 的位置
     * @param cardJson     卡片 JSON 内容（含 toolName/args/request/response/log/status/duration）
     * @param isExecuting  是否仍在执行中
     */
    void onToolCallResult(int messageIndex, String cardJson, boolean isExecuting);

    /**
     * 递归工具调用时，创建新的占位消息（写入 chat_record 标记并添加到 UI）。
     * 由 ToolCallCoordinator 在递归循环中调用。
     *
     * @param toolName  工具名称
     * @param uuid      业务 UUID（用于与 tool_call_log 关联）
     * @return 新占位消息的索引（相对于当前批次起始位置）
     */
    int onNewPlaceholder(String toolName, String uuid);
}
