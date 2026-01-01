package alin.android.alinos.net;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import alin.android.alinos.bean.ConfigBean;

public class OpenAINetHelper extends AbstractNetHelper {
    private static final String TAG = "OpenAINetHelper";
    private static final String TARGET_API_PATH = "/v1/chat/completions";
    // 优化：延长超时时间（适配siliconflow等第三方API）
    private static final int CONNECT_TIMEOUT = 60 * 1000;  // 60秒连接超时
    private static final int READ_TIMEOUT = 120 * 1000;    // 120秒读取超时
    // 重试机制：最多重试2次
    private static final int MAX_RETRY = 2;

    public OpenAINetHelper(android.content.Context context, ConfigBean config) {
        super(context, config);
        // 初始化HTTPS信任（解决部分证书问题）
        initSSLTrust();
    }

    @Override
    public String getServiceType() {
        return "OpenAI";
    }

    @Override
    public boolean validateConfig() {
        if (config == null) {
            showToast("配置为空");
            return false;
        }

        // 校验：siliconflow需要API Key + 模型 + 服务器地址
        boolean isValid = !TextUtils.isEmpty(config.getApiKey()) &&
                          !TextUtils.isEmpty(config.getModel()) &&
                          !TextUtils.isEmpty(config.getServerUrl());

        if (!isValid) {
            showToast("配置不完整：必填【API Key】+【模型名称】+【服务器地址】");
        }
        return isValid;
    }

    // 核心优化：添加重试机制
    @Override
    public String sendMessage(String userMessage) {
        // 重试2次，解决网络抖动问题
        for (int retry = 0; retry <= MAX_RETRY; retry++) {
            try {
                String result = sendMessageWithRetry(userMessage, retry);
                // 非超时错误，直接返回
                if (!result.contains("[网络超时]")) {
                    return result;
                }
                Log.w(TAG, "第" + (retry + 1) + "次请求超时，重试...");
            } catch (Exception e) {
                Log.e(TAG, "第" + (retry + 1) + "次请求异常", e);
                if (retry == MAX_RETRY) {
                    return "[请求异常] 多次重试失败：" + e.getMessage();
                }
            }
        }
        return "[网络超时] 多次重试仍无法连接到服务器，请检查网络或稍后再试";
    }

    // 实际请求逻辑（拆分出来，方便重试）
    private String sendMessageWithRetry(String userMessage, int retryCount) {
        Log.d(TAG, "开始第" + (retryCount + 1) + "次请求，用户消息：" + userMessage);
        
        if (!validateConfig()) {
            return "[配置错误] 请补全API Key、模型名称和服务器地址";
        }

        // 构建请求体（适配siliconflow格式）
        String requestBody;
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", config.getModel());
            jsonBody.put("temperature", 0.7);
            jsonBody.put("max_tokens", 2000);
            jsonBody.put("stream", false);

            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", userMessage);
            messages.put(message);
            jsonBody.put("messages", messages);

            requestBody = jsonBody.toString();
            Log.d(TAG, "第" + (retryCount + 1) + "次请求体：" + requestBody);
        } catch (Exception e) {
            Log.e(TAG, "构造请求体失败", e);
            return "[构造请求失败] " + e.getMessage();
        }

        // URL拼接（无双斜杠）
        String apiUrl = config.getServerUrl();
        if (!apiUrl.contains(TARGET_API_PATH)) {
            Log.d(TAG, "补全接口路径 -> " + TARGET_API_PATH);
            apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
            apiUrl += TARGET_API_PATH;
        }
        Log.d(TAG, "最终请求URL：" + apiUrl);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            
            // HTTPS适配：强制使用TLS 1.2/1.3（siliconflow要求）
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                httpsConn.setSSLSocketFactory(SSLContext.getDefault().getSocketFactory());
                httpsConn.setHostnameVerifier((hostname, session) -> true); // 跳过域名校验（兜底）
            }

            // 核心：延长超时时间
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            // 禁用缓存，确保每次请求都是新的
            conn.setUseCaches(false);
            conn.setDefaultUseCaches(false);

            // 请求头（适配siliconflow）
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
            conn.setRequestProperty("Accept", "application/json");
            // 添加User-Agent，避免被风控
            conn.setRequestProperty("User-Agent", "AlinOS/1.0 (Android)");

            Log.d(TAG, "开始发送请求体...");
            // 发送请求体
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.getBytes("UTF-8");
                os.write(input, 0, input.length);
                os.flush();
            }

            Log.d(TAG, "等待服务端响应...");
            // 分步日志：先获取响应码（定位超时环节）
            int responseCode = conn.getResponseCode();
            Log.d(TAG, "服务端响应码：" + responseCode);

            // 读取响应
            InputStream inputStream = responseCode >= 200 && responseCode < 300 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
            Log.d(TAG, "服务端原始响应：" + response.toString());

            // 解析响应
            if (responseCode == HttpURLConnection.HTTP_OK) {
                JSONObject jsonResponse = new JSONObject(response.toString());
                String content = "";
                if (jsonResponse.has("choices") && jsonResponse.getJSONArray("choices").length() > 0) {
                    JSONObject choice = jsonResponse.getJSONArray("choices").getJSONObject(0);
                    content = choice.has("message") ? choice.getJSONObject("message").getString("content") : choice.optString("text", "");
                    
                    if ("length".equals(choice.optString("finish_reason"))) {
                        content += "\n\n[提示：回复因达到长度限制而被截断]";
                    }
                }
                return TextUtils.isEmpty(content) ? "[响应异常] 服务端返回无有效内容" : content;
            } else {
                // 解析错误信息（siliconflow的错误格式）
                String errorMsg = response.toString();
                try {
                    JSONObject errorJson = new JSONObject(response.toString());
                    errorMsg = errorJson.optString("error", errorJson.optString("message", errorMsg));
                } catch (Exception e) {
                    // 解析失败则用原始响应
                }
                return "[服务端错误] HTTP " + responseCode + ": " + errorMsg;
            }

        } catch (java.net.SocketTimeoutException e) {
            Log.e(TAG, "第" + (retryCount + 1) + "次请求超时", e);
            return "[网络超时] 连接/读取服务器超时（已重试" + retryCount + "次），请检查网络或稍后再试";
        } catch (java.net.UnknownHostException e) {
            Log.e(TAG, "无法解析地址", e);
            return "[网络错误] 无法解析服务器地址：" + apiUrl;
        } catch (Exception e) {
            Log.e(TAG, "请求异常", e);
            return "[请求异常] " + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // 初始化SSL信任（解决HTTPS证书问题）
    private void initSSLTrust() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        } catch (Exception e) {
            Log.w(TAG, "初始化SSL信任失败", e);
        }
    }

    protected void showToast(String msg) {
        if (context != null) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show();
        }
        Log.w(TAG, msg);
    }
}