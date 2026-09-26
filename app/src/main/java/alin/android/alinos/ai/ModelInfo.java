package alin.android.alinos.ai;

import org.json.JSONObject;

/**
 * 单个模型定义（来自 assets/models.json，由 pi 的 provider 数据转译）。
 *
 * <p>成本单位为 <b>美元 / 百万 token</b>，与上游一致。
 */
public class ModelInfo {

    /** 模型 id，如 deepseek-v4-flash。 */
    public final String id;
    /** 展示名。 */
    public final String name;
    /** 协议类型：openai-completions / openai-responses / anthropic-messages / ...。 */
    public final String api;
    /** 默认 base URL（可能为空，由 provider 决定）。 */
    public final String baseUrl;
    /** 所属 provider id，如 deepseek。 */
    public final String providerId;
    /** 上下文窗口（token）。 */
    public final int contextWindow;
    /** 单次最大输出（token）。 */
    public final int maxTokens;
    /** 是否支持推理/思考。 */
    public final boolean reasoning;
    /** 支持模态，如 ["text"]、["text","image"]。 */
    public final String input;
    /** 原始 cost 对象（含 input/output/cacheRead/cacheWrite，可能有 tiers）。 */
    public final JSONObject cost;

    public ModelInfo(String providerId, String id, String name, String api, String baseUrl,
                     int contextWindow, int maxTokens, boolean reasoning,
                     String input, JSONObject cost) {
        this.providerId = providerId;
        this.id = id;
        this.name = name;
        this.api = api;
        this.baseUrl = baseUrl;
        this.contextWindow = contextWindow;
        this.maxTokens = maxTokens;
        this.reasoning = reasoning;
        this.input = input;
        this.cost = cost;
    }

    /** 是否支持图片输入。 */
    public boolean supportsImage() {
        return input != null && input.contains("image");
    }

    @Override
    public String toString() {
        return name == null ? id : name;
    }
}
