package alin.android.alinos.prompt;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.bean.ChatRecordBean;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ChatDBHelper;
import alin.android.alinos.manager.ChatStreamEventBus;
import alin.android.alinos.net.OpenAIStreamNetHelper;
import alin.android.alinos.tools.ToolConverter;
import alin.android.alinos.tools.ToolRegistry;
import alin.android.alinos.utils.TokenEstimator;

/**
 * 统一的提示词服务 — 纯流式。
 * 所有消息发送都走 OpenAIStreamNetHelper，支持取消。
 */
public class PromptService {
    private static final String TAG = "PromptService";

    private Context mContext;
    private OpenAIStreamNetHelper mStreamNetHelper;

    public PromptService(Context context) {
        this.mContext = context;
    }

    // ================================================================
    //  流式发送（唯一对外发送入口）
    // ================================================================

    /**
     * 发送流式消息。
     *
     * @param config    AI 配置（openai 类型会自动转为 openai_stream）
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @param listener  流式事件监听器
     */
    public void sendStreamMessage(ConfigBean config, int sessionId, String userInput,
                                  ChatStreamEventBus.StreamEventListener listener) {
        if (config == null) {
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("配置为空"));
            }
            return;
        }

        // 构建包含历史上下文的 messages
        JSONArray messages;
        try {
            messages = buildOpenAIMessages(sessionId, userInput, null);
        } catch (Exception e) {
            Log.e(TAG, "构建消息失败", e);
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("构建消息失败: " + e.getMessage()));
            }
            return;
        }

        // 检查 Token 限制
        if (checkContextWindow(config, sessionId, userInput, null)) {
            if (listener != null) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError(
                                "消息长度超出模型上下文窗口限制，请缩短消息或清除部分历史记录"));
            }
            return;
        }

        // 构建 tools 定义（当前使用测试工具集，后续由 ToolIntentRouter 按需加载）
        JSONArray tools = buildToolsPayload();

        // 直接创建流式助手
        mStreamNetHelper = new OpenAIStreamNetHelper(mContext, config);
        mStreamNetHelper.sendStreamMessageWithMessages(sessionId, messages, tools, listener);
    }

    // 工具定义缓存（会话内不变，避免每轮重复序列化）
    private static volatile JSONArray sCachedToolsJson;
    private static volatile int sCachedToolsVersion = -1;

    /**
     * 从 ToolRegistry 加载工具并转为 OpenAI tools 格式。
     * 结果缓存——工具定义在应用生命周期内不变，首轮序列化后直接复用。
     */
    private JSONArray buildToolsPayload() {
        int currentCount = ToolRegistry.getAllTools().size();
        if (sCachedToolsJson != null && sCachedToolsVersion == currentCount) {
            return sCachedToolsJson;
        }
        try {
            sCachedToolsJson = ToolConverter.convertAll(ToolRegistry.getAllTools());
            sCachedToolsVersion = currentCount;
            Log.d(TAG, "工具定义已缓存: " + currentCount + " 个");
        } catch (Exception e) {
            Log.w(TAG, "构建tools载荷失败", e);
            return null;
        }
        return sCachedToolsJson;
    }

    /**
     * 取消当前的流式请求。
     */
    public void cancelStream() {
        if (mStreamNetHelper != null) {
            mStreamNetHelper.cancelStream();
            mStreamNetHelper = null;
        }
    }

    // ================================================================
    //  消息构建
    // ================================================================

    /** 获取默认 system prompt（ChatActivity.buildCurrentMessages 复用此方法，避免硬编码分散）。 */
    public static String getDefaultSystemPrompt() {
        return "你是运行在 Android 手机本地 PTY 终端中的 AI 助手。\n\n"
                + "## 环境说明\n"
                + "- 这是一个 busybox/coreutils + curl + OpenSSH 的本地终端环境\n"
                + "- 可用命令: bash, curl, ssh, scp, sftp, ssh-keygen, ls, cat, cp, mv, rm, mkdir, grep, find, tar, gzip, ps, kill, ping, top, mount, date, echo 等\n"
                + "- 没有包管理器 (apt/dpkg/yum)，没有 git, python, vim, tmux, screen\n"
                + "- SSH 可连接远程服务器，curl 可下载或调用 API\n\n"

                + "## 可用工具\n"
                + "你可以通过以下工具与终端会话交互，所有工具均以 JSON 格式调用。\n\n"

                + "### 1. search_tools\n"
                + "**功能**：搜索当前系统已注册的所有可用工具。\n"
                + "**用途**：当你不确定有哪些工具可用，或需要查找特定功能的工具时使用。\n"
                + "**参数**：\n"
                + "  - query (string, 可选)：搜索关键词，模糊匹配工具名称和描述。留空则返回全部工具列表。\n"
                + "**返回**：工具列表，包含名称、描述和参数概要。\n\n"

                + "### 2. localshell_shell_read\n"
                + "**功能**：读取终端画面快照，不执行任何命令，不会干扰前台进程。\n"
                + "**适用场景**：轮询长时间任务进度（下载/编译/安装）、查看命令输出、检测菜单界面。\n"
                + "**参数**：\n"
                + "  - sessionId (string, 必填, 默认 \"default\")：会话 ID\n"
                + "  - waitMs (int, 可选, 默认 0)：延迟等待毫秒数（最大 5000），下载/安装任务建议 2000~3000\n"
                + "  - returnMode (enum, 可选, 默认 \"last_20\")：返回模式 (last_20, last_n, all)\n"
                + "  - lines (int, 可选, 默认 20)：last_n 模式的行数\n"
                + "  - colorEscape (boolean, 可选, 默认 true)：是否将 ANSI 颜色转为中文标签（菜单/列表必须 true）\n"
                + "  - cursorMode (boolean, 可选, 默认 false)：是否在行首加行号标记\n\n"

                + "### 3. localshell_shell_send_key\n"
                + "**功能**：发送控制键/方向键/功能键，支持批量发送（用 '|' 分隔）。\n"
                + "**适用场景**：导航菜单/对话框，中断进程 (Ctrl+C)，发送 Tab/Esc 等。\n"
                + "**参数**：\n"
                + "  - sessionId (string, 必填, 默认 \"default\")\n"
                + "  - key (string, 必填)：按键名，多个用 '|' 分隔（如 \"Down|Down|Enter\"）。支持 Ctrl+A~Z, Enter, Tab, Escape, Backspace, Delete, Up, Down, Left, Right, PageUp, PageDown, Home, End, F1~F12\n"
                + "  - returnMode, lines, colorEscape, cursorMode 同 shell_read\n\n"

                + "### 4. localshell_shell_exec\n"
                + "**功能**：执行一条 Shell 命令并等待输出。\n"
                + "**重要限制**：\n"
                + "  - 如果前台有进程在运行（如 apt/curl 下载中），新命令会排队或干扰前台，**绝对不要用 sleep/echo 等命令来等待**。\n"
                + "  - 命令执行后前台还在跑（有进度条/百分比）→ 用 shell_read(waitMs) 轮询进度，不要再重复执行 shell_exec。\n"
                + "  - 命令已结束（看到提示符 # 或 $）→ 可执行下一条 shell_exec。\n"
                + "**参数**：\n"
                + "  - sessionId (string, 必填, 默认 \"default\")\n"
                + "  - command (string, 必填)：要执行的命令\n"
                + "  - waitMs (int, 可选, 默认 500)：等待输出的毫秒数（短命令 200，安装下载首次 1200）\n"
                + "  - returnMode (enum, 可选, 默认 \"last_20\")：仅支持 last_20 和 last_n\n"
                + "  - lines (int, 可选, 默认 20)\n"
                + "  - colorEscape (boolean, 可选, 默认 true)\n\n"

                + "### 5. localshell_session_status\n"
                + "**功能**：查询指定会话是否存活。\n"
                + "**参数**：\n"
                + "  - sessionId (string, 必填)\n\n"

                + "## 调用格式\n"
                + "当你需要调用工具时，必须输出以下格式的 JSON 块，放在独立的一行：\n"
                + "```\n<tool_call>\n{\"name\": \"工具名\", \"arguments\": {\"参数1\": \"值\", \"参数2\": ...}}\n</tool_call>\n```\n"
                + "工具执行结果会以文本形式返回，请根据结果决定下一步操作。\n\n"

                + "## 典型示例\n"
                + "### 示例1：查看所有可用工具\n"
                + "```\n<tool_call>\n{\"name\": \"search_tools\", \"arguments\": {}}\n</tool_call>\n```\n"
                + "### 示例2：搜索与“会话”相关的工具\n"
                + "```\n<tool_call>\n{\"name\": \"search_tools\", \"arguments\": {\"query\": \"session\"}}\n</tool_call>\n```\n"
                + "### 示例3：查看当前终端内容（轮询进度）\n"
                + "```\n<tool_call>\n{\"name\": \"localshell_shell_read\", \"arguments\": {\"sessionId\": \"default\", \"waitMs\": 2000, \"colorEscape\": true}}\n</tool_call>\n```\n"
                + "### 示例4：在菜单中下移两次并确认\n"
                + "```\n<tool_call>\n{\"name\": \"localshell_shell_send_key\", \"arguments\": {\"sessionId\": \"default\", \"key\": \"Down|Down|Enter\", \"colorEscape\": true}}\n</tool_call>\n```\n"
                + "### 示例5：执行命令列出文件\n"
                + "```\n<tool_call>\n{\"name\": \"localshell_shell_exec\", \"arguments\": {\"sessionId\": \"default\", \"command\": \"ls -la\", \"waitMs\": 200}}\n</tool_call>\n```\n"
                + "### 示例6：检查会话是否存在\n"
                + "```\n<tool_call>\n{\"name\": \"localshell_session_status\", \"arguments\": {\"sessionId\": \"default\"}}\n</tool_call>\n```\n"

                + "## 策略建议\n"
                + "- 初次进入会话时，建议先调用 `search_tools` 确认可用工具集。\n"
                + "- 交互式任务（apt/curl 等）优先使用 shell_read 轮询，避免阻塞。\n"
                + "- 菜单导航时，始终设置 colorEscape=true 以识别高亮行。\n"
                + "- 会话 ID 默认为 \"default\"，除非明确创建了其他会话。\n"
                + "- 回复简洁专业，中文优先。\n";
    }

    /**
     * 构建符合 OpenAI 标准的 messages 数组。
     * 格式：system + 最近10条历史 + 当前用户消息
     */
    public JSONArray buildOpenAIMessages(int sessionId, String currentUserMessage, String systemPrompt) {
        JSONArray messages = new JSONArray();

        String systemContent = systemPrompt != null ? systemPrompt : getDefaultSystemPrompt();
        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemContent);
            messages.put(systemMsg);
        } catch (Exception e) {
            Log.e(TAG, "构建system消息失败", e);
        }

        List<ChatRecordBean> recentHistory = getRecentHistory(sessionId);
        for (ChatRecordBean record : recentHistory) {
            try {
                JSONObject msg = new JSONObject();
                String role = mapToOpenAIRole(record.getMsgType(), record.getSender());
                msg.put("role", role);
                msg.put("content", record.getContent());
                messages.put(msg);
            } catch (Exception e) {
                Log.e(TAG, "转换历史消息失败: " + record.getId(), e);
            }
        }

        try {
            JSONObject currentMsg = new JSONObject();
            currentMsg.put("role", "user");
            currentMsg.put("content", currentUserMessage);
            messages.put(currentMsg);
        } catch (Exception e) {
            Log.e(TAG, "构建当前用户消息失败", e);
        }

        return messages;
    }

    // ================================================================
    //  历史消息
    // ================================================================

    public List<ChatRecordBean> getChatHistory(int sessionId) {
        if (sessionId <= 0) {
            Log.w(TAG, "无效的会话ID: " + sessionId);
            return new ArrayList<>();
        }
        try {
            ChatDBHelper dbHelper = new ChatDBHelper(mContext);
            return dbHelper.getRecordsBySessionId(sessionId);
        } catch (Exception e) {
            Log.e(TAG, "获取历史消息失败", e);
            return new ArrayList<>();
        }
    }

    private List<ChatRecordBean> getRecentHistory(int sessionId) {
        // 使用 ContextCache 替代简单的"最后10条"截断
        List<ChatRecordBean> history = getChatHistory(sessionId);
        ContextCache cache = new ContextCache(history);
        return cache.buildRecordList();
    }

    private String mapToOpenAIRole(int msgType, String sender) {
        if (msgType == 0) {
            return "user";
        } else if (msgType == 1) {
            return "assistant";
        } else if (msgType == 2) {
            // 工具调用占位标记，不需要映射为 OpenAI 角色
            return null;
        }
        Log.w(TAG, "无法映射的消息类型: msgType=" + msgType + ", sender=" + sender);
        return null;
    }

    // ================================================================
    //  Token 估算 & 上下文窗口
    // ================================================================

    public int estimateMessagesTokens(JSONArray messages) {
        if (messages == null || messages.length() == 0) return 0;

        List<String> contents = new ArrayList<>();
        try {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                String content = msg.getString("content");
                if (content != null && !content.isEmpty()) {
                    contents.add(content);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析messages估算Token失败", e);
        }
        return TokenEstimator.estimateMessagesTokens(contents);
    }

    private int calculateTotalTokens(int sessionId, String currentUserMessage, String systemPrompt) {
        String systemContent = systemPrompt != null ? systemPrompt : getDefaultSystemPrompt();
        int systemTokens = TokenEstimator.estimateTokens(systemContent);
        int totalTokens = systemTokens;

        List<ChatRecordBean> recentHistory = getRecentHistory(sessionId);
        int historyTokens = 0;
        for (ChatRecordBean record : recentHistory) {
            historyTokens += record.getEstimatedTokens();
        }
        totalTokens += historyTokens;

        int userMessageTokens = TokenEstimator.estimateTokens(currentUserMessage);
        totalTokens += userMessageTokens;

        Log.d(TAG, "calculateTotalTokens: system=" + systemTokens
                + ", history(" + recentHistory.size() + "条)=" + historyTokens
                + ", user=" + userMessageTokens
                + ", total=" + totalTokens);
        return totalTokens;
    }

    private boolean checkContextWindow(ConfigBean config, int sessionId,
                                       String currentUserMessage, String systemPrompt) {
        if (config == null) return false;
        int totalTokens = calculateTotalTokens(sessionId, currentUserMessage, systemPrompt);
        int contextWindow = config.getModelContextWindow();
        if (contextWindow <= 0) contextWindow = 4096;
        boolean isExceeding = totalTokens > contextWindow;
        if (isExceeding) {
            Log.w(TAG, "消息超出上下文窗口: " + totalTokens + " > " + contextWindow);
        } else {
            Log.d(TAG, "Token估算: " + totalTokens + " / " + contextWindow);
        }
        return isExceeding;
    }

    public int estimateTextTokens(String text) {
        return TokenEstimator.estimateTokens(text);
    }

    public boolean isExceedingContextWindow(ConfigBean config, JSONArray messages, String currentUserMessage) {
        if (config == null || messages == null) return false;
        int totalTokens = estimateMessagesTokens(messages);
        int contextWindow = config.getModelContextWindow();
        if (contextWindow <= 0) contextWindow = 4096;
        boolean isExceeding = totalTokens > contextWindow;
        if (isExceeding) {
            Log.w(TAG, "消息超出上下文窗口: " + totalTokens + " > " + contextWindow);
        } else {
            Log.d(TAG, "消息Token估算: " + totalTokens + " / " + contextWindow);
        }
        return isExceeding;
    }
}
