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
import alin.android.alinos.net.BaseNetHelper;
import alin.android.alinos.net.NetHelperFactory;
import alin.android.alinos.net.OpenAINetHelper;
import alin.android.alinos.net.OpenAIStreamNetHelper;
import alin.android.alinos.utils.TokenEstimator;

/**
 * 统一的提示词服务，封装所有发送和接收逻辑。
 * 原有的 ChatActivity 发送/接收功能迁移到此服务，通过回调处理 UI 更新。
 */
public class PromptService {
    private static final String TAG = "PromptService";

    private Context mContext;
    private OpenAIStreamNetHelper mStreamNetHelper; // 用于流式请求，支持取消

    public PromptService(Context context) {
        this.mContext = context;
    }

    /**
     * 同步发送消息（阻塞调用，适用于普通请求）
     * @param config AI 配置
     * @param userInput 用户输入
     * @return AI 回复内容，如果出错返回错误信息（以 [错误] 开头）
     */
    public String sendMessageSync(ConfigBean config, String userInput) {
        if (config == null) {
            return "[错误] 未配置 AI 服务";
        }

        // 检查是否有网络助手
        BaseNetHelper netHelper = null;
        try {
            netHelper = NetHelperFactory.createNetHelper(mContext, config);
        } catch (Exception e) {
            return "[错误] 创建网络助手失败: " + e.getMessage();
        }

        if (netHelper == null) {
            return "[错误] 不支持的 AI 服务类型：" + config.getType();
        }

        try {
            // 调用网络接口
            String response = netHelper.sendMessage(userInput);

            // 检查是否是错误响应
            if (response == null || response.trim().isEmpty()) {
                return "[错误] AI 返回空响应";
            }

            // 检查错误格式
            if (response.startsWith("[错误]") || response.startsWith("[配置错误]")) {
                return response;
            }

            return response;
        } catch (Exception e) {
            e.printStackTrace();
            return "[错误] AI 服务异常：" + e.getMessage();
        }
    }

    /**
     * 异步发送消息（非阻塞，通过回调返回结果）
     * @param config AI 配置
     * @param userInput 用户输入
     * @param callback 结果回调
     */
    public void sendMessageAsync(ConfigBean config, String userInput, final Callback callback) {
        new Thread(() -> {
            String result = sendMessageSync(config, userInput);
            if (callback != null) {
                callback.onResult(result);
            }
        }).start();
    }

    /**
     * 发送流式消息（长按发送触发）
     * @param config AI 配置（注意：流式需要将 type 替换为 openai_stream）
     * @param sessionId 会话 ID，用于回调中标识
     * @param userInput 用户输入
     * @param listener 流式事件监听器
     */
    public void sendStreamMessage(ConfigBean config, int sessionId, String userInput,
                                  ChatStreamEventBus.StreamEventListener listener) {
        // 校验配置
        if (config == null) {
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("配置为空"));
            }
            return;
        }

        // 流式配置转换：将 openai 替换为 openai_stream
        String originType = config.getType().toLowerCase();
        String targetType = originType.replaceAll("^openai$", "openai_stream");
        ConfigBean streamConfig = new ConfigBean(config);
        streamConfig.setType(targetType);

        // 校验：处理替换后的 type，兼容原有逻辑
        if (!"openai_stream".equalsIgnoreCase(streamConfig.getType())) {
            String errorMsg = "仅支持 type=openai/openai_stream 的配置，当前 type：" + originType;
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError(errorMsg));
            }
            return;
        }

        // 构建包含历史上下文的messages
        JSONArray messages;
        try {
            messages = buildOpenAIMessages(sessionId, userInput, null); // 使用null作为系统提示词
        } catch (Exception e) {
            Log.e(TAG, "构建消息失败", e);
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("构建消息失败: " + e.getMessage()));
            }
            return;
        }

        // 检查Token限制（使用原始配置的上下文窗口）
        if (isExceedingContextWindow(config, messages, userInput)) {
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("消息长度超出模型上下文窗口限制，请缩短消息或清除部分历史记录"));
            }
            return;
        }

        // 创建流式网络助手
        mStreamNetHelper = (OpenAIStreamNetHelper) NetHelperFactory.createNetHelper(mContext, streamConfig);
        if (mStreamNetHelper == null) {
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("不支持 OpenAI 流式请求"));
            }
            return;
        }

        // 发起流式请求（使用预构建的messages）
        mStreamNetHelper.sendStreamMessageWithMessages(sessionId, messages, listener);
    }

    /**
     * 取消当前的流式请求
     */
    public void cancelStream() {
        if (mStreamNetHelper != null) {
            mStreamNetHelper.cancelStream();
            mStreamNetHelper = null;
        }
    }

    // ==============================================
    // 新增：Token估算和消息构建功能
    // ==============================================

    /**
     * 从数据库获取指定会话的历史消息
     * @param sessionId 会话ID
     * @return 按时间排序的历史消息列表（最近的消息在最后）
     */
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

    /**
     * 构建符合OpenAI标准的messages数组
     * 格式：system + 最近10条历史消息（user/assistant交替） + 当前用户消息
     * @param sessionId 会话ID（用于获取历史消息）
     * @param currentUserMessage 当前用户消息
     * @param systemPrompt 系统提示词（可为空，使用默认值）
     * @return JSONArray格式的messages
     */
    public JSONArray buildOpenAIMessages(int sessionId, String currentUserMessage, String systemPrompt) {
        JSONArray messages = new JSONArray();

        // 1. 添加system消息
        String systemContent = systemPrompt != null ? systemPrompt : "你是一个AI助手，回答简洁专业。";
        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemContent);
            messages.put(systemMsg);
        } catch (Exception e) {
            Log.e(TAG, "构建system消息失败", e);
        }

        // 2. 获取历史消息
        List<ChatRecordBean> history = getChatHistory(sessionId);

        // 3. 过滤并转换历史消息为OpenAI格式
        List<JSONObject> historyMessages = new ArrayList<>();
        for (ChatRecordBean record : history) {
            try {
                JSONObject msg = new JSONObject();

                // 根据msgType和sender确定角色
                String role = mapToOpenAIRole(record.getMsgType(), record.getSender());
                if (role == null) {
                    continue; // 跳过无法映射的消息
                }

                msg.put("role", role);
                msg.put("content", record.getContent());
                historyMessages.add(msg);
            } catch (Exception e) {
                Log.e(TAG, "转换历史消息失败: " + record.getId(), e);
            }
        }

        // 4. 只保留最近10条历史消息（5轮对话）
        // 注意：历史消息已经是按时间升序排列的（getRecordsBySessionId返回ASC）
        int startIndex = Math.max(0, historyMessages.size() - 10); // 最多10条
        for (int i = startIndex; i < historyMessages.size(); i++) {
            messages.put(historyMessages.get(i));
        }

        // 5. 添加当前用户消息
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

    /**
     * 将本地消息类型映射为OpenAI角色
     * @param msgType 消息类型（0:用户，1:AI）
     * @param sender 发送者标识
     * @return "user" 或 "assistant"，无法映射时返回null
     */
    private String mapToOpenAIRole(int msgType, String sender) {
        // 根据ChatActivity中的逻辑：
        // msgType 0 + sender "我" -> user
        // msgType 1 + sender (模型类型) -> assistant
        if (msgType == 0) {
            return "user";
        } else if (msgType == 1) {
            return "assistant";
        }

        Log.w(TAG, "无法映射的消息类型: msgType=" + msgType + ", sender=" + sender);
        return null;
    }

    /**
     * 估算messages数组的总Token数
     * @param messages JSONArray格式的messages
     * @return 估算的Token总数
     */
    public int estimateMessagesTokens(JSONArray messages) {
        if (messages == null || messages.length() == 0) {
            return 0;
        }

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

    /**
     * 估算文本的Token数（使用TokenEstimator工具类）
     * @param text 输入文本
     * @return 估算的Token数
     */
    public int estimateTextTokens(String text) {
        return TokenEstimator.estimateTokens(text);
    }

    /**
     * 检查消息是否超出上下文窗口限制
     * @param config AI配置
     * @param messages JSONArray格式的messages
     * @param currentUserMessage 当前用户消息（用于单独估算）
     * @return 是否超出限制，true表示超出
     */
    public boolean isExceedingContextWindow(ConfigBean config, JSONArray messages, String currentUserMessage) {
        if (config == null || messages == null) {
            return false;
        }

        // 计算总Token数
        int totalTokens = estimateMessagesTokens(messages);

        // 获取配置的上下文窗口大小
        int contextWindow = config.getModelContextWindow();
        if (contextWindow <= 0) {
            contextWindow = 4096; // 默认值
        }

        // 检查是否超出
        boolean isExceeding = totalTokens > contextWindow;

        if (isExceeding) {
            Log.w(TAG, "消息超出上下文窗口限制: " + totalTokens + " > " + contextWindow + " tokens");
        } else {
            Log.d(TAG, "消息Token估算: " + totalTokens + " / " + contextWindow + " tokens");
        }

        return isExceeding;
    }

    /**
     * 内部方法：同步发送消息并返回包含Token信息的AIResponse
     */
    private AIResponse sendMessageWithHistorySyncInternal(ConfigBean config, int sessionId, String userInput, String systemPrompt) {
        if (config == null) {
            return new AIResponse("[错误] 未配置 AI 服务");
        }

        // 构建包含历史上下文的messages
        JSONArray messages;
        try {
            messages = buildOpenAIMessages(sessionId, userInput, systemPrompt);
        } catch (Exception e) {
            Log.e(TAG, "构建消息失败", e);
            return new AIResponse("[错误] 构建消息失败: " + e.getMessage());
        }

        // 检查Token限制
        if (isExceedingContextWindow(config, messages, userInput)) {
            return new AIResponse("[错误] 消息长度超出模型上下文窗口限制，请缩短消息或清除部分历史记录");
        }

        // 检查是否有网络助手
        BaseNetHelper netHelper = null;
        try {
            netHelper = NetHelperFactory.createNetHelper(mContext, config);
        } catch (Exception e) {
            return new AIResponse("[错误] 创建网络助手失败: " + e.getMessage());
        }

        if (netHelper == null) {
            return new AIResponse("[错误] 不支持的 AI 服务类型：" + config.getType());
        }

        // 根据网络助手类型选择调用方式
        try {
            String response;
            int promptTokens = 0;
            int completionTokens = 0;
            int totalTokens = 0;

            // 如果是OpenAINetHelper，并且支持sendMessageWithMessages方法，则使用预构建的messages
            if (netHelper instanceof OpenAINetHelper) {
                OpenAINetHelper openAiHelper = (OpenAINetHelper) netHelper;
                OpenAINetHelper.OpenAIResponse openaiResponse = openAiHelper.sendMessageWithMessages(messages);
                response = openaiResponse.getContent();
                if (openaiResponse.hasUsage()) {
                    promptTokens = openaiResponse.getPromptTokens();
                    completionTokens = openaiResponse.getCompletionTokens();
                    totalTokens = openaiResponse.getTotalTokens();
                }
            } else {
                // 其他类型的助手，回退到原有逻辑（只发送当前消息）
                response = netHelper.sendMessage(userInput);
            }

            // 检查是否是错误响应
            if (response == null || response.trim().isEmpty()) {
                return new AIResponse("[错误] AI 返回空响应");
            }

            // 检查错误格式
            if (response.startsWith("[错误]") || response.startsWith("[配置错误]")) {
                return new AIResponse(response);
            }

            if (promptTokens > 0 || completionTokens > 0 || totalTokens > 0) {
                return new AIResponse(response, promptTokens, completionTokens, totalTokens);
            } else {
                return new AIResponse(response);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new AIResponse("[错误] AI 服务异常：" + e.getMessage());
        }
    }

    /**
     * 同步发送消息（增强版，支持历史上下文）
     * @param config AI配置
     * @param sessionId 会话ID（用于获取历史消息）
     * @param userInput 用户输入
     * @param systemPrompt 系统提示词（可为空）
     * @return AI回复内容
     */
    public String sendMessageWithHistorySync(ConfigBean config, int sessionId, String userInput,
                                             String systemPrompt) {
        AIResponse aiResponse = sendMessageWithHistorySyncInternal(config, sessionId, userInput, systemPrompt);
        return aiResponse.getContent();
    }


    /**
     * 异步发送消息（增强版，支持历史上下文）
     * @param config AI配置
     * @param sessionId 会话ID（用于获取历史消息）
     * @param userInput 用户输入
     * @param systemPrompt 系统提示词（可为空）
     * @param callback 结果回调
     */
    public void sendMessageWithHistoryAsync(ConfigBean config, int sessionId, String userInput,
                                           String systemPrompt, final Callback callback) {
        new Thread(() -> {
            AIResponse aiResponse = sendMessageWithHistorySyncInternal(config, sessionId, userInput, systemPrompt);
            if (callback != null) {
                String result = aiResponse.getContent();
                if (aiResponse.hasUsage()) {
                    callback.onResultWithTokens(result, aiResponse.getPromptTokens(),
                                                aiResponse.getCompletionTokens(), aiResponse.getTotalTokens());
                } else {
                    callback.onResult(result);
                }
            }
        }).start();
    }

    /**
     * AI响应包装类，包含内容和Token信息
     */
    public static class AIResponse {
        private String content;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private boolean hasUsage;

        public AIResponse(String content) {
            this.content = content;
            this.hasUsage = false;
        }

        public AIResponse(String content, int promptTokens, int completionTokens, int totalTokens) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.hasUsage = true;
        }

        public String getContent() { return content; }
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
        public boolean hasUsage() { return hasUsage; }
    }

    /**
     * 异步回调接口
     */
    public interface Callback {
        void onResult(String result);

        /**
         * 新增：带Token使用情况的回调
         * @param result AI回复内容
         * @param promptTokens 提示词token数
         * @param completionTokens 补全token数
         * @param totalTokens 总token数
         */
        default void onResultWithTokens(String result, int promptTokens, int completionTokens, int totalTokens) {
            // 默认实现，调用原有方法保持兼容
            onResult(result);
        }
    }
}