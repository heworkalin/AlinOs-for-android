package alin.android.alinos.ai;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 费用计算 —— 逻辑与 pi-ai 的 {@code calculateCost} 保持一致。
 *
 * <p>单价单位为「美元 / 百万 token」，cost 配置结构：
 * <pre>
 * {"input":0.14, "output":0.28, "cacheRead":0.0028, "cacheWrite":0,
 *  "tiers":[{"inputTokensAbove":200000, "input":..., "output":...}]}
 * </pre>
 */
public final class CostCalculator {

    private CostCalculator() {
    }

    /** 按模型 cost 配置计算 usage 的费用（原地写入 usage 的 cost* 字段）。 */
    public static void calculate(JSONObject costCfg, Usage usage) {
        if (costCfg == null || usage == null) return;

        long inputTokens = usage.input + usage.cacheRead + usage.cacheWrite;

        // 阶梯定价：取「门槛低于当前输入量」中门槛最高的一档
        JSONObject rates = costCfg;
        double matchedThreshold = -1;
        JSONArray tiers = costCfg.optJSONArray("tiers");
        if (tiers != null) {
            for (int i = 0; i < tiers.length(); i++) {
                JSONObject t = tiers.optJSONObject(i);
                if (t == null) continue;
                double above = t.optDouble("inputTokensAbove", -1);
                if (inputTokens > above && above > matchedThreshold) {
                    rates = t;
                    matchedThreshold = above;
                }
            }
        }

        double rateInput = rates.optDouble("input", 0);
        double rateOutput = rates.optDouble("output", 0);
        double rateCacheRead = rates.optDouble("cacheRead", 0);
        double rateCacheWrite = rates.optDouble("cacheWrite", 0);

        long longWrite = usage.cacheWrite1h;
        long shortWrite = usage.cacheWrite - longWrite;

        usage.costInput = rateInput / 1_000_000.0 * usage.input;
        usage.costOutput = rateOutput / 1_000_000.0 * usage.output;
        usage.costCacheRead = rateCacheRead / 1_000_000.0 * usage.cacheRead;
        // Anthropic：1 小时保留的缓存写入按 2 倍输入价计费
        usage.costCacheWrite = (rateCacheWrite * shortWrite + rateInput * 2 * longWrite) / 1_000_000.0;
        usage.costTotal = usage.costInput + usage.costOutput
                + usage.costCacheRead + usage.costCacheWrite;
    }

    /** 便捷方法：为指定模型计算。 */
    public static void calculate(ModelInfo model, Usage usage) {
        if (model == null) return;
        calculate(model.cost, usage);
    }
}
