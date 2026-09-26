package alin.android.alinos.net;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import alin.android.alinos.ai.ModelInfo;
import alin.android.alinos.ai.ModelRegistry;
import alin.android.alinos.ai.Usage;
import alin.android.alinos.ai.stream.StreamClient;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.manager.ChatStreamEventBus;

/**
 * AI 流式引擎 —— 多协议入口。
 *
 * <p>内部使用 {@link StreamClient} + {@link alin.android.alinos.ai.dialect.DialectRegistry}，
 * 对外暴露与旧 {@link OpenAIStreamNetHelper} 相同的事件总线接口，
 * 因此 {@code PromptService} 与 {@code ToolCallCoordinator} 无需感知协议差异。
 *
 * <p>支持：openai-completions / openai-responses / anthropic-messages。
 */
public class AiStreamEngine {

    private static final String TAG = "AiStreamEngine";

    private final Context context;
    private final ConfigBean config;
    private final StreamClient client = new StreamClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-stream");
        t.setDaemon(true);
        return t;
    });

    public AiStreamEngine(Context context, ConfigBean config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    public ConfigBean getConfig() {
        return config;
    }

    /** 取消当前流。 */
    public void cancelStream() {
        client.cancel();
    }

    /**
     * 发送流式请求（异步）。事件通过 {@link ChatStreamEventBus.StreamEventListener} 回调。
     */
    public void sendStreamMessageWithMessages(int sessionId, JSONArray messages, JSONArray tools,
                                              ChatStreamEventBus.StreamEventListener listener) {
        if (listener == null) return;

        final String apiType = config.effectiveApiType();
        final String baseUrl = config.getServerUrl() == null ? "" : config.getServerUrl().trim();
        final String apiKey = config.getApiKey() == null ? "" : config.getApiKey().trim();
        final String modelId = config.getModel();
        final int maxTokens = config.getMaxResponseTokens();

        ModelInfo modelInfo = resolveModel(modelId);
        final double temperature = optExtraDouble("temperature", 0.7);
        final JSONObject extra = buildExtraBody();

        Log.d(TAG, "发送: api=" + apiType + " provider=" + config.getProviderId()
                + " model=" + modelId + " base=" + baseUrl
                + " tools=" + (tools == null ? 0 : tools.length()));

        executor.execute(() -> {
            final StringBuilder textBuf = new StringBuilder();
            final StringBuilder thinkBuf = new StringBuilder();

            client.send(apiType, baseUrl, apiKey, modelId, messages, tools,
                    maxTokens, temperature, modelInfo, extra, new StreamClient.Listener() {

                        @Override
                        public void onText(String delta) {
                            textBuf.append(delta);
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildChunk(sessionId, delta));
                        }

                        @Override
                        public void onThink(String delta) {
                            thinkBuf.append(delta);
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildThinkChunk(sessionId, delta));
                        }

                        @Override
                        public void onToolCalls(JSONArray toolCalls) {
                            if (toolCalls == null || toolCalls.length() == 0) return;
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildToolCalls(
                                            sessionId, toolCalls.toString()));
                        }

                        @Override
                        public void onUsage(Usage usage) {
                            if (usage == null) return;
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildUsage(
                                            sessionId,
                                            (int) usage.input,
                                            (int) usage.output,
                                            (int) usage.totalTokens(),
                                            usage.costTotal,
                                            usage.toJson().toString()));
                        }

                        @Override
                        public void onDone(String stopReason) {
                            if (thinkBuf.length() > 0) {
                                listener.onStreamEvent("stream_chat",
                                        ChatStreamEventBus.StreamEventData.buildThinkFinish(
                                                sessionId, thinkBuf.toString()));
                            }
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildFinish(
                                            sessionId, textBuf.toString()));
                        }

                        @Override
                        public void onError(String message) {
                            listener.onStreamEvent("stream_chat",
                                    ChatStreamEventBus.StreamEventData.buildError(message));
                        }
                    });
        });
    }

    /** 兼容旧调用：自动补齐 tools=null。 */
    public void sendStreamMessageWithMessages(int sessionId, JSONArray messages,
                                              ChatStreamEventBus.StreamEventListener listener) {
        sendStreamMessageWithMessages(sessionId, messages, null, listener);
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    /** 费用明细事件（eventType = stream_cost）。 */
    private void fireCostEvent(int sessionId, Usage usage) {
        if (usage == null) return;
        try {
            ChatStreamEventBus.getInstance().post("stream_cost",
                    ChatStreamEventBus.StreamEventData.buildUsage(sessionId,
                            (int) usage.input, (int) usage.output, (int) usage.totalTokens(),
                            usage.costTotal, usage.toJson().toString()));
        } catch (Exception e) {
            Log.w(TAG, "发送费用事件失败", e);
        }
    }

    private ModelInfo resolveModel(String modelId) {
        try {
            ModelRegistry registry = ModelRegistry.get(context);
            ModelInfo m = registry.model(config.getProviderId(), modelId);
            if (m == null) m = registry.modelById(modelId);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取自定义参数里的数值。 */
    private double optExtraDouble(String key, double def) {
        try {
            JSONObject o = extraObject();
            if (o != null && o.has(key)) return o.optDouble(key, def);
        } catch (Exception ignored) {
        }
        return def;
    }

    private JSONObject extraObject() {
        if (config.getExtraJson() == null || config.getExtraJson().trim().isEmpty()) return null;
        try {
            return new JSONObject(config.getExtraJson());
        } catch (Exception e) {
            return null;
        }
    }

    /** 自定义参数（temperature/top_p 已在 body 里处理，这里给出其余可透传项）。 */
    private JSONObject buildExtraBody() {
        JSONObject extra = extraObject();
        if (extra == null) return null;
        JSONObject out = new JSONObject();
        Iterator<String> keys = extra.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if ("temperature".equals(k) || "top_p".equals(k)) continue;
            try {
                out.put(k, extra.opt(k));
            } catch (Exception ignored) {
            }
        }
        return out.length() == 0 ? null : out;
    }
}
