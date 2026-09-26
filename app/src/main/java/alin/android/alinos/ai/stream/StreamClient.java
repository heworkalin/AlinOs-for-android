package alin.android.alinos.ai.stream;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import alin.android.alinos.ai.CostCalculator;
import alin.android.alinos.ai.ModelInfo;
import alin.android.alinos.ai.Usage;
import alin.android.alinos.ai.dialect.ApiDialect;
import alin.android.alinos.ai.dialect.DialectRegistry;
import alin.android.alinos.ai.dialect.StreamEvent;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * 统一流式客户端 —— 多协议共用一套 HTTP/SSE 循环。
 *
 * <p>请求构建与事件解析全部委托给 {@link ApiDialect}，
 * 本类只负责：发请求、逐行读 SSE、把 {@link StreamEvent} 回调出去、
 * 以及根据模型定价计算费用。
 */
public class StreamClient {

    private static final String TAG = "StreamClient";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 流式回调。 */
    public interface Listener {
        void onText(String delta);

        void onThink(String delta);

        /** 一轮结束时累积好的工具调用：[{id,name,arguments}]。 */
        void onToolCalls(JSONArray toolCalls);

        void onUsage(Usage usage);

        void onDone(String stopReason);

        void onError(String message);
    }

    private final OkHttpClient client;
    private volatile Call currentCall;
    private volatile boolean cancelled;

    public StreamClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)   // SSE 长连接
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void cancel() {
        cancelled = true;
        Call c = currentCall;
        if (c != null && !c.isCanceled()) c.cancel();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 发送一次流式请求（阻塞，应在线程中调用）。
     *
     * @param apiType  协议 id（openai-completions / openai-responses / anthropic-messages）
     * @param baseUrl  服务器地址（可为空，用协议默认）
     * @param apiKey   API Key
     * @param modelId  模型 id
     * @param messages OpenAI 风格消息数组
     * @param tools    OpenAI 风格工具数组
     * @param model    模型元数据（可为 null；用于计费）
     */
    public void send(String apiType, String baseUrl, String apiKey, String modelId,
                     JSONArray messages, JSONArray tools, int maxTokens, double temperature,
                     ModelInfo model, Listener listener) {
        send(apiType, baseUrl, apiKey, modelId, messages, tools, maxTokens, temperature,
                model, null, listener);
    }

    /**
     * 同上，并支持把自定义参数（extra_json）并入请求体。
     */
    public void send(String apiType, String baseUrl, String apiKey, String modelId,
                     JSONArray messages, JSONArray tools, int maxTokens, double temperature,
                     ModelInfo model, JSONObject extra, Listener listener) {
        cancelled = false;
        ApiDialect dialect = DialectRegistry.get(apiType);

        String url = dialect.buildUrl(baseUrl, modelId, true);
        String body = dialect.buildBody(modelId, messages, tools, maxTokens, temperature, true);
        body = mergeExtra(body, extra);

        Log.d(TAG, "协议=" + dialect.id() + " 请求URL=" + url);
        Log.d(TAG, "请求体预览: " + preview(body));

        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON_TYPE));
        for (Map.Entry<String, String> e : dialect.headers(apiKey).entrySet()) {
            rb.addHeader(e.getKey(), e.getValue());
        }

        ApiDialect.ParseState state = new ApiDialect.ParseState();
        Usage totalUsage = new Usage();
        boolean[] usageSeen = {false};

        Call call = client.newCall(rb.build());
        currentCall = call;
        try (Response response = call.execute()) {
            ResponseBody rbBody = response.body();
            if (!response.isSuccessful()) {
                String err = rbBody == null ? "" : rbBody.string();
                listener.onError("HTTP " + response.code() + ": " + preview(err));
                return;
            }
            if (rbBody == null) {
                listener.onError("empty response body");
                return;
            }

            BufferedSource source = rbBody.source();
            String line;
            while (!cancelled && (line = source.readUtf8Line()) != null) {
                String data = extractData(line);
                if (data == null) continue;
                if ("[DONE]".equals(data)) break;
                if (data.isEmpty()) continue;
                if (dialect.isDone(data, state)) {
                    // 仍把该帧交给 parse 以提取 usage/stop reason
                }

                List<StreamEvent> events = dialect.parse(data, state);
                for (StreamEvent ev : events) {
                    switch (ev.type) {
                        case TEXT:
                            listener.onText(ev.text);
                            break;
                        case THINK:
                            listener.onThink(ev.text);
                            break;
                        case TOOL_CALL:
                            // 分片即时转发（调用方自行累积），最终在 done 时统一给出
                            break;
                        case USAGE:
                            Usage u = parseUsageJson(ev.text);
                            if (u != null) {
                                usageSeen[0] = true;
                                // Anthropic 的 usage 分两段上报，这里做累加
                                mergeUsage(totalUsage, u);
                            }
                            break;
                        case DONE:
                            state.stopReason = ev.stopReason;
                            break;
                        case ERROR:
                            listener.onError(ev.text);
                            return;
                        default:
                            break;
                    }
                }

                if (dialect.isDone(data, state)) break;
            }

            if (cancelled) {
                listener.onError("cancelled");
                return;
            }

            // 工具调用汇总
            JSONArray toolCalls = new JSONArray();
            for (ApiDialect.ToolCallAccumulator acc : state.toolCalls.values()) {
                if (acc.name == null || acc.name.isEmpty()) continue;
                toolCalls.put(acc.toJson());
            }

            // 计费
            if (usageSeen[0]) {
                if (model != null) CostCalculator.calculate(model, totalUsage);
                listener.onUsage(totalUsage);
            }

            if (toolCalls.length() > 0) listener.onToolCalls(toolCalls);

            String stop = state.stopReason == null
                    ? (toolCalls.length() > 0 ? "tool_use" : "stop")
                    : state.stopReason;
            listener.onDone(stop);
        } catch (IOException e) {
            if (cancelled) {
                listener.onError("cancelled");
            } else {
                listener.onError("网络异常: " + e.getMessage());
            }
        } catch (Exception e) {
            listener.onError("流处理异常: " + e);
        } finally {
            currentCall = null;
        }
    }

    /** 把自定义参数并入请求体（同名时以自定义为准）。 */
    private static String mergeExtra(String body, JSONObject extra) {
        if (extra == null || extra.length() == 0) return body;
        try {
            JSONObject o = new JSONObject(body);
            java.util.Iterator<String> keys = extra.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                o.put(k, extra.opt(k));
            }
            return o.toString();
        } catch (Exception e) {
            return body;
        }
    }

    // ---------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------

    /** 从一行 SSE 中提取 data 内容；非数据行返回 null（兼容有/无空格）。 */
    private static String extractData(String line) {
        if (line == null || line.isEmpty()) return null;
        if (line.startsWith("data:")) {
            return line.substring(5).trim();
        }
        // 裸 JSON 行（JSONL）
        if (line.startsWith("{")) return line.trim();
        // event: / id: / : 注释 等一律忽略
        return null;
    }

    private static Usage parseUsageJson(String json) {
        try {
            return Usage.fromJson(new JSONObject(json));
        } catch (Exception e) {
            return null;
        }
    }

    /** 合并分段上报的 usage（Anthropic 的 input/output 分两帧）。 */
    private static void mergeUsage(Usage total, Usage part) {
        if (part.input > 0) total.input = part.input;
        if (part.output > 0) total.output = part.output;
        if (part.cacheRead > 0) total.cacheRead = part.cacheRead;
        if (part.cacheWrite > 0) total.cacheWrite = part.cacheWrite;
        if (part.cacheWrite1h > 0) total.cacheWrite1h = part.cacheWrite1h;
        if (part.reasoning > 0) total.reasoning = part.reasoning;
    }

    private static String preview(String s) {
        if (s == null) return "";
        s = s.trim().replace("\n", " ");
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
