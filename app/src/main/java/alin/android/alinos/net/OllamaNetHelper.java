package alin.android.alinos.net;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import alin.android.alinos.bean.ConfigBean;

public class OllamaNetHelper extends AbstractNetHelper {
    private static final String TAG = "OllamaNetHelper";
    private static final String TARGET_API_PATH = "/api/generate";
    // 重试次数常量（父类未定义则在此补充）
    protected static final int MAX_RETRY = 2;
    // 超时时间（毫秒）
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    public OllamaNetHelper(Context context, ConfigBean config) {
        super(context, config);
    }

    @Override
    public String getServiceType() {
        return "Ollama";
    }

    @Override
    public boolean validateConfig() {
        if (config == null) {
            showToast("配置为空");
            return false;
        }

        // 校验：Ollama需要模型 + 服务器地址（默认本地）
        boolean isValid = !TextUtils.isEmpty(config.getModel()) &&
                !TextUtils.isEmpty(config.getServerUrl());

        if (!isValid) {
            showToast("配置不完整：必填【模型名称】+【服务器地址】（本地默认http://localhost:11434）");
        }
        return isValid;
    }

    @Override
    public String sendMessage(String userMessage) {
        // 空消息校验
        if (TextUtils.isEmpty(userMessage)) {
            return "[错误] 用户输入不能为空";
        }
        
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

    private String sendMessageWithRetry(String userMessage, int retryCount) {
        Log.d(TAG, "开始第" + (retryCount + 1) + "次请求，用户消息：" + userMessage);
        
        if (!validateConfig()) {
            return "[配置错误] 请补全模型名称和服务器地址";
        }

        String requestBody;
        try {
            // 对齐Ollama的API格式（Ollama不需要messages数组，直接用prompt）
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", config.getModel());
            jsonBody.put("prompt", userMessage); // Ollama核心参数：prompt
            jsonBody.put("stream", false); // 关闭流式返回

            requestBody = jsonBody.toString();

            Log.d(TAG, "第" + (retryCount + 1) + "次请求体：" + requestBody);
        } catch (Exception e) {
            Log.e(TAG, "构造请求体失败", e);
            return "[构造请求失败] " + e.getMessage();
        }

        // 构建完整请求URL
        String serverUrl = config.getServerUrl().trim();
        if (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
        String fullUrl = serverUrl + TARGET_API_PATH;
        HttpURLConnection connection = null;
        OutputStream outputStream = null;
        InputStream inputStream = null;
        BufferedReader reader = null;

        try {
            // 忽略SSL证书（如果是HTTPS的Ollama服务）
            trustAllCertificates();

            URL url = new URL(fullUrl);
            connection = (HttpURLConnection) url.openConnection();
            // 设置请求参数
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            // 发送请求体
            outputStream = connection.getOutputStream();
            outputStream.write(requestBody.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();

            // 获取响应码
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorMsg = "[请求失败] 服务器返回错误码：" + responseCode;
                Log.e(TAG, errorMsg + "，URL：" + fullUrl);
                return errorMsg;
            }

            // 读取响应内容
            inputStream = connection.getInputStream();
            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder responseBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }
            String responseStr = responseBuilder.toString();
            String shortContent = responseStr.length() > 40
                    ? responseStr.substring(0, 40)
                    : responseStr;
            Log.d(TAG, "第" + (retryCount + 1) + "次请求响应：" + shortContent);

            // 解析Ollama响应（提取response字段）
            JSONObject responseJson = new JSONObject(responseStr);
            String aiResponse = responseJson.optString("response", "");
            if (TextUtils.isEmpty(aiResponse)) {
                return "[响应异常] AI返回空内容，原始响应：" + responseStr;
            }
            return aiResponse;

        } catch (java.net.SocketTimeoutException e) {
            Log.e(TAG, "第" + (retryCount + 1) + "次请求超时", e);
            return "[网络超时] 连接服务器超时，请检查服务器是否在线";
        } catch (Exception e) {
            Log.e(TAG, "第" + (retryCount + 1) + "次请求异常", e);
            return "[请求异常] " + e.getMessage();
        } finally {
            // 关闭资源
            try {
                if (reader != null) reader.close();
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                if (connection != null) connection.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "关闭资源失败", e);
            }
        }
    }

    /**
     * 忽略SSL证书（解决自签名证书/HTTPS连接问题）
     */
    private void trustAllCertificates() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Log.w(TAG, "忽略SSL证书失败", e);
        }
    }

    /**
     * 补全父类AbstractNetHelper的showToast方法（如果父类未实现）
     */
    protected void showToast(String msg) {
        if (context != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        } else {
            Log.w(TAG, "Context为空，无法显示Toast：" + msg);
        }
    }
}