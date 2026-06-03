package alin.android.alinos.bean;

/**
 * SSH 配置实体。
 */
public class SshConfigBean {
    private int id;
    private String name;
    private String host;
    private int port;
    private String username;
    private String password;
    private String authType;   // "password" / "key"
    private String keyContent;
    private String description;
    private String configType;  // "local_termux" / "remote"

    public SshConfigBean() {
        this.port = 8022;
        this.authType = "password";
        this.configType = "remote";
    }

    public SshConfigBean(String name, String host, int port, String username,
                         String password, String authType, String keyContent,
                         String description, String configType) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.authType = authType;
        this.keyContent = keyContent;
        this.description = description;
        this.configType = configType;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getKeyContent() { return keyContent; }
    public void setKeyContent(String keyContent) { this.keyContent = keyContent; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getConfigType() { return configType; }
    public void setConfigType(String configType) { this.configType = configType; }
}
