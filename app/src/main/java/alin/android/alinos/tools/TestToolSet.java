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
                "搜索当前系统已注册的所有可用工具/技能。支持按关键词模糊搜索名称和描述。" +
                "不传 query 时返回全部工具列表。AI 可以用此工具了解自己有哪些能力可用",
                ToolMeta.params(
                        ToolMeta.param("query", "string", false, "",
                                "搜索关键词，模糊匹配工具名称和描述。留空返回全部工具")
                ),
                params -> {
                    String query = params.optString("query", "").trim();
                    java.util.List<ToolMeta> tools;
                    if (query.isEmpty()) {
                        tools = ToolRegistry.getAllTools();
                    } else {
                        tools = ToolRegistry.searchTools(query);
                    }
                    JSONObject result = new JSONObject();
                    result.put("status", "success");
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
                }
        );
    }
}
