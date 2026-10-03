package alin.android.alinos.manager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 聊天专属流式EventBus（补全缺失类）
 * 用于流式消息通信，和原有悬浮窗EventBus隔离
 */
public class ChatStreamEventBus {
    private static volatile ChatStreamEventBus instance;
    private final Map<String, CopyOnWriteArrayList<StreamEventListener>> listenerMap;

    private ChatStreamEventBus() {
        listenerMap = new HashMap<>();
    }

    // 单例获取
    public static ChatStreamEventBus getInstance() {
        if (instance == null) {
            synchronized (ChatStreamEventBus.class) {
                if (instance == null) {
                    instance = new ChatStreamEventBus();
                }
            }
        }
        return instance;
    }

    // 注册监听器
    public void register(String eventType, StreamEventListener listener) {
        listenerMap.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    // 解注册
    public void unregister(String eventType, StreamEventListener listener) {
        CopyOnWriteArrayList<StreamEventListener> listeners = listenerMap.get(eventType);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    // 发送事件
    public void post(String eventType, StreamEventData data) {
        CopyOnWriteArrayList<StreamEventListener> listeners = listenerMap.get(eventType);
        if (listeners != null) {
            for (StreamEventListener listener : listeners) {
                listener.onStreamEvent(eventType, data);
            }
        }
    }

    // 事件监听器接口
    public interface StreamEventListener {
        void onStreamEvent(String eventType, StreamEventData data);
    }

    // 流式事件数据载体（补全缺失的内部类）
    public static class StreamEventData {
        private int sessionId;
        private String chunkContent;
        private String fullContent;
        private boolean isFinish;
        private boolean isError;
        private String errorMsg;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private String toolCallsJson; // 工具调用数据（JSON数组）
        private boolean thinkFinish; // 标记仅为 think 完成事件（后续可能还有 tool_calls）
        private boolean isUsage;     // 标记这是用量/费用事件
        private boolean toolChainDone; // 标记：工具链真正结束（最后一轮 AI 文本完成）
        private double costTotal;    // 本次请求费用（美元）
        private String usageJson;    // 完整用量 JSON（含 cost 明细）

        // 快速构建增量消息
        public static StreamEventData buildChunk(int sessionId, String chunkContent) {
            StreamEventData data = new StreamEventData();
            data.sessionId = sessionId;
            data.chunkContent = chunkContent;
            data.isFinish = false;
            data.isError = false;
            return data;
        }

        // 快速构建结束消息
        public static StreamEventData buildFinish(int sessionId, String fullContent) {
            StreamEventData data = new StreamEventData();
            data.sessionId = sessionId;
            data.fullContent = fullContent;
            data.isFinish = true;
            data.isError = false;
            return data;
        }

        /**
         * 构建「工具链真正结束」的结束消息（最后一轮 AI 文本完成）。
         *
         * <p>与 {@link #buildFinish} 的区别：带 {@code toolChainDone} 标记。
         * ChatActivity 只有在收到该标记时才恢复发送按钮，避免工具链中间轮的
         * finish 提前把按钮恢复为「发送」。
         */
        public static StreamEventData buildToolChainFinish(int sessionId, String fullContent) {
            StreamEventData data = buildFinish(sessionId, fullContent);
            data.toolChainDone = true;
            return data;
        }

        // 快速构建错误消息
        public static StreamEventData buildError(String errorMsg) {
            StreamEventData data = new StreamEventData();
            data.isError = true;
            data.errorMsg = errorMsg;
            data.isFinish = true;
            return data;
        }

        // 快速构建Usage消息
        public static StreamEventData buildUsage(int sessionId, int promptTokens, int completionTokens, int totalTokens) {
            return buildUsage(sessionId, promptTokens, completionTokens, totalTokens, 0, null);
        }

        // 快速构建Usage消息（含费用）
        public static StreamEventData buildUsage(int sessionId, int promptTokens, int completionTokens,
                                                 int totalTokens, double costTotal, String usageJson) {
            StreamEventData data = new StreamEventData();
            data.sessionId = sessionId;
            data.promptTokens = promptTokens;
            data.completionTokens = completionTokens;
            data.totalTokens = totalTokens;
            data.costTotal = costTotal;
            data.usageJson = usageJson;
            data.isUsage = true;
            data.isFinish = false;
            data.isError = false;
            return data;
        }

        // 快速构建工具调用消息（流式结束，触发执行循环）
        public static StreamEventData buildToolCalls(int sessionId, String toolCallsJson) {
            StreamEventData data = new StreamEventData();
            data.sessionId = sessionId;
            data.toolCallsJson = toolCallsJson;
            data.isFinish = true;
            data.isError = false;
            return data;
        }

        // 快速构建Think块消息
        public static StreamEventData buildThinkChunk(int sessionId, String thinkContent) {
            StreamEventData data = new StreamEventData();
            data.sessionId = sessionId;
            data.chunkContent = thinkContent;
            data.isFinish = false;
            data.isError = false;
            return data;
        }

        // 快速构建Think块结束消息（注意：thinkFinish=true 标记这不是最终事件，后续可能还有 tool_calls）
        public static StreamEventData buildThinkFinish(int sessionId, String fullThinkContent) {
            StreamEventData data = new StreamEventData();
            data.sessionId = sessionId;
            data.fullContent = fullThinkContent;
            data.isFinish = true;
            data.isError = false;
            data.thinkFinish = true;
            return data;
        }

        // Getter方法（补全缺失的getter）
        public int getSessionId() { return sessionId; }
        public String getChunkContent() { return chunkContent; }
        public String getFullContent() { return fullContent; }
        public boolean isFinish() { return isFinish; }
        public boolean isError() { return isError; }
        public String getErrorMsg() { return errorMsg; }
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
        public String getToolCallsJson() { return toolCallsJson; }
        public boolean isThinkFinish() { return thinkFinish; }
        public boolean isUsage() { return isUsage; }
        public boolean isToolChainDone() { return toolChainDone; }
        public double getCostTotal() { return costTotal; }
        public String getUsageJson() { return usageJson; }
    }
}