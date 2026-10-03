package alin.android.alinos.log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息过滤：在门面层统一脱敏，避免各调用点自己处理。
 *
 * <p>规则以键值对形式匹配（key=value / key: value），命中后 value 替换为 {@code ***}。
 * 调试阶段可在日志界面关闭。
 */
public final class SensitiveFilter {

    private SensitiveFilter() {
    }

    /** 需要脱敏的键名（不区分大小写）。 */
    private static final String KEYS =
            "password|passwd|pwd|token|secret|api[_-]?key|apikey|authorization|"
            + "access[_-]?key|private[_-]?key|passphrase|credential|cookie|session[_-]?id";

    /** key=value / key: value / key"value" 形式。 */
    private static final Pattern KV = Pattern.compile(
            "(?i)(" + KEYS + ")\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|\\S+)");

    /** Bearer / Basic 认证头。 */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(bearer|basic)\\s+[A-Za-z0-9._\\-+/=]{8,}");

    /** 疑似私钥块。 */
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    public static String filter(String input) {
        if (input == null || input.isEmpty()) return input;
        String out = input;

        out = PRIVATE_KEY.matcher(out).replaceAll("-----BEGIN PRIVATE KEY-----***");
        out = BEARER.matcher(out).replaceAll("$1 ***");
        out = replaceKv(out);
        return out;
    }

    private static String replaceKv(String input) {
        Matcher m = KV.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            // 值很短且明显非敏感（如 true/false/数字小值）也一并遮盖，保持规则简单可预期
            String masked = value.startsWith("\"") ? "\"***\""
                    : value.startsWith("'") ? "'***'" : "***";
            m.appendReplacement(sb, Matcher.quoteReplacement(key + "=" + masked));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
