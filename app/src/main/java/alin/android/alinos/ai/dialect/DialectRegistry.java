package alin.android.alinos.ai.dialect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协议注册表：按 api id 取到对应的 {@link ApiDialect}。
 *
 * <p>未知协议回退到 {@code openai-completions}，保证老配置可用。
 */
public final class DialectRegistry {

    private static final Map<String, ApiDialect> DIALECTS = new LinkedHashMap<>();

    static {
        register(new ChatCompletionsDialect());
        register(new ResponsesDialect());
        register(new AnthropicMessagesDialect());
    }

    private DialectRegistry() {
    }

    private static void register(ApiDialect d) {
        DIALECTS.put(d.id(), d);
    }

    public static ApiDialect get(String apiId) {
        if (apiId == null || apiId.trim().isEmpty()) {
            return DIALECTS.get(ChatCompletionsDialect.ID);
        }
        ApiDialect d = DIALECTS.get(apiId.trim());
        if (d != null) return d;
        // 兼容别名
        if (apiId.contains("responses")) return DIALECTS.get(ResponsesDialect.ID);
        if (apiId.contains("anthropic")) return DIALECTS.get(AnthropicMessagesDialect.ID);
        return DIALECTS.get(ChatCompletionsDialect.ID);
    }

    public static boolean supports(String apiId) {
        return apiId != null && DIALECTS.containsKey(apiId.trim());
    }

    public static List<String> ids() {
        return new ArrayList<>(DIALECTS.keySet());
    }
}
