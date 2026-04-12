package alin.android.alinos.utils;

import android.text.TextUtils;
import android.util.Log;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Token估算工具类
 * 根据三种语言场景估算Token数量：
 * 1. 场景A：纯中文/混合符号 - Token ≈ 字符数 ÷ 1.5
 * 2. 场景B：纯英文/数字 - Token ≈ 单词数 × 1.3
 * 3. 场景C：中英混合 - (中文字符 ÷ 1.5) + (英文单词 × 1.3)
 */
public class TokenEstimator {
    private static final String TAG = "TokenEstimator";

    // 中文字符正则表达式（包括基本CJK统一表意文字和扩展A区）
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fff\\u3400-\\u4dbf]");
    // 英文单词分割正则表达式（按非单词字符分割）
    private static final Pattern WORD_SPLIT_PATTERN = Pattern.compile("\\W+");

    // 估算系数
    private static final double CHINESE_TOKEN_RATIO = 1.5; // 中文字符数 ÷ 1.5
    private static final double ENGLISH_TOKEN_RATIO = 1.3; // 英文单词数 × 1.3

    /**
     * 估算文本的Token数量
     * @param text 输入文本
     * @return 估算的Token数量（四舍五入到整数）
     */
    public static int estimateTokens(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }

        // 分析文本类型
        TextAnalysis analysis = analyzeText(text);

        // 根据分析结果选择估算方法
        if (analysis.isPureChinese()) {
            // 场景A：纯中文/混合符号
            return estimateChineseTokens(text);
        } else if (analysis.isPureEnglish()) {
            // 场景B：纯英文/数字
            return estimateEnglishTokens(text);
        } else {
            // 场景C：中英混合
            return estimateMixedTokens(text, analysis);
        }
    }

    /**
     * 场景A：纯中文/混合符号估算
     * Token ≈ 字符数 ÷ 1.5
     */
    private static int estimateChineseTokens(String text) {
        int charCount = text.length();
        double tokens = charCount / CHINESE_TOKEN_RATIO;
        return (int) Math.round(tokens);
    }

    /**
     * 场景B：纯英文/数字估算
     * Token ≈ 单词数 × 1.3
     */
    private static int estimateEnglishTokens(String text) {
        int wordCount = countEnglishWords(text);
        double tokens = wordCount * ENGLISH_TOKEN_RATIO;
        return (int) Math.round(tokens);
    }

    /**
     * 场景C：中英混合估算
     * Token ≈ (中文字符 ÷ 1.5) + (英文单词 × 1.3)
     */
    private static int estimateMixedTokens(String text, TextAnalysis analysis) {
        // 计算中文字符数
        int chineseCharCount = analysis.chineseCharCount;
        double chineseTokens = chineseCharCount / CHINESE_TOKEN_RATIO;

        // 计算英文单词数
        int englishWordCount = countEnglishWords(text);
        double englishTokens = englishWordCount * ENGLISH_TOKEN_RATIO;

        double totalTokens = chineseTokens + englishTokens;
        return (int) Math.round(totalTokens);
    }

    /**
     * 统计英文单词数
     */
    private static int countEnglishWords(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }

        // 移除首尾空白，按非单词字符分割
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }

        String[] words = WORD_SPLIT_PATTERN.split(trimmed);
        int count = 0;
        for (String word : words) {
            if (!word.isEmpty() && isEnglishWord(word)) {
                count++;
            }
        }

        return count;
    }

    /**
     * 判断字符串是否是英文单词（包含数字）
     */
    private static boolean isEnglishWord(String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }

        // 检查是否包含至少一个英文字母或数字
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isLetter(c) || Character.isDigit(c)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 分析文本类型
     */
    private static TextAnalysis analyzeText(String text) {
        TextAnalysis analysis = new TextAnalysis();

        if (TextUtils.isEmpty(text)) {
            return analysis;
        }

        int chineseCount = 0;
        int englishCount = 0;
        int otherCount = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (isChineseCharacter(c)) {
                chineseCount++;
            } else if (isEnglishCharacter(c)) {
                englishCount++;
            } else {
                otherCount++;
            }
        }

        analysis.chineseCharCount = chineseCount;
        analysis.englishCharCount = englishCount;
        analysis.otherCharCount = otherCount;
        analysis.totalCharCount = text.length();

        return analysis;
    }

    /**
     * 判断字符是否为中文字符
     */
    private static boolean isChineseCharacter(char c) {
        // 检查基本CJK统一表意文字和扩展A区
        return (c >= '\u4e00' && c <= '\u9fff') ||
               (c >= '\u3400' && c <= '\u4dbf');
    }

    /**
     * 判断字符是否为英文字符（字母或数字）
     */
    private static boolean isEnglishCharacter(char c) {
        // 英文字母（大小写）或数字
        return (c >= 'A' && c <= 'Z') ||
               (c >= 'a' && c <= 'z') ||
               (c >= '0' && c <= '9');
    }

    /**
     * 文本分析结果类
     */
    private static class TextAnalysis {
        int chineseCharCount = 0;
        int englishCharCount = 0;
        int otherCharCount = 0;
        int totalCharCount = 0;

        boolean isPureChinese() {
            // 纯中文：有中文字符，没有英文字符
            return chineseCharCount > 0 && englishCharCount == 0;
        }

        boolean isPureEnglish() {
            // 纯英文：有英文字符，没有中文字符
            return englishCharCount > 0 && chineseCharCount == 0;
        }

        boolean isMixed() {
            // 混合：既有中文又有英文
            return chineseCharCount > 0 && englishCharCount > 0;
        }
    }

    /**
     * 快速估算方法（粗略法）
     * 混合内容总长度 ≈ 字符数 × 0.6
     * 用于快速估算，精度较低
     */
    public static int estimateTokensQuick(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }

        int charCount = text.length();
        double tokens = charCount * 0.6; // 快速粗略法
        return (int) Math.round(tokens);
    }

    /**
     * 估算消息列表的总Token数
     * @param messages 消息列表（每条消息的content）
     * @return 总Token数
     */
    public static int estimateMessagesTokens(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int totalTokens = 0;
        for (String message : messages) {
            totalTokens += estimateTokens(message);
        }

        return totalTokens;
    }
}