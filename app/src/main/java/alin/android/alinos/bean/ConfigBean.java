package alin.android.alinos.bean;

public class ConfigBean {
    private int id;
    private String type; // OpenAI/Ollama/MCP
    private String serverUrl; // 服务器地址
    private String apiKey; // API密钥（Ollama可空）
    private String model; // 新增：智慧体型号（如gpt-3.5-turbo/llama3）
    private boolean isDefault; // 是否默认配置

    // 空构造
    public ConfigBean() {}

    // 新增配置构造（含model）
    public ConfigBean(String type, String serverUrl, String apiKey, String model, boolean isDefault) {
        this.type = type;
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.isDefault = isDefault;
    }

    // getter/setter（新增model的getter/setter）
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
}