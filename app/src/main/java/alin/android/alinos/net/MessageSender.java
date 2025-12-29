package alin.android.alinos.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;

import alin.android.alinos.bean.ChatRecordBean;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ChatDBHelper;
import alin.android.alinos.db.ConfigDBHelper;

import alin.android.alinos.manager.EventBus;


public class MessageSender {
    private static MessageSender instance;
    private Context context;
    private ChatDBHelper chatDbHelper;
    private ConfigDBHelper configDbHelper;
    
     // 事件类型
    public static final String EVENT_MESSAGE_ADDED = "message_added";
    public static final String EVENT_AI_RESPONSE_RECEIVED = "ai_response_received";
    

    private MessageSender(Context context) {
        this.context = context.getApplicationContext();
        this.chatDbHelper = new ChatDBHelper(this.context);
        this.configDbHelper = new ConfigDBHelper(this.context);
    }
    
    public static synchronized MessageSender getInstance(Context context) {
        if (instance == null) {
            instance = new MessageSender(context);
        }
        return instance;
    }
    
    /**
     * 发送消息（统一入口）- 参照ChatActivity的逻辑
     * @param sessionId 会话ID
     * @param content 消息内容
     * @param listener 回调监听器
     */
    public void sendMessage(int sessionId, String content, OnMessageListener listener) {
        if (TextUtils.isEmpty(content)) {
            if (listener != null) listener.onError("消息内容不能为空");
            return;
        }
        
        // 获取当前配置 - 优先使用默认配置
        ConfigBean config = getCurrentConfig();
        if (config == null) {
            if (listener != null) listener.onError("请先配置AI服务");
            return;
        }
        
        // 1. 保存用户消息 - 参照ChatActivity
        ChatRecordBean userRecord = new ChatRecordBean(
                sessionId,
                0,
                " ",  // 统一使用"我"
                content,
                System.currentTimeMillis()
        );
        long recordId = chatDbHelper.addRecord(userRecord);
        
        if (recordId == -1) {
            if (listener != null) listener.onError("保存用户消息失败");
            return;
        }else {
            ByteArrayOutputStream aiResponse = new ByteArrayOutputStream();
            EventMessage event = new EventMessage(sessionId, aiResponse.toString(), recordId);
           EventBus.getInstance().post(EVENT_AI_RESPONSE_RECEIVED, event);
            }
        
        if (listener != null) {
            listener.onMessageSent(content);
        }
        
        // 2. 发送到AI
        sendToAI(sessionId, content, config, listener);
    }
    
    /**
     * 获取当前配置 - 优先获取默认配置
     */
    private ConfigBean getCurrentConfig() {
        // 先尝试获取默认配置
        ConfigBean defaultConfig = configDbHelper.getDefaultConfig();
        if (defaultConfig != null) {
            return defaultConfig;
        }
        
        // 如果没有默认配置，获取第一个配置
        return configDbHelper.getFirstConfig();
    }
    
    /**
     * 发送到AI - 统一AI调用逻辑
     */
    private void sendToAI(int sessionId, String content, ConfigBean config, OnMessageListener listener) {
        new Thread(() -> {
            try {
                // 使用ChatActivity的生成AI回复逻辑
                String aiReply = generateAIResponse(content, config);
                
                runOnUiThread(() -> {
                    if (TextUtils.isEmpty(aiReply)) {
                        if (listener != null) {
                            listener.onError("AI返回空响应");
                        }
                    } else if (aiReply.startsWith("[错误]") || aiReply.startsWith("[配置错误]")) {
                        if (listener != null) {
                            listener.onError(aiReply);
                        }
                    } else {
                        // 保存AI回复
                        ChatRecordBean aiRecord = new ChatRecordBean(
                                sessionId,
                                1,
                                config.getType(),
                                aiReply,
                                System.currentTimeMillis()
                        );
                        long recordId = chatDbHelper.addRecord(aiRecord);
                        
                        // 发送事件通知消息已保存
                        if (recordId > 0) {
                            EventMessage event = new EventMessage(sessionId, aiReply, recordId);
                            EventBus.getInstance().post(EVENT_AI_RESPONSE_RECEIVED, event);
                        }
                        if (listener != null) {
                            listener.onAIResponse(aiReply);
                        }
                    }
                });
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (listener != null) {
                        listener.onError("AI服务异常：" + e.getMessage());
                    }
                });
            }
        }).start();
    }
    
    public static class EventMessage {
        private int sessionId;
        private String content;
        private long recordId;
        
        public EventMessage(int sessionId, String content, long recordId) {
            this.sessionId = sessionId;
            this.content = content;
            this.recordId = recordId;
        }
        // 修正后的构造方法（复用 content 字段，不新增变量）
        public EventMessage(int sessionId, String aiResponse) {
            this.sessionId = sessionId;
            this.content = aiResponse; // 用 content 存储 AI 回复，避免新增变量
            this.recordId = -1; // 无 recordId 时设为默认值，不影响逻辑
        }

        public int getSessionId() { return sessionId; }
        public String getContent() { return content; }
        public long getRecordId() { return recordId; }
    }
    /**
     * 生成AI回复 - 参照ChatActivity的generateAIResponse方法
     */
    private String generateAIResponse(String userInput, ConfigBean config) {
        if (config == null) {
            return "[错误] 未配置AI服务";
        }
        
        try {
            // 使用NetHelperFactory创建网络助手
            BaseNetHelper netHelper = NetHelperFactory.createNetHelper(context, config);
            if (netHelper == null) {
                return "[错误] 不支持的AI服务类型：" + config.getType();
            }
            
            // 调用网络接口
            String response = netHelper.sendMessage(userInput);
            
            // 检查是否是错误响应
            if (response == null || response.trim().isEmpty()) {
                return "[错误] AI返回空响应";
            }
            
            // 检查错误格式
            if (response.startsWith("[错误]") || response.startsWith("[配置错误]")) {
                return response;
            }
            
            return response;
            
        } catch (Exception e) {
            return "[错误] AI服务异常：" + e.getMessage();
        }
    }
    
    private void runOnUiThread(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }
    
    public interface OnMessageListener {
        void onMessageSent(String content);
        void onAIResponse(String content);
        void onError(String error);
    }
}