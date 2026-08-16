package alin.android.alinos.voice.core;

import android.content.Context;

import java.io.File;
import java.util.List;

import alin.android.alinos.voice.AppConfigStore;
import alin.android.alinos.voice.AudioService;

/**
 * 模型路径统一解析（ASR / TTS / KWS 三模块复用）。
 *
 * 规则（数据库优先）：
 *  1. 读 configs 中保存的模型选择（保存配置时写入，如 asr_model / tts_model / kws_model）
 *  2. 若保存的是自定义模型（models 表 custom 记录）→ 使用自定义绑定路径
 *  3. 若保存的是内置/未保存 → 使用默认内置目录
 *  4. 返回的目录可能不存在 → 调用方提示到音频管理界面配置
 */
public class ModelResolver {

    private ModelResolver() {}

    /**
     * 解析 ASR 模型目录。
     *
     * @param savedKey configs 中保存的模型 key（如 paraformer / 自定义名），null 时用默认
     */
    public static File resolveAsr(Context ctx, String savedKey) {
        AppConfigStore store = AudioService.getInstance(ctx).getConfigStore();
        String key = savedKey != null ? savedKey : store.getConfig("asr_model", "paraformer");
        // 内置 key → 内置目录
        File builtin = AudioService.getInstance(ctx).getAsrModelDir(key);
        if (builtin.exists() && builtin.isDirectory()) return builtin;
        // 自定义模型 → 数据库绑定路径
        for (AppConfigStore.Model m : store.getModels("asr")) {
            if (!m.builtin && m.name.equals(key)) {
                File f = new File(m.path);
                if (f.exists()) return f;
            }
        }
        return builtin; // 默认内置（可能不存在，由调用方提示）
    }

    /** 解析 TTS 模型目录（savedKey 如 melo / 自定义名） */
    public static File resolveTts(Context ctx, String savedKey) {
        AppConfigStore store = AudioService.getInstance(ctx).getConfigStore();
        String key = savedKey != null ? savedKey : store.getConfig("tts_model", "melo");
        File builtin = AudioService.getInstance(ctx).getTtsModelDir(key);
        if (builtin.exists() && builtin.isDirectory()) return builtin;
        for (AppConfigStore.Model m : store.getModels("tts")) {
            if (!m.builtin && m.name.equals(key)) {
                File f = new File(m.path);
                if (f.exists()) return f;
            }
        }
        return builtin;
    }

    /** 解析 KWS 唤醒词模型目录（configs.kws_model：builtin 或自定义名） */
    public static File resolveKws(Context ctx) {
        AppConfigStore store = AudioService.getInstance(ctx).getConfigStore();
        String key = store.getConfig("kws_model", "builtin");
        if (!"builtin".equals(key)) {
            for (AppConfigStore.Model m : store.getModels("kws")) {
                if (!m.builtin && m.name.equals(key)) {
                    File f = new File(m.path);
                    if (f.exists()) return f;
                }
            }
        }
        return AudioService.getInstance(ctx).getKwsModelDir();
    }

    /** 解析声纹模型文件（configs.kws_speaker_model：builtin 或自定义名） */
    public static File resolveSpeaker(Context ctx) {
        AppConfigStore store = AudioService.getInstance(ctx).getConfigStore();
        String key = store.getConfig("kws_speaker_model", "builtin");
        if (!"builtin".equals(key)) {
            for (AppConfigStore.Model m : store.getModels("speaker")) {
                if (!m.builtin && m.name.equals(key)) {
                    File f = new File(m.path);
                    if (f.exists()) return f;
                }
            }
        }
        return new File(AudioService.getInstance(ctx).getModelDir(), "speaker/campplus.onnx");
    }
}
