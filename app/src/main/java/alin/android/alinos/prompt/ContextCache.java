package alin.android.alinos.prompt;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.bean.ChatRecordBean;
import alin.android.alinos.utils.TokenEstimator;

/**
 * 对话上下文缓存 —— 替代简单的"取最后10条"策略。
 *
 * 核心规则：
 * 1. 用户消息（msgType=0）：全部保留，不截断
 * 2. AI 回复（msgType=1）：全部保留，不截断
 * 3. 工具结果（msgType=2/tool）：替换为一行摘要，格式：
 *    [工具摘要] shell_exec: apt install curl → 成功(1200ms)
 * 4. 当估算 token 超限时，从最旧的工具摘要开始丢弃
 *
 * 使用方式：
 *   ContextCache cache = new ContextCache(chatHistory, maxTokens);
 *   JSONArray messages = cache.buildMessages();
 */
public class ContextCache {

    private static final String TAG = "ContextCache";
    private static final int DEFAULT_MAX_TOKENS = 8000; // 为其他内容留出空间

    private final List<ChatRecordBean> mSource;
    private final int mMaxTokens;

    public ContextCache(List<ChatRecordBean> chatHistory) {
        this(chatHistory, DEFAULT_MAX_TOKENS);
    }

    public ContextCache(List<ChatRecordBean> chatHistory, int maxTokens) {
        this.mSource = chatHistory;
        this.mMaxTokens = maxTokens;
    }

    /**
     * 构建压缩后的 messages JSONArray（不含 system prompt 和当前用户输入）。
     * 外部负责拼接 system + messages + 当前输入。
     */
    public JSONArray buildMessages() {
        JSONArray arr = new JSONArray();
        List<CompressedEntry> entries = compress();

        for (CompressedEntry e : entries) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("role", e.role);
                msg.put("content", e.content);
                arr.put(msg);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    /**
     * 压缩结果：保留用户消息和 AI 回复原文，工具结果替换为单行摘要。
     */
    private List<CompressedEntry> compress() {
        List<CompressedEntry> entries = new ArrayList<>();
        int totalTokens = 0;

        // 从旧到新遍历
        for (ChatRecordBean r : mSource) {
            if (isEmpty(r.getContent())) continue;

            String role;
            String content;

            if (r.getMsgType() == 0) {
                // 用户消息：全文保留
                role = "user";
                content = r.getContent();
            } else if (r.getMsgType() == 2 || r.getContent().startsWith("[tool_call")) {
                // 工具标记：跳过（这些只是占位符，没有实际信息量）
                continue;
            } else if (r.getContent().startsWith("[工具摘要]")) {
                // 已经是摘要格式，直接保留
                role = "user"; // 摘要作为 user 消息注入
                content = r.getContent();
            } else {
                // AI 回复或其他：保留原文
                role = "assistant";
                content = r.getContent();
            }

            int tokens = TokenEstimator.estimateTokens(content);
            entries.add(new CompressedEntry(role, content, tokens));
            totalTokens += tokens;
        }

        // Token 超限时从最旧的非用户/非 AI 消息开始丢弃
        if (totalTokens > mMaxTokens) {
            entries = trimToBudget(entries, totalTokens);
        }

        Log.d(TAG, "上下文压缩: " + mSource.size() + "条原始 → "
                + entries.size() + "条, 估算 " + totalTokens + " tokens");
        return entries;
    }

    /**
     * 从旧到新丢弃，优先丢工具摘要，最后才丢 AI 回复和用户消息。
     */
    private List<CompressedEntry> trimToBudget(List<CompressedEntry> entries, int currentTokens) {
        List<CompressedEntry> result = new ArrayList<>(entries);

        // 第一轮：从旧到新丢弃 tool 摘要
        for (int i = 0; i < result.size() && currentTokens > mMaxTokens; i++) {
            CompressedEntry e = result.get(i);
            if (e.content.startsWith("[工具摘要]")) {
                currentTokens -= e.tokens;
                result.set(i, null);
            }
        }

        // 第二轮：从旧到新丢弃 AI 回复（长文本优先）
        for (int i = 0; i < result.size() && currentTokens > mMaxTokens; i++) {
            CompressedEntry e = result.get(i);
            if (e != null && "assistant".equals(e.role)) {
                currentTokens -= e.tokens;
                result.set(i, null);
            }
        }

        // 第三轮：从旧到新丢弃用户消息（尽量不丢）
        for (int i = 0; i < result.size() && currentTokens > mMaxTokens; i++) {
            CompressedEntry e = result.get(i);
            if (e != null && "user".equals(e.role) && !e.content.startsWith("[工具摘要]")) {
                currentTokens -= e.tokens;
                result.set(i, null);
            }
        }

        // 清理 null 条目
        List<CompressedEntry> cleaned = new ArrayList<>();
        for (CompressedEntry e : result) {
            if (e != null) cleaned.add(e);
        }
        return cleaned;
    }

    // ─── 工具结果 → 摘要转换（外部调用，写入 chat_record） ───

    /**
     * 将工具执行结果转为单行摘要，可写入 chat_record 作为压缩后的历史。
     *
     * @param toolName   工具名（如 shell_exec）
     * @param command    命令/参数摘要（如 apt install curl）
     * @param status     执行结果（success / error）
     * @param durationMs 耗时（毫秒）
     * @param resultPreview 结果预览（前 60 字符）
     * @return 摘要文本
     */
    public static String buildToolSummary(String toolName, String command,
                                           String status, long durationMs,
                                           String resultPreview) {
        String statusIcon = "error".equals(status) ? "失败" : "成功";
        String cmdShort = command.length() > 60 ? command.substring(0, 57) + "..." : command;
        String resultShort = resultPreview != null && !resultPreview.isEmpty()
                ? " → " + resultPreview.substring(0, Math.min(60, resultPreview.length()))
                : "";

        return "[工具摘要] " + toolName + ": " + cmdShort
                + " → " + statusIcon + "(" + durationMs + "ms)" + resultShort;
    }

    /**
     * 返回压缩后的 ChatRecordBean 列表（供旧接口兼容）。
     * 工具标记替换为摘要，用户/AI 消息保留原文。
     */
    public List<ChatRecordBean> buildRecordList() {
        List<CompressedEntry> entries = compress();
        List<ChatRecordBean> result = new ArrayList<>();
        for (CompressedEntry e : entries) {
            int msgType = "user".equals(e.role) ? 0 : 1;
            ChatRecordBean r = new ChatRecordBean();
            r.setMsgType(msgType);
            r.setContent(e.content);
            result.add(r);
        }
        return result;
    }

    // ─── 辅助 ───

    private boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    static class CompressedEntry {
        final String role;
        final String content;
        final int tokens;

        CompressedEntry(String role, String content, int tokens) {
            this.role = role;
            this.content = content;
            this.tokens = tokens;
        }
    }
}
