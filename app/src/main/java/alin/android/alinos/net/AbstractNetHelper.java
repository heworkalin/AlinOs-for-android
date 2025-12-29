package alin.android.alinos.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import alin.android.alinos.bean.ConfigBean;

// 这是一个抽象类，实现部分公共功能
public abstract class AbstractNetHelper implements BaseNetHelper {
    protected Context context;
    protected ConfigBean config;
    protected Handler mainHandler;
    
    public AbstractNetHelper(Context context, ConfigBean config) {
        this.context = context;
        this.config = config;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    @Override
    public void setContextAndConfig(Context context, ConfigBean config) {
        this.context = context;
        this.config = config;
    }
    
    @Override
    public abstract String getServiceType();
    
    @Override
    public abstract boolean validateConfig();
    
    @Override
    public abstract String sendMessage(String message);
    
    protected void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}