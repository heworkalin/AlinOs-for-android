package alin.android.alinos.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * ToolMeta → OpenAI Function Calling JSON Schema 转换器。
 *
 * 将 AlinOs 内部统一的 {@link ToolMeta} 定义转为 OpenAI 标准的 tools 数组格式，
 * 可直接注入 Chat Completion API 的 {@code tools} 参数。
 *
 * 输出示例（单个工具）:
 * <pre>
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "get_weather",
 *     "description": "获取指定城市的天气信息",
 *     "parameters": {
 *       "type": "object",
 *       "properties": {
 *         "location": {
 *           "type": "string",
 *           "description": "城市名称"
 *         }
 *       },
 *       "required": ["location"]
 *     }
 *   }
 * }
 * </pre>
 */
public class ToolConverter {

    private ToolConverter() {}

    /**
     * 将单个 {@link ToolMeta} 转为 OpenAI tools 格式的 JSONObject。
     */
    public static JSONObject convert(ToolMeta tool) {
        JSONObject fn = new JSONObject();
        JSONObject result = new JSONObject();
        try {
            result.put("type", "function");

            fn.put("name", tool.functionName);
            fn.put("description", tool.description);
            fn.put("parameters", buildParameters(tool.params));

            result.put("function", fn);
        } catch (Exception e) {
            // 序列化失败时应不影响主流程，返回空对象
        }
        return result;
    }

    /**
     * 将 {@link ToolMeta} 列表转为 OpenAI tools JSONArray。
     */
    public static JSONArray convertAll(List<ToolMeta> tools) {
        JSONArray arr = new JSONArray();
        if (tools == null) return arr;
        for (ToolMeta t : tools) {
            arr.put(convert(t));
        }
        return arr;
    }

    // ================================================================
    //  parameters 构建
    // ================================================================

    private static JSONObject buildParameters(ToolMeta.Param[] params) {
        JSONObject paramsObj = new JSONObject();
        try {
            paramsObj.put("type", "object");
            paramsObj.put("properties", buildProperties(params));
            paramsObj.put("required", buildRequired(params));
        } catch (Exception ignored) {}
        return paramsObj;
    }

    private static JSONObject buildProperties(ToolMeta.Param[] params) {
        JSONObject props = new JSONObject();
        if (params == null) return props;
        for (ToolMeta.Param p : params) {
            try {
                props.put(p.name, buildProperty(p));
            } catch (Exception ignored) {}
        }
        return props;
    }

    private static JSONArray buildRequired(ToolMeta.Param[] params) {
        JSONArray required = new JSONArray();
        if (params == null) return required;
        for (ToolMeta.Param p : params) {
            if (p.required) {
                required.put(p.name);
            }
        }
        return required;
    }

    // ================================================================
    //  单个参数 → JSON Schema property
    // ================================================================

    private static JSONObject buildProperty(ToolMeta.Param param) {
        JSONObject prop = new JSONObject();
        try {
            // 类型映射
            switch (param.type) {
                case "int":
                case "long":
                    prop.put("type", "integer");
                    break;
                case "boolean":
                    prop.put("type", "boolean");
                    break;
                case "enum":
                    prop.put("type", "string");
                    if (param.enumValues != null && param.enumValues.length > 0) {
                        JSONArray enumArr = new JSONArray();
                        for (String ev : param.enumValues) {
                            enumArr.put(ev);
                        }
                        prop.put("enum", enumArr);
                    }
                    break;
                default:
                    prop.put("type", "string");
                    break;
            }

            // 描述
            if (param.description != null && !param.description.isEmpty()) {
                prop.put("description", param.description);
            }
        } catch (Exception ignored) {}
        return prop;
    }
}
