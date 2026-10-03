package alin.android.alinos.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import alin.android.alinos.log.AlinLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.bean.ToolCallLogBean;
import alin.android.alinos.db.ToolCallDbHelper;
import alin.android.alinos.manager.ChatStreamEventBus;
import alin.android.alinos.net.AiStreamEngine;

/**
 * 工具调用协调器 —— Tool Calling 循环引擎。
 *
 * 负责：
 * 1. 解析 tool_calls 找到对应工具
 * 2. 并发执行工具
 * 3. 记录调用日志到 ToolCallDbHelper（UUID 关联）
 * 4. 发射 UI 事件（工具卡片更新）
 * 5. 构造 tool_result 回注
 * 6. 重新调用 LLM → 循环直到模型返回文本
 */
public class ToolCallCoordinator {

    private static final String TAG = "ToolCallCoordinator";
    private static final int MAX_LOOP = -1; // -1 = 不做限制（测试用）
    private static final int MAX_MESSAGES = 30; // 历史消息上限（防 OOM）
    private static final int LOOP_YIELD_MS = 300; // 轮间让出 CPU（防 ANR）

    private final Context mContext;
    private final ConfigBean mConfig;
    private final int mSessionId;
    private final ChatStreamEventBus.StreamEventListener mListener;
    private final ToolCallDbHelper mDbHelper;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ToolCallCardCallback mCardCallback;
    private JSONArray mMessages; // 完整消息历史（含 system + user + assistant + tool）
    private int mLoopCount = 0;
    private volatile boolean mStopped = false; // 用户触发停止
    private AiStreamEngine mHelper; // 复用引擎（避免每轮 new OkHttpClient）
    private String[] mToolUuids; // 当前批次工具对应的 UUID 数组
    private int mNextIndex = 0;  // 累加的消息索引（跨递归调用递增）
    private int[] mCurrentIndices; // 当前批次每个工具在 UI 中的索引（相对于 capturedStartPos）

    public ToolCallCoordinator(Context context, ConfigBean config, int sessionId,
                               JSONArray messages, ChatStreamEventBus.StreamEventListener listener) {
        this(context, config, sessionId, messages, listener, null);
    }

    public ToolCallCoordinator(Context context, ConfigBean config, int sessionId,
                               JSONArray messages, ChatStreamEventBus.StreamEventListener listener,
                               ToolCallCardCallback cardCallback) {
        this.mContext = context;
        this.mConfig = config;
        this.mSessionId = sessionId;
        this.mMessages = messages;
        this.mListener = listener;
        this.mCardCallback = cardCallback;
        this.mDbHelper = new ToolCallDbHelper(context);
    }

    /**
     * 启动工具执行循环（在后台线程运行）。
     *
     * @param toolCallsJson LLM 返回的 tool_calls JSON 数组
     * @param uuids         每个工具对应的 UUID（由 ChatActivity 预先生成）
     */
    public void execute(JSONArray toolCallsJson, String[] uuids) {
        mToolUuids = uuids;
        mStopped = false;
        new Thread(() -> runLoop(toolCallsJson)).start();
    }

    /** 停止正在执行的工具调用循环 */
    public void stop() {
        mStopped = true;
        AlinLog.d(TAG, "收到停止信号");
        // 立即中断当前正在执行的 proot 进程（若在跑），
        // 使阻塞命令（sleep/apt 等）不必等超时。
        try {
            boolean killed = alin.android.alinos.proot.ProotContainerManager.cancelRunning();
            AlinLog.d(TAG, "cancelRunning => " + killed);
        } catch (Throwable e) {
            AlinLog.w(TAG, "cancelRunning 失败: " + e.getMessage());
        }
    }

    private void runLoop(JSONArray toolCallsJson) {
        if (MAX_LOOP > 0 && ++mLoopCount > MAX_LOOP) {
            AlinLog.w(TAG, "工具调用循环超过上限(" + MAX_LOOP + ")，终止");
            emitError("工具调用循环次数过多，已自动终止");
            return;
        }
        if (MAX_LOOP <= 0) {
            mLoopCount++;
        }

        // 轮间让出 CPU，避免连续跑满触发 ANR
        if (mLoopCount > 1) {
            try { Thread.sleep(LOOP_YIELD_MS); } catch (InterruptedException ignored) {}
        }

        // 裁剪消息历史，防止 OOM
        trimMessages();

        AlinLog.d(TAG, "═══ Tool Call Loop #" + mLoopCount + " ═══");
        AlinLog.d(TAG, "工具数: " + toolCallsJson.length());

        // ========== 计算当前批次每个工具的 UI 索引 ==========
        mCurrentIndices = new int[toolCallsJson.length()];

        // ========== 递归调用时：生成 UUID 并通过回调创建占位消息 ==========
        if (mLoopCount > 1) {
            mToolUuids = new String[toolCallsJson.length()];
            for (int i = 0; i < toolCallsJson.length(); i++) {
                try {
                    String toolName = ToolMeta.toolCallName(toolCallsJson.getJSONObject(i));
                    if (toolName.isEmpty()) toolName = "unknown";
                    final String finalArgs = ToolMeta.toolCallArguments(toolCallsJson.getJSONObject(i));
                    String uuid = UUID.randomUUID().toString();
                    mToolUuids[i] = uuid;

                    if (mCardCallback != null) {
                        // 同步等待 UI 线程创建占位消息（获取正确的索引）
                        final CountDownLatch latch = new CountDownLatch(1);
                        final int[] resultIdx = new int[1];
                        final String finalUuid = uuid;
                        final String finalToolName = toolName;
                        mMainHandler.post(() -> {
                            resultIdx[0] = mCardCallback.onNewPlaceholder(finalToolName, finalArgs, finalUuid);
                            latch.countDown();
                        });
                        latch.await(5, TimeUnit.SECONDS);
                        mCurrentIndices[i] = resultIdx[0];
                        AlinLog.d(TAG, "  递归占位消息[" + i + "]: " + toolName + " uuid=" + uuid + " idx=" + resultIdx[0]);
                    } else {
                        mCurrentIndices[i] = mNextIndex + i;
                    }
                } catch (Exception e) {
                    AlinLog.e(TAG, "递归占位消息创建失败", e);
                    mCurrentIndices[i] = mNextIndex + i;
                }
            }
        } else {
            // 首次调用：索引 = mNextIndex + i（由 ChatActivity 预先创建的占位消息位置）
            for (int i = 0; i < toolCallsJson.length(); i++) {
                mCurrentIndices[i] = mNextIndex + i;
            }
        }

        // ========== 1. 并发执行所有工具 ==========
        JSONArray toolResults = new JSONArray();
        for (int i = 0; i < toolCallsJson.length(); i++) {
            if (mStopped) { emitError("用户停止了执行"); return; }
            try {
                JSONObject tc = toolCallsJson.getJSONObject(i);
                String toolCallId = tc.optString("id", "call_" + i);
                String toolName = ToolMeta.toolCallName(tc);
                String argumentsStr = ToolMeta.toolCallArguments(tc);
                String uuid = (mToolUuids != null && i < mToolUuids.length) ? mToolUuids[i]
                        : UUID.randomUUID().toString();

                AlinLog.d(TAG, "├─ 执行工具[" + i + "]: " + toolName + " uuid=" + uuid);
                AlinLog.d(TAG, "│  参数: " + argumentsStr);

                // 查找工具
                ToolMeta tool = ToolRegistry.findToolByFunctionName(toolName);
                if (tool == null) {
                    AlinLog.w(TAG, "│  工具未注册: " + toolName);
                    emitToolCallResult(toolName, argumentsStr, "{}", "error", "tool not registered", 0, mCurrentIndices[i]);
                    toolResults.put(buildToolResultMessage(toolCallId, toolName,
                            ToolMeta.error("tool not registered: " + toolName)));
                    // 记录失败日志
                    ToolCallLogBean failBean = new ToolCallLogBean(
                            uuid, mSessionId, toolName, toolCallId, argumentsStr, System.currentTimeMillis());
                    failBean.setResult("{}");
                    failBean.setStatus("error");
                    failBean.setErrorMessage("tool not registered: " + toolName);
                    mDbHelper.insert(failBean);
                    continue;
                }

                // 执行工具
                long startMs = System.currentTimeMillis();
                JSONObject params = new JSONObject(argumentsStr);
                JSONObject result;
                String status = "success";
                String errorMsg = "";

                try {
                    result = tool.executor.execute(params);
                    // 执行期间用户点了停止：无论工具实际结果如何，
                    // 强制把返回内容替换为「用户已终止」并终止循环。
                    if (mStopped) {
                        result = ToolMeta.error("用户已终止");
                        status = "error";
                        errorMsg = "用户已终止";
                        emitToolCallResult(toolName, argumentsStr, result.toString(), "error", errorMsg,
                                System.currentTimeMillis() - startMs, mCurrentIndices[i]);
                        // 仍记一条日志，然后结束
                        ToolCallLogBean stopBean = new ToolCallLogBean(
                                uuid, mSessionId, toolName, toolCallId, argumentsStr, System.currentTimeMillis());
                        stopBean.setResult(result.toString());
                        stopBean.setStatus("error");
                        stopBean.setErrorMessage("用户已终止");
                        stopBean.setDurationMs(System.currentTimeMillis() - startMs);
                        mDbHelper.insert(stopBean);
                        emitError("用户已终止");
                        return;
                    }
                    if (result == null) result = ToolMeta.ok();
                    // 统一：每个工具都应带 status；缺失则补上
                    status = result.optString("status", "success");
                    if (status.isEmpty()) status = "success";
                    result.put("status", status);
                    emitToolCallResult(toolName, argumentsStr, result.toString(), status, "",
                            System.currentTimeMillis() - startMs, mCurrentIndices[i]);
                } catch (Exception e) {
                    if (mStopped) {
                        result = ToolMeta.error("用户已终止");
                        status = "error";
                        errorMsg = "用户已终止";
                        emitToolCallResult(toolName, argumentsStr, result.toString(), "error", errorMsg,
                                System.currentTimeMillis() - startMs, mCurrentIndices[i]);
                        emitError("用户已终止");
                        return;
                    }
                    result = ToolMeta.error(e.getMessage());
                    status = "error";
                    errorMsg = e.getMessage() == null ? "unknown error" : e.getMessage();
                    AlinLog.e(TAG, "│  ❌ 执行失败: " + errorMsg);
                    emitToolCallResult(toolName, argumentsStr, result.toString(), "error", errorMsg,
                            System.currentTimeMillis() - startMs, mCurrentIndices[i]);
                }

                // 记录日志到 DB（UUID 关联）
                long elapsed = System.currentTimeMillis() - startMs;
                ToolCallLogBean logBean = new ToolCallLogBean(
                        uuid, mSessionId, toolName, toolCallId, argumentsStr, System.currentTimeMillis());
                logBean.setResult(result.toString());
                logBean.setStatus(status);
                logBean.setErrorMessage(errorMsg);
                logBean.setDurationMs(elapsed);
                mDbHelper.insert(logBean);
                AlinLog.d(TAG, "│  uuid=" + uuid + ", 耗时: " + elapsed + "ms");

                // 构造 tool_result 消息
                toolResults.put(buildToolResultMessage(toolCallId, toolName, result));

            } catch (Exception e) {
                AlinLog.e(TAG, "处理工具调用异常", e);
            }
        }

        // 累加消息索引（基于实际 UI 位置）
        if (mCurrentIndices.length > 0) {
            mNextIndex = mCurrentIndices[mCurrentIndices.length - 1] + 1;
        }

        AlinLog.d(TAG, "╘═ 工具执行完毕，共 " + toolCallsJson.length() + " 个");

        // ========== 2. 构建回注消息 ==========
        // 添加 assistant 的 tool_calls 回复
        try {
            JSONObject assistantMsg = new JSONObject();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", "");
            // 归一化为 OpenAI 嵌套结构（各 provider/dialect 都按 function 包裹解析）
            assistantMsg.put("tool_calls", ToolMeta.normalizeToolCalls(toolCallsJson));
            mMessages.put(assistantMsg);
        } catch (Exception e) {
            AlinLog.e(TAG, "构建 assistant 消息失败", e);
        }

        // 添加 tool 结果
        for (int i = 0; i < toolResults.length(); i++) {
            try {
                mMessages.put(toolResults.getJSONObject(i));
            } catch (Exception e) {
                AlinLog.e(TAG, "添加 tool 结果消息失败", e);
            }
        }

        // ========== 3. 重新调用 LLM ==========
        reCallLlm();
    }

    private void reCallLlm() {
        final CountDownLatch latch = new CountDownLatch(1);
        final JSONArray finalMessages = mMessages;
        final boolean[] isToolCalls = {false};
        final JSONArray nextToolCalls = new JSONArray();
        final StringBuilder textBuffer = new StringBuilder();
        final String[] roundText = {null};  // 本轮结束时的正文（用于判定后发 finish）

        AlinLog.d(TAG, "回注完成，重新请求 LLM...");

        // 复用 LLM 连接（不每轮 new，减少 OkHttpClient 堆积）
        JSONArray toolsPayload = buildToolsPayload();
        if (mHelper == null) mHelper = new AiStreamEngine(mContext, mConfig);
        mHelper.sendStreamMessageWithMessages(mSessionId, finalMessages, toolsPayload, (eventType, data) -> {
            if (data.isError()) {
                emitError(data.getErrorMsg());
                latch.countDown();
                return;
            }

            // Think 块完成（仅转发，不释放 latch——后续可能还有 tool_calls 事件）
            if (data.isThinkFinish()) {
                return;
            }

            // 增量文本（缓冲）
            if (data.getChunkContent() != null && !data.isFinish()) {
                textBuffer.append(data.getChunkContent());
                mListener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildChunk(mSessionId, data.getChunkContent()));
            }

            // 工具调用（继续循环）
            if (data.getToolCallsJson() != null && !data.getToolCallsJson().isEmpty()) {
                try {
                    JSONArray calls = new JSONArray(data.getToolCallsJson());
                    for (int i = 0; i < calls.length(); i++) {
                        nextToolCalls.put(calls.getJSONObject(i));
                    }
                    isToolCalls[0] = true;
                } catch (Exception e) {
                    AlinLog.e(TAG, "解析递归 tool_calls 失败", e);
                }

                // 关闭当前 AI 流式文本 — 下一轮递归创建新 AI 消息，避免所有 reasoning 挤在一个对话框里
                if (textBuffer.length() > 0) {
                    mListener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildFinish(mSessionId, textBuffer.toString()));
                    textBuffer.setLength(0);
                }
            }

            // 完成（普通文本或工具调用——本轮结束）
            if (data.isFinish() && !data.isThinkFinish()) {
                // 本轮普通文本（非工具调用）：记录文本，finish 事件延后到
                // 判定是否继续递归后再发（避免中间轮提前恢复按钮）。
                if (data.getToolCallsJson() == null) {
                    if (!textBuffer.toString().isEmpty()) {
                        roundText[0] = textBuffer.toString();
                    } else if (data.getFullContent() != null) {
                        roundText[0] = data.getFullContent();
                    }
                }
                latch.countDown();
            }
        });

        // 等待流式完成
        try {
            latch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitError("工具调用循环被中断");
            return;
        }

        // 判断是否需要继续循环
        if (isToolCalls[0] && nextToolCalls.length() > 0) {
            // 还要继续下一轮工具调用：发一个普通 finish 关闭本轮 AI 文本，
            // 但不带 toolChainDone 标记（ChatActivity 不会因此恢复按钮）。
            if (roundText[0] != null && !roundText[0].isEmpty()) {
                mListener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildFinish(mSessionId, roundText[0]));
            }
            AlinLog.d(TAG, "进入下一轮工具调用循环，共 " + nextToolCalls.length() + " 个工具");
            mMessages = finalMessages; // 保留已累积的消息
            runLoop(nextToolCalls);
        } else {
            // 工具链真正结束：发带 toolChainDone 标记的 finish，
            // 只有它才会让 ChatActivity 恢复发送按钮为「发送」。
            String finalText = roundText[0] == null ? "" : roundText[0];
            mListener.onStreamEvent("stream_chat",
                    ChatStreamEventBus.StreamEventData.buildToolChainFinish(mSessionId, finalText));
        }
    }

    /** ANSI 转义序列正则（\033[...m 等控制码） */
    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile("\\u001B\\[[0-9;]*[a-zA-Z]|\\u001B[@-_]|" +
                    "\\(B|\\(0|\\u0007|\\r\\u001B\\[K");

    /** 裁剪消息历史，保留 system + 最近 N 条消息，防止 OOM。 */
    private void trimMessages() {
        if (mMessages == null || mMessages.length() <= MAX_MESSAGES) return;
        try {
            JSONArray trimmed = new JSONArray();
            // 保留第一条（system prompt）
            trimmed.put(mMessages.getJSONObject(0));
            // 保留最后 (MAX_MESSAGES - 1) 条
            int start = mMessages.length() - (MAX_MESSAGES - 1);
            for (int i = start; i < mMessages.length(); i++) {
                trimmed.put(mMessages.getJSONObject(i));
            }
            mMessages = trimmed;
            AlinLog.d(TAG, "消息裁剪: " + (mMessages.length() + " → " + trimmed.length()));
        } catch (Exception e) {
            AlinLog.w(TAG, "消息裁剪失败", e);
        }
    }

    private JSONObject buildToolResultMessage(String toolCallId, String toolName, JSONObject result) {
        JSONObject msg = new JSONObject();
        try {
            msg.put("role", "tool");
            msg.put("tool_call_id", toolCallId);
            msg.put("name", toolName);
            // 剥离 ANSI 转义码，避免浪费 LLM token 且干扰理解
            String rawContent = result.toString();
            String cleanContent = ANSI_PATTERN.matcher(rawContent).replaceAll("");
            msg.put("content", cleanContent);
        } catch (Exception ignored) {}
        return msg;
    }

    // ================================================================
    //  UI 事件发射
    // ================================================================

    private void emitToolCallResult(String toolName, String args, String result,
                                     String status, String errorMsg, long durationMs, int index) {
        try {
            JSONObject card = new JSONObject();
            card.put("toolName", toolName);
            card.put("args", args);
            card.put("request", "tool_call: " + toolName + "\narguments: " + args);
            card.put("response", "status: " + status + "\n" + result);
            card.put("log", "耗时: " + durationMs + "ms" + (errorMsg.isEmpty() ? "" : "\n错误: " + errorMsg));
            card.put("status", status);
            card.put("duration", durationMs < 1 ? "<1ms" : (durationMs / 1000.0) + "s");

            if (mCardCallback != null) {
                mCardCallback.onToolCallResult(index, card.toString(), false);
            }
        } catch (Exception e) {
            AlinLog.e(TAG, "发射工具调用 UI 事件失败", e);
        }
    }

    /** 构建工具定义载荷（复用 ToolConverter）。 */
    private JSONArray buildToolsPayload() {
        try {
            return ToolConverter.convertAll(ToolRegistry.getAiTools());
        } catch (Exception e) {
            AlinLog.w(TAG, "构建 tools 载荷失败", e);
            return null;
        }
    }

    private void emitError(String msg) {
        mListener.onStreamEvent("stream_chat",
                ChatStreamEventBus.StreamEventData.buildError(msg));
    }
}
