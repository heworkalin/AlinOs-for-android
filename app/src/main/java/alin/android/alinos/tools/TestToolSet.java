package alin.android.alinos.tools;

import org.json.JSONObject;

/**
 * 基础工具集 —— 系统和测试工具。
 *
 * 包含：
 * - 元工具（search_tools）：搜索当前系统注册的所有可用工具/技能
 * 真实 {@link alin.android.alinos.localshell.LocalShellExecutor} 工具待链路稳定后逐步接入。
 */
public class TestToolSet {

    private TestToolSet() {}
    public static void register() {
        ToolRegistry.register("search_tools",
                "Search the tool registry for available tools/skills. Matches the query fuzzily "
                + "against tool names and descriptions. Omit query to list every registered tool.",
                ToolMeta.params(
                        ToolMeta.param("query", "string", false, "",
                                "Search keyword matched against tool names and descriptions. "
                                        + "Leave empty to return all tools")
                ),
                params -> {
                    String query = params.optString("query", "").trim();
                    // 只暴露 AI 可见工具：内部 / 调试工具不得泄露给模型。
                    java.util.List<ToolMeta> all = ToolRegistry.getAiTools();
                    java.util.List<ToolMeta> tools;
                    if (query.isEmpty()) {
                        tools = all;
                    } else {
                        tools = new java.util.ArrayList<>();
                        String lower = query.toLowerCase();
                        for (ToolMeta t : all) {
                            if (t.displayName.toLowerCase().contains(lower)
                                    || t.description.toLowerCase().contains(lower)) {
                                tools.add(t);
                            }
                        }
                    }
                    JSONObject result = ToolMeta.ok();
                    result.put("total", tools.size());
                    org.json.JSONArray items = new org.json.JSONArray();
                    for (ToolMeta t : tools) {
                        org.json.JSONObject item = new org.json.JSONObject();
                        item.put("name", t.functionName);
                        item.put("description", t.description);
                        org.json.JSONArray paramNames = new org.json.JSONArray();
                        for (ToolMeta.Param p : t.params) {
                            paramNames.put(p.name + (p.required ? "*" : ""));
                        }
                        item.put("parameters", paramNames);
                        items.put(item);
                    }
                    result.put("tools", items);
                    return result;
                },
                ToolMeta.Scope.AI, ToolMeta.Category.META
        );
    }
}
