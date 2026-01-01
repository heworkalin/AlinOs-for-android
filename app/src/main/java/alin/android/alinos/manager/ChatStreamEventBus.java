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

        // 快速构建错误消息
        public static StreamEventData buildError(String errorMsg) {
            StreamEventData data = new StreamEventData();
            data.isError = true;
            data.errorMsg = errorMsg;
            data.isFinish = true;
            return data;
        }

        // Getter方法（补全缺失的getter）
        public int getSessionId() { return sessionId; }
        public String getChunkContent() { return chunkContent; }
        public String getFullContent() { return fullContent; }
        public boolean isFinish() { return isFinish; }
        public boolean isError() { return isError; }
        public String getErrorMsg() { return errorMsg; }
    }
}