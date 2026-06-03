package alin.android.alinos.net.openai;

import alin.android.alinos.net.openai.model.*;
import alin.android.alinos.manager.ChatStreamEventBus;

/**
 * OpenAI REST API 完整接口定义。
 * 基于 openapi.yaml v2.3.0，覆盖所有消费者端端点。
 *
 * 实现状态标记：
 *   ✅ 已实现    🔶 预留骨架    ⬜ 未实现
 */
public interface OpenAIApi {

    // ================================================================
    // Chat — 对话补全（核心模块）
    // ================================================================

    /** ✅ POST /v1/chat/completions — 创建流式对话补全 */
    void createChatCompletionStream(ChatRequest request, int sessionId,
                                    ChatStreamEventBus.StreamEventListener listener);

    /** 🔶 POST /v1/chat/completions — 创建同步对话补全（非流式） */
    ChatResponse createChatCompletion(ChatRequest request);

    /** 🔶 GET /v1/chat/completions — 列出历史补全记录 */
    String listChatCompletions(String after, int limit, String order);

    /** 🔶 GET /v1/chat/completions/{completion_id} — 获取单个补全 */
    ChatResponse getChatCompletion(String completionId);

    /** 🔶 POST /v1/chat/completions/{completion_id} — 更新补全 */
    ChatResponse updateChatCompletion(String completionId, ChatRequest request);

    /** 🔶 DELETE /v1/chat/completions/{completion_id} — 删除补全 */
    CommonResponses.DeletionStatus deleteChatCompletion(String completionId);

    /** 🔶 GET /v1/chat/completions/{completion_id}/messages — 获取补全消息 */
    String getChatCompletionMessages(String completionId, String after, int limit, String order);


    // ================================================================
    // Audio — 语音合成 & 识别
    // ================================================================

    /** 🔶 POST /v1/audio/speech — TTS 文字转语音，返回音频字节 */
    byte[] createSpeech(AudioSpeechRequest request);

    /** 🔶 POST /v1/audio/transcriptions — STT 语音转文字 */
    String createTranscription(AudioTranscriptionRequest request, byte[] audioFile);


    // ================================================================
    // Embeddings — 文本向量化
    // ================================================================

    /** 🔶 POST /v1/embeddings — 创建文本嵌入向量 */
    EmbeddingResponse createEmbedding(EmbeddingRequest request);


    // ================================================================
    // Images — 图片生成
    // ================================================================

    /** 🔶 POST /v1/images/generations — 从文本生成图片 */
    CommonResponses.ImageResponse createImage(ImageGenerationRequest request);


    // ================================================================
    // Models — 模型管理
    // ================================================================

    /** 🔶 GET /v1/models — 列出所有可用模型 */
    CommonResponses.ModelListResponse listModels();

    /** 🔶 GET /v1/models/{model} — 获取指定模型信息 */
    CommonResponses.ModelInfo retrieveModel(String modelId);

    /** 🔶 DELETE /v1/models/{model} — 删除微调模型 */
    CommonResponses.DeletionStatus deleteModel(String modelId);


    // ================================================================
    // Moderations — 内容审核
    // ================================================================

    /** 🔶 POST /v1/moderations — 审核文本内容 */
    CommonResponses.ModerationResponse createModeration(CommonResponses.ModerationRequest request);


    // ================================================================
    // Files — 文件管理
    // ================================================================

    /** 🔶 GET /v1/files — 列出上传文件 */
    String listFiles(String purpose);

    /** 🔶 POST /v1/files — 上传文件 */
    CommonResponses.FileInfo uploadFile(String filePath, String purpose);

    /** 🔶 DELETE /v1/files/{file_id} — 删除文件 */
    CommonResponses.DeletionStatus deleteFile(String fileId);

    /** 🔶 GET /v1/files/{file_id} — 获取文件信息 */
    CommonResponses.FileInfo retrieveFile(String fileId);

    /** 🔶 GET /v1/files/{file_id}/content — 下载文件内容 */
    byte[] downloadFile(String fileId);


    // ================================================================
    // Conversations — 对话管理（新版 API，/v1/conversations）
    // ================================================================

    /** ⬜ POST /v1/conversations — 创建对话 */
    // String createConversation(...);

    /** ⬜ GET /v1/conversations/{id} — 获取对话 */
    // String getConversation(String id);

    /** ⬜ POST /v1/conversations/{id}/items — 添加对话项 */
    // String createConversationItems(String id, ...);

    /** ⬜ GET /v1/conversations/{id}/items — 列出对话项 */
    // String listConversationItems(String id, ...);


    // ================================================================
    // Responses — 响应 API（新版，/v1/responses）
    // ================================================================

    /** ⬜ POST /v1/responses — 创建响应 */
    // String createResponse(...);

    /** ⬜ GET /v1/responses/{id} — 获取响应 */
    // String getResponse(String id);

    /** ⬜ DELETE /v1/responses/{id} — 删除响应 */
    // String deleteResponse(String id);


    // ================================================================
    // Realtime — 实时通信（WebRTC）
    // ================================================================

    /** ⬜ POST /v1/realtime/sessions — 创建实时会话 */
    // String createRealtimeSession(...);

    /** ⬜ POST /v1/realtime/transcription_sessions — 创建实时转录会话 */
    // String createRealtimeTranscriptionSession(...);
}
