package alin.android.alinos.tools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 工具元数据：描述一个可调用的工具函数及其参数。
 *
 * <p>重构要点（统一接口约定）：
 * <ul>
 *   <li>{@link Scope} —— 工具的可见面：{@link Scope#AI} 暴露给模型 / MCP，
 *       {@link Scope#INTERNAL} 仅供测试界面与内部调用，{@link Scope#DEBUG} 为调试能力；</li>
 *   <li>{@link Category} —— 能力分组，供测试界面分类展示与按需查询；</li>
 *   <li>所有执行的返回值都应带上 {@code status} 字段（见 {@link #ok()} / {@link #error(String)}），
 *       错误统一收敛到 {@code error} 字段。</li>
 * </ul>
 */
public class ToolMeta {

    /** 工具可见面。 */
    public enum Scope {
        /** 暴露给 AI / MCP 客户端。 */
        AI,
        /** 仅内部与测试界面可用，不喂给模型。 */
        INTERNAL,
        /** 调试能力，仅在开发者界面出现。 */
        DEBUG
    }

    /** 能力分组。 */
    public enum Category {
        /** 容器内文件与命令执行（proot）。 */
        CONTAINER,
        /** 宿主侧 PTY 终端会话。 */
        SHELL,
        /** 远端 SSH。 */
        SSH,
        /** 音频能力（ASR / TTS / KWS / 声纹）。 */
        AUDIO,
        /** 系统 / 元信息。 */
        SYSTEM,
        /** 元工具（工具发现等）。 */
        META
    }

    public final String displayName;
    public final String description;
    public final String functionName;
    public final Param[] params;
    public final Executor executor;
    public final Scope scope;
    public final Category category;

    public ToolMeta(String displayName, String description, String functionName,
                    Param[] params, Executor executor, Scope scope, Category category) {
        this.displayName = displayName;
        this.description = description;
        this.functionName = functionName;
        this.params = params;
        this.executor = executor;
        this.scope = scope;
        this.category = category;
    }

    /** 兼容旧构造：默认 AI 可见、SYSTEM 分组。 */
    public ToolMeta(String displayName, String description, String functionName,
                    Param[] params, Executor executor) {
        this(displayName, description, functionName, params, executor, Scope.AI, Category.SYSTEM);
    }

    public static class Param {
        public final String name;
        public final String type;       // "string", "long", "int", "boolean", "enum"
        public final boolean required;
        public final String defaultValue;
        public final String description;
        public final String[] enumValues;

        public Param(String name, String type, boolean required,
                     String defaultValue, String description, String[] enumValues) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.defaultValue = defaultValue;
            this.description = description;
            this.enumValues = enumValues;
        }

        public Param(String name, String type, boolean required,
                     String defaultValue, String description) {
            this(name, type, required, defaultValue, description, null);
        }
    }

    @FunctionalInterface
    public interface Executor {
        JSONObject execute(JSONObject params) throws Exception;
    }

    // =====================================================================
    //  统一返回约定（轻量方案）
    //
    //  成功：{ "status": "success", <工具专有字段...> }
    //  失败：{ "status": "error", "error": "<message>", <可选字段...> }
    //
    //  约定：工具专有字段保留在顶层，避免大范围改动消费方与历史数据；
    //        仅强制每个工具都带 status，并统一错误字段名。
    // =====================================================================

    /** 构造成功结果（工具专有字段由调用方继续 put）。 */
    public static JSONObject ok() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", "success");
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 在已有对象上标记成功。 */
    public static JSONObject markOk(JSONObject o) {
        if (o == null) return ok();
        try {
            o.put("status", "success");
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 构造失败结果。 */
    public static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("status", "error");
            o.put("error", message == null ? "unknown error" : message);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 在已有对象上标记失败。 */
    public static JSONObject markError(JSONObject o, String message) {
        if (o == null) return error(message);
        try {
            o.put("status", "error");
            o.put("error", message == null ? "unknown error" : message);
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * 归一化旧式返回：把 {@code error_code}/{@code message} 收敛为统一的 {@code error}，
     * 并保证一定有 {@code status}。
     */
    public static JSONObject normalize(JSONObject o) {
        if (o == null) return error("empty result");
        try {
            String status = o.optString("status", "success");
            if (status.isEmpty()) status = "success";
            o.put("status", status);
            if ("error".equals(status) && !o.has("error")) {
                String msg = o.optString("message", "");
                String code = o.optString("error_code", "");
                if (msg.isEmpty()) msg = code.isEmpty() ? "unknown error" : code;
                o.put("error", msg);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    // 辅助：快速构造参数列表
    public static Param[] params(Param... ps) { return ps; }
    public static Param param(String name, String type, boolean required,
                               String defaultValue, String description, String[] enumValues) {
        return new Param(name, type, required, defaultValue, description, enumValues);
    }
    public static Param param(String name, String type, boolean required,
                               String defaultValue, String description) {
        return new Param(name, type, required, defaultValue, description);
    }

    // =====================================================================
    //  工具调用 JSON 解析（兼容扁平与嵌套两种形态）
    //
    //  本项目内部累积器产出扁平结构： {"id","name","arguments"}
    //  but 历史数据 / 某些 provider 可能给出 OpenAI 嵌套结构：
    //  {"id","type","function":{"name","arguments"}}
    // =====================================================================

    /** 从单个 tool_call 对象中取出工具名，兼容扁平/嵌套。 */
    public static String toolCallName(JSONObject tc) {
        if (tc == null) return "";
        JSONObject fn = tc.optJSONObject("function");
        if (fn != null) return fn.optString("name", "");
        return tc.optString("name", "");
    }

    /** 从单个 tool_call 对象中取出参数字符串，兼容扁平/嵌套。 */
    public static String toolCallArguments(JSONObject tc) {
        if (tc == null) return "{}";
        JSONObject fn = tc.optJSONObject("function");
        String args = fn != null ? fn.optString("arguments", "") : tc.optString("arguments", "");
        return (args == null || args.trim().isEmpty()) ? "{}" : args;
    }

    /**
     * 把 tool_calls 数组归一化为 OpenAI 嵌套结构（发往 API 的标准形态）：
     * {@code [{"id","type":"function","function":{"name","arguments"}}]}。
     *
     * <p>本项目内部累积器产出扁平结构（{@code {id,name,arguments}}），
     * 但发往各 provider 前必须转成嵌套形态（Anthropic / Responses 等方言
     * 都按 {@code function} 包裹解析）。
     */
    public static JSONArray normalizeToolCalls(JSONArray toolCalls) {
        JSONArray out = new JSONArray();
        if (toolCalls == null) return out;
        for (int i = 0; i < toolCalls.length(); i++) {
            JSONObject tc = toolCalls.optJSONObject(i);
            if (tc == null) continue;
            JSONObject norm = new JSONObject();
            try {
                String id = tc.optString("id", "");
                if (id.isEmpty()) id = "call_" + i;
                norm.put("id", id);
                norm.put("type", "function");
                JSONObject fn = new JSONObject();
                fn.put("name", toolCallName(tc));
                fn.put("arguments", toolCallArguments(tc));
                norm.put("function", fn);
            } catch (Exception ignored) {
                continue;
            }
            out.put(norm);
        }
        return out;
    }
}
