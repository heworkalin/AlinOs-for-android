package alin.android.alinos.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ConfigDBHelper;

public class ConfigManager {
    private static ConfigManager instance;
    private Context context;
    private ConfigDBHelper configDbHelper;
    private SharedPreferences prefs;
    
    // 配置键名
    private static final String PREF_NAME = "ai_config";
    private static final String KEY_CURRENT_CONFIG_ID = "current_config_id";
    private static final String KEY_CURRENT_SESSION_ID = "current_session_id";
    
    private ConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.configDbHelper = new ConfigDBHelper(this.context);
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    public static synchronized ConfigManager getInstance(Context context) {
        if (instance == null) {
            instance = new ConfigManager(context);
        }
        return instance;
    }
    
    /**
     * 获取当前配置
     */
    public ConfigBean getCurrentConfig() {
        int configId = prefs.getInt(KEY_CURRENT_CONFIG_ID, -1);
        if (configId == -1) {
            // 获取第一个可用配置
            return configDbHelper.getFirstConfig();
        }
        return configDbHelper.getConfigById(configId);
    }
    
    /**
     * 设置当前配置
     */
    public void setCurrentConfig(ConfigBean config) {
        if (config != null) {
            prefs.edit().putInt(KEY_CURRENT_CONFIG_ID, config.getId()).apply();
        }
    }
    
    /**
     * 获取当前会话ID
     */
    public int getCurrentSessionId() {
        return prefs.getInt(KEY_CURRENT_SESSION_ID, -1);
    }
    
    /**
     * 设置当前会话ID
     */
    public void setCurrentSessionId(int sessionId) {
        prefs.edit().putInt(KEY_CURRENT_SESSION_ID, sessionId).apply();
    }
    
    /**
     * 清除会话ID（用于悬浮窗模式）
     */
    public void clearSessionId() {
        prefs.edit().remove(KEY_CURRENT_SESSION_ID).apply();
    }
}