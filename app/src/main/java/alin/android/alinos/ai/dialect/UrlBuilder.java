package alin.android.alinos.ai.dialect;

/**
 * URL 拼接工具 —— 统一处理「baseUrl 可能已带版本前缀」的兼容问题。
 *
 * <p>例如配置 {@code https://host:8080/v1} 时，不应拼成 {@code /v1/v1/chat/completions}。
 */
public final class UrlBuilder {

    private UrlBuilder() {
    }

    /**
     * @param baseUrl     用户配置（可为空）
     * @param defaultBase 协议默认 base
     * @param versionPath 版本前缀，如 {@code /v1}
     * @param endpoint    端点后缀，如 {@code /chat/completions}
     */
    public static String endpoint(String baseUrl, String defaultBase,
                                  String versionPath, String endpoint) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) b = defaultBase;
        if (!b.startsWith("http")) b = "https://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);

        // 已经是完整端点
        if (b.endsWith(endpoint)) return b;
        // base 已带版本前缀（/v1、/v1beta、/openai/v1 等）
        if (b.endsWith(versionPath)) return b + endpoint;
        // base 里出现过版本前缀（如 /openai/v1/ 之类）但不在末尾
        if (b.contains(versionPath + "/")) {
            return b + endpoint;
        }
        return b + versionPath + endpoint;
    }
}
