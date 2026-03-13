package alin.android.alinos.net;

import android.content.Context;
import android.util.Log;

import alin.android.alinos.bean.ConfigBean;

public class NetHelperFactory {
    private static final String TAG = "NetHelperFactory";
    
    /**
     * 原有同步方法（完全保留，一行不改）
     */
    public static BaseNetHelper createNetHelper(Context context, ConfigBean config) {
        if (config == null || config.getType() == null) {
            Log.w(TAG, "创建同步助手失败：配置为空或type未指定");
            return null;
        }
        
        String type = config.getType().toLowerCase();
        Log.d(TAG, "创建同步助手，type：" + type);
        
        switch (type) {
            case "openai":
                return new OpenAINetHelper(context, config);
            case "openai_stream":
                return new OpenAIStreamNetHelper(context, config);
            case "ollama":
                return new OllamaNetHelper(context, config);
            case "ollama_stream":
                Log.w(TAG, "待实现");
                return null;
            default:
                Log.w(TAG, "不支持的同步助手类型：" + type);
                return null;
        }
    }

}