package alin.android.alinos.ai;

import java.util.ArrayList;
import java.util.List;

/** 一个模型提供方（provider）及其模型列表。 */
public class ProviderInfo {

    /** provider id，如 deepseek / openai / anthropic。 */
    public final String id;
    /** 展示名。 */
    public final String name;
    /** 该 provider 下所有模型。 */
    public final List<ModelInfo> models = new ArrayList<>();
    /** 该 provider 出现的协议统计（api → 模型数）。 */
    public final List<String[]> apis = new ArrayList<>();

    public ProviderInfo(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** 默认协议（第一个模型的 api）。 */
    public String defaultApi() {
        return models.isEmpty() ? "openai-completions" : models.get(0).api;
    }

    /** 默认 base URL。 */
    public String defaultBaseUrl() {
        for (ModelInfo m : models) {
            if (m.baseUrl != null && !m.baseUrl.isEmpty()) return m.baseUrl;
        }
        return "";
    }

    @Override
    public String toString() {
        return name;
    }
}
