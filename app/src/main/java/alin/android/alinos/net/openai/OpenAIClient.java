package alin.android.alinos.net.openai;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.manager.ChatStreamEventBus;
import alin.android.alinos.net.OpenAIStreamNetHelper;
import alin.android.alinos.net.openai.model.*;

/**
 * OpenAI API 客户端实现。
 *
 * 当前状态：
 *   ✅ Chat 流式 — 委托给 OpenAIStreamNetHelper
 *   🔶 Chat 同步、Audio、Embeddings、Images、Models、Moderation、Files — 骨架已建
 *   ⬜ Conversations、Responses、Realtime — 接口已声明，待实现
 */
public class OpenAIClient implements OpenAIApi {

    private static final String TAG = "OpenAIClient";
    private final Context mContext;
    private final ConfigBean mConfig;
    private final String mBaseUrl;
    private final String mApiKey;
    private OpenAIStreamNetHelper mStreamHelper;

    public OpenAIClient(Context context, ConfigBean config) {
        this.mContext = context;
        this.mConfig = config;
        this.mBaseUrl = normalizeBaseUrl(config.getServerUrl());
        this.mApiKey = config.getApiKey();
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null) return "https://api.openai.com/v1";
        url = url.trim();
        if (!url.startsWith("http")) url = "https://" + url;
        if (!url.endsWith("/v1")) {
            url = url.endsWith("/") ? url + "v1" : url + "/v1";
        }
        return url;
    }

    // ================================================================
    // Chat
    // ================================================================

    @Override
    public void createChatCompletionStream(ChatRequest request, int sessionId,
                                           ChatStreamEventBus.StreamEventListener listener) {
        if (mStreamHelper == null) {
            mStreamHelper = new OpenAIStreamNetHelper(mContext, mConfig);
        }
        // 直接委托给现有流式助手
        mStreamHelper.sendStreamMessageWithMessages(sessionId, buildMessagesJson(request), listener);
    }

    @Override
    public ChatResponse createChatCompletion(ChatRequest request) {
        String body = request.toJson().toString();
        String result = httpPost("/chat/completions", body);
        if (result == null) return null;
        try {
            return ChatResponse.fromJson(new JSONObject(result));
        } catch (Exception e) {
            Log.e(TAG, "解析 ChatResponse 失败", e);
            return null;
        }
    }

    @Override
    public String listChatCompletions(String after, int limit, String order) {
        StringBuilder path = new StringBuilder("/chat/completions?limit=").append(limit)
                .append("&order=").append(order != null ? order : "desc");
        if (after != null) path.append("&after=").append(after);
        Log.d(TAG, "listChatCompletions: " + path);
        // TODO: 实现分页逻辑
        return "[]";
    }

    @Override
    public ChatResponse getChatCompletion(String completionId) {
        try {
            String result = httpGet("/chat/completions/" + completionId);
            return result != null ? ChatResponse.fromJson(new JSONObject(result)) : null;
        } catch (Exception e) {
            Log.e(TAG, "getChatCompletion 失败", e);
            return null;
        }
    }

    @Override
    public ChatResponse updateChatCompletion(String completionId, ChatRequest request) {
        try {
            String body = request.toJson().toString();
            String result = httpPost("/chat/completions/" + completionId, body);
            return result != null ? ChatResponse.fromJson(new JSONObject(result)) : null;
        } catch (Exception e) {
            Log.e(TAG, "updateChatCompletion 失败", e);
            return null;
        }
    }

    @Override
    public CommonResponses.DeletionStatus deleteChatCompletion(String completionId) {
        try {
            String result = httpDelete("/chat/completions/" + completionId);
            return result != null ? CommonResponses.DeletionStatus.fromJson(new JSONObject(result)) : null;
        } catch (Exception e) {
            Log.e(TAG, "deleteChatCompletion 失败", e);
            return null;
        }
    }

    @Override
    public String getChatCompletionMessages(String completionId, String after, int limit, String order) {
        StringBuilder path = new StringBuilder("/chat/completions/").append(completionId)
                .append("/messages?limit=").append(limit)
                .append("&order=").append(order != null ? order : "desc");
        if (after != null) path.append("&after=").append(after);
        Log.d(TAG, "getChatCompletionMessages: " + path);
        // TODO: 实现
        return "[]";
    }

    // ================================================================
    // Audio
    // ================================================================

    @Override
    public byte[] createSpeech(AudioSpeechRequest request) {
        Log.d(TAG, "createSpeech: model=" + request.model + ", voice=" + request.voice);
        String body = request.toJson().toString();
        return httpPostBinary("/audio/speech", body);
    }

    @Override
    public String createTranscription(AudioTranscriptionRequest request, byte[] audioFile) {
        Log.d(TAG, "createTranscription: model=" + request.model);
        // TODO: multipart/form-data 上传音频文件
        return null;
    }

    // ================================================================
    // Embeddings
    // ================================================================

    @Override
    public EmbeddingResponse createEmbedding(EmbeddingRequest request) {
        Log.d(TAG, "createEmbedding: model=" + request.model);
        String body = request.toJson().toString();
        String result = httpPost("/embeddings", body);
        if (result == null) return null;
        try {
            return EmbeddingResponse.fromJson(new JSONObject(result));
        } catch (Exception e) {
            Log.e(TAG, "解析 EmbeddingResponse 失败", e);
            return null;
        }
    }

    // ================================================================
    // Images
    // ================================================================

    @Override
    public CommonResponses.ImageResponse createImage(ImageGenerationRequest request) {
        Log.d(TAG, "createImage: model=" + request.model);
        String body = request.toJson().toString();
        String result = httpPost("/images/generations", body);
        if (result == null) return null;
        try {
            return CommonResponses.ImageResponse.fromJson(new JSONObject(result));
        } catch (Exception e) {
            Log.e(TAG, "解析 ImageResponse 失败", e);
            return null;
        }
    }

    // ================================================================
    // Models
    // ================================================================

    @Override
    public CommonResponses.ModelListResponse listModels() {
        Log.d(TAG, "listModels");
        String result = httpGet("/models");
        if (result == null) return null;
        try {
            return CommonResponses.ModelListResponse.fromJson(new JSONObject(result));
        } catch (Exception e) {
            Log.e(TAG, "解析 ModelListResponse 失败", e);
            return null;
        }
    }

    @Override
    public CommonResponses.ModelInfo retrieveModel(String modelId) {
        Log.d(TAG, "retrieveModel: " + modelId);
        String result = httpGet("/models/" + modelId);
        if (result == null) return null;
        try {
            return CommonResponses.ModelInfo.fromJson(new JSONObject(result));
        } catch (Exception e) {
            Log.e(TAG, "解析 ModelInfo 失败", e);
            return null;
        }
    }

    @Override
    public CommonResponses.DeletionStatus deleteModel(String modelId) {
        Log.d(TAG, "deleteModel: " + modelId);
        String result = httpDelete("/models/" + modelId);
        if (result == null) return null;
        try {
            return CommonResponses.DeletionStatus.fromJson(new JSONObject(result));
        } catch (Exception e) {
            Log.e(TAG, "解析 DeletionStatus 失败", e);
            return null;
        }
    }

    // ================================================================
    // Moderations
    // ================================================================

    @Override
    public CommonResponses.ModerationResponse createModeration(CommonResponses.ModerationRequest request) {
        Log.d(TAG, "createModeration: model=" + request.model);
        String body = request.toJson().toString();
        String result = httpPost("/moderations", body);
        if (result == null) return null;
        try {
            return CommonResponses.ModerationResponse.fromJson(new JSONObject(result));
        } catch (Exception e) {
            Log.e(TAG, "解析 ModerationResponse 失败", e);
            return null;
        }
    }

    // ================================================================
    // Files
    // ================================================================

    @Override
    public String listFiles(String purpose) {
        String path = "/files";
        if (purpose != null) path += "?purpose=" + purpose;
        Log.d(TAG, "listFiles: " + path);
        return httpGet(path);
    }

    @Override
    public CommonResponses.FileInfo uploadFile(String filePath, String purpose) {
        Log.d(TAG, "uploadFile: " + filePath + ", purpose=" + purpose);
        // TODO: multipart/form-data 上传
        return null;
    }

    @Override
    public CommonResponses.DeletionStatus deleteFile(String fileId) {
        Log.d(TAG, "deleteFile: " + fileId);
        String result = httpDelete("/files/" + fileId);
        if (result == null) return null;
        try {
            return CommonResponses.DeletionStatus.fromJson(new JSONObject(result));
        } catch (Exception e) {
            Log.e(TAG, "解析 DeletionStatus 失败", e);
            return null;
        }
    }

    @Override
    public CommonResponses.FileInfo retrieveFile(String fileId) {
        Log.d(TAG, "retrieveFile: " + fileId);
        String result = httpGet("/files/" + fileId);
        if (result == null) return null;
        try {
            return CommonResponses.FileInfo.fromJson(new JSONObject(result));
        } catch (Exception e) {
            Log.e(TAG, "解析 FileInfo 失败", e);
            return null;
        }
    }

    @Override
    public byte[] downloadFile(String fileId) {
        Log.d(TAG, "downloadFile: " + fileId);
        return httpGetBinary("/files/" + fileId + "/content");
    }

    // ================================================================
    // 内部 HTTP 工具方法
    // ================================================================

    private String httpGet(String path) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(mBaseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            } else {
                Log.w(TAG, "GET " + path + " → HTTP " + code);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "GET " + path + " 失败", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String httpPost(String path, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(mBaseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            } else {
                Log.w(TAG, "POST " + path + " → HTTP " + code + ": " + readStream(conn.getErrorStream()));
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "POST " + path + " 失败", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String httpDelete(String path) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(mBaseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            } else {
                Log.w(TAG, "DELETE " + path + " → HTTP " + code);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "DELETE " + path + " 失败", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private byte[] httpPostBinary(String path, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(mBaseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readBinaryStream(conn.getInputStream());
            } else {
                Log.w(TAG, "POST(binary) " + path + " → HTTP " + code);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "POST(binary) " + path + " 失败", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private byte[] httpGetBinary(String path) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(mBaseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readBinaryStream(conn.getInputStream());
            } else {
                Log.w(TAG, "GET(binary) " + path + " → HTTP " + code);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "GET(binary) " + path + " 失败", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        is.close();
        return bos.toString("UTF-8");
    }

    private byte[] readBinaryStream(InputStream is) throws Exception {
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        is.close();
        return bos.toByteArray();
    }

    private JSONArray buildMessagesJson(ChatRequest request) {
        JSONArray arr = new JSONArray();
        for (ChatRequest.Message m : request.messages) {
            arr.put(m.toJson());
        }
        return arr;
    }

    public void cancelStream() {
        if (mStreamHelper != null) {
            mStreamHelper.cancelStream();
            mStreamHelper = null;
        }
    }
}
