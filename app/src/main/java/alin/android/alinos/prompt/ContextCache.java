package alin.android.alinos.prompt;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.bean.ChatRecordBean;

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

    private final List<ChatRecordBean> mSource;

    public ContextCache(List<ChatRecordBean> chatHistory) {
        this.mSource = chatHistory;
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
     * 压缩结果：保留用户消息和 AI 回复原文，工具标记跳过。
     * 不做本地 token 估算截断——token 溢出由服务器报错判定，本地估算仅给用户参考。
     */
    private List<CompressedEntry> compress() {
        List<CompressedEntry> entries = new ArrayList<>();

        for (ChatRecordBean r : mSource) {
            if (isEmpty(r.getContent())) continue;

            String role;
            String content;

            if (r.getMsgType() == 0) {
                role = "user";
                content = r.getContent();
            } else if (r.getMsgType() == 2 || r.getContent().startsWith("[tool_call")) {
                // 工具标记跳过（只有 UUID+工具名，没信息量）
                continue;
            } else {
                role = "assistant";
                content = r.getContent();
            }

            entries.add(new CompressedEntry(role, content));
        }

        Log.d(TAG, "上下文: " + mSource.size() + "条原始 → " + entries.size() + "条");
        return entries;
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

        CompressedEntry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
