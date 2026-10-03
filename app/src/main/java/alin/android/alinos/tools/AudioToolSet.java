package alin.android.alinos.tools;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;

import alin.android.alinos.voice.AppConfigStore;
import alin.android.alinos.voice.AudioService;

/**
 * 音频能力工具集（INTERNAL）。
 *
 * <p>不暴露给 AI；当前音频模块处于搁置状态，这里只提供同步可查询的
 * 配置 / 模型状态能力，便于测试界面对照检查。异步推理（ASR/TTS/KWS）
 * 通过 {@link AudioService} 的 SDK 接口调用，不做成同步工具。
 */
public class AudioToolSet {

    private AudioToolSet() {
    }

    public static void register(final Context ctx) {
        final AudioService audio = AudioService.getInstance(ctx);
        final AppConfigStore cfg = AppConfigStore.getInstance(ctx);

        ToolRegistry.register("audio_get_config",
                "Read an audio configuration value (asr_engine / tts_engine / kws_keyword ...).",
                ToolMeta.params(
                        ToolMeta.param("key", "string", true, "", "Config key"),
                        ToolMeta.param("default", "string", false, "", "Value returned when unset")),
                p -> {
                    JSONObject o = ToolMeta.ok();
                    o.put("key", p.optString("key", ""));
                    o.put("value", cfg.getConfig(p.optString("key", ""), p.optString("default", "")));
                    return o;
                },
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.AUDIO);

        ToolRegistry.register("audio_set_config",
                "Write an audio configuration value.",
                ToolMeta.params(
                        ToolMeta.param("key", "string", true, "", "Config key"),
                        ToolMeta.param("value", "string", true, "", "Config value")),
                p -> {
                    cfg.setConfig(p.optString("key", ""), p.optString("value", ""));
                    JSONObject o = ToolMeta.ok();
                    o.put("key", p.optString("key", ""));
                    o.put("value", p.optString("value", ""));
                    return o;
                },
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.AUDIO);

        ToolRegistry.register("audio_model_status",
                "Report installed state of ASR / TTS / KWS / speaker models.",
                new ToolMeta.Param[0],
                p -> {
                    JSONObject o = ToolMeta.ok();
                    JSONObject m = new JSONObject();
                    m.put("asr_sherpa", audio.isAsrInstalled("sherpa"));
                    m.put("kws", audio.isKwsInstalled());
                    m.put("tts_sherpa", audio.isTtsInstalled("sherpa"));
                    m.put("vosk", audio.isVoskInstalled());
                    o.put("models", m);
                    o.put("model_dir", path(audio.getModelDir()));
                    o.put("vad_model", path(audio.getVadModelFile()));
                    return o;
                },
                ToolMeta.Scope.INTERNAL, ToolMeta.Category.AUDIO);
    }

    private static String path(File f) {
        return f == null ? "" : f.getAbsolutePath();
    }
}
