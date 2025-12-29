package alin.android.alinos.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// Ollama模型下载工具类
public class OllamaApiClient {
    private static final String TAG = "OllamaApiClient";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 下载Ollama模型（调用/api/pull接口）
    public static void downloadModel(String serverUrl, String modelName, OnDownloadListener listener) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // 拼接Ollama下载接口地址
                URL url = new URL(serverUrl + "/api/pull");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);

                // 构建请求体
                JSONObject requestBody = new JSONObject();
                requestBody.put("name", modelName); // 模型名称
                requestBody.put("stream", false); // 关闭流式响应

                // 发送请求
                OutputStream os = conn.getOutputStream();
                os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                // 解析响应
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    Log.d(TAG, "模型下载响应：" + response.toString());
                    mainHandler.post(listener::onSuccess);
                } else {
                    String error = "请求失败：" + conn.getResponseCode();
                    mainHandler.post(() -> listener.onError(error));
                }
            } catch (Exception e) {
                Log.e(TAG, "模型下载异常", e);
                mainHandler.post(() -> listener.onError("网络异常：" + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // 下载回调接口
    public interface OnDownloadListener {
        void onSuccess();
        void onError(String error);
    }
}