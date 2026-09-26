package alin.android.alinos.bean;

public class ConfigBean {
    private int id;
    private String type; // DeepSeek / OpenAI / Ollama
    private String serverUrl; // 服务器地址
    private String apiKey; // API密钥（Ollama可空）
    private String model; // 新增：智慧体型号（如gpt-3.5-turbo/llama3）
    /** 模型提供方 id（deepseek / openai / anthropic / ...），用于查注册表。 */
    private String providerId;
    /** 协议类型：openai-completions / openai-responses / anthropic-messages / auto。 */
    private String apiType;
    /** 自定义/高级参数（JSON 对象字符串）。 */
    private String extraJson;
    private boolean isDefault; // 是否默认配置
    private int maxResponseTokens = 8192; // 模型最大回复消息（默认8192）
    private int userInputCharLimit = 200000; // 用户输入字符限制（默认20万）
    private int modelContextWindow = 131072; // 模型上下文窗口（默认128K）

    // 空构造器
    public ConfigBean() {}

    // 新增配置构造（含model）
    public ConfigBean(String type, String serverUrl, String apiKey, String model, boolean isDefault) {
        this(type, serverUrl, apiKey, model, isDefault, 8192, 200000, 131072);
    }

    // 新增配置构造（含所有参数）
    public ConfigBean(String type, String serverUrl, String apiKey, String model, boolean isDefault,
                     int maxResponseTokens, int userInputCharLimit, int modelContextWindow) {
        this.type = type;
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.isDefault = isDefault;
        this.maxResponseTokens = maxResponseTokens;
        this.userInputCharLimit = userInputCharLimit;
        this.modelContextWindow = modelContextWindow;
    }

    // ========== 修复后的拷贝构造器（核心修改） ==========
    public ConfigBean(ConfigBean source) {
        // 复制source对象的所有字段（字段名称与类定义完全匹配）
        this.id = source.getId();
        this.type = source.getType();
        this.serverUrl = source.getServerUrl();
        this.apiKey = source.getApiKey();
        this.model = source.getModel();
        this.providerId = source.getProviderId();
        this.apiType = source.getApiType();
        this.extraJson = source.getExtraJson();
        this.isDefault = source.isDefault(); // 修正：isDefault 而非 enable
        this.maxResponseTokens = source.getMaxResponseTokens();
        this.userInputCharLimit = source.getUserInputCharLimit();
        this.modelContextWindow = source.getModelContextWindow();
    }

    // getter/setter（保持原有，确保方法名称正确）
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }

    public String getApiType() { return apiType; }
    public void setApiType(String apiType) { this.apiType = apiType; }

    public String getExtraJson() { return extraJson; }
    public void setExtraJson(String extraJson) { this.extraJson = extraJson; }

    /** 解析自定义参数为可编辑的 key-value 列表（保持插入顺序）。 */
    public java.util.List<String[]> getExtraPairs() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (extraJson == null || extraJson.trim().isEmpty()) return out;
        try {
            org.json.JSONObject o = new org.json.JSONObject(extraJson);
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = o.opt(k);
                out.add(new String[]{k, v == null ? "" : String.valueOf(v)});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 由 key-value 列表重建 extraJson。 */
    public void setExtraPairs(java.util.List<String[]> pairs) {
        org.json.JSONObject o = new org.json.JSONObject();
        if (pairs != null) {
            for (String[] p : pairs) {
                if (p == null || p.length < 1) continue;
                String k = p[0] == null ? "" : p[0].trim();
                if (k.isEmpty()) continue;
                String v = p.length > 1 && p[1] != null ? p[1] : "";
                try {
                    o.put(k, parseValue(v));
                } catch (Exception ignored) {
                }
            }
        }
        this.extraJson = o.length() == 0 ? null : o.toString();
    }

    /** 把输入字符串尽量转成 JSON 原生类型（数字/布尔），否则保留字符串。 */
    private static Object parseValue(String v) {
        String s = v == null ? "" : v.trim();
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        if ("null".equalsIgnoreCase(s)) return org.json.JSONObject.NULL;
        try {
            if (s.matches("-?\\d+")) return Long.parseLong(s);
            if (s.matches("-?\\d*\\.\\d+([eE][-+]?\\d+)?")) return Double.parseDouble(s);
        } catch (Exception ignored) {
        }
        return v == null ? "" : v;
    }

    /** 生效的协议：未配置时按 provider/type 推断。 */
    public String effectiveApiType() {
        if (apiType != null && !apiType.trim().isEmpty() && !"auto".equals(apiType)) {
            return apiType.trim();
        }
        String pid = providerId == null ? "" : providerId.toLowerCase();
        if (pid.contains("anthropic") || "claude".equalsIgnoreCase(type)) return "anthropic-messages";
        return "openai-completions";
    }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public int getMaxResponseTokens() { return maxResponseTokens; }
    public void setMaxResponseTokens(int maxResponseTokens) { this.maxResponseTokens = maxResponseTokens; }

    public int getUserInputCharLimit() { return userInputCharLimit; }
    public void setUserInputCharLimit(int userInputCharLimit) { this.userInputCharLimit = userInputCharLimit; }

    public int getModelContextWindow() { return modelContextWindow; }
    public void setModelContextWindow(int modelContextWindow) { this.modelContextWindow = modelContextWindow; }
}