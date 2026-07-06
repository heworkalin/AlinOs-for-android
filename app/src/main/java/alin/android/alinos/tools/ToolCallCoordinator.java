package alin.android.alinos.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.bean.ToolCallLogBean;
import alin.android.alinos.db.ToolCallDbHelper;
import alin.android.alinos.manager.ChatStreamEventBus;
import alin.android.alinos.net.OpenAIStreamNetHelper;

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

    private final Context mContext;
    private final ConfigBean mConfig;
    private final int mSessionId;
    private final ChatStreamEventBus.StreamEventListener mListener;
    private final ToolCallDbHelper mDbHelper;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ToolCallCardCallback mCardCallback;
    private JSONArray mMessages; // 完整消息历史（含 system + user + assistant + tool）
    private int mLoopCount = 0;
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
        new Thread(() -> runLoop(toolCallsJson)).start();
    }

    private void runLoop(JSONArray toolCallsJson) {
        if (MAX_LOOP > 0 && ++mLoopCount > MAX_LOOP) {
            Log.w(TAG, "工具调用循环超过上限(" + MAX_LOOP + ")，终止");
            emitError("工具调用循环次数过多，已自动终止");
            return;
        }
        if (MAX_LOOP <= 0) {
            mLoopCount++;
        }

        Log.d(TAG, "═══ Tool Call Loop #" + mLoopCount + " ═══");
        Log.d(TAG, "工具数: " + toolCallsJson.length());

        // ========== 计算当前批次每个工具的 UI 索引 ==========
        mCurrentIndices = new int[toolCallsJson.length()];

        // ========== 递归调用时：生成 UUID 并通过回调创建占位消息 ==========
        if (mLoopCount > 1) {
            mToolUuids = new String[toolCallsJson.length()];
            for (int i = 0; i < toolCallsJson.length(); i++) {
                try {
                    String toolName = toolCallsJson.getJSONObject(i)
                            .getJSONObject("function").optString("name", "unknown");
                    String uuid = UUID.randomUUID().toString();
                    mToolUuids[i] = uuid;

                    if (mCardCallback != null) {
                        // 同步等待 UI 线程创建占位消息（获取正确的索引）
                        final CountDownLatch latch = new CountDownLatch(1);
                        final int[] resultIdx = new int[1];
                        final String finalUuid = uuid;
                        final String finalToolName = toolName;
                        mMainHandler.post(() -> {
                            resultIdx[0] = mCardCallback.onNewPlaceholder(finalToolName, finalUuid);
                            latch.countDown();
                        });
                        latch.await(5, TimeUnit.SECONDS);
                        mCurrentIndices[i] = resultIdx[0];
                        Log.d(TAG, "  递归占位消息[" + i + "]: " + toolName + " uuid=" + uuid + " idx=" + resultIdx[0]);
                    } else {
                        mCurrentIndices[i] = mNextIndex + i;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "递归占位消息创建失败", e);
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
            try {
                JSONObject tc = toolCallsJson.getJSONObject(i);
                String toolCallId = tc.optString("id", "call_" + i);
                String toolName = tc.getJSONObject("function").optString("name", "");
                String argumentsStr = tc.getJSONObject("function").optString("arguments", "{}");
                String uuid = (mToolUuids != null && i < mToolUuids.length) ? mToolUuids[i]
                        : UUID.randomUUID().toString();

                Log.d(TAG, "├─ 执行工具[" + i + "]: " + toolName + " uuid=" + uuid);
                Log.d(TAG, "│  参数: " + argumentsStr);

                // 查找工具
                ToolMeta tool = ToolRegistry.findToolByFunctionName(toolName);
                if (tool == null) {
                    Log.w(TAG, "│  工具未注册: " + toolName);
                    emitToolCallResult(toolName, argumentsStr, "{}", "error", "工具未注册", 0, mCurrentIndices[i]);
                    toolResults.put(buildToolResultMessage(toolCallId, toolName,
                            new JSONObject().put("error", "工具未注册: " + toolName)));
                    // 记录失败日志
                    ToolCallLogBean failBean = new ToolCallLogBean(
                            uuid, mSessionId, toolName, toolCallId, argumentsStr, System.currentTimeMillis());
                    failBean.setResult("{}");
                    failBean.setStatus("error");
                    failBean.setErrorMessage("工具未注册: " + toolName);
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
                    emitToolCallResult(toolName, argumentsStr, result.toString(), "success", "",
                            System.currentTimeMillis() - startMs, mCurrentIndices[i]);
                } catch (Exception e) {
                    result = new JSONObject();
                    result.put("error", e.getMessage());
                    status = "error";
                    errorMsg = e.getMessage();
                    Log.e(TAG, "│  ❌ 执行失败: " + e.getMessage());
                    emitToolCallResult(toolName, argumentsStr, "{}", "error", errorMsg,
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
                Log.d(TAG, "│  uuid=" + uuid + ", 耗时: " + elapsed + "ms");

                // 构造 tool_result 消息
                toolResults.put(buildToolResultMessage(toolCallId, toolName, result));

            } catch (Exception e) {
                Log.e(TAG, "处理工具调用异常", e);
            }
        }

        // 累加消息索引（基于实际 UI 位置）
        if (mCurrentIndices.length > 0) {
            mNextIndex = mCurrentIndices[mCurrentIndices.length - 1] + 1;
        }

        Log.d(TAG, "╘═ 工具执行完毕，共 " + toolCallsJson.length() + " 个");

        // ========== 2. 构建回注消息 ==========
        // 添加 assistant 的 tool_calls 回复
        try {
            JSONObject assistantMsg = new JSONObject();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", "");
            assistantMsg.put("tool_calls", toolCallsJson);
            mMessages.put(assistantMsg);
        } catch (Exception e) {
            Log.e(TAG, "构建 assistant 消息失败", e);
        }

        // 添加 tool 结果
        for (int i = 0; i < toolResults.length(); i++) {
            try {
                mMessages.put(toolResults.getJSONObject(i));
            } catch (Exception e) {
                Log.e(TAG, "添加 tool 结果消息失败", e);
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

        Log.d(TAG, "回注完成，重新请求 LLM...");

        // 重新调用时仍携带工具定义（LLM 可能继续调用工具）
        JSONArray toolsPayload = buildToolsPayload();
        OpenAIStreamNetHelper helper = new OpenAIStreamNetHelper(mContext, mConfig);
        helper.sendStreamMessageWithMessages(mSessionId, finalMessages, toolsPayload, (eventType, data) -> {
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
                    Log.e(TAG, "解析递归 tool_calls 失败", e);
                }

                // 关闭当前 AI 流式文本 — 下一轮递归创建新 AI 消息，避免所有 reasoning 挤在一个对话框里
                if (textBuffer.length() > 0) {
                    mListener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildFinish(mSessionId, textBuffer.toString()));
                    textBuffer.setLength(0);
                }
            }

            // 完成（普通文本或工具调用——最终事件）
            if (data.isFinish() && !data.isThinkFinish()) {
                // 普通文本结束（非工具调用）
                if (data.getToolCallsJson() == null) {
                    String finalText = !textBuffer.toString().isEmpty()
                            ? textBuffer.toString()
                            : (data.getFullContent() != null ? data.getFullContent() : "");
                    if (!finalText.isEmpty()) {
                        mListener.onStreamEvent("stream_chat",
                                ChatStreamEventBus.StreamEventData.buildFinish(mSessionId, finalText));
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
            Log.d(TAG, "进入下一轮工具调用循环，共 " + nextToolCalls.length() + " 个工具");
            mMessages = finalMessages; // 保留已累积的消息
            runLoop(nextToolCalls);
        }
    }

    /** ANSI 转义序列正则（\033[...m 等控制码） */
    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile("\\u001B\\[[0-9;]*[a-zA-Z]|\\u001B[@-_]|" +
                    "\\(B|\\(0|\\u0007|\\r\\u001B\\[K");

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
            Log.e(TAG, "发射工具调用 UI 事件失败", e);
        }
    }

    /** 构建工具定义载荷（复用 ToolConverter）。 */
    private JSONArray buildToolsPayload() {
        try {
            return ToolConverter.convertAll(ToolRegistry.getAllTools());
        } catch (Exception e) {
            Log.w(TAG, "构建 tools 载荷失败", e);
            return null;
        }
    }

    private void emitError(String msg) {
        mListener.onStreamEvent("stream_chat",
                ChatStreamEventBus.StreamEventData.buildError(msg));
    }
}
