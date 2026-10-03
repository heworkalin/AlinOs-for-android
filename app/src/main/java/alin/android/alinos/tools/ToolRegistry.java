package alin.android.alinos.tools;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 + 分发器。
 *
 * <p>当前向 AI / MCP 暴露的工具：
 * <ul>
 *   <li>工作环境：{@code bash} / {@code read} / {@code write} / {@code edit}；</li>
 *   <li>元工具：{@code search_tools}。</li>
 * </ul>
 *
 * <p><b>交互式 PTY 工具（localshell_*）与 SSH 工具（ssh_*）均已不再注册给 AI。</b>
 * 相关实现仍保留在 {@link alin.android.alinos.localshell.LocalShellExecutor} /
 * {@link alin.android.alinos.localshell.SshLocalhost}，供测试界面与 SSH UI 直接调用。
 */
public class ToolRegistry {

    private static final Map<String, ToolMeta> tools = new LinkedHashMap<>();

    static {
        // 注册测试工具集
        TestToolSet.register();
    }

    /**
     * 需要 Context 的工具注册入口（由 ChatActivity / McpServerActivity / McpServerService 调用）。
     *
     * <p>SSH 工具已不再暴露给 AI（待重新设计），当前无需在此注册工具。
     */
    public static void init(Context context) {
        if (context != null) {
            Context app = context.getApplicationContext();
            // 注册工作环境工具（bash / read / write / edit + ls/grep/find）
            ContainerToolSet.register(app);
            // 注册宿主侧 PTY 工具（INTERNAL / DEBUG，不暴露给 AI）
            ShellToolSet.register();
            // 注册 SSH 工具（INTERNAL）
            SshToolSet.register(app);
            // 注册音频工具（INTERNAL，仅同步可查询能力）
            AudioToolSet.register(app);
            // 注册调试工具（DEBUG，供 MCP 读取运行态与统一日志）
            DebugToolSet.register(app);
        }
    }

    /** 公开注册方法，供 TestToolSet 或动态工具注册使用（默认 AI 可见 / SYSTEM 分组）。 */
    public static void register(String displayName, String description,
                                 ToolMeta.Param[] params, ToolMeta.Executor executor) {
        register(displayName, description, params, executor,
                ToolMeta.Scope.AI, ToolMeta.Category.SYSTEM);
    }

    /** 完整注册（带可见面与分组）。 */
    public static void register(String displayName, String description,
                                 ToolMeta.Param[] params, ToolMeta.Executor executor,
                                 ToolMeta.Scope scope, ToolMeta.Category category) {
        tools.put(displayName, new ToolMeta(displayName, description,
            displayName.replace("localshell_", ""), params, executor, scope, category));
    }

    /** 获取完整工具列表（全部可见面，测试界面 / 内部查询用）。 */
    public static List<ToolMeta> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /** 仅获取暴露给 AI / MCP 的工具（喂模型用）。 */
    public static List<ToolMeta> getAiTools() {
        List<ToolMeta> result = new ArrayList<>();
        for (ToolMeta t : tools.values()) {
            if (t.scope == ToolMeta.Scope.AI) result.add(t);
        }
        return result;
    }

    /**
     * MCP 服务端的可见工具集。
     *
     * <p><b>MCP 是开发者调试通道，与 App 内部运行的 AI 无关</b>，
     * 因此除了 {@link ToolMeta.Scope#AI} 工具，还要暴露 {@link ToolMeta.Scope#DEBUG}
     * 调试工具（如 debug_*），否则就失去了调试意义。
     *
     * <p>不含 {@link ToolMeta.Scope#INTERNAL}（宿主 PTY / SSH / 音频等内部能力）。
     */
    public static List<ToolMeta> getMcpTools() {
        List<ToolMeta> result = new ArrayList<>();
        for (ToolMeta t : tools.values()) {
            if (t.scope == ToolMeta.Scope.AI || t.scope == ToolMeta.Scope.DEBUG) {
                result.add(t);
            }
        }
        return result;
    }

    /** 按可见面筛选。 */
    public static List<ToolMeta> getToolsByScope(ToolMeta.Scope scope) {
        List<ToolMeta> result = new ArrayList<>();
        for (ToolMeta t : tools.values()) {
            if (t.scope == scope) result.add(t);
        }
        return result;
    }

    /** 按分组筛选。 */
    public static List<ToolMeta> getToolsByCategory(ToolMeta.Category category) {
        List<ToolMeta> result = new ArrayList<>();
        for (ToolMeta t : tools.values()) {
            if (t.category == category) result.add(t);
        }
        return result;
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
}
