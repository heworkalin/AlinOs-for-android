package alin.android.alinos.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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

    private Context mContext;
    private ConfigBean mConfig;
    private boolean mIsStreamRunning;
    private StringBuilder mStreamCache;
    private StringBuilder mChunkBuffer; // 分片聚合缓冲区
    private long mLastPushTime; // 最后一次推送时间
    private OkHttpClient mOkHttpClient;
    private Call mCurrentCall; // 用于取消请求

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

    // ========== 核心：流式请求（替换为OkHttp） ==========
    public void sendStreamMessage(int sessionId, String userMessage, ChatStreamEventBus.StreamEventListener listener) {
        // 前置校验
        if (!validateConfig()) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("配置不完整"));
            return;
        }
        if (mIsStreamRunning) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("已有请求在运行"));
            return;
        }

        // 重置状态
        mIsStreamRunning = true;
        mStreamCache.setLength(0);
        mChunkBuffer.setLength(0);
        mLastPushTime = System.currentTimeMillis();

        // 构建请求体
        String requestBody = buildRequestBody(userMessage);
        if (TextUtils.isEmpty(requestBody)) {
            listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("构建请求体失败"));
            mIsStreamRunning = false;
            return;
        }

        // 构建请求URL（兼容硅基流动/本地服务）
        String apiUrl = buildRequestUrl();

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
                listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("请求失败：" + e.getMessage()));
                mIsStreamRunning = false;
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!mIsStreamRunning || !response.isSuccessful()) {
                    if (response != null) {
                        String errorMsg = "响应失败：" + response.code() + "，" + response.message();
                        Log.e(TAG, errorMsg);
                        listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError(errorMsg));
                    }
                    mIsStreamRunning = false;
                    return;
                }

                // 解析流式响应（核心优化：分片聚合）
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    listener.onStreamEvent("stream_chat", ChatStreamEventBus.StreamEventData.buildError("响应体为空"));
                    mIsStreamRunning = false;
                    return;
                }

                BufferedSource source = responseBody.source();
                String line;
                while ((line = source.readUtf8Line()) != null && mIsStreamRunning) {
                    if (TextUtils.isEmpty(line) || !line.startsWith("data: ")) continue;

                    String dataStr = line.substring(6).trim();
                    if ("[DONE]".equals(dataStr)) break;

                    // 解析分片内容
                    try {
                        JSONObject chunkJson = new JSONObject(dataStr);
                        JSONArray choices = chunkJson.getJSONArray("choices");
                        if (choices.length() == 0) continue;

                        JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                        String chunkContent = delta.optString("content", "");
                        if (TextUtils.isEmpty(chunkContent)) continue;

                        // 聚合分片：凑够阈值/超时就推送
                        mChunkBuffer.append(chunkContent);
                        mStreamCache.append(chunkContent);
                        long currentTime = System.currentTimeMillis();

                        if (mChunkBuffer.length() >= AGGREGATE_THRESHOLD || (currentTime - mLastPushTime) >= PUSH_TIMEOUT) {
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildChunk(sessionId, mChunkBuffer.toString()));
                            mChunkBuffer.setLength(0);
                            mLastPushTime = currentTime;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析分片失败：", e);
                    }
                }

                // 推送最后剩余的分片
                if (mChunkBuffer.length() > 0) {
                    listener.onStreamEvent("stream_chat",
                            ChatStreamEventBus.StreamEventData.buildChunk(sessionId, mChunkBuffer.toString()));
                }

                // 通知流式结束
                listener.onStreamEvent("stream_chat",
                        ChatStreamEventBus.StreamEventData.buildFinish(sessionId, mStreamCache.toString()));

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
            jsonBody.put("max_tokens", 2048); // 硅基流动推荐值
            jsonBody.put("stream", true); // 强制流式

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", userMessage));
            jsonBody.put("messages", messages);

            return jsonBody.toString();
        } catch (Exception e) {
            Log.e(TAG, "构建请求体失败：", e);
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

    // 取消流式请求（修复内存泄漏）
    public void cancelStream() {
        Log.d(TAG, "取消流式请求");
        mIsStreamRunning = false;
        if (mCurrentCall != null && !mCurrentCall.isCanceled()) {
            mCurrentCall.cancel();
        }
        mChunkBuffer.setLength(0);
        mStreamCache.setLength(0);
    }

    // Toast工具方法
    private void showToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mContext != null) {
                Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}