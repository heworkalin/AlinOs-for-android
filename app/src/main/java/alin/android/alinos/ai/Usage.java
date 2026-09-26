package alin.android.alinos.ai;

import org.json.JSONObject;

/**
 * Token 用量与费用（对齐 pi-ai 的 Usage 结构）。
 *
 * <p>费用单位：美元。成本按「美元 / 百万 token」的单价计算。
 */
public class Usage {

    /** 未命中缓存的输入 token。 */
    public long input;
    /** 输出 token（已包含 reasoning token）。 */
    public long output;
    /** 命中缓存的读取 token。 */
    public long cacheRead;
    /** 写入缓存的 token。 */
    public long cacheWrite;
    /** 其中 1 小时保留的缓存写入（Anthropic 会上报该拆分）。 */
    public long cacheWrite1h;
    /** 推理 token（output 的子集）。 */
    public long reasoning;

    public double costInput;
    public double costOutput;
    public double costCacheRead;
    public double costCacheWrite;
    public double costTotal;

    public long totalTokens() {
        return input + output + cacheRead + cacheWrite;
    }

    public void add(Usage other) {
        if (other == null) return;
        input += other.input;
        output += other.output;
        cacheRead += other.cacheRead;
        cacheWrite += other.cacheWrite;
        cacheWrite1h += other.cacheWrite1h;
        reasoning += other.reasoning;
        costInput += other.costInput;
        costOutput += other.costOutput;
        costCacheRead += other.costCacheRead;
        costCacheWrite += other.costCacheWrite;
        costTotal += other.costTotal;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("input", input);
            o.put("output", output);
            o.put("cache_read", cacheRead);
            o.put("cache_write", cacheWrite);
            if (cacheWrite1h > 0) o.put("cache_write_1h", cacheWrite1h);
            if (reasoning > 0) o.put("reasoning", reasoning);
            o.put("total_tokens", totalTokens());
            JSONObject c = new JSONObject();
            c.put("input", costInput);
            c.put("output", costOutput);
            c.put("cache_read", costCacheRead);
            c.put("cache_write", costCacheWrite);
            c.put("total", costTotal);
            o.put("cost", c);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Usage fromJson(JSONObject o) {
        Usage u = new Usage();
        if (o == null) return u;
        u.input = o.optLong("input", 0);
        u.output = o.optLong("output", 0);
        u.cacheRead = o.optLong("cache_read", 0);
        u.cacheWrite = o.optLong("cache_write", 0);
        u.cacheWrite1h = o.optLong("cache_write_1h", 0);
        u.reasoning = o.optLong("reasoning", 0);
        JSONObject c = o.optJSONObject("cost");
        if (c != null) {
            u.costInput = c.optDouble("input", 0);
            u.costOutput = c.optDouble("output", 0);
            u.costCacheRead = c.optDouble("cache_read", 0);
            u.costCacheWrite = c.optDouble("cache_write", 0);
            u.costTotal = c.optDouble("total", 0);
        }
        return u;
    }

    /** 人类可读的费用文本，例如 "$0.001234"。 */
    public String costText() {
        if (costTotal <= 0) return "$0";
        if (costTotal < 0.01) return String.format(java.util.Locale.US, "$%.6f", costTotal);
        return String.format(java.util.Locale.US, "$%.4f", costTotal);
    }
}
