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
public class OpenAIStreamNetHelper implements BaseNetHelper {
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

    // ========== 接口实现 ==========

    @Override
    public String sendMessage(String message) {
        Log.w(TAG, "请使用sendStreamMessage触发流式");
        return "[提示] 长按发送按钮触发流式响应";
    }

    @Override
    public String getServiceType() {
        return mConfig != null ? mConfig.getType() : "openai_stream";
    }

    @Override
    public boolean validateConfig() {
        if (mConfig == null) { showToast("配置为空"); return false; }
        if (TextUtils.isEmpty(mConfig.getServerUrl()) || TextUtils.isEmpty(mConfig.getModel())) {
            showToast("服务器地址/模型不能为空"); return false;
        }
        return true;
    }

    @Override
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

    private void parseStreamResponse(ResponseBody responseBody, int sessionId,
                                     ChatStreamEventBus.StreamEventListener listener) throws IOException {
        BufferedSource source = responseBody.source();
        String line;

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
            Log.d(TAG, "🔷 分片#" + chunkCount + "，长度=" + dataStr.length() + ", 预览: " +
                    (dataStr.length() > 200 ? dataStr.substring(0, 200) : dataStr));

            try {
                JSONObject chunkJson = new JSONObject(dataStr);
                Log.d(TAG, "chunkJson keys: " + chunkJson.keys());

                // ✅ 修复：只有 usage 字段非 null 且有内容时才当作 usage 帧处理
                // NVIDIA API 每帧都携带 "usage": null，旧写法 has("usage") 会把所有内容帧都跳过
                
                // ========== 修复：正确判断 usage 帧，兼容 NVIDIA / minimax ==========
                boolean isUsageFrame = false;
                boolean hasContent = false;

                // 先检查是否有有效content
                if (chunkJson.has("choices") && chunkJson.getJSONArray("choices").length() > 0) {
                    JSONObject delta = chunkJson.getJSONArray("choices").getJSONObject(0).optJSONObject("delta");
                    if (delta != null && (delta.has("content") || delta.has("text") || delta.has("message") || delta.has("response"))) {
                        String testContent = delta.optString("content",
                                          delta.optString("text",
                                          delta.optString("message",
                                          delta.optString("response", ""))));
                        if (!TextUtils.isEmpty(testContent)) {
                            hasContent = true;
                        }
                    }
                }

                // 判断是否为有效usage帧
                if (chunkJson.has("usage")) {
                    Object usageObj = chunkJson.get("usage");
                    // 只有 usage 不为 null && 不是JSONObject.NULL && 是对象 && 不为空 才是 usage 帧
                    if (usageObj != null && usageObj != JSONObject.NULL &&
                        usageObj instanceof JSONObject && ((JSONObject) usageObj).length() > 0) {
                        isUsageFrame = true;
                        Log.d(TAG, "检测到有效usage帧，usage字段长度：" + ((JSONObject) usageObj).length());
                    }
                }

                // 处理usage信息（如果有）
                if (isUsageFrame) {
                    Log.d(TAG, "处理usage帧Token信息");
                    JSONObject usage = chunkJson.getJSONObject("usage");
                    int promptTokens = usage.optInt("prompt_tokens", 0);
                    int completionTokens = usage.optInt("completion_tokens", 0);
                    int totalTokens = usage.optInt("total_tokens", 0);
                    listener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildUsage(sessionId, promptTokens, completionTokens, totalTokens));
                    mReceivedUsageFromServer = true;
                }

                // 只有纯usage帧（无有效content）时才跳过内容处理
                if (isUsageFrame && !hasContent) {
                    Log.d(TAG, "纯usage帧，跳过内容处理");
                    continue;
                }

                // 正常内容分片
                Log.d(TAG, "🔵 开始处理内容分片，hasContent=" + hasContent + ", isUsageFrame=" + isUsageFrame);
                JSONArray choices = chunkJson.getJSONArray("choices");
                Log.d(TAG, "choices长度: " + choices.length());
                if (choices.length() == 0) {
                    Log.d(TAG, "空choices帧，跳过");
                    continue;
                }

                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                Log.d(TAG, "delta has content: " + delta.has("content") +
                        ", isNull: " + delta.isNull("content"));

                // 提取内容，支持多种字段名
                String chunkContent = "";
                if (delta.has("content") && !delta.isNull("content")) {
                    chunkContent = delta.getString("content");
                } else if (delta.has("text") && !delta.isNull("text")) {
                    chunkContent = delta.getString("text");
                } else if (delta.has("message") && !delta.isNull("message")) {
                    chunkContent = delta.getString("message");
                } else if (delta.has("response") && !delta.isNull("response")) {
                    chunkContent = delta.getString("response");
                }

                Log.d(TAG, "提取内容长度: " + chunkContent.length());

                if (TextUtils.isEmpty(chunkContent)) {
                    Log.d(TAG, "chunkContent为空，跳过该分片");
                    continue;
                }

                Log.d(TAG, "成功提取chunkContent: '" +
                        (chunkContent.length() > 30 ? chunkContent.substring(0, 30) : chunkContent) + "'");

                long currentTime = System.currentTimeMillis();
                mLastChunkReceiveTime = currentTime;
                if (!mIsFirstChunkReceived) {
                    mIsFirstChunkReceived = true;
                    cancelTimeoutTips();
                }
                resetIntervalTimeout();

                // 聚合推送（包含累积操作）
                synchronized (mChunkBuffer) {
                    // 累积到缓存
                    mChunkBuffer.append(chunkContent);
                    mStreamCache.append(chunkContent);
                    Log.d(TAG, "✅ 成功追加内容，chunk长度=" + chunkContent.length() +
                          ", mChunkBuffer长度=" + mChunkBuffer.length() +
                          ", mStreamCache长度=" + mStreamCache.length());

                    // 检查是否需要发送（阈值=1，每次都会发送）
                    if (mChunkBuffer.length() >= AGGREGATE_THRESHOLD ||
                            (currentTime - mLastPushTime) >= PUSH_TIMEOUT) {
                        String aggregated = mChunkBuffer.toString();
                        Log.d(TAG, "🚀 发送聚合chunk事件，长度=" + aggregated.length());
                        listener.onStreamEvent("stream_chat",
                                ChatStreamEventBus.StreamEventData.buildChunk(sessionId, aggregated));
                        mChunkBuffer.setLength(0);
                        mLastPushTime = currentTime;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "解析分片失败: " + (dataStr.length() > 100 ? dataStr.substring(0, 100) : dataStr), e);
            }
        }

        // 推送剩余分片
        synchronized (mChunkBuffer) {
            if (mChunkBuffer.length() > 0) {
                String remaining = mChunkBuffer.toString();
                Log.d(TAG, "发送剩余chunk，长度=" + remaining.length());
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildChunk(sessionId, remaining));
                mChunkBuffer.setLength(0);
            }
        }

        // 通知结束
        String finalContent;
        synchronized (mStreamCache) {
            finalContent = mStreamCache.toString();
            Log.d(TAG, "📊 流式结束统计：mStreamCache长度=" + finalContent.length());
            if (finalContent.isEmpty()) {
                Log.w(TAG, "⚠️ 警告：mStreamCache为空，检查分片处理逻辑");
                // 打印最近处理的分片信息用于调试
                Log.d(TAG, "最近分片处理状态：mIsStreamRunning=" + mIsStreamRunning +
                      ", mIsFirstChunkReceived=" + mIsFirstChunkReceived +
                      ", mReceivedUsageFromServer=" + mReceivedUsageFromServer);
            } else {
                Log.d(TAG, "流式内容预览：" + (finalContent.length() > 100 ? finalContent.substring(0, 100) + "..." : finalContent));
            }
        }

        // 过滤 <think>...</think> 思考块，不显示给用户
        //String beforeFilter = finalContent;
       // finalContent = finalContent.replaceAll("(?s)<think>.*?</think>", "").trim();
       // Log.d(TAG, "🔧 过滤思考块：过滤前长度=" + beforeFilter.length() + "，过滤后长度=" + finalContent.length());

        listener.onStreamEvent("stream_chat",
                ChatStreamEventBus.StreamEventData.buildFinish(sessionId, finalContent));
        Log.d(TAG, "📤 发送流式结束事件，sessionId=" + sessionId + "，最终内容长度=" + finalContent.length());
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