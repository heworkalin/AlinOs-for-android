package alin.android.alinos.service;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

// 必须继承 AccessibilityService
public class DeviceAccessibilityService extends AccessibilityService {
    private static final String TAG = "DeviceAccessService";

    // 服务连接成功时调用（服务启动后执行）
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "无障碍服务已成功连接");
        // 后续可添加业务逻辑（如拦截电源键、模拟手势等）
    }

    // 接收系统无障碍事件（如界面变化、按钮点击等）
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 暂为空实现，后续可根据需求处理事件
        Log.d(TAG, "收到无障碍事件：" + event.getEventType());
    }

    // 服务被中断时调用（如系统强制停止服务）
    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务被中断");
    }
}