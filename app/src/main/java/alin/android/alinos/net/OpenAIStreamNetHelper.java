package alin.android.alinos.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException; // 新增：解决IOException找不到
import java.util.concurrent.TimeUnit; // 新增：解决TimeUnit找不到

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
 * OpenAI流式请求类 - 替换OkHttp解决重定向/高延迟，适配所有平台
 */
public class OpenAIStreamNetHelper implements BaseNetHelper {
    private static final String TAG = "OpenAIStreamNetHelper";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    // 聚合阈值：8个字符推送一次，平衡实时性和延迟
    private static final int AGGREGATE_THRESHOLD = 8;
    // 超时兜底：200ms没推送就强制更UI，避免攒批过久
    private static final long PUSH_TIMEOUT = 200;
    // 超时管理常量（从ChatActivity迁移）
    private static final long STREAM_TIMEOUT_TIPS_DELAY = 3000;      // 3秒：轻提示（无影响）
    private static final long STREAM_FINAL_TIMEOUT = 480000;         // 8分钟：全局总超时
    private static final long STREAM_CHUNK_INTERVAL_TIMEOUT = 180000; // 3分钟：相邻分片间隔超时

    private Context mContext;
    private ConfigBean mConfig;
    private boolean mIsStreamRunning;
    private StringBuilder mStreamCache;
    private StringBuilder mChunkBuffer; // 分片聚合缓冲区
    private long mLastPushTime; // 最后一次推送时间
    private OkHttpClient mOkHttpClient;
    private Call mCurrentCall; // 用于取消请求
    // 超时管理字段（从ChatActivity迁移）
    private Handler mTimeoutHandler;
    private Runnable mTimeoutTipsRunnable;
    private Runnable mFinalTimeoutRunnable;
    private Runnable mIntervalTimeoutRunnable;
    private ChatStreamEventBus.StreamEventListener mStreamEventListener;
    private int mSessionId;
    private long mLastChunkReceiveTime = 0L;
    private boolean mIsFirstChunkReceived = false;
    private boolean mIsTimeoutTipShowed = false;
    // 新增：超时重试计数
    private int mTimeoutRetryCount = 0;
    // 新增：服务器是否支持stream_options参数
    private boolean mServerSupportsStreamOptions = true;
    // 新增：本次请求是否收到服务端usage信息
    private boolean mReceivedUsageFromServer = false;
    // 新增：当前请求的messages（用于本地Token估算）
    private JSONArray mCurrentMessages = null;
    // 新增：当前请求的用户消息（用于sendStreamMessage的Token估算）
    private String mCurrentUserMessage = null;

    // 构造器：初始化OkHttp（核心改造）
    public OpenAIStreamNetHelper(Context context, ConfigBean config) {
        this.mContext = context;
        this.mConfig = config;
        this.mIsStreamRunning = false;
        this.mStreamCache = new StringBuilder();
        this.mChunkBuffer = new StringBuilder();
        this.mLastPushTime = System.currentTimeMillis();

        // 初始化OkHttp，解决重定向+超时+连接复用
        this.mOkHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS) // 适配硅基流动高延迟
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true) // 强制跟随307/308重定向
                .followSslRedirects(true)
                .connectionPool(new ConnectionPool(5, 30, TimeUnit.SECONDS)) // 连接复用
                .retryOnConnectionFailure(true) // 弱网重试
                .build();

        Log.d(TAG, "初始化流式助手，配置：type=" + config.getType() + ", url=" + config.getServerUrl());

        // 初始化超时管理
        mTimeoutHandler = new Handler(Looper.getMainLooper());
        initTimeoutRunnables();
    }

    // ========== 接口实现（保留） ==========
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
        if (mConfig == null) {
            showToast("配置为空");
            return false;
        }
        boolean hasUrl = !TextUtils.isEmpty(mConfig.getServerUrl());
        boolean hasModel = !TextUtils.isEmpty(mConfig.getModel());
        if (!hasUrl || !hasModel) {
            showToast("服务器地址/模型不能为空");
            return false;
        }
        return true;
    }

    @Override
    public void setContextAndConfig(Context context, ConfigBean config) {
        this.mContext = context;
        this.mConfig = config;
    }

    // ========== 超时管理方法 ==========
    private void initTimeoutRunnables() {
        // 1. 3秒轻提示
        mTimeoutTipsRunnable = () -> {
            if (!mIsStreamRunning || mIsTimeoutTipShowed) return;
            Log.d(TAG, "流式请求3秒未收到分片，轻提示");
            mIsTimeoutTipShowed = true;
            showToast("流式请求已发送，正在等待响应...");
        };

        // 2. 3分钟间隔超时（每30秒检查一次）
        mIntervalTimeoutRunnable = () -> {
            if (!mIsStreamRunning || !mIsFirstChunkReceived) return;
            long currentTime = System.currentTimeMillis();
            if ((currentTime - mLastChunkReceiveTime) > STREAM_CHUNK_INTERVAL_TIMEOUT) {
                Log.e(TAG, "上下文断开异常：相邻分片接收间隔超3分钟");
                // 先尝试重试而非直接报错
                if (mTimeoutRetryCount < 2) {
                    mTimeoutRetryCount++;
                    Log.d(TAG, "间隔超时，第" + mTimeoutRetryCount + "次重试...");
                    // 重新发送请求
                    retryStreamRequest();
                    return;
                } else {
                    // 重试失败后报错
                    if (mStreamEventListener != null) {
                        mStreamEventListener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildError("[超时重试] 上下文断开异常"));
                    }
                    cancelStream();
                }
            } else {
                // 继续检查
                mTimeoutHandler.postDelayed(mIntervalTimeoutRunnable, 30000);
            }
        };

        // 3. 8分钟全局总超时
        mFinalTimeoutRunnable = () -> {
            if (!mIsStreamRunning) return;
            Log.e(TAG, "流式请求8分钟全局总超时");
            // 先尝试重试而非直接报错
            if (mTimeoutRetryCount < 2) {
                mTimeoutRetryCount++;
                Log.d(TAG, "全局超时，第" + mTimeoutRetryCount + "次重试...");
                // 重新发送请求
                retryStreamRequest();
                return;
            } else {
                // 重试失败后报错
                if (mStreamEventListener != null) {
                    mStreamEventListener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildError("[超时重试] 全局超时异常"));
                }
                cancelStream();
            }
        };
    }

    // 启动超时检测
    private void startTimeoutDetection() {
        mIsTimeoutTipShowed = false;
        mTimeoutRetryCount = 0;
        mLastChunkReceiveTime = 0L;
        mIsFirstChunkReceived = false;

        mTimeoutHandler.postDelayed(mTimeoutTipsRunnable, STREAM_TIMEOUT_TIPS_DELAY);
        mTimeoutHandler.postDelayed(mFinalTimeoutRunnable, STREAM_FINAL_TIMEOUT);
        // 间隔超时需要定期检查，每30秒检查一次
        mTimeoutHandler.postDelayed(mIntervalTimeoutRunnable, 30000); // 30秒后开始检查间隔超时
    }

    // 停止超时检测
    private void stopTimeoutDetection() {
        mTimeoutHandler.removeCallbacks(mTimeoutTipsRunnable);
        mTimeoutHandler.removeCallbacks(mFinalTimeoutRunnable);
        mTimeoutHandler.removeCallbacks(mIntervalTimeoutRunnable);
        // 清理引用
        mStreamEventListener = null;
    }

    // 重置间隔超时计时器（收到分片时调用）
    private void resetIntervalTimeout() {
        mTimeoutHandler.removeCallbacks(mIntervalTimeoutRunnable);
        mTimeoutHandler.postDelayed(mIntervalTimeoutRunnable, 30000); // 重新开始检查
    }

    // ========== 核心：流式请求（替换为OkHttp） ==========
    public void sendStreamMessage(int sessionId, String userMessage, ChatStreamEventBus.StreamEventListener listener) {
        // 前置校验
        if (!validateConfig()) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("配置不完整"));
            return;
        }
        Log.d(TAG, "配置信息: type=" + mConfig.getType() + ", serverUrl=" + mConfig.getServerUrl() + ", model=" + mConfig.getModel());
        if (mIsStreamRunning) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("已有请求在运行"));
            return;
        }

        // 保存listener和sessionId用于超时管理
        mStreamEventListener = listener;
        mSessionId = sessionId;

        // 重置状态
        mIsStreamRunning = true;
        mStreamCache.setLength(0);
        mChunkBuffer.setLength(0);
        mLastPushTime = System.currentTimeMillis();
        mReceivedUsageFromServer = false;
        mCurrentMessages = null;

        // 启动超时检测
        startTimeoutDetection();

        // 构建请求体
        String requestBody = buildRequestBody(userMessage);
        Log.d(TAG, "请求体长度: " + (requestBody != null ? requestBody.length() : 0));
        if (requestBody != null && requestBody.length() > 0) {
            Log.d(TAG, "请求体预览: " + requestBody.substring(0, Math.min(200, requestBody.length())));
        }
        if (TextUtils.isEmpty(requestBody)) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("构建请求体失败"));
            mIsStreamRunning = false;
            return;
        }

        // 构建请求URL（兼容硅基流动/本地服务）
        String apiUrl = buildRequestUrl();
        Log.d(TAG, "请求URL: " + apiUrl);

        // 构建OkHttp请求
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(requestBody, JSON))
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Authorization", "Bearer " + (TextUtils.isEmpty(mConfig.getApiKey()) ? "empty" : mConfig.getApiKey()))
                .addHeader("Accept", "text/event-stream")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Connection", "keep-alive")
                .addHeader("Accept-Encoding", "identity")
                .build();

        // 发起异步流式请求
        mCurrentCall = mOkHttpClient.newCall(request);
        mCurrentCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (!mIsStreamRunning) return;
                Log.e(TAG, "流式请求失败：", e);
                Log.d(TAG, "请求失败详情: url=" + (call.request() != null ? call.request().url() : "null") + ", exception=" + e.getClass().getName() + ": " + e.getMessage());
                listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("请求失败：" + e.getMessage()));
                stopTimeoutDetection();
                mIsStreamRunning = false;
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "收到响应: code=" + response.code() + ", message=" + response.message() + ", headers=" + response.headers());
                if (!mIsStreamRunning || !response.isSuccessful()) {
                    if (response != null) {
                        String errorMsg = "响应失败：" + response.code() + "，" + response.message();
                        Log.e(TAG, errorMsg);
                        listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError(errorMsg));
                    }
                    stopTimeoutDetection();
                    mIsStreamRunning = false;
                    return;
                }

                // 解析流式响应（核心优化：分片聚合）
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("响应体为空"));
                    stopTimeoutDetection();
                    mIsStreamRunning = false;
                    return;
                }

                BufferedSource source = responseBody.source();
                String line;
                while ((line = source.readUtf8Line()) != null && mIsStreamRunning) {
                    if (TextUtils.isEmpty(line)) continue;
                    if (!line.startsWith("data: ")) {
                        Log.d(TAG, "忽略非数据行: " + line);
                        continue;
                    }

                    String dataStr = line.substring(6).trim();
                    if ("[DONE]".equals(dataStr)) break;

                    // 解析分片内容
                    Log.d(TAG, "解析分片数据，长度=" + dataStr.length() + ", 预览: " + (dataStr.length() > 200 ? dataStr.substring(0, 200) : dataStr));
                    try {
                        JSONObject chunkJson = new JSONObject(dataStr);
                        Log.d(TAG, "chunkJson keys: " + chunkJson.keys());

                        // 检查是否是usage消息（包含usage字段）
                        if (chunkJson.has("usage")) {
                            JSONObject usage = chunkJson.getJSONObject("usage");
                            int promptTokens = usage.optInt("prompt_tokens", 0);
                            int completionTokens = usage.optInt("completion_tokens", 0);
                            int totalTokens = usage.optInt("total_tokens", 0);

                            // 发送usage事件
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildUsage(sessionId, promptTokens, completionTokens, totalTokens));
                            mReceivedUsageFromServer = true;
                            continue;
                        }

                        // 正常的内容分片
                        JSONArray choices = chunkJson.getJSONArray("choices");
                        if (choices.length() == 0) {
                            Log.d(TAG, "choices数组为空，跳过该分片");
                            continue;
                        }

                        JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                        String deltaStr = delta.toString();
                        Log.d(TAG, "delta对象keys: " + delta.keys());
                        // 打印delta所有键值对
                        try {
                            java.util.Iterator<String> keys = delta.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                Object value = delta.get(key);
                                Log.d(TAG, "delta[" + key + "] = " + value + " (类型: " + (value != null ? value.getClass().getSimpleName() : "null") + ")");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "打印delta键值对失败", e);
                        }
                        Log.d(TAG, "delta has content: " + delta.has("content") + ", delta: " + (deltaStr.length() > 100 ? deltaStr.substring(0, 100) : deltaStr));
                        // 尝试从delta提取内容，支持多种字段名
                        String chunkContent = "";
                        if (delta.has("content")) {
                            chunkContent = delta.optString("content", "");
                            Log.d(TAG, "从content字段提取: '" + chunkContent + "'");
                        } else if (delta.has("text")) {
                            chunkContent = delta.optString("text", "");
                            Log.d(TAG, "从text字段提取: '" + chunkContent + "'");
                        } else if (delta.has("message")) {
                            chunkContent = delta.optString("message", "");
                            Log.d(TAG, "从message字段提取: '" + chunkContent + "'");
                        } else if (delta.has("response")) {
                            chunkContent = delta.optString("response", "");
                            Log.d(TAG, "从response字段提取: '" + chunkContent + "'");
                        } else {
                            // 如果没有已知字段，尝试将delta本身作为字符串（某些API可能直接返回文本）
                            chunkContent = delta.toString();
                            Log.d(TAG, "从delta.toString()提取: '" + chunkContent + "'");
                        }

                        if (TextUtils.isEmpty(chunkContent)) {
                            Log.d(TAG, "chunkContent为空，跳过该分片");
                            continue;
                        }
                        Log.d(TAG, "成功提取chunkContent，长度=" + chunkContent.length() + ", 内容预览: " + (chunkContent.length() > 50 ? chunkContent.substring(0, 50) : chunkContent));

                        // 聚合分片：凑够阈值/超时就推送
                        mChunkBuffer.append(chunkContent);
                        mStreamCache.append(chunkContent);
                        Log.d(TAG, "追加到mStreamCache，长度=" + chunkContent.length() +
                              ", 累计长度=" + mStreamCache.length() +
                              ", 内容预览：" + (chunkContent.length() > 20 ? chunkContent.substring(0, 20) : chunkContent));
                        long currentTime = System.currentTimeMillis();

                        // 更新分片接收时间（用于间隔超时检测）
                        mLastChunkReceiveTime = currentTime;
                        if (!mIsFirstChunkReceived) {
                            mIsFirstChunkReceived = true;
                        }
                        // 重置间隔超时计时器
                        resetIntervalTimeout();

                        if (mChunkBuffer.length() > 0 && (mChunkBuffer.length() >= AGGREGATE_THRESHOLD || (currentTime - mLastPushTime) >= PUSH_TIMEOUT)) {
                            Log.d(TAG, "发送聚合chunk事件，长度=" + mChunkBuffer.length() + ", 内容预览: " + (mChunkBuffer.length() > 50 ? mChunkBuffer.substring(0, 50) : mChunkBuffer.toString()));
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildChunk(sessionId, mChunkBuffer.toString()));
                            mChunkBuffer.setLength(0);
                            mLastPushTime = currentTime;
                        } else {
                            Log.d(TAG, "mChunkBuffer状态: 长度=" + mChunkBuffer.length() + ", 阈值=" + AGGREGATE_THRESHOLD + ", 时间差=" + (currentTime - mLastPushTime) + "ms");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析分片失败，数据: " + (dataStr != null ? (dataStr.length() > 100 ? dataStr.substring(0, 100) : dataStr) : "null"), e);
                    }
                }

                // 推送最后剩余的分片
                if (mChunkBuffer.length() > 0) {
                    Log.d(TAG, "发送剩余chunk事件，长度=" + mChunkBuffer.length() + ", 内容预览: " + (mChunkBuffer.length() > 50 ? mChunkBuffer.substring(0, 50) : mChunkBuffer.toString()));
                    listener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildChunk(sessionId, mChunkBuffer.toString()));
                }

                // 通知流式结束
                Log.d(TAG, "流式结束，mStreamCache长度=" + mStreamCache.length() + ", mChunkBuffer长度=" + mChunkBuffer.length());
                if (mStreamCache.length() == 0) {
                    Log.w(TAG, "警告：mStreamCache为空，可能分片内容未正确累积");
                }
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildFinish(sessionId, mStreamCache.toString()));

                // 如果服务端未提供usage，则使用本地估算
                sendLocalUsageIfNeeded(sessionId, listener);

                stopTimeoutDetection();
                mIsStreamRunning = false;
                responseBody.close();
            }
        });
    }

    /**
     * 发送流式消息（支持预构建的messages）
     */
    public void sendStreamMessageWithMessages(int sessionId, JSONArray messages, ChatStreamEventBus.StreamEventListener listener) {
        // 前置校验
        if (!validateConfig()) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("配置不完整"));
            return;
        }
        Log.d(TAG, "配置信息: type=" + mConfig.getType() + ", serverUrl=" + mConfig.getServerUrl() + ", model=" + mConfig.getModel());
        if (mIsStreamRunning) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("已有请求在运行"));
            return;
        }

        // 保存listener和sessionId用于超时管理
        mStreamEventListener = listener;
        mSessionId = sessionId;

        // 重置状态
        mIsStreamRunning = true;
        mStreamCache.setLength(0);
        mChunkBuffer.setLength(0);
        mLastPushTime = System.currentTimeMillis();
        mReceivedUsageFromServer = false;
        mCurrentMessages = null;

        // 启动超时检测
        startTimeoutDetection();

        // 构建请求体
        String requestBody = buildRequestBodyWithMessages(messages);
        Log.d(TAG, "请求体长度: " + (requestBody != null ? requestBody.length() : 0));
        if (requestBody != null && requestBody.length() > 0) {
            Log.d(TAG, "请求体预览: " + requestBody.substring(0, Math.min(200, requestBody.length())));
        }
        if (TextUtils.isEmpty(requestBody)) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("构建请求体失败"));
            mIsStreamRunning = false;
            return;
        }

        // 构建请求URL（兼容硅基流动/本地服务）
        String apiUrl = buildRequestUrl();
        Log.d(TAG, "请求URL: " + apiUrl);

        // 构建OkHttp请求
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(requestBody, JSON))
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Authorization", "Bearer " + (TextUtils.isEmpty(mConfig.getApiKey()) ? "empty" : mConfig.getApiKey()))
                .addHeader("Accept", "text/event-stream")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Connection", "keep-alive")
                .addHeader("Accept-Encoding", "identity")
                .build();

        // 发起异步流式请求
        mCurrentCall = mOkHttpClient.newCall(request);
        mCurrentCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (!mIsStreamRunning) return;
                Log.e(TAG, "流式请求失败：", e);
                Log.d(TAG, "请求失败详情: url=" + (call.request() != null ? call.request().url() : "null") + ", exception=" + e.getClass().getName() + ": " + e.getMessage());
                listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("请求失败：" + e.getMessage()));
                stopTimeoutDetection();
                mIsStreamRunning = false;
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "收到响应: code=" + response.code() + ", message=" + response.message() + ", headers=" + response.headers());
                if (!mIsStreamRunning || !response.isSuccessful()) {
                    if (response != null) {
                        String errorMsg = "响应失败：" + response.code() + "，" + response.message();
                        Log.e(TAG, errorMsg);
                        listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError(errorMsg));
                    }
                    stopTimeoutDetection();
                    mIsStreamRunning = false;
                    return;
                }

                // 解析流式响应（核心优化：分片聚合）
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("响应体为空"));
                    stopTimeoutDetection();
                    mIsStreamRunning = false;
                    return;
                }

                BufferedSource source = responseBody.source();
                String line;
                while ((line = source.readUtf8Line()) != null && mIsStreamRunning) {
                    if (TextUtils.isEmpty(line)) continue;
                    if (!line.startsWith("data: ")) {
                        Log.d(TAG, "忽略非数据行: " + line);
                        continue;
                    }

                    String dataStr = line.substring(6).trim();
                    if ("[DONE]".equals(dataStr)) break;

                    // 解析分片内容
                    Log.d(TAG, "解析分片数据，长度=" + dataStr.length() + ", 预览: " + (dataStr.length() > 200 ? dataStr.substring(0, 200) : dataStr));
                    try {
                        JSONObject chunkJson = new JSONObject(dataStr);
                        Log.d(TAG, "chunkJson keys: " + chunkJson.keys());

                        // 检查是否是usage消息（包含usage字段）
                        if (chunkJson.has("usage")) {
                            JSONObject usage = chunkJson.getJSONObject("usage");
                            int promptTokens = usage.optInt("prompt_tokens", 0);
                            int completionTokens = usage.optInt("completion_tokens", 0);
                            int totalTokens = usage.optInt("total_tokens", 0);

                            // 发送usage事件
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildUsage(sessionId, promptTokens, completionTokens, totalTokens));
                            mReceivedUsageFromServer = true;
                            continue;
                        }

                        // 正常的内容分片
                        JSONArray choices = chunkJson.getJSONArray("choices");
                        if (choices.length() == 0) {
                            Log.d(TAG, "choices数组为空，跳过该分片");
                            continue;
                        }

                        JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                        String deltaStr = delta.toString();
                        Log.d(TAG, "delta对象keys: " + delta.keys());
                        // 打印delta所有键值对
                        try {
                            java.util.Iterator<String> keys = delta.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                Object value = delta.get(key);
                                Log.d(TAG, "delta[" + key + "] = " + value + " (类型: " + (value != null ? value.getClass().getSimpleName() : "null") + ")");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "打印delta键值对失败", e);
                        }
                        Log.d(TAG, "delta has content: " + delta.has("content") + ", delta: " + (deltaStr.length() > 100 ? deltaStr.substring(0, 100) : deltaStr));
                        // 尝试从delta提取内容，支持多种字段名
                        String chunkContent = "";
                        if (delta.has("content")) {
                            chunkContent = delta.optString("content", "");
                            Log.d(TAG, "从content字段提取: '" + chunkContent + "'");
                        } else if (delta.has("text")) {
                            chunkContent = delta.optString("text", "");
                            Log.d(TAG, "从text字段提取: '" + chunkContent + "'");
                        } else if (delta.has("message")) {
                            chunkContent = delta.optString("message", "");
                            Log.d(TAG, "从message字段提取: '" + chunkContent + "'");
                        } else if (delta.has("response")) {
                            chunkContent = delta.optString("response", "");
                            Log.d(TAG, "从response字段提取: '" + chunkContent + "'");
                        } else {
                            // 如果没有已知字段，尝试将delta本身作为字符串（某些API可能直接返回文本）
                            chunkContent = delta.toString();
                            Log.d(TAG, "从delta.toString()提取: '" + chunkContent + "'");
                        }

                        if (TextUtils.isEmpty(chunkContent)) {
                            Log.d(TAG, "chunkContent为空，跳过该分片");
                            continue;
                        }
                        Log.d(TAG, "成功提取chunkContent，长度=" + chunkContent.length() + ", 内容预览: " + (chunkContent.length() > 50 ? chunkContent.substring(0, 50) : chunkContent));

                        // 聚合分片：凑够阈值/超时就推送
                        mChunkBuffer.append(chunkContent);
                        mStreamCache.append(chunkContent);
                        Log.d(TAG, "追加到mStreamCache，长度=" + chunkContent.length() +
                              ", 累计长度=" + mStreamCache.length() +
                              ", 内容预览：" + (chunkContent.length() > 20 ? chunkContent.substring(0, 20) : chunkContent));
                        long currentTime = System.currentTimeMillis();

                        // 更新分片接收时间（用于间隔超时检测）
                        mLastChunkReceiveTime = currentTime;
                        if (!mIsFirstChunkReceived) {
                            mIsFirstChunkReceived = true;
                        }
                        // 重置间隔超时计时器
                        resetIntervalTimeout();

                        if (mChunkBuffer.length() > 0 && (mChunkBuffer.length() >= AGGREGATE_THRESHOLD || (currentTime - mLastPushTime) >= PUSH_TIMEOUT)) {
                            Log.d(TAG, "发送聚合chunk事件，长度=" + mChunkBuffer.length() + ", 内容预览: " + (mChunkBuffer.length() > 50 ? mChunkBuffer.substring(0, 50) : mChunkBuffer.toString()));
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildChunk(sessionId, mChunkBuffer.toString()));
                            mChunkBuffer.setLength(0);
                            mLastPushTime = currentTime;
                        } else {
                            Log.d(TAG, "mChunkBuffer状态: 长度=" + mChunkBuffer.length() + ", 阈值=" + AGGREGATE_THRESHOLD + ", 时间差=" + (currentTime - mLastPushTime) + "ms");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析分片失败，数据: " + (dataStr != null ? (dataStr.length() > 100 ? dataStr.substring(0, 100) : dataStr) : "null"), e);
                    }
                }

                // 推送最后剩余的分片
                if (mChunkBuffer.length() > 0) {
                    Log.d(TAG, "发送剩余chunk事件，长度=" + mChunkBuffer.length() + ", 内容预览: " + (mChunkBuffer.length() > 50 ? mChunkBuffer.substring(0, 50) : mChunkBuffer.toString()));
                    listener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildChunk(sessionId, mChunkBuffer.toString()));
                }

                // 通知流式结束
                Log.d(TAG, "流式结束，mStreamCache长度=" + mStreamCache.length() + ", mChunkBuffer长度=" + mChunkBuffer.length());
                if (mStreamCache.length() == 0) {
                    Log.w(TAG, "警告：mStreamCache为空，可能分片内容未正确累积");
                }
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildFinish(sessionId, mStreamCache.toString()));

                // 如果服务端未提供usage，则使用本地估算
                sendLocalUsageIfNeeded(sessionId, listener);

                stopTimeoutDetection();
                mIsStreamRunning = false;
                responseBody.close();
            }
        });
    }

    // 构建请求体（标准化，适配所有平台）
    private String buildRequestBody(String userMessage) {
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", mConfig.getModel());
            jsonBody.put("temperature", 0.7);
            jsonBody.put("max_tokens", mConfig.getMaxResponseTokens()); // 使用配置的最大回复消息
            jsonBody.put("stream", true); // 强制流式

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", userMessage));
            jsonBody.put("messages", messages);

            // 如果服务器支持stream_options，则添加以获取usage信息
            if (mServerSupportsStreamOptions) {
                JSONObject streamOptions = new JSONObject();
                streamOptions.put("include_usage", true);
                jsonBody.put("stream_options", streamOptions);
            }

            return jsonBody.toString();
        } catch (Exception e) {
            Log.e(TAG, "构建请求体失败：", e);
            return null;
        }
    }

    // 构建请求体（支持预构建的messages）
    private String buildRequestBodyWithMessages(JSONArray messages) {
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", mConfig.getModel());
            jsonBody.put("temperature", 0.7);
            jsonBody.put("max_tokens", mConfig.getMaxResponseTokens()); // 使用配置的最大回复消息
            jsonBody.put("stream", true); // 强制流式
            jsonBody.put("messages", messages);

            // 如果服务器支持stream_options，则添加以获取usage信息
            if (mServerSupportsStreamOptions) {
                JSONObject streamOptions = new JSONObject();
                streamOptions.put("include_usage", true);
                jsonBody.put("stream_options", streamOptions);
            }

            return jsonBody.toString();
        } catch (Exception e) {
            Log.e(TAG, "构建请求体失败（预构建messages）：", e);
            return null;
        }
    }

    // 构建请求URL（兼容不同平台的路径）
    private String buildRequestUrl() {
        String baseUrl = mConfig.getServerUrl().trim();
        if (!baseUrl.startsWith("http")) {
            baseUrl = "http://" + baseUrl;
        }
        if (!baseUrl.endsWith("/v1/chat/completions")) {
            baseUrl = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";
        }
        return baseUrl;
    }

    // 检测是否为NVIDIA API
    private boolean isNvidiaApi() {
        return mConfig != null && mConfig.getServerUrl() != null &&
               mConfig.getServerUrl().toLowerCase().contains("nvidia");
    }

    // 取消流式请求（修复内存泄漏）
    public void cancelStream() {
        Log.d(TAG, "取消流式请求");
        stopTimeoutDetection();
        mIsStreamRunning = false;
        if (mCurrentCall != null && !mCurrentCall.isCanceled()) {
            mCurrentCall.cancel();
        }
        mChunkBuffer.setLength(0);
        mStreamCache.setLength(0);
    }

    // 本地估算Token并发送usage事件（如果服务端未提供）
    private void sendLocalUsageIfNeeded(int sessionId, ChatStreamEventBus.StreamEventListener listener) {
        if (mReceivedUsageFromServer) {
            return; // 服务端已提供usage，无需本地估算
        }

        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;

        try {
            // 估算prompt tokens
            if (mCurrentMessages != null) {
                // 有预构建的messages，使用TokenEstimator估算
                List<String> contents = new ArrayList<>();
                for (int i = 0; i < mCurrentMessages.length(); i++) {
                    JSONObject msg = mCurrentMessages.getJSONObject(i);
                    String content = msg.getString("content");
                    if (content != null && !content.isEmpty()) {
                        contents.add(content);
                    }
                }
                promptTokens = TokenEstimator.estimateMessagesTokens(contents);
            } else {
                // 没有预构建messages，可能是通过sendStreamMessage发送的单条用户消息
                // 这里无法准确估算，暂时设为0
                promptTokens = 0;
            }

            // 估算completion tokens（基于已接收的完整响应内容）
            String fullResponse = mStreamCache.toString();
            completionTokens = TokenEstimator.estimateTokens(fullResponse);

            totalTokens = promptTokens + completionTokens;

            // 发送usage事件
            listener.onStreamEvent("stream_chat",
                    ChatStreamEventBus.StreamEventData.buildUsage(sessionId, promptTokens, completionTokens, totalTokens));
            Log.d(TAG, "本地估算Token: prompt=" + promptTokens + ", completion=" + completionTokens + ", total=" + totalTokens);
        } catch (Exception e) {
            Log.e(TAG, "本地估算Token失败", e);
        }
    }

    // Toast工具方法
    private void showToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mContext != null) {
                Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 重试流式请求（暂时只记录日志，由ChatActivity处理重试）
    private void retryStreamRequest() {
        Log.d(TAG, "retryStreamRequest: 超时重试触发，由ChatActivity处理重试逻辑");
        // 发送超时重试错误，让ChatActivity处理重试
        if (mStreamEventListener != null) {
            mStreamEventListener.onStreamEvent("stream_chat",
                ChatStreamEventBus.StreamEventData.buildError("[超时重试] 请求超时，触发重试"));
        }
        cancelStream();
    }
}