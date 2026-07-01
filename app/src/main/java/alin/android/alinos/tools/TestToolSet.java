package alin.android.alinos.tools;

import org.json.JSONObject;

/**
 * 基础工具集 —— 系统和测试工具。
 *
 * 包含：
 * - 元工具（search_tools）：搜索当前系统注册的所有可用工具/技能
 * - 测试工具（get_weather/get_time/calculate）：固定返回数据，用于调试 Tool Calling 链路
 *
 * 真实 {@link alin.android.alinos.localshell.LocalShellExecutor} 工具待链路稳定后逐步接入。
 */
public class TestToolSet {

    private TestToolSet() {}

    /** 注册 4 个测试工具到 {@link ToolRegistry}。 */
    public static void register() {
        ToolRegistry.register("get_weather",
                "获取指定城市的当前天气信息。适用于查询各地天气状况",
                ToolMeta.params(
                        ToolMeta.param("location", "string", true, "",
                                "城市名称，如 北京、上海、广州")
                ),
                params -> {
                    String location = params.optString("location", "未知");
                    JSONObject result = new JSONObject();
                    result.put("status", "success");
                    result.put("location", location);
                    result.put("temperature", 28);
                    result.put("condition", "晴");
                    result.put("humidity", 60);
                    result.put("wind", "3级");
                    return result;
                }
        );

        ToolRegistry.register("get_time",
                "获取指定时区的当前时间。适用于查询时间、时区转换",
                ToolMeta.params(
                        ToolMeta.param("timezone", "string", false, "Asia/Shanghai",
                                "IANA 时区标识，如 Asia/Shanghai、America/New_York")
                ),
                params -> {
                    JSONObject result = new JSONObject();
                    result.put("status", "success");
                    result.put("time", "12:00");
                    result.put("timezone", params.optString("timezone", "Asia/Shanghai"));
                    result.put("date", "2026-07-01");
                    return result;
                }
        );

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

        ToolRegistry.register("calculate",
                "执行数学计算。适用于算术运算、表达式求值",
                ToolMeta.params(
                        ToolMeta.param("expression", "string", true, "",
                                "数学表达式，如 (12 + 8) * 3")
                ),
                params -> {
                    String expr = params.optString("expression", "");
                    JSONObject result = new JSONObject();
                    result.put("status", "success");
                    result.put("expression", expr);
                    result.put("result", 42);
                    return result;
                }
        );
    }
}
