package alin.android.alinos.mcp;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * MCP 服务管理器（单例）。
 *
 * 统一管理 {@link McpHttpServer} 生命周期，向 UI 层回调日志与状态变化，
 * 使 Activity / 前台 Service 都能控制同一份服务实例。
 */
public class McpServerManager {

    /** 状态/日志监听器。 */
    public interface Listener {
        void onLog(String line);
        void onStatusChange(boolean running);
    }

    private static final McpServerManager INSTANCE = new McpServerManager();

    private final List<Listener> listeners = new ArrayList<>();
    private McpHttpServer server;
    private int currentPort;

    private McpServerManager() {}

    public static McpServerManager getInstance() {
        return INSTANCE;
    }

    /** 注册监听器。 */
    public synchronized void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public synchronized void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 启动服务。端口被占用时抛出异常。 */
    public synchronized void start(int port) throws IOException {
        stop();
        currentPort = port;
        server = new McpHttpServer(port, new McpHttpServer.Listener() {
            @Override
            public void onLog(String line) {
                dispatchLog(line);
            }

            @Override
            public void onStatusChange(boolean running) {
                dispatchStatus(running);
            }
        });
        server.start();
    }

    /** 停止服务。 */
    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    public int getCurrentPort() {
        return currentPort;
    }

    // ==================== 日志缓冲（供 UI 查询历史） ====================

    private static final int MAX_LOG_LINES = 300;
    private final List<String> logBuffer = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private void dispatchLog(String line) {
        String ts = "[" + timeFmt.format(new Date()) + "] " + line;
        synchronized (logBuffer) {
            logBuffer.add(ts);
            if (logBuffer.size() > MAX_LOG_LINES) {
                logBuffer.remove(0);
            }
        }
        synchronized (listeners) {
            for (Listener l : listeners) {
                try {
                    l.onLog(ts);
                } catch (Exception ignored) {}
            }
        }
    }

    private void dispatchStatus(boolean running) {
        synchronized (listeners) {
            for (Listener l : listeners) {
                try {
                    l.onStatusChange(running);
                } catch (Exception ignored) {}
            }
        }
    }

    /** 获取历史日志（供界面重建时恢复）。 */
    public List<String> getLogBuffer() {
        synchronized (logBuffer) {
            return new ArrayList<>(logBuffer);
        }
    }
}
