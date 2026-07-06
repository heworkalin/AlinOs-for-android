package alin.android.alinos.prompt;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.bean.ChatRecordBean;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ChatDBHelper;
import alin.android.alinos.manager.ChatStreamEventBus;
import alin.android.alinos.net.OpenAIStreamNetHelper;
import alin.android.alinos.tools.ToolConverter;
import alin.android.alinos.tools.ToolRegistry;
import alin.android.alinos.utils.TokenEstimator;

/**
 * 统一的提示词服务 — 纯流式。
 * 所有消息发送都走 OpenAIStreamNetHelper，支持取消。
 */
public class PromptService {
    private static final String TAG = "PromptService";

    private Context mContext;
    private OpenAIStreamNetHelper mStreamNetHelper;

    public PromptService(Context context) {
        this.mContext = context;
    }

    // ================================================================
    //  流式发送（唯一对外发送入口）
    // ================================================================

    /**
     * 发送流式消息。
     *
     * @param config    AI 配置（openai 类型会自动转为 openai_stream）
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @param listener  流式事件监听器
     */
    public void sendStreamMessage(ConfigBean config, int sessionId, String userInput,
                                  ChatStreamEventBus.StreamEventListener listener) {
        if (config == null) {
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("配置为空"));
            }
            return;
        }

        // 构建包含历史上下文的 messages
        JSONArray messages;
        try {
            messages = buildOpenAIMessages(sessionId, userInput, null);
        } catch (Exception e) {
            Log.e(TAG, "构建消息失败", e);
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("构建消息失败: " + e.getMessage()));
            }
            return;
        }

        // 检查 Token 限制
        if (checkContextWindow(config, sessionId, userInput, null)) {
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError(
                                "消息长度超出模型上下文窗口限制，请缩短消息或清除部分历史记录"));
            }
            return;
        }

        // 构建 tools 定义（当前使用测试工具集，后续由 ToolIntentRouter 按需加载）
        JSONArray tools = buildToolsPayload();

        // 直接创建流式助手
        mStreamNetHelper = new OpenAIStreamNetHelper(mContext, config);
        mStreamNetHelper.sendStreamMessageWithMessages(sessionId, messages, tools, listener);
    }

    /**
     * 从 ToolRegistry 加载工具并转为 OpenAI tools 格式。
     * 当前统一加载全部已注册工具（11个localshell + 4个测试），
     * 后续由 ToolIntentRouter 按意图只注入本轮需要的子集。
     */
    private JSONArray buildToolsPayload() {
        try {
            return ToolConverter.convertAll(ToolRegistry.getAllTools());
        } catch (Exception e) {
            Log.w(TAG, "构建tools载荷失败", e);
            return null;
        }
    }

    /**
     * 取消当前的流式请求。
     */
    public void cancelStream() {
        if (mStreamNetHelper != null) {
            mStreamNetHelper.cancelStream();
            mStreamNetHelper = null;
        }
    }

    // ================================================================
    //  消息构建
    // ================================================================

    /**
     * 构建符合 OpenAI 标准的 messages 数组。
     * 格式：system + 最近10条历史 + 当前用户消息
     */
    public JSONArray buildOpenAIMessages(int sessionId, String currentUserMessage, String systemPrompt) {
        JSONArray messages = new JSONArray();

        String systemContent = systemPrompt != null ? systemPrompt : "你是一个AI助手，回答简洁专业。";
        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemContent);
            messages.put(systemMsg);
        } catch (Exception e) {
            Log.e(TAG, "构建system消息失败", e);
        }

        List<ChatRecordBean> recentHistory = getRecentHistory(sessionId);
        for (ChatRecordBean record : recentHistory) {
            try {
                JSONObject msg = new JSONObject();
                String role = mapToOpenAIRole(record.getMsgType(), record.getSender());
                msg.put("role", role);
                msg.put("content", record.getContent());
                messages.put(msg);
            } catch (Exception e) {
                Log.e(TAG, "转换历史消息失败: " + record.getId(), e);
            }
        }

        try {
            JSONObject currentMsg = new JSONObject();
            currentMsg.put("role", "user");
            currentMsg.put("content", currentUserMessage);
            messages.put(currentMsg);
        } catch (Exception e) {
            Log.e(TAG, "构建当前用户消息失败", e);
        }

        return messages;
    }

    // ================================================================
    //  历史消息
    // ================================================================

    public List<ChatRecordBean> getChatHistory(int sessionId) {
        if (sessionId <= 0) {
            Log.w(TAG, "无效的会话ID: " + sessionId);
            return new ArrayList<>();
        }
        try {
            ChatDBHelper dbHelper = new ChatDBHelper(mContext);
            return dbHelper.getRecordsBySessionId(sessionId);
        } catch (Exception e) {
            Log.e(TAG, "获取历史消息失败", e);
            return new ArrayList<>();
        }
    }

    private List<ChatRecordBean> getRecentHistory(int sessionId) {
        List<ChatRecordBean> history = getChatHistory(sessionId);
        List<ChatRecordBean> filtered = new ArrayList<>();
        for (ChatRecordBean record : history) {
            String role = mapToOpenAIRole(record.getMsgType(), record.getSender());
            if (role != null) {
                filtered.add(record);
            }
        }
        int startIndex = Math.max(0, filtered.size() - 10);
        return new ArrayList<>(filtered.subList(startIndex, filtered.size()));
    }

    private String mapToOpenAIRole(int msgType, String sender) {
        if (msgType == 0) {
            return "user";
        } else if (msgType == 1) {
            return "assistant";
        } else if (msgType == 2) {
            // 工具调用占位标记，不需要映射为 OpenAI 角色
            return null;
        }
        Log.w(TAG, "无法映射的消息类型: msgType=" + msgType + ", sender=" + sender);
        return null;
    }

    // ================================================================
    //  Token 估算 & 上下文窗口
    // ================================================================

    public int estimateMessagesTokens(JSONArray messages) {
        if (messages == null || messages.length() == 0) return 0;

        List<String> contents = new ArrayList<>();
        try {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                String content = msg.getString("content");
                if (content != null && !content.isEmpty()) {
                    contents.add(content);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析messages估算Token失败", e);
        }
        return TokenEstimator.estimateMessagesTokens(contents);
    }

    private int calculateTotalTokens(int sessionId, String currentUserMessage, String systemPrompt) {
        String systemContent = systemPrompt != null ? systemPrompt : "你是一个AI助手，回答简洁专业。";
        int systemTokens = TokenEstimator.estimateTokens(systemContent);
        int totalTokens = systemTokens;

        List<ChatRecordBean> recentHistory = getRecentHistory(sessionId);
        int historyTokens = 0;
        for (ChatRecordBean record : recentHistory) {
            historyTokens += record.getEstimatedTokens();
        }
        totalTokens += historyTokens;

        int userMessageTokens = TokenEstimator.estimateTokens(currentUserMessage);
        totalTokens += userMessageTokens;

        Log.d(TAG, "calculateTotalTokens: system=" + systemTokens
                + ", history(" + recentHistory.size() + "条)=" + historyTokens
                + ", user=" + userMessageTokens
                + ", total=" + totalTokens);
        return totalTokens;
    }

    private boolean checkContextWindow(ConfigBean config, int sessionId,
                                       String currentUserMessage, String systemPrompt) {
        if (config == null) return false;
        int totalTokens = calculateTotalTokens(sessionId, currentUserMessage, systemPrompt);
        int contextWindow = config.getModelContextWindow();
        if (contextWindow <= 0) contextWindow = 4096;
        boolean isExceeding = totalTokens > contextWindow;
        if (isExceeding) {
            Log.w(TAG, "消息超出上下文窗口: " + totalTokens + " > " + contextWindow);
        } else {
            Log.d(TAG, "Token估算: " + totalTokens + " / " + contextWindow);
        }
        return isExceeding;
    }

    public int estimateTextTokens(String text) {
        return TokenEstimator.estimateTokens(text);
    }

    public boolean isExceedingContextWindow(ConfigBean config, JSONArray messages, String currentUserMessage) {
        if (config == null || messages == null) return false;
        int totalTokens = estimateMessagesTokens(messages);
        int contextWindow = config.getModelContextWindow();
        if (contextWindow <= 0) contextWindow = 4096;
        boolean isExceeding = totalTokens > contextWindow;
        if (isExceeding) {
            Log.w(TAG, "消息超出上下文窗口: " + totalTokens + " > " + contextWindow);
        } else {
            Log.d(TAG, "消息Token估算: " + totalTokens + " / " + contextWindow);
        }
        return isExceeding;
    }
}
