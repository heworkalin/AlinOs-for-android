package alin.android.alinos.tools;

import alin.android.alinos.localshell.LocalShellExecutor;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 + 分发器。
 * 所有 {@link LocalShellExecutor} 的方法在此注册，供 DevToolsActivity 调用。
 */
public class ToolRegistry {

    private static final Map<String, ToolMeta> tools = new LinkedHashMap<>();

    static {
        LocalShellExecutor exec = LocalShellExecutor.getInstance();
        registerTool("localshell_create_session",
            "创建一个新的永久 PTY 会话。提供 id 或 name（二选一）。"
            + " id 格式 [a-zA-Z0-9_-]+，最大 64 字符，已存在则报错。"
            + " 仅提供 name 时自动生成随机 id。返回 { id, name }。",
            params(
                param("id", "string", false, "", "会话 ID（与 name 二选一），仅允许字母数字下划线短线，最大 64 字符"),
                param("name", "string", false, "", "会话名称（与 id 二选一），支持中文，仅提供 name 时自动生成 ID")
            ),
            p -> {
                String id = p.optString("id", "").trim();
                String name = p.optString("name", "").trim();
                if (id.isEmpty() && name.isEmpty()) {
                    JSONObject err = new JSONObject();
                    err.put("status", "error");
                    err.put("error_code", "INVALID_PARAMS");
                    err.put("message", "请提供 id 或 name（二选一）。");
                    return err;
                }
                if (id.isEmpty()) {
                    String generatedId = "session_"
                        + java.util.UUID.randomUUID().toString().substring(0, 8);
                    return exec.create_session(generatedId, name);
                }
                return exec.create_session(id, name.isEmpty() ? null : name);
            }
        );
        registerTool("localshell_destroy_session",
            "关闭指定会话并释放资源",
            params(
                param("sessionId", "string", true, "", "终端会话 ID")
            ),
            p -> exec.destroy_session(p.getString("sessionId"))
        );
        registerTool("localshell_list_sessions",
            "列出所有会话及其存活状态",
            new ToolMeta.Param[0],
            p -> exec.list_sessions()
        );
        registerTool("localshell_search_session",
            "搜索会话（按 ID 或名称）",
            params(
                param("query", "string", true, "", "搜索关键词"),
                param("by", "enum", false, "name", "搜索方式",
                    new String[]{"id", "name"})
            ),
            p -> exec.search_session(p.getString("query"), p.optString("by", "name"))
        );
        registerTool("localshell_session_status",
            "查询指定会话的存活状态",
            params(
                param("sessionId", "string", true, "", "终端会话 ID")
            ),
            p -> exec.session_status(p.getString("sessionId"))
        );
        registerTool("localshell_rename_session",
            "重命名会话（仅改标识，不影响终端）",
            params(
                param("sessionId", "string", true, "", "终端会话 ID"),
                param("newName", "string", true, "", "新名称，支持中文")
            ),
            p -> exec.rename_session(p.getString("sessionId"), p.getString("newName"))
        );
        registerTool("localshell_shell_exec",
            "在会话中执行 Shell 命令并等待输出。\n\n"
            + "⚠ 这是交互式 PTY 环境，不是标准 bash：\n"
            + "- shell_exec 会往 shell 里塞一条新命令。如果前台有进程在跑(apt/curl下载中)，\n"
            + "  新命令会排队或干扰前台——绝对不能用 sleep/echo 来等时！\n"
            + "- 命令执行后前台还在跑(有进度条/百分比) → 用 shell_read(waitMs) 轮询进度\n"
            + "- 命令已结束(看到提示符#或$) → 可以执行下一个 shell_exec\n\n"
            + "📌 waitMs 参考：短命令(ls/cat)用200；安装下载(apt/curl)首次用1200，\n"
            + "后续进度检查改用 shell_read(waitMs=2000~3000) 而非重复 exec。\n"
            + "返回 {status:'session_died'} 用 create_session 重建会话。",
            params(
                param("sessionId", "string", true, "default", "终端会话 ID"),
                param("command", "string", true, "", "要执行的命令，如 ls -la"),
                param("waitMs", "int", false, "500", "等待命令输出的毫秒数(长命令如apt install用1200)"),
                param("returnMode", "enum", false, "last_20", "返回模式",
                    new String[]{"last_20", "last_n"}),
                param("lines", "int", false, "20", "返回行数 (last_n 模式)"),
                param("colorEscape", "boolean", false, "true", "颜色转中文标签。菜单/列表场景必开true，否则无法识别高亮行(反色)")
            ),
            p -> exec.shell_exec(
                p.getString("sessionId"),
                p.getString("command"),
                p.optLong("waitMs", 200),
                p.optString("returnMode", "last_20"),
                p.optInt("lines", 20),
                p.optBoolean("colorEscape", true))
        );
        registerTool("localshell_shell_write",
            "向会话写入文本（不追加回车），用于交互应答",
            params(
                param("sessionId", "string", true, "default", "终端会话 ID"),
                param("text", "string", true, "", "要写入的文本"),
                param("returnMode", "enum", false, "last_20", "返回模式",
                    new String[]{"last_20", "last_n", "all"}),
                param("lines", "int", false, "20", "返回行数 (last_n 模式)"),
                param("colorEscape", "boolean", false, "true", "颜色转中文标签。菜单/列表场景必开true，否则无法识别高亮行(反色)"),
                param("cursorMode", "boolean", false, "false", "行首加行号标记(如[0][1][42])。菜单导航一般不需要，调试或定位光标时用")
            ),
            p -> exec.shell_write(
                p.getString("sessionId"),
                p.getString("text"),
                p.optString("returnMode", "last_20"),
                p.optInt("lines", 20),
                p.optBoolean("colorEscape", true),
                p.optBoolean("cursorMode", false))
        );
        registerTool("localshell_shell_send_key",
            "向会话发送控制键/方向键/功能键。\n"
            + "批量发送多个按键时用 '|' 分隔，如 'Down|Down|Enter' 一次发送三次按键。\n"
            + "常用组合: 'Down|Down|Enter'(导航到第3项并确认), 'Tab|Enter'(跳到按钮区按确定), 'Ctrl+C'(中断)。",
            params(
                param("sessionId", "string", true, "default", "终端会话 ID"),
                param("key", "string", true, "", "按键名称，多个用|分隔(如Down|Down|Enter)。支持: Ctrl+C,Ctrl+D,Ctrl+Z,Enter,Tab,Escape,Backspace,Delete,Up,Down,Left,Right,PageUp,PageDown,Home,End,F1~F12"),
                param("returnMode", "enum", false, "last_20", "返回模式",
                    new String[]{"last_20", "last_n", "all"}),
                param("lines", "int", false, "20", "返回行数 (last_n 模式)"),
                param("colorEscape", "boolean", false, "true", "颜色转中文标签。菜单/列表场景必开true，否则无法识别高亮行(反色)"),
                param("cursorMode", "boolean", false, "false", "行首加行号标记(如[0][1][42])。菜单导航一般不需要，调试或定位光标时用")
            ),
            p -> exec.shell_send_keys(
                p.getString("sessionId"),
                p.getString("key"),
                p.optString("returnMode", "last_20"),
                p.optInt("lines", 20),
                p.optBoolean("colorEscape", true),
                p.optBoolean("cursorMode", false))
        );
        registerTool("localshell_shell_read",
            "仅读取终端画面快照，不执行任何命令，不会干扰前台进程。\n\n"
            + "⭐ 交互式 PTY 环境须知：\n"
            + "- 这是交互式伪终端，不是标准 bash。命令在前台持续运行时（apt/git/curl 下载安装中），\n"
            + "  用 shell_read 轮询进度——绝对不要用 shell_exec 执行 sleep/echo 等命令来等时。\n"
            + "- 识别到下载/安装/编译任务(进度条/百分比/速度)时，设 waitMs=2000~3000 延迟读取，\n"
            + "  让任务多跑一会儿再返回画面，减少无效调用次数。\n"
            + "- 两次 shell_read 返回相同内容 → 进程卡住或已完成，再做判断。\n\n"
            + "⭐ whiptail/dialog 菜单：必须 colorEscape=true，高亮行有[反色]标记，\n"
            + "  找[反色]行→数它第几个→计算按几次方向键→shell_send_key(批量)|Enter。",
            params(
                param("sessionId", "string", true, "default", "终端会话 ID"),
                param("waitMs", "int", false, "0", "延迟等待毫秒数。下载/安装任务设2000~3000让进度多跑再读(最大5000)"),
                param("returnMode", "enum", false, "last_20", "返回模式",
                    new String[]{"last_20", "last_n", "all"}),
                param("lines", "int", false, "20", "返回行数 (last_n 模式，最大 5000)"),
                param("colorEscape", "boolean", false, "true", "颜色转中文标签。菜单/列表场景必开true，否则无法识别高亮行(反色)"),
                param("cursorMode", "boolean", false, "false", "行首加行号标记(如[0][1][42])。菜单导航一般不需要，调试或定位光标时用")
            ),
            p -> exec.shell_read(
                p.getString("sessionId"),
                p.optString("returnMode", "last_20"),
                p.optInt("lines", 20),
                p.optBoolean("colorEscape", true),
                p.optBoolean("cursorMode", false),
                p.optLong("waitMs", 0))
        );
        registerTool("localshell_read_history_canvas",
            "读取终端完整历史输出（含滚动区），生成归档文件",
            params(
                param("sessionId", "string", true, "default", "终端会话 ID"),
                param("returnMode", "enum", false, "last_20", "返回模式",
                    new String[]{"last_20", "last_n", "all"}),
                param("lines", "int", false, "20", "返回行数 (last_n 模式，默认 20，最大 5000)"),
                param("colorEscape", "boolean", false, "true", "颜色转中文标签。菜单/列表场景必开true，否则无法识别高亮行(反色)")
            ),
            p -> exec.read_history_canvas(
                p.getString("sessionId"),
                p.optString("returnMode", "last_20"),
                p.optInt("lines", 20),
                p.optBoolean("colorEscape", true))
        );
        registerTool("localshell_shell_get_debug_view",
            "获取终端的样式调试视图（行号/颜色/光标）",
            params(
                param("sessionId", "string", true, "default", "终端会话 ID"),
                param("showLineNumbers", "boolean", false, "true", "显示行号"),
                param("showStyles", "boolean", false, "true", "显示样式标记"),
                param("showCursor", "boolean", false, "true", "显示光标位置")
            ),
            p -> exec.shell_get_debug_view(
                p.getString("sessionId"),
                p.optBoolean("showLineNumbers", true),
                p.optBoolean("showStyles", true),
                p.optBoolean("showCursor", true))
        );
        // 注册测试工具集（Tool Calling 调试阶段使用）
        TestToolSet.register();
    }

    /** 公开注册方法，供 TestToolSet 或动态工具注册使用。 */
    public static void register(String displayName, String description,
                                 ToolMeta.Param[] params, ToolMeta.Executor executor) {
        tools.put(displayName, new ToolMeta(displayName, description,
            displayName.replace("localshell_", ""), params, executor));
    }

    /** 内部注册（保留原名，Private 供 localshell 工具使用）。 */
    private static void registerTool(String displayName, String description,
                                      ToolMeta.Param[] params, ToolMeta.Executor executor) {
        register(displayName, description, params, executor);
    }

    /** 获取完整工具列表。 */
    public static List<ToolMeta> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /** 按 displayName 查找。 */
    public static ToolMeta findTool(String displayName) {
        return tools.get(displayName);
    }

    /** 按 functionName 查找（用于 tool_calls 路由）。 */
    public static ToolMeta findToolByFunctionName(String functionName) {
        if (functionName == null) return null;
        for (ToolMeta t : tools.values()) {
            if (functionName.equals(t.functionName)) {
                return t;
            }
        }
        return null;
    }

    /** 搜索匹配的工具。 */
    public static List<ToolMeta> searchTools(String query) {
        List<ToolMeta> result = new ArrayList<>();
        String lower = query.toLowerCase();
        for (ToolMeta t : tools.values()) {
            if (t.displayName.toLowerCase().contains(lower)
                    || t.description.toLowerCase().contains(lower)) {
                result.add(t);
            }
        }
        return result;
    }

    /** 构建参数的 JSON 模板。 */
    public static JSONObject buildParamTemplate(ToolMeta tool) {
        JSONObject tmpl = new JSONObject();
        try {
            for (ToolMeta.Param p : tool.params) {
                if (p.defaultValue != null && !p.defaultValue.isEmpty()) {
                    switch (p.type) {
                        case "long":
                        case "int":
                            tmpl.put(p.name, Long.parseLong(p.defaultValue));
                            break;
                        case "boolean":
                            tmpl.put(p.name, Boolean.parseBoolean(p.defaultValue));
                            break;
                        default:
                            tmpl.put(p.name, p.defaultValue);
                    }
                } else {
                    tmpl.put(p.name, "");
                }
            }
        } catch (Exception ignored) {}
        return tmpl;
    }

    // 辅助方法（static import 到 ToolMeta）
    private static ToolMeta.Param[] params(ToolMeta.Param... ps) { return ps; }
    private static ToolMeta.Param param(String name, String type, boolean required,
                                         String defaultValue, String description,
                                         String[] enumValues) {
        return new ToolMeta.Param(name, type, required, defaultValue, description, enumValues);
    }
    private static ToolMeta.Param param(String name, String type, boolean required,
                                         String defaultValue, String description) {
        return new ToolMeta.Param(name, type, required, defaultValue, description);
    }
}
