package alin.android.alinos.bean;

public class ConfigBean {
    private int id;
    private String type; // OpenAI/Ollama/MCP
    private String serverUrl; // 服务器地址
    private String apiKey; // API密钥（Ollama可空）
    private String model; // 新增：智慧体型号（如gpt-3.5-turbo/llama3）
    private boolean isDefault; // 是否默认配置
    private int maxResponseTokens = 1024; // 模型最大回复消息（默认1024）
    private int userInputCharLimit = 2000; // 用户输入字符限制（默认2000）
    private int modelContextWindow = 4096; // 模型最高极限上下文（默认4096）

    // 空构造器
    public ConfigBean() {}

    // 新增配置构造（含model）
    public ConfigBean(String type, String serverUrl, String apiKey, String model, boolean isDefault) {
        this(type, serverUrl, apiKey, model, isDefault, 1024, 2000, 4096);
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
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public int getMaxResponseTokens() { return maxResponseTokens; }
    public void setMaxResponseTokens(int maxResponseTokens) { this.maxResponseTokens = maxResponseTokens; }

    public int getUserInputCharLimit() { return userInputCharLimit; }
    public void setUserInputCharLimit(int userInputCharLimit) { this.userInputCharLimit = userInputCharLimit; }

    public int getModelContextWindow() { return modelContextWindow; }
    public void setModelContextWindow(int modelContextWindow) { this.modelContextWindow = modelContextWindow; }
}