package alin.android.alinos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import alin.android.alinos.localshell.LocalShellExecutor;
import alin.android.alinos.mcp.McpServerManager;
import alin.android.alinos.tools.ToolRegistry;

/**
 * MCP 工具服务（前台服务）。
 *
 * 以常驻通知保活 MCP Server：
 *  - 后台运行时不被系统回收，保证局域网/本机 MCP 客户端持续可连
 *  - 通知点击回到 {@link McpServerActivity}
 */
public class McpServerService extends Service {

    private static final String CHANNEL_ID = "mcp_server";
    private static final int NOTIFICATION_ID = 20250618;

    /** 端口保存键（与 McpServerActivity 共用）。 */
    public static final String PREF_NAME = "mcp_server_prefs";
    public static final String KEY_PORT = "port";
    public static final int DEFAULT_PORT = 8765;

    @Override
    public void onCreate() {
        super.onCreate();
        setupNotificationChannel();
        Notification n = buildNotification();
        if (n != null) {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        }
        // 注册 SSH 工具集（ToolRegistry.init 幂等，重复调用安全）
        ToolRegistry.init(this);
        // 初始化本地 Shell 执行器上下文（必须！否则 localshell/ssh 工具报 NOT_INITIALIZED）
        LocalShellExecutor.provideContext(this);

        // 从配置读取端口并启动服务
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int port = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        try {
            McpServerManager.getInstance().start(port);
        } catch (Exception e) {
            McpServerManager.getInstance().stop();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 被系统杀死后自动重建
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        McpServerManager.getInstance().stop();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    // ==================== 通知 ====================

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "MCP 工具服务", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("MCP Server 前台服务，保证本地工具服务持续运行");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification() {
        Intent tapIntent = new Intent(this, McpServerActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int icon = R.drawable.ic_foreground;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(icon)
                .setContentTitle("MCP 工具服务运行中")
                .setContentText("本地工具已透传为 MCP Server，点击管理")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    /** 便捷启动入口。 */
    public static void start(Context context) {
        Intent intent = new Intent(context, McpServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, McpServerService.class));
    }
}
