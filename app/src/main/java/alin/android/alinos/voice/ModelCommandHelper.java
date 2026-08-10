package alin.android.alinos.voice;

/**
 * 模型导入命令规范工具类
 * 
 * 用于生成/解析模型安装命令，用户可以将命令复制后在终端执行
 * 
 * 命令格式规范：
 * 
 * 【格式 1】直接下载并解压（推荐）
 *   alinos-model install <model-type> <model-key>
 * 
 * 【格式 2】从本地路径导入
 *   alinos-model import <local-path> --type <model-type>
 * 
 * 【格式 3】从 URL 下载
 *   alinos-model download <url> --type <model-type>
 * 
 * 支持的 model-type：
 *   asr       - ASR 离线模型
 *   kws       - KWS 唤醒模型  
 *   speaker   - 声纹模型
 *   vad       - VAD 静音检测
 *   sherpa    - sherpa-onnx 核心库
 * 
 * 【格式 4】查看已安装模型
 *   alinos-model list
 * 
 * 【格式 5】删除模型
 *   alinos-model uninstall <model-key>
 */
public class ModelCommandHelper {

    /**
     * 生成 Paraformer 模型安装命令
     */
    public static String getParaformerCommand() {
        return "# 安装 Paraformer 中文语音识别模型\n" +
                "# 来源：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2\n" +
                "alinos-model install asr paraformer\n\n" +
                "# 或直接下载后导入：\n" +
                "# alinos-model download https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2 --type asr";
    }

    /**
     * 生成 SenseVoice 模型安装命令
     */
    public static String getSenseVoiceCommand() {
        return "# 安装 SenseVoice 多语种语音识别模型\n" +
                "# 来源：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2025-09-09.tar.bz2\n" +
                "alinos-model install asr sensevoice\n\n" +
                "# 支持中文/英语/日语/韩语/粤语，带情绪和噪音分类";
    }

    /**
     * 生成 Whisper Tiny 模型安装命令
     */
    public static String getWhisperCommand() {
        return "# 安装 Whisper Tiny 多语言模型\n" +
                "# 来源：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2\n" +
                "alinos-model install asr whisper";
    }

    /**
     * 生成 KWS 唤醒模型安装命令
     */
    public static String getKwsCommand() {
        return "# 安装 KWS 唤醒模型（移动版，优化体积）\n" +
                "# 来源：https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2\n" +
                "alinos-model install kws mobile";
    }

    /**
     * 生成声纹模型安装命令
     */
    public static String getSpeakerCommand() {
        return "# 安装 CAM++ 声纹识别模型\n" +
                "# 来源：https://huggingface.co/welcomyou/campplus-3dspeaker-200k-onnx/resolve/main/campplus_cn_en_common_200k.onnx\n" +
                "alinos-model install speaker campplus\n\n" +
                "# ONNX 格式，可直接使用";
    }

    /**
     * 生成 sherpa-onnx 核心库安装命令
     */
    public static String getSherpaCommand() {
        return "# 安装 sherpa-onnx v1.13.2 核心库\n" +
                "# 来源：https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar\n" +
                "alinos-model install sherpa 1.13.2";
    }

    /**
     * 生成 VAD 模型安装命令
     */
    public static String getVadCommand() {
        return "# 安装 VAD 静音检测模型\n" +
                "# 来源：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx\n" +
                "alinos-model install vad silero";
    }

    /**
     * 生成所有模型一键安装命令
     */
    public static String getAllModelsCommand() {
        return "# 一键安装所有推荐模型\n" +
                "# 包含：sherpa-onnx 核心 + Paraformer ASR + KWS 唤醒 + CAM++ 声纹\n" +
                "# 总计约 100MB\n\n" +
                "alinos-model install all";
    }

    /**
     * 生成自定义 URL 下载命令
     */
    public static String getCustomDownloadCommand(String url, String modelType) {
        return "# 从自定义 URL 下载模型\n" +
                "alinos-model download \"" + url + "\" --type " + modelType;
    }

    /**
     * 生成本地文件导入命令
     */
    public static String getLocalImportCommand(String localPath, String modelType) {
        return "# 从本地文件导入模型\n" +
                "alinos-model import \"" + localPath + "\" --type " + modelType;
    }

    /**
     * 获取模型下载清单（JSON 格式，可用于批量下载）
     */
    public static String getDownloadManifest() {
        return "{\n" +
                "  \"sherpa-onnx\": {\n" +
                "    \"version\": \"1.13.2\",\n" +
                "    \"url\": \"https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar\"\n" +
                "  },\n" +
                "  \"asr_paraformer\": {\n" +
                "    \"type\": \"asr\",\n" +
                "    \"name\": \"Paraformer-Nano\",\n" +
                "    \"url\": \"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2\",\n" +
                "    \"size\": \"70MB\",\n" +
                "    \"description\": \"中文语音识别，准确率最优\"\n" +
                "  },\n" +
                "  \"asr_sensevoice\": {\n" +
                "    \"type\": \"asr\",\n" +
                "    \"name\": \"SenseVoice\",\n" +
                "    \"url\": \"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2025-09-09.tar.bz2\",\n" +
                "    \"size\": \"200MB\",\n" +
                "    \"description\": \"多语种语音识别，带情绪分类\"\n" +
                "  },\n" +
                "  \"kws_mobile\": {\n" +
                "    \"type\": \"kws\",\n" +
                "    \"name\": \"KWS-移动版\",\n" +
                "    \"url\": \"https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2\",\n" +
                "    \"size\": \"3.3MB\",\n" +
                "    \"description\": \"中文唤醒词检测，移动端优化\"\n" +
                "  },\n" +
                "  \"speaker_campplus\": {\n" +
                "    \"type\": \"speaker\",\n" +
                "    \"name\": \"CAM++ 声纹\",\n" +
                "    \"url\": \"https://huggingface.co/welcomyou/campplus-3dspeaker-200k-onnx/resolve/main/campplus_cn_en_common_200k.onnx\",\n" +
                "    \"size\": \"27MB\",\n" +
                "    \"description\": \"声纹识别，验证说话人身份\"\n" +
                "  }\n" +
                "}";
    }

    /**
     * 获取代理地址列表（JSON 格式）
     * 用户可自定义替换
     */
    public static String getProxyList() {
        return "// 代理地址配置\n" +
                "// 按优先级排列，下载失败时自动切换下一个\n" +
                "// 格式：https://代理域名/ + 原始URL\n" +
                "//\n" +
                "// 【推荐 1】gh-proxy.com - 国内代理，速度快\n" +
                "//   https://gh-proxy.com/\n" +
                "// 【推荐 2】ghfast.top - 备用代理\n" +
                "//   https://ghfast.top/\n" +
                "//\n" +
                "// 原始地址（直连）：\n" +
                "//   https://github.com/\n" +
                "//   https://huggingface.co/\n" +
                "//   https://modelscope.cn/\n" +
                "//\n" +
                "// 注意：如果用户有自定义代理（如 Clash 透明代理），\n" +
                "// 可以直接使用系统代理，无需指定代理地址";
    }

    /**
     * 生成 sherpa-onnx 配置片段（用于 Activity 内粘贴）
     * 用户复制后粘贴到对应位置
     */
    public static String getSherpaConfigSnippet(String modelKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("// sherpa-onnx 配置 - ").append(modelKey).append("\n\n");
        
        switch (modelKey) {
            case "asr/paraformer":
                sb.append("// ASR 离线模型配置\n")
                  .append("String asrModelDir = getFilesDir().getAbsolutePath() + \"/voice_models/asr/paraformer\";\n")
                  .append("// 模型文件：\n")
                  .append("//   - model.int8.onnx  （ASR 模型权重）\n")
                  .append("//   - tokens.txt       （词表文件）\n")
                  .append("//\n")
                  .append("// sherpa-onnx 初始化示例：\n")
                  .append("// OfflineModelConfig modelConfig = new OfflineModelConfig();\n")
                  .append("// modelConfig.setAsrModelDir(asrModelDir);\n")
                  .append("// modelConfig.setDecoderModel(\"\");  // paraformer 无需 decoder\n")
                  .append("// modelConfig.setJoinerModel(\"\");   // paraformer 无需 joiner\n")
                  .append("// modelConfig.setTokensFile(asrModelDir + \"/tokens.txt\");\n")
                  .append("// modelConfig.setModelType(\"paraformer\");\n")
                  .append("// modelConfig.setNumThreads(2);     // 建议 2 线程\n")
                  .append("// modelConfig.setDebug(false);        // 生产环境关闭 debug\n");
                break;
                
            case "kws":
                sb.append("// KWS 唤醒词模型配置\n")
                  .append("String kwsModelDir = getFilesDir().getAbsolutePath() + \"/voice_models/kws\";\n")
                  .append("// 模型文件：\n")
                  .append("//   - encoder.onnx   （编码器）\n")
                  .append("//   - decoder.onnx   （解码器）\n")
                  .append("//   - joiner.onnx    （连接层）\n")
                  .append("//\n")
                  .append("// sherpa-onnx 初始化示例：\n")
                  .append("// KwsConfig kwsConfig = new KwsConfig();\n")
                  .append("// kwsConfig.setTrainerType(\"zipformer\");\n")
                  .append("// kwsConfig.setModelDir(kwsModelDir);\n")
                  .append("// kwsConfig.setKeywordsFile(kwsModelDir + \"/keywords.txt\");\n")
                  .append("// kwsConfig.setHotwordsFile(\"\");  // 可选热词\n");
                break;
                
            case "speaker":
                sb.append("// 声纹模型配置\n")
                  .append("String speakerModelFile = getFilesDir().getAbsolutePath() + \"/voice_models/speaker/campplus.onnx\";\n")
                  .append("// CAM++ 声纹模型初始化示例：\n")
                  .append("// SpeakerVerificationConfig speakerConfig = new SpeakerVerificationConfig();\n")
                  .append("// speakerConfig.setModelFile(speakerModelFile);\n")
                  .append("// speakerConfig.setNumThreads(1);\n");
                break;
                
            default:
                sb.append("// 请根据实际模型路径配置\n");
                break;
        }
        
        return sb.toString();
    }
}
