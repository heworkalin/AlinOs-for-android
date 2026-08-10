package alin.android.alinos.mcp;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

import alin.android.alinos.tools.ToolMeta;
import alin.android.alinos.tools.ToolRegistry;

/**
 * MCP（Model Context Protocol）协议处理器。
 *
 * 遵循官方 2025-06-18 稳定版规范（JSON-RPC 2.0）：
 *  - initialize / notifications/initialized 生命周期握手
 *  - tools/list / tools/call 工具能力
 *  - ping 心跳
 *  - 标准 JSON-RPC 错误码（-32700 / -32600 / -32601 / -32602 / -32603）
 *
 * 所有工具通过 {@link ToolRegistry} 注册分发，响应按规范包装
 * content + structuredContent + isError。
 */
public class McpProtocolHandler {

    private static final String TAG = "McpProtocol";

    /** 本服务实现的协议版本。 */
    public static final String PROTOCOL_VERSION = "2025-06-18";
    /** 兼容的旧版本（按规范：版本不符时返回服务端支持的版本）。 */
    private static final List<String> SUPPORTED_VERSIONS = Arrays.asList(
            "2025-06-18", "2025-03-26", "2024-11-05");

    private static final String SERVER_NAME = "AlinOsTools";
    private static final String SERVER_TITLE = "AlinOs 工具服务";
    private static final String SERVER_VERSION = "1.0.0";

    /**
     * 处理一条客户端消息。
     *
     * @param jsonBody 客户端 POST 的 JSON-RPC 消息
     * @return 响应 JSON 字符串；通知（notification）返回 null（HTTP 202 无 body）
     */
    public String handleMessage(String jsonBody) {
        try {
            JSONObject msg = new JSONObject(jsonBody);

            // 通知：无 id，不响应
            if (!msg.has("id")) {
                String method = msg.optString("method", "");
                if (!method.isEmpty()) {
                    Log.d(TAG, "收到通知: " + method);
                }
                return null;
            }

            String method = msg.optString("method", "");
            Object id = msg.opt("id");

            switch (method) {
                case "initialize":
                    return initialize(msg, id);
                case "ping":
                    return success(id, new JSONObject());
                case "tools/list":
                    return toolsList(msg, id);
                case "tools/call":
                    return toolsCall(msg, id);
                default:
                    return error(id, -32601, "Method not found: " + method);
            }
        } catch (JSONException e) {
            Log.e(TAG, "JSON 解析失败", e);
            return error(null, -32700, "Parse error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "处理消息异常", e);
            return error(null, -32603, "Internal error: " + e.getMessage());
        }
    }

    /** 协议版本是否受支持（用于 MCP-Protocol-Version 头校验）。 */
    public static boolean isSupportedVersion(String version) {
        return SUPPORTED_VERSIONS.contains(version);
    }

    // ==================== initialize ====================

    private String initialize(JSONObject msg, Object id) {
        JSONObject params = msg.optJSONObject("params");

        // 版本协商：客户端请求的版本不在支持列表时，返回服务端支持的版本
        String clientVersion = params != null ? params.optString("protocolVersion", "") : "";
        String negotiated = SUPPORTED_VERSIONS.contains(clientVersion)
                ? clientVersion : PROTOCOL_VERSION;

        JSONObject result = new JSONObject();
        try {
            result.put("protocolVersion", negotiated);

            JSONObject capabilities = new JSONObject();
            JSONObject toolsCap = new JSONObject();
            toolsCap.put("listChanged", false); // 工具列表静态，不推送变更
            capabilities.put("tools", toolsCap);
            result.put("capabilities", capabilities);

            JSONObject serverInfo = new JSONObject();
            serverInfo.put("name", SERVER_NAME);
            serverInfo.put("title", SERVER_TITLE);
            serverInfo.put("version", SERVER_VERSION);
            result.put("serverInfo", serverInfo);

            // 环境标注：告知连接上来的 AI 这是什么环境、能力边界、先看哪个帮助工具
            result.put("instructions",
                    "这是运行在 Android 手机上的 MCP 工具服务端（AlinOs），"
                    + "持有一个永久会话的交互式 PTY 终端：预编译精简 rootfs（bash/curl/ssh/scp 等），"
                    + "非 root、无 proot，无包管理器/git/python，系统分区只读。\n"
                    + "1. 首次连接请先调用 system_environment 获取完整能力范围（能做什么/不能做什么）与安全注意事项；\n"
                    + "2. 工具清单通过 tools/list 获取，全部工具可直接调用；\n"
                    + "3. 终端为交互式 PTY：前台有进程时勿用 sleep/echo 等待，用 localshell_shell_read 轮询；\n"
                    + "4. 执行破坏性命令前必须向用户确认。");
        } catch (JSONException ignored) {}

        Log.d(TAG, "initialize: 版本协商 -> " + negotiated);
        return success(id, result);
    }

    // ==================== tools/list ====================

    private String toolsList(JSONObject msg, Object id) {
        JSONObject result = new JSONObject();
        try {
            JSONArray tools = new JSONArray();
            for (ToolMeta tool : ToolRegistry.getAllTools()) {
                tools.put(buildToolSchema(tool));
            }
            result.put("tools", tools);
        } catch (JSONException ignored) {}
        return success(id, result);
    }

    /** ToolMeta -> MCP 工具定义（JSON Schema 2020-12 风格 inputSchema）。 */
    private static JSONObject buildToolSchema(ToolMeta tool) throws JSONException {
        JSONObject schema = new JSONObject();
        schema.put("name", tool.displayName);              // 全局唯一，调用时严格匹配
        schema.put("title", tool.functionName);            // 展示用别名
        schema.put("description", tool.description);

        JSONObject input = new JSONObject();
        input.put("type", "object");

        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        if (tool.params != null) {
            for (ToolMeta.Param p : tool.params) {
                JSONObject prop = new JSONObject();
                switch (p.type) {
                    case "int":
                    case "long":
                        prop.put("type", "integer");
                        break;
                    case "boolean":
                        prop.put("type", "boolean");
                        break;
                    default:
                        prop.put("type", "string");
                }
                if (p.enumValues != null && p.enumValues.length > 0) {
                    JSONArray enums = new JSONArray();
                    for (String e : p.enumValues) enums.put(e);
                    prop.put("enum", enums);
                }
                if (p.description != null && !p.description.isEmpty()) {
                    prop.put("description", p.description);
                }
                if (p.defaultValue != null && !p.defaultValue.isEmpty()) {
                    prop.put("default", convertDefault(p));
                }
                properties.put(p.name, prop);
                if (p.required) required.put(p.name);
            }
        }
        input.put("properties", properties);
        if (required.length() > 0) input.put("required", required);

        schema.put("inputSchema", input);
        return schema;
    }

    /** 默认值按类型转换，便于客户端做类型提示。 */
    private static Object convertDefault(ToolMeta.Param p) {
        switch (p.type) {
            case "int":
            case "long":
                try { return Long.parseLong(p.defaultValue); } catch (NumberFormatException e) { return p.defaultValue; }
            case "boolean":
                return Boolean.parseBoolean(p.defaultValue);
            default:
                return p.defaultValue;
        }
    }

    // ==================== tools/call ====================

    private String toolsCall(JSONObject msg, Object id) {
        JSONObject params = msg.optJSONObject("params");
        String name = params != null ? params.optString("name", "") : "";
        JSONObject arguments = params != null ? params.optJSONObject("arguments") : null;
        if (arguments == null) arguments = new JSONObject();

        ToolMeta tool = ToolRegistry.findTool(name);
        if (tool == null) {
            Log.w(TAG, "未知工具: " + name);
            return error(id, -32602, "Unknown tool: " + name);
        }

        // 必填参数校验（服务端不得跳过 inputSchema 校验）
        if (tool.params != null) {
            for (ToolMeta.Param p : tool.params) {
                if (p.required && !arguments.has(p.name)) {
                    return error(id, -32602, "Missing required argument: " + p.name
                            + " for tool: " + name);
                }
            }
        }

        try {
            JSONObject execResult = tool.executor.execute(arguments);
            return success(id, wrapToolResult(execResult));
        } catch (Exception e) {
            Log.e(TAG, "工具执行失败: " + name, e);
            // 工具执行错误：在 result 中以 isError=true 体现（非 JSON-RPC error）
            JSONObject result = new JSONObject();
            try {
                result.put("content", contentArray("工具执行失败: " + name + " -> " + e.getMessage()));
                result.put("isError", true);
            } catch (JSONException ignored) {}
            return success(id, result);
        }
    }

    /** 将工具执行结果包装为 MCP tools/call result。 */
    private static JSONObject wrapToolResult(JSONObject execResult) {
        JSONObject result = new JSONObject();
        try {
            result.put("resultType", "complete");

            // 业务异常（工具自身返回 status:error）与正常结果区分
            boolean isError = "error".equals(execResult.optString("status", ""))
                    || "failed".equals(execResult.optString("status", ""));
            result.put("isError", isError);

            // 文本块：序列化 JSON（多行内容，客户端可直接阅读）
            String text = execResult.toString(2);
            result.put("content", contentArray(text));

            // 结构化内容：与 text 内容并行返回（官方推荐）
            result.put("structuredContent", execResult);
        } catch (JSONException ignored) {}
        return result;
    }

    private static JSONArray contentArray(String text) throws JSONException {
        JSONObject block = new JSONObject();
        block.put("type", "text");
        block.put("text", text);
        JSONArray arr = new JSONArray();
        arr.put(block);
        return arr;
    }

    // ==================== 响应构造 ====================

    private static String success(Object id, JSONObject result) {
        JSONObject resp = new JSONObject();
        try {
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);
            resp.put("result", result);
        } catch (JSONException ignored) {}
        return resp.toString();
    }

    private static String error(Object id, int code, String message) {
        JSONObject resp = new JSONObject();
        try {
            resp.put("jsonrpc", "2.0");
            if (id != null) resp.put("id", id);
            JSONObject err = new JSONObject();
            err.put("code", code);
            err.put("message", message);
            resp.put("error", err);
        } catch (JSONException ignored) {}
        return resp.toString();
    }
}
