package alin.android.alinos.net;

import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import alin.android.alinos.bean.ConfigBean;

public class OpenAINetHelper extends AbstractNetHelper { // 继承抽象类

    public OpenAINetHelper(android.content.Context context, ConfigBean config) {
        super(context, config);
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
        
        // 检查所有必要字段
        boolean isValid = !TextUtils.isEmpty(config.getApiKey()) && 
                          !TextUtils.isEmpty(config.getModel());
        
        if (!isValid) {
            showToast("OpenAI配置不完整：需要API密钥和模型名称");
        }
        
        return isValid;
    }

    @Override
    public String sendMessage(String userMessage) {
        // 1. 先验证配置
        if (!validateConfig()) {
            return "[配置错误] OpenAPI配置不完整";
        }

        // 2. 构建请求体
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
        } catch (Exception e) {
            return "[构造请求失败] " + e.getMessage();
        }

        // 3. 构建URL - 使用用户配置的地址
        String apiUrl;
        if (!TextUtils.isEmpty(config.getServerUrl())) {
            apiUrl = config.getServerUrl();
            // 确保URL格式正确
            if (!apiUrl.contains("://")) {
                apiUrl = "https://" + apiUrl;
            }
            if (!apiUrl.contains("/v1/chat/completions")) {
                if (!apiUrl.endsWith("/")) {
                    apiUrl += "/";
                }
                apiUrl += "v1/chat/completions";
            }
        } else {
            // 默认使用OpenAI官方API
            apiUrl = "https://api.openai.com/v1/chat/completions";
        }

        HttpURLConnection conn = null;
        try {
            // 4. 创建连接
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            
            // 5. 配置请求
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000); // 30秒连接超时
            conn.setReadTimeout(60000);    // 60秒读取超时
            
            // 请求头
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
            conn.setRequestProperty("Accept", "application/json");
            
            // 6. 发送请求体
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.getBytes("UTF-8");
                os.write(input, 0, input.length);
            }
            
            // 7. 获取响应
            int responseCode = conn.getResponseCode();
            
            // 读取响应内容
            InputStream inputStream;
            if (responseCode >= 200 && responseCode < 300) {
                inputStream = conn.getInputStream();
            } else {
                inputStream = conn.getErrorStream();
            }
            
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
            
            // 8. 处理响应
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try {
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    JSONArray choices = jsonResponse.getJSONArray("choices");
                    if (choices.length() > 0) {
                        JSONObject choice = choices.getJSONObject(0);
                        JSONObject message = choice.getJSONObject("message");
                        String content = message.getString("content");
                        
                        // 检查是否被截断
                        if (choice.has("finish_reason") && "length".equals(choice.getString("finish_reason"))) {
                            content += "\n\n[提示：回复因达到长度限制而被截断]";
                        }
                        
                        return content;
                    } else {
                        return "[API错误] 响应中没有choices";
                    }
                } catch (Exception e) {
                    return "[解析响应失败] " + e.getMessage();
                }
            } else {
                // 处理错误响应
                try {
                    JSONObject errorJson = new JSONObject(response.toString());
                    String errorMessage = errorJson.optString("message", errorJson.toString());
                    return "[API错误] HTTP " + responseCode + ": " + errorMessage;
                } catch (Exception e) {
                    return "[API错误] HTTP " + responseCode + ": " + response.toString();
                }
            }
            
        } catch (java.net.SocketTimeoutException e) {
            return "[网络超时] 连接或读取超时，请检查网络连接";
        } catch (java.net.UnknownHostException e) {
            return "[网络错误] 无法解析服务器地址: " + apiUrl;
        } catch (java.io.IOException e) {
            return "[网络错误] " + e.getMessage();
        } catch (Exception e) {
            return "[请求异常] " + e.getMessage();
        } finally {
            // 9. 断开连接
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}