package alin.android.alinos.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.manager.ChatStreamEventBus;
import alin.android.alinos.utils.TokenEstimator;

/**
 * OpenAI流式请求类
 * 重构说明：将两个方法中重复的 onResponse 解析逻辑抽取为 parseStreamResponse()，
 * 消除代码重复，确保 usage 判断修复只需维护一处。
 */
public class OpenAIStreamNetHelper {
    private static final String TAG = "OpenAIStreamNetHelper";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final int AGGREGATE_THRESHOLD = 1;
    private static final long PUSH_TIMEOUT = 200;
    private static final long STREAM_TIMEOUT_TIPS_DELAY = 3000;
    private static final long STREAM_FINAL_TIMEOUT = 480000;
    private static final long STREAM_CHUNK_INTERVAL_TIMEOUT = 180000;

    private Context mContext;
    private ConfigBean mConfig;
    private boolean mIsStreamRunning;
    private StringBuffer mStreamCache;
    private StringBuffer mChunkBuffer;
    private long mLastPushTime;
    private OkHttpClient mOkHttpClient;
    private Call mCurrentCall;

    private Handler mTimeoutHandler;
    private Runnable mTimeoutTipsRunnable;
    private Runnable mFinalTimeoutRunnable;
    private Runnable mIntervalTimeoutRunnable;
    private ChatStreamEventBus.StreamEventListener mStreamEventListener;
    private int mSessionId;
    private long mLastChunkReceiveTime = 0L;
    private boolean mIsFirstChunkReceived = false;
    private boolean mIsTimeoutTipShowed = false;
    private int mTimeoutRetryCount = 0;
    private boolean mServerSupportsStreamOptions = true;
    private boolean mReceivedUsageFromServer = false;
    private JSONArray mCurrentMessages = null;
    private String mCurrentUserMessage = null;
    private JSONArray mCurrentTools = null; // 当前请求的工具定义

    public OpenAIStreamNetHelper(Context context, ConfigBean config) {
        this.mContext = context;
        this.mConfig = config;
        this.mIsStreamRunning = false;
        this.mStreamCache = new StringBuffer();
        this.mChunkBuffer = new StringBuffer();
        this.mLastPushTime = System.currentTimeMillis();

        this.mOkHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(new ConnectionPool(5, 30, TimeUnit.SECONDS))
                .retryOnConnectionFailure(true)
                .build();

        Log.d(TAG, "初始化流式助手，配置：type=" + config.getType() + ", url=" + config.getServerUrl());

        mTimeoutHandler = new Handler(Looper.getMainLooper());
        initTimeoutRunnables();
    }

    // ========== 公共方法 ==========

    public String sendMessage(String message) {
        Log.w(TAG, "请使用sendStreamMessage触发流式");
        return "[提示] 长按发送按钮触发流式响应";
    }

    public String getServiceType() {
        return mConfig != null ? mConfig.getType() : "openai_stream";
    }

    public boolean validateConfig() {
        if (mConfig == null) { showToast("配置为空"); return false; }
        if (TextUtils.isEmpty(mConfig.getServerUrl()) || TextUtils.isEmpty(mConfig.getModel())) {
            showToast("服务器地址/模型不能为空"); return false;
        }
        return true;
    }

    public void setContextAndConfig(Context context, ConfigBean config) {
        this.mContext = context;
        this.mConfig = config;
    }

    // ========== 超时管理 ==========

    private void initTimeoutRunnables() {
        mTimeoutTipsRunnable = () -> {
            if (!mIsStreamRunning || mIsTimeoutTipShowed) return;
            Log.d(TAG, "流式请求3秒未收到分片，轻提示");
            mIsTimeoutTipShowed = true;
            showToast("流式请求已发送，正在等待响应...");
        };

        mIntervalTimeoutRunnable = () -> {
            if (!mIsStreamRunning || !mIsFirstChunkReceived) return;
            long currentTime = System.currentTimeMillis();
            if ((currentTime - mLastChunkReceiveTime) > STREAM_CHUNK_INTERVAL_TIMEOUT) {
                Log.e(TAG, "上下文断开异常：相邻分片接收间隔超3分钟");
                if (mTimeoutRetryCount < 2) {
                    mTimeoutRetryCount++;
                    retryStreamRequest();
                    return;
                }
                if (mStreamEventListener != null) {
                    mStreamEventListener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildError("[超时重试] 上下文断开异常"));
                }
                cancelStream();
            } else {
                mTimeoutHandler.postDelayed(mIntervalTimeoutRunnable, 30000);
            }
        };

        mFinalTimeoutRunnable = () -> {
            if (!mIsStreamRunning) return;
            Log.e(TAG, "流式请求8分钟全局总超时");
            if (mTimeoutRetryCount < 2) {
                mTimeoutRetryCount++;
                retryStreamRequest();
                return;
            }
            if (mStreamEventListener != null) {
                mStreamEventListener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("[超时重试] 全局超时异常"));
            }
            cancelStream();
        };
    }

    private void startTimeoutDetection() {
        mIsTimeoutTipShowed = false;
        mTimeoutRetryCount = 0;
        mLastChunkReceiveTime = 0L;
        mIsFirstChunkReceived = false;
        mTimeoutHandler.postDelayed(mTimeoutTipsRunnable, STREAM_TIMEOUT_TIPS_DELAY);
        mTimeoutHandler.postDelayed(mFinalTimeoutRunnable, STREAM_FINAL_TIMEOUT);
        mTimeoutHandler.postDelayed(mIntervalTimeoutRunnable, 30000);
    }

    private void stopTimeoutDetection() {
        mTimeoutHandler.removeCallbacks(mTimeoutTipsRunnable);
        mTimeoutHandler.removeCallbacks(mFinalTimeoutRunnable);
        mTimeoutHandler.removeCallbacks(mIntervalTimeoutRunnable);
        mStreamEventListener = null;
    }

    private void resetIntervalTimeout() {
        mTimeoutHandler.removeCallbacks(mIntervalTimeoutRunnable);
        mTimeoutHandler.postDelayed(mIntervalTimeoutRunnable, 30000);
    }

    private void cancelTimeoutTips() {
        mTimeoutHandler.removeCallbacks(mTimeoutTipsRunnable);
        mIsTimeoutTipShowed = true;
    }

    // ========== 核心公共方法：解析流式响应 ==========
    // 原来两个方法的 onResponse 里有完全相同的解析逻辑，现在统一在这里维护。

    // 工具调用累积状态（流式解析用）
    private JSONArray mAccumulatedToolCalls = null;
    private boolean mIsToolCallsResponse = false;

    private void parseStreamResponse(ResponseBody responseBody, int sessionId,
                                     ChatStreamEventBus.StreamEventListener listener) throws IOException {
        BufferedSource source = responseBody.source();
        String line;

        // 重置工具调用累积状态
        mAccumulatedToolCalls = new JSONArray();
        mIsToolCallsResponse = false;
        boolean hasToolCallsFinish = false;

        int chunkCount = 0;
        while ((line = source.readUtf8Line()) != null && mIsStreamRunning) {
            if (TextUtils.isEmpty(line)) continue;
            if (!line.startsWith("data: ")) {
                Log.d(TAG, "忽略非数据行: " + line);
                continue;
            }

            String dataStr = line.substring(6).trim();
            if ("[DONE]".equals(dataStr)) break;

            chunkCount++;
            if (chunkCount <= 3 || chunkCount % 50 == 0) {
                Log.d(TAG, "🔷 分片#" + chunkCount + "，长度=" + dataStr.length());
            }

            try {
                JSONObject chunkJson = new JSONObject(dataStr);

                // ========== usage 帧判断 ==========
                boolean isUsageFrame = false;
                boolean hasContent = false;
                boolean hasToolCalls = false;

                if (chunkJson.has("choices") && chunkJson.getJSONArray("choices").length() > 0) {
                    JSONObject choice = chunkJson.getJSONArray("choices").getJSONObject(0);
                    JSONObject delta = choice.optJSONObject("delta");
                    if (delta != null) {
                        // 检测 content
                        if (delta.has("content") && !delta.isNull("content")
                                && !TextUtils.isEmpty(delta.optString("content", ""))) {
                            hasContent = true;
                        }
                        // 检测 tool_calls
                        if (delta.has("tool_calls")) {
                            hasToolCalls = true;
                        }
                    }
                    // 检测 finish_reason
                    if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                        String fr = choice.optString("finish_reason", "");
                        if ("tool_calls".equals(fr)) {
                            hasToolCallsFinish = true;
                            Log.d(TAG, "🛠️ 检测到 finish_reason=tool_calls");
                        }
                    }
                }

                // usage 帧处理
                if (chunkJson.has("usage")) {
                    Object usageObj = chunkJson.get("usage");
                    if (usageObj != null && usageObj != JSONObject.NULL
                            && usageObj instanceof JSONObject && ((JSONObject) usageObj).length() > 0) {
                        isUsageFrame = true;
                        JSONObject usage = chunkJson.getJSONObject("usage");
                        int promptTokens = usage.optInt("prompt_tokens", 0);
                        int completionTokens = usage.optInt("completion_tokens", 0);
                        int totalTokens = usage.optInt("total_tokens", 0);
                        listener.onStreamEvent("stream_chat",
                                ChatStreamEventBus.StreamEventData.buildUsage(sessionId, promptTokens, completionTokens, totalTokens));
                        mReceivedUsageFromServer = true;
                    }
                }
                if (isUsageFrame && !hasContent && !hasToolCalls) {
                    continue;
                }

                // ========== tool_calls 分片累积 ==========
                if (hasToolCalls) {
                    JSONArray choices = chunkJson.getJSONArray("choices");
                    JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                    JSONArray toolCallsDelta = delta.getJSONArray("tool_calls");
                    mIsToolCallsResponse = true;

                    for (int i = 0; i < toolCallsDelta.length(); i++) {
                        JSONObject tc = toolCallsDelta.getJSONObject(i);
                        int idx = tc.getInt("index");

                        // 找到或创建该 index 的 tool_call 条目
                        JSONObject target = null;
                        for (int j = 0; j < mAccumulatedToolCalls.length(); j++) {
                            if (mAccumulatedToolCalls.getJSONObject(j).optInt("index", -1) == idx) {
                                target = mAccumulatedToolCalls.getJSONObject(j);
                                break;
                            }
                        }
                        if (target == null) {
                            target = new JSONObject();
                            target.put("index", idx);
                            target.put("id", "");
                            target.put("type", "function");
                            JSONObject func = new JSONObject();
                            func.put("name", "");
                            func.put("arguments", "");
                            target.put("function", func);
                            mAccumulatedToolCalls.put(target);
                        }

                        // 增量填充
                        if (tc.has("id") && !tc.isNull("id")) {
                            target.put("id", tc.getString("id"));
                        }
                        if (tc.has("type") && !tc.isNull("type")) {
                            target.put("type", tc.getString("type"));
                        }
                        if (tc.has("function")) {
                            JSONObject funcDelta = tc.getJSONObject("function");
                            JSONObject func = target.getJSONObject("function");
                            if (funcDelta.has("name") && !funcDelta.isNull("name")) {
                                func.put("name", funcDelta.getString("name"));
                            }
                            if (funcDelta.has("arguments") && !funcDelta.isNull("arguments")) {
                                String curr = func.optString("arguments", "");
                                curr += funcDelta.getString("arguments");
                                func.put("arguments", curr);
                            }
                        }
                    }
                    continue; // tool_calls 内容不由常规文本通道处理
                }

                // ========== 正常文本分片 ==========
                if (!hasContent) {
                    continue;
                }

                JSONObject delta = chunkJson.getJSONArray("choices").getJSONObject(0).getJSONObject("delta");
                String chunkContent = delta.optString("content", "");

                if (TextUtils.isEmpty(chunkContent)) {
                    continue;
                }

                long currentTime = System.currentTimeMillis();
                mLastChunkReceiveTime = currentTime;
                if (!mIsFirstChunkReceived) {
                    mIsFirstChunkReceived = true;
                    cancelTimeoutTips();
                }
                resetIntervalTimeout();

                // Think 块剥离
                if (chunkContent.contains("<think>") || chunkContent.contains("</think>")
                        || mStreamCache.toString().contains("<think>")) {
                    // 流式过程中累积 think 内容，统一在结束后分离
                }

                // 累积到缓存
                synchronized (mChunkBuffer) {
                    mChunkBuffer.append(chunkContent);
                    mStreamCache.append(chunkContent);

                    if (mChunkBuffer.length() >= AGGREGATE_THRESHOLD ||
                            (currentTime - mLastPushTime) >= PUSH_TIMEOUT) {
                        String aggregated = mChunkBuffer.toString();
                        listener.onStreamEvent("stream_chat",
                                ChatStreamEventBus.StreamEventData.buildChunk(sessionId, aggregated));
                        mChunkBuffer.setLength(0);
                        mLastPushTime = currentTime;
                    }
                }

            } catch (Exception e) {
                if (chunkCount <= 5) {
                    Log.e(TAG, "解析分片失败: " + (dataStr.length() > 100 ? dataStr.substring(0, 100) : dataStr), e);
                }
            }
        }

        // 推送剩余分片
        synchronized (mChunkBuffer) {
            if (mChunkBuffer.length() > 0) {
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildChunk(sessionId, mChunkBuffer.toString()));
                mChunkBuffer.setLength(0);
            }
        }

        // ========== 完成处理 ==========
        String finalContent;
        synchronized (mStreamCache) {
            finalContent = mStreamCache.toString();
        }

        // 分离 Think 块内容
        String thinkContent = "";
        String cleanContent = finalContent;
        if (finalContent.contains("<think>") && finalContent.contains("</think>")) {
            int start = finalContent.indexOf("<think>") + 7;
            int end = finalContent.indexOf("</think>");
            if (start < end) {
                thinkContent = finalContent.substring(start, end).trim();
                cleanContent = (finalContent.substring(0, start - 7)
                        + finalContent.substring(end + 8)).trim();
            }
        }
        if (!thinkContent.isEmpty()) {
            Log.d(TAG, "🧠 检测到 Think 块，长度=" + thinkContent.length());
            listener.onStreamEvent("stream_chat",
                    ChatStreamEventBus.StreamEventData.buildThinkFinish(sessionId, thinkContent));
        }

        // 如果是 tool_calls 响应，发送工具调用事件
        if (hasToolCallsFinish && mAccumulatedToolCalls.length() > 0) {
            try {
                JSONArray cleanedCalls = new JSONArray();
                for (int i = 0; i < mAccumulatedToolCalls.length(); i++) {
                    JSONObject tc = mAccumulatedToolCalls.getJSONObject(i);
                    JSONObject cleaned = new JSONObject();
                    cleaned.put("id", tc.optString("id", "call_" + i));
                    cleaned.put("type", "function");
                    JSONObject func = new JSONObject();
                    JSONObject fn = tc.optJSONObject("function");
                    String fnName = fn != null ? fn.optString("name", "") : "";
                    String fnArgs = fn != null ? fn.optString("arguments", "{}") : "{}";
                    func.put("name", fnName);
                    func.put("arguments", fnArgs);
                    cleaned.put("function", func);
                    cleanedCalls.put(cleaned);
                }

                Log.d(TAG, "🛠️ 发送工具调用事件，共 " + cleanedCalls.length() + " 个工具");
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildToolCalls(sessionId, cleanedCalls.toString()));
            } catch (Exception e) {
                Log.e(TAG, "构建 tool_calls 事件失败", e);
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("工具调用解析失败"));
            }
        } else {
            // 正常文本结束
            listener.onStreamEvent("stream_chat",
                    ChatStreamEventBus.StreamEventData.buildFinish(sessionId, cleanContent));
            Log.d(TAG, "📤 流式完成，最终内容长度=" + cleanContent.length());
        }
    }

    // ========== 公共请求逻辑 ==========

    private void executeStreamRequest(int sessionId, Request request,
                                      ChatStreamEventBus.StreamEventListener listener) {
        mCurrentCall = mOkHttpClient.newCall(request);
        mCurrentCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (!mIsStreamRunning) return;
                Log.e(TAG, "流式请求失败：" + e.getMessage());
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildError("请求失败：" + e.getMessage()));
                stopTimeoutDetection();
                mIsStreamRunning = false;
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "收到响应: code=" + response.code());
                if (!mIsStreamRunning || !response.isSuccessful()) {
                    String errorMsg = "响应失败：" + response.code();
                    Log.e(TAG, errorMsg);
                    listener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildError(errorMsg));
                    stopTimeoutDetection();
                    mIsStreamRunning = false;
                    return;
                }

                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    listener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildError("响应体为空"));
                    stopTimeoutDetection();
                    mIsStreamRunning = false;
                    return;
                }

                try {
                    parseStreamResponse(responseBody, sessionId, listener);
                } finally {
                    sendLocalUsageIfNeeded(sessionId, listener);
                    stopTimeoutDetection();
                    mIsStreamRunning = false;
                    responseBody.close();
                }
            }
        });
    }

    // ========== 对外接口：两个发送方法现在都非常简洁 ==========

    public void sendStreamMessage(int sessionId, String userMessage,
                                  ChatStreamEventBus.StreamEventListener listener) {
        if (!validateConfig()) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("配置不完整"));
            return;
        }
        if (mIsStreamRunning) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("已有请求在运行"));
            return;
        }

        mStreamEventListener = listener;
        mSessionId = sessionId;
        mIsStreamRunning = true;
        mReceivedUsageFromServer = false;
        mCurrentMessages = null;

        synchronized (mStreamCache) { synchronized (mChunkBuffer) {
            mStreamCache.setLength(0); mChunkBuffer.setLength(0);
        }}
        mLastPushTime = System.currentTimeMillis();
        startTimeoutDetection();

        String requestBody = buildRequestBody(userMessage);
        if (TextUtils.isEmpty(requestBody)) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("构建请求体失败"));
            mIsStreamRunning = false;
            return;
        }

        Log.d(TAG, "请求URL: " + buildRequestUrl());
        Log.d(TAG, "请求体预览: " + requestBody.substring(0, Math.min(200, requestBody.length())));

        Request request = buildOkHttpRequest(requestBody);
        executeStreamRequest(sessionId, request, listener);
    }

    public void sendStreamMessageWithMessages(int sessionId, JSONArray messages,
                                              ChatStreamEventBus.StreamEventListener listener) {
        sendStreamMessageWithMessages(sessionId, messages, null, listener);
    }

    public void sendStreamMessageWithMessages(int sessionId, JSONArray messages,
                                              JSONArray tools,
                                              ChatStreamEventBus.StreamEventListener listener) {
        if (!validateConfig()) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("配置不完整"));
            return;
        }
        if (mIsStreamRunning) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("已有请求在运行"));
            return;
        }

        mStreamEventListener = listener;
        mSessionId = sessionId;
        mIsStreamRunning = true;
        mReceivedUsageFromServer = false;
        mCurrentMessages = messages;
        mCurrentUserMessage = null;
        mCurrentTools = tools;

        synchronized (mStreamCache) { synchronized (mChunkBuffer) {
            mStreamCache.setLength(0); mChunkBuffer.setLength(0);
        }}
        mLastPushTime = System.currentTimeMillis();
        startTimeoutDetection();

        String requestBody = buildRequestBodyWithMessages(messages);
        if (TextUtils.isEmpty(requestBody)) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("构建请求体失败"));
            mIsStreamRunning = false;
            return;
        }

        Log.d(TAG, "请求URL: " + buildRequestUrl());
        Log.d(TAG, "请求体预览: " + requestBody.substring(0, Math.min(200, requestBody.length())));

        Request request = buildOkHttpRequest(requestBody);
        executeStreamRequest(sessionId, request, listener);
    }

    // ========== 工具方法 ==========

    private Request buildOkHttpRequest(String requestBody) {
        return new Request.Builder()
                .url(buildRequestUrl())
                .post(RequestBody.create(requestBody, JSON))
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Authorization", "Bearer " +
                        (TextUtils.isEmpty(mConfig.getApiKey()) ? "empty" : mConfig.getApiKey()))
                .addHeader("Accept", "text/event-stream")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Connection", "keep-alive")
                .addHeader("Accept-Encoding", "identity")
                .build();
    }

    private String buildRequestBody(String userMessage) {
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", mConfig.getModel());
            jsonBody.put("temperature", 0.7);
            jsonBody.put("max_tokens", mConfig.getMaxResponseTokens());
            jsonBody.put("stream", true);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", userMessage));
            jsonBody.put("messages", messages);
            mCurrentMessages = messages;
            mCurrentUserMessage = userMessage;
            if (mServerSupportsStreamOptions) {
                jsonBody.put("stream_options", new JSONObject().put("include_usage", true));
            }
            return jsonBody.toString();
        } catch (Exception e) {
            Log.e(TAG, "构建请求体失败：", e);
            return null;
        }
    }

    private String buildRequestBodyWithMessages(JSONArray messages) {
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", mConfig.getModel());
            jsonBody.put("temperature", 0.7);
            jsonBody.put("max_tokens", mConfig.getMaxResponseTokens());
            jsonBody.put("stream", true);
            jsonBody.put("messages", messages);
            if (mCurrentTools != null && mCurrentTools.length() > 0) {
                jsonBody.put("tools", mCurrentTools);
                jsonBody.put("tool_choice", "auto");
                Log.d(TAG, "注入 " + mCurrentTools.length() + " 个工具定义");
            }
            if (mServerSupportsStreamOptions) {
                jsonBody.put("stream_options", new JSONObject().put("include_usage", true));
            }
            return jsonBody.toString();
        } catch (Exception e) {
            Log.e(TAG, "构建请求体失败（预构建messages）：", e);
            return null;
        }
    }

    private String buildRequestUrl() {
        String baseUrl = mConfig.getServerUrl().trim();
        if (!baseUrl.startsWith("http")) baseUrl = "http://" + baseUrl;
        if (!baseUrl.endsWith("/v1/chat/completions")) {
            baseUrl = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";
        }
        return baseUrl;
    }

    public void cancelStream() {
        Log.d(TAG, "取消流式请求，当前运行状态: " + mIsStreamRunning);
        if (!mIsStreamRunning) return;
        stopTimeoutDetection();
        mIsStreamRunning = false;
        if (mCurrentCall != null && !mCurrentCall.isCanceled()) mCurrentCall.cancel();
        synchronized (mStreamCache) { synchronized (mChunkBuffer) {
            mChunkBuffer.setLength(0); mStreamCache.setLength(0);
        }}
        Log.d(TAG, "缓冲区已清空");
    }

    private void sendLocalUsageIfNeeded(int sessionId, ChatStreamEventBus.StreamEventListener listener) {
        if (mReceivedUsageFromServer) return;
        try {
            int promptTokens = 0;
            if (mCurrentMessages != null) {
                List<String> contents = new ArrayList<>();
                for (int i = 0; i < mCurrentMessages.length(); i++) {
                    String content = mCurrentMessages.getJSONObject(i).getString("content");
                    if (!TextUtils.isEmpty(content)) contents.add(content);
                }
                promptTokens = TokenEstimator.estimateMessagesTokens(contents);
            }
            String fullResponse;
            synchronized (mStreamCache) { fullResponse = mStreamCache.toString(); }
            int completionTokens = TokenEstimator.estimateTokens(fullResponse);
            int totalTokens = promptTokens + completionTokens;
            listener.onStreamEvent("stream_chat",
                    ChatStreamEventBus.StreamEventData.buildUsage(sessionId, promptTokens, completionTokens, totalTokens));
            Log.d(TAG, "本地估算Token: prompt=" + promptTokens + ", completion=" + completionTokens);
        } catch (Exception e) {
            Log.e(TAG, "本地估算Token失败", e);
        }
    }

    private void retryStreamRequest() {
        Log.d(TAG, "retryStreamRequest: 超时重试触发");
        if (mStreamEventListener != null) {
            mStreamEventListener.onStreamEvent("stream_chat",
                    ChatStreamEventBus.StreamEventData.buildError("[超时重试] 请求超时，触发重试"));
        }
        cancelStream();
    }

    private void showToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mContext != null) Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
        });
    }
}