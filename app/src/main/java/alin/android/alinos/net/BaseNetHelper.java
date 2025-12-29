package alin.android.alinos.net;

import android.content.Context;

import alin.android.alinos.bean.ConfigBean;

// 这是一个接口，不是抽象类
public interface BaseNetHelper {
    /**
     * 发送消息到AI服务
     */
    String sendMessage(String message);
    
    /**
     * 获取服务类型
     */
    String getServiceType();
    
    /**
     * 验证配置是否有效
     */
    boolean validateConfig();
    
    /**
     * 设置上下文和配置（如果需要）
     */
    void setContextAndConfig(Context context, ConfigBean config);
}