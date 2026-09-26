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
 * <p>当前只向 AI / MCP 暴露两个工具：
 * <ul>
 *   <li>元工具：{@code search_tools}；</li>
 *   <li>环境说明：{@code system_environment}。</li>
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
        // 注册环境说明工具（MCP 客户端帮助技能）
        EnvironmentToolSet.register();
    }

    /**
     * 需要 Context 的工具注册入口（由 ChatActivity / McpServerActivity / McpServerService 调用）。
     *
     * <p>SSH 工具已不再暴露给 AI（待重新设计），当前无需在此注册工具。
     */
    public static void init(Context context) {
        if (context != null) {
            // 注册工作环境工具（bash / read / write / edit / ls / grep / find）
            ContainerToolSet.register(context.getApplicationContext());
        }
    }

    /** 公开注册方法，供 TestToolSet 或动态工具注册使用。 */
    public static void register(String displayName, String description,
                                 ToolMeta.Param[] params, ToolMeta.Executor executor) {
        tools.put(displayName, new ToolMeta(displayName, description,
            displayName.replace("localshell_", ""), params, executor));
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
}
