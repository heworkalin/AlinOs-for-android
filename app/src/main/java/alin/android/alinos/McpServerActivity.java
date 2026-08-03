package alin.android.alinos;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import alin.android.alinos.mcp.McpServerManager;
import alin.android.alinos.tools.ToolRegistry;

/**
 * MCP 服务器管理界面。
 *
 * 功能：
 *  - 显示当前设备 IP 与监听地址
 *  - 端口自定义（默认 8765，保存到 SharedPreferences）
 *  - 一键启动/停止（前台服务 + 常驻通知保活）
 *  - 实时运行日志
 *  - 一键复制 MCP 客户端连接地址
 */
public class McpServerActivity extends AppCompatActivity implements View.OnClickListener {

    private static final int REQ_NOTIFY_PERMISSION = 100;

    private TextView tvStatus;
    private ImageView ivStatusDot;
    private TextView tvIp;
    private EditText etPort;
    private Button btnToggle;
    private TextView tvToolCount;
    private TextView tvLog;
    private TextView tvUrl;
    private Button btnCopy;

    private final McpServerManager manager = McpServerManager.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        setContentView(R.layout.activity_mcp_server);

        // 注册 SSH 工具集（ToolRegistry.init 幂等）
        ToolRegistry.init(this);

        tvStatus = findViewById(R.id.tv_status);
        ivStatusDot = findViewById(R.id.iv_status_dot);
        tvIp = findViewById(R.id.tv_ip);
        etPort = findViewById(R.id.et_port);
        btnToggle = findViewById(R.id.btn_toggle);
        tvToolCount = findViewById(R.id.tv_tool_count);
        tvLog = findViewById(R.id.tv_log);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvUrl = findViewById(R.id.tv_url);
        btnCopy = findViewById(R.id.btn_copy);

        btnToggle.setOnClickListener(this);
        btnCopy.setOnClickListener(this);

        // 恢复端口配置
        SharedPreferences prefs = getSharedPreferences(McpServerService.PREF_NAME, MODE_PRIVATE);
        etPort.setText(String.valueOf(prefs.getInt(McpServerService.KEY_PORT,
                McpServerService.DEFAULT_PORT)));

        // 工具统计
        tvToolCount.setText("已注册 " + ToolRegistry.getAllTools().size() + " 个工具，全部透传为 MCP tools");

        // 恢复历史日志
        StringBuilder sb = new StringBuilder();
        for (String line : manager.getLogBuffer()) {
            sb.append(line).append('\n');
        }
        tvLog.setText(sb.toString());

        // 注册状态/日志监听
        manager.addListener(serverListener);

        refreshIp();
        refreshStatus();
        refreshUrl();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshIp();
        refreshStatus();
        refreshUrl();
    }

    @Override
    protected void onDestroy() {
        manager.removeListener(serverListener);
        super.onDestroy();
    }

    /** 服务状态与日志回调（来自网络线程，需切主线程刷新 UI）。 */
    private final McpServerManager.Listener serverListener = new McpServerManager.Listener() {
        @Override
        public void onLog(String line) {
            runOnUiThread(() -> {
                String old = tvLog.getText().toString();
                tvLog.setText(old + line + "\n");
                // 滚动到底部（文本总高 - 可视高度）
                tvLog.post(() -> {
                    int total = tvLog.getLayout() != null ? tvLog.getLayout().getHeight() : 0;
                    int viewport = tvLog.getHeight();
                    tvLog.scrollTo(0, Math.max(0, total - viewport));
                });
            });
        }

        @Override
        public void onStatusChange(boolean running) {
            runOnUiThread(() -> {
                refreshStatus();
                refreshUrl();
            });
        }
    };

    // ==================== 界面刷新 ====================

    private void refreshStatus() {
        boolean running = manager.isRunning();
        tvStatus.setText(running ? "运行中" : "已停止");
        tvStatus.setTextColor(running ? 0xFF2E7D32 : 0xFFC62828);
        ivStatusDot.setBackgroundResource(running
                ? R.drawable.circle_dot_green : R.drawable.circle_dot_red);
        btnToggle.setText(running ? "停止服务" : "启动服务");
        etPort.setEnabled(!running);
        etPort.setAlpha(running ? 0.5f : 1f);
    }

    private void refreshIp() {
        List<String> ips = getIpv4Addresses();
        if (ips.isEmpty()) {
            tvIp.setText("未获取到网络地址（请检查 WiFi/热点）");
        } else {
            tvIp.setText(TextUtils.join("\n", ips));
        }
    }

    private void refreshUrl() {
        int port = getPortFromPrefs();
        List<String> ips = getIpv4Addresses();
        StringBuilder sb = new StringBuilder("MCP 客户端连接地址：\n");
        if (ips.isEmpty()) {
            sb.append("http://127.0.0.1:").append(port).append("/mcp\n");
        } else {
            for (String ip : ips) {
                sb.append("http://").append(ip).append(":").append(port).append("/mcp\n");
            }
        }
        tvUrl.setText(sb.toString());
    }

    // ==================== 按钮事件 ====================

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_toggle) {
            if (manager.isRunning()) {
                McpServerService.stop(this);
            } else {
                startServer();
            }
        } else if (id == R.id.btn_copy) {
            int port = getPortFromPrefs();
            String url = "http://127.0.0.1:" + port + "/mcp";
            List<String> ips = getIpv4Addresses();
            if (!ips.isEmpty()) {
                url = "http://" + ips.get(0) + ":" + port + "/mcp";
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("MCP 地址", url));
            Toast.makeText(this, "已复制: " + url, Toast.LENGTH_LONG).show();
        }
    }

    private void startServer() {
        // 1. 端口校验
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }
        if (port < 1 || port > 65535) {
            Toast.makeText(this, "端口必须在 1~65535 之间", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. 端口占用预检（给出明确反馈）
        try (ServerSocket ss = new ServerSocket(port)) {
            // 端口可用
        } catch (IOException e) {
            Toast.makeText(this, "端口 " + port + " 已被占用，请更换端口", Toast.LENGTH_LONG).show();
            return;
        }

        // 3. 保存端口
        getSharedPreferences(McpServerService.PREF_NAME, MODE_PRIVATE)
                .edit()
                .putInt(McpServerService.KEY_PORT, port)
                .apply();
        etPort.setEnabled(false);

        // 4. Android 13+ 通知权限（前台服务必须）
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFY_PERMISSION);
            return; // 权限回调后继续启动
        }

        launchService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY_PERMISSION) {
            // 无论授予与否都继续（未授予则通知不可见，服务仍可运行）
            launchService();
        }
    }

    private void launchService() {
        McpServerService.start(this);
        Toast.makeText(this, "MCP 服务启动中…", Toast.LENGTH_SHORT).show();
    }

    // ==================== 工具方法 ====================

    private int getPortFromPrefs() {
        return getSharedPreferences(McpServerService.PREF_NAME, MODE_PRIVATE)
                .getInt(McpServerService.KEY_PORT, McpServerService.DEFAULT_PORT);
    }

    /** 遍历所有网络接口，返回当前设备可达的 IPv4 地址列表（WiFi/热点/USB 共享）。 */
    private List<String> getIpv4Addresses() {
        List<String> ips = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                 en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> ae = ni.getInetAddresses(); ae.hasMoreElements(); ) {
                    InetAddress addr = ae.nextElement();
                    if (addr instanceof Inet4Address) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // 网络接口枚举失败时降级为空列表
        }

        // 兜底：WifiManager 提供的主 IP 未命中时补充
        if (ips.isEmpty()) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int ip = wm.getConnectionInfo().getIpAddress();
                    if (ip != 0) {
                        ips.add(String.format("%d.%d.%d.%d",
                                ip & 0xFF, (ip >> 8) & 0xFF, (ip >> 16) & 0xFF, (ip >> 24) & 0xFF));
                    }
                }
            } catch (Exception ignored) {}
        }
        return ips;
    }
}
