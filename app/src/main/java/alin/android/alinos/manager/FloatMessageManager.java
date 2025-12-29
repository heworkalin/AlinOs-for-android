package alin.android.alinos.manager;

import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import alin.android.alinos.bean.ChatRecordBean;
import alin.android.alinos.bean.ChatSessionBean;
import alin.android.alinos.db.ChatDBHelper;
import alin.android.alinos.net.MessageSender; // 确保有这个import

public class FloatMessageManager {
    private static FloatMessageManager instance;
    private Context context;
    private ChatDBHelper chatDbHelper;
    private ChatSessionBean floatSession;
    private MessageSender messageSender;

    public interface OnMessageListener {
        void onMessageSent(String content);
        void onAIResponse(String content);
        void onError(String error);
    }
    
    private FloatMessageManager(Context context) {
        this.context = context.getApplicationContext();
        this.chatDbHelper = new ChatDBHelper(this.context);
        this.messageSender = MessageSender.getInstance(this.context);
        
        // 获取或创建悬浮窗默认会话
        this.floatSession = chatDbHelper.getOrCreateFloatWindowSession();
    }
    
    public static synchronized FloatMessageManager getInstance(Context context) {
        if (instance == null) {
            instance = new FloatMessageManager(context);
        }
        return instance;
    }
    
    /**
     * 发送消息
     */
    public void sendMessage(String content, OnMessageListener listener) {
        messageSender.sendMessage(floatSession.getId(), content, new MessageSender.OnMessageListener() {
            @Override
            public void onMessageSent(String content) {
                if (listener != null) listener.onMessageSent(content);
            }
            
            @Override
            public void onAIResponse(String content) {
                if (listener != null) listener.onAIResponse(content);
            }
            
            @Override
            public void onError(String error) {
                if (listener != null) listener.onError(error);
            }
        });
    }
    
    /**
     * 获取悬浮窗会话
     */
    public ChatSessionBean getFloatSession() {
        return floatSession;
    }
}