package alin.android.alinos.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 极简 HTTP 服务器（零依赖，纯 ServerSocket 实现）。
 * 仅承载 MCP Streamable HTTP 传输所需的单端点路由：
 *   - POST /mcp  ：接收 JSON-RPC 请求，返回 application/json 或 text/event-stream
 *   - GET  /mcp  ：规范允许直接 405（不提供长连 SSE 下行流）
 *   - GET  /     ：健康信息页
 *
 * 每个连接只处理一个请求后关闭，与官方 MCP SDK 客户端的每次 POST 新连接行为一致。
 */
public class McpHttpServer {

    /** 服务事件监听器（日志 / 状态变化），由 UI 层实现。 */
    public interface Listener {
        void onLog(String line);
        void onStatusChange(boolean running);
    }

    /** 简单 HTTP 响应。 */
    public static class Response {
        final int status;
        final String contentType;
        final byte[] body;
        final boolean closeConnection;

        Response(int status, String contentType, byte[] body, boolean closeConnection) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.closeConnection = closeConnection;
        }

        static Response json(int status, String json) {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            return new Response(status, "application/json", b, false);
        }

        static Response empty(int status) {
            return new Response(status, null, new byte[0], true);
        }

        static Response sse(String jsonEvent) {
            String frame = "data: " + jsonEvent + "\n\n";
            byte[] b = frame.getBytes(StandardCharsets.UTF_8);
            return new Response(200, "text/event-stream", b, true);
        }
    }

    private final int port;
    private final Listener listener;
    private final McpProtocolHandler protocol;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;

    public McpHttpServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
        this.protocol = new McpProtocolHandler();
    }

    /** 启动监听。端口被占用 / 无权限时抛异常由调用方处理。 */
    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        pool = new ThreadPoolExecutor(2, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(64),
                new ThreadPoolExecutor.CallerRunsPolicy());
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "mcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log("HTTP 服务已启动，监听端口 " + port);
        if (listener != null) listener.onStatusChange(true);
    }

    /** 停止服务并释放端口。 */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
        log("HTTP 服务已停止");
        if (listener != null) listener.onStatusChange(false);
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    // ==================== 连接接收 ====================

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(30000); // 单请求 30s 超时，防止挂死线程
                pool.execute(() -> handleConnection(socket));
            } catch (SocketException e) {
                break; // 服务停止导致的 accept 中断
            } catch (IOException e) {
                log("accept 异常: " + e.getMessage());
                try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    // ==================== 请求处理 ====================

    private void handleConnection(Socket socket) {
        try (Socket s = socket;
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {

            HttpRequest req = HttpRequest.parse(in);
            if (req == null) {
                writeResponse(out, Response.empty(400));
                return;
            }

            Response resp = route(req);
            writeResponse(out, resp);

        } catch (Exception e) {
            log("连接处理异常: " + e.getMessage());
        }
    }

    private void writeResponse(OutputStream out, Response resp) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(resp.status).append(" ").append(statusText(resp.status)).append("\r\n");
        head.append("Content-Type: ").append(resp.contentType != null ? resp.contentType : "text/plain").append("\r\n");
        head.append("Content-Length: ").append(resp.body.length).append("\r\n");
        head.append("Cache-Control: no-cache\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Headers: MCP-Protocol-Version, MCP-Session-Id, Authorization, Content-Type, Last-Event-ID\r\n");
        head.append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n");
        if (resp.closeConnection) {
            head.append("Connection: close\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(resp.body);
        out.flush();
    }

    private Response route(HttpRequest req) {
        String path = req.path;
        String method = req.method;

        // CORS 预检
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return Response.empty(204);
        }

        // 健康页
        if ("/".equals(path) && "GET".equalsIgnoreCase(method)) {
            String json = "{\"service\":\"AlinOs MCP Server\",\"protocol\":\"" + McpProtocolHandler.PROTOCOL_VERSION
                    + "\",\"endpoint\":\"POST /mcp\",\"status\":\"running\"}";
            return Response.json(200, json);
        }

        // MCP 端点：POST /mcp 与 POST /（兼容将根路径配置为端点的客户端）
        if (("/mcp".equals(path) || "/".equals(path)) && "POST".equalsIgnoreCase(method)) {
            return handleMcpPost(req);
        }
        if ("/mcp".equals(path) && "GET".equalsIgnoreCase(method)) {
            // 规范：不支持 SSE 下行流时返回 405
            return Response.empty(405);
        }

        return Response.json(404, "{\"error\":\"Not Found\"}");
    }

    private Response handleMcpPost(HttpRequest req) {
        // Origin 校验（防 DNS rebinding）
        String origin = req.headers.get("origin");
        if (origin != null && !isAllowedOrigin(origin)) {
            log("拒绝跨源请求: " + origin);
            return Response.empty(403);
        }

        // 协议版本头校验
        String protoVer = req.headers.get("mcp-protocol-version");
        if (protoVer != null && !protoVer.isEmpty()
                && !McpProtocolHandler.isSupportedVersion(protoVer)) {
            return Response.json(400, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,"
                    + "\"message\":\"Unsupported MCP-Protocol-Version: " + protoVer + "\"}}");
        }

        if (req.body == null || req.body.length == 0) {
            return Response.empty(400);
        }

        String body = new String(req.body, StandardCharsets.UTF_8);
        String accept = req.headers.get("accept");

        // 协议处理：返回 JSON 响应字符串，或 null（通知，202）
        String jsonResp = protocol.handleMessage(body);
        if (jsonResp == null) {
            return Response.empty(202); // 通知：Accepted 无 body
        }

        // 客户端仅接受 SSE 时才用 SSE 包装，否则统一返回 application/json
        if (accept != null && accept.contains("text/event-stream")
                && !accept.contains("application/json")) {
            return Response.sse(jsonResp);
        }
        return Response.json(200, jsonResp);
    }

    /** 允许本地来源的 Origin（浏览器调试场景），其余拒绝。 */
    private boolean isAllowedOrigin(String origin) {
        String o = origin.toLowerCase();
        return o.startsWith("http://localhost")
                || o.startsWith("http://127.0.0.1")
                || o.startsWith("http://[::1]")
                || o.startsWith("file://");
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default: return "Unknown";
        }
    }

    private void log(String line) {
        if (listener != null) listener.onLog(line);
    }

    // ==================== HTTP 请求解析 ====================

    private static class HttpRequest {
        String method;
        String path;
        Map<String, String> headers = new HashMap<>();
        byte[] body;

        /** 从流中解析一个 HTTP 请求；失败返回 null。 */
        static HttpRequest parse(InputStream in) throws IOException {
            HttpRequest req = new HttpRequest();

            // 请求行
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return null;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return null;
            req.method = parts[0].toUpperCase();
            req.path = parts[1];

            // 请求头
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    req.headers.put(line.substring(0, idx).trim().toLowerCase(),
                            line.substring(idx + 1).trim());
                }
            }

            // 请求体
            String contentLength = req.headers.get("content-length");
            if (contentLength != null) {
                int len;
                try {
                    len = Integer.parseInt(contentLength.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (len > 0) {
                    if (len > 4 * 1024 * 1024) return null; // 限制 4MB
                    req.body = new byte[len];
                    int read = 0;
                    while (read < len) {
                        int n = in.read(req.body, read, len - read);
                        if (n < 0) return null;
                        read += n;
                    }
                }
            }
            return req;
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
                if (sb.length() > 8192) break; // 防止超长行
            }
            if (sb.length() == 0 && c == -1) return null;
            return sb.toString();
        }
    }
}
