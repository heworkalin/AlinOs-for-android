package alin.android.alinos.bean;

public class ChatSessionBean {
    private int id;
    private String sessionName;
    private int configId;
    private long createTime;
    private int sessionType; // 添加sessionType字段

    // 构造方法
    public ChatSessionBean() {}

    public ChatSessionBean(String sessionName, int configId, long createTime) {
        this.sessionName = sessionName;
        this.configId = configId;
        this.createTime = createTime;
        this.sessionType = 0; // 默认普通会话
    }

    // Getter & Setter
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    
    public String getSessionName() { return sessionName; }
    public void setSessionName(String sessionName) { this.sessionName = sessionName; }
    
    public int getConfigId() { return configId; }
    public void setConfigId(int configId) { this.configId = configId; }
    
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    
    public int getSessionType() { return sessionType; }
    public void setSessionType(int sessionType) { this.sessionType = sessionType; }
}