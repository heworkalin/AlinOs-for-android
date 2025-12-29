package alin.android.alinos.net;

import android.content.Context;

import alin.android.alinos.bean.ConfigBean;

public class NetHelperFactory {
    
    /**
     * 根据配置创建相应的网络助手
     */
    public static BaseNetHelper createNetHelper(Context context, ConfigBean config) {
        if (config == null || config.getType() == null) {
            return null;
        }
        
        String type = config.getType().toLowerCase();
        
        switch (type) {
            case "openai":
                return new OpenAINetHelper(context, config);
            case "ollama":
                // 稍后实现Ollama支持
                // return new OllamaNetHelper(context, config);
                return null;
            default:
                return null;
        }
    }
}