package alin.android.alinos.tools;

import org.json.JSONObject;

/** 工具元数据：描述一个可调用的工具函数及其参数。 */
public class ToolMeta {
    public final String displayName;
    public final String description;
    public final String functionName;
    public final Param[] params;
    public final Executor executor;

    public ToolMeta(String displayName, String description, String functionName,
                    Param[] params, Executor executor) {
        this.displayName = displayName;
        this.description = description;
        this.functionName = functionName;
        this.params = params;
        this.executor = executor;
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
}
