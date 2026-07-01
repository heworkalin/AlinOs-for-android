package alin.android.alinos;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.adapter.ChatAdapter;
import alin.android.alinos.adapter.SessionAdapter;
import alin.android.alinos.bean.ChatMessage;
import alin.android.alinos.bean.ChatRecordBean;
import alin.android.alinos.bean.ChatSessionBean;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ChatDBHelper;
import alin.android.alinos.db.ConfigDBHelper;
import alin.android.alinos.manager.ChatStreamEventBus;
import alin.android.alinos.prompt.PromptService;
import alin.android.alinos.tools.ToolCallCoordinator;
import alin.android.alinos.utils.TokenEstimator;
import alin.android.alinos.db.ToolCallDbHelper;
import alin.android.alinos.bean.ToolCallLogBean;

import org.json.JSONArray;
import org.json.JSONObject;

public class ChatActivity extends AppCompatActivity {

    // 新增日志TAG
    private static final String TAG = "ChatActivity_Stream";


    // 控件声明
    private DrawerLayout drawerLayout;
    private RecyclerView rvSessionList, rvChat;
    private TextView tvSessionName, tvModelName;
    private EditText etInput;

    // 数据库 & 数据相关
    private ChatDBHelper mChatDbHelper;
    private static ConfigDBHelper mConfigDbHelper;
    private ToolCallDbHelper mToolCallDbHelper;
    private List<ChatSessionBean> mSessionList;
    private SessionAdapter mSessionAdapter;
    private ChatAdapter mChatAdapter;
    private final List<ChatMessage> mMessageList = new ArrayList<>();
    private int mCurrentSessionId = -1;
    private ConfigBean mCurrentConfig;

    // 软键盘监听相关
    private ViewTreeObserver.OnGlobalLayoutListener mGlobalLayoutListener;
    private boolean isKeyboardShowing = false;

    // 流式相关控制
    private long mStreamRecordId = -1; // 流式消息ID（隔离原有ID）
    private final StringBuilder mStreamContentBuffer = new StringBuilder();
    private int mAiMessagePosition = -1; // AI消息在列表中的位置
    private int mThinkMessagePosition = -1; // Think 块消息位置
    private int mToolCallStartPosition = -1; // 工具调用卡片起始位置
    private boolean isStreamLoading = false;
    private boolean isStreamFinished = false;   // 标记流式是否真正完成
    private final Handler mStreamHandler = new Handler(Looper.getMainLooper()); // 用于错误重试
    private int retryCount = 0; // 503错误重试计数器
    private PromptService mPromptService; // 统一的提示词服务

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        AiConfigActivity.ActivityStateManager.getInstance().setChatActivityVisible(true);
        // 初始化数据库
        mChatDbHelper = new ChatDBHelper(this);
        mConfigDbHelper = new ConfigDBHelper(this);
        mToolCallDbHelper = new ToolCallDbHelper(this);
        // 初始化提示词服务
        mPromptService = new PromptService(this);

        // 初始化所有控件
        initViews();
        // 初始化适配器
        initAdapter();
        // 初始化会话列表
        initSessionList();

        // 默认选中第一个会话（如有）
        if (mSessionList != null && !mSessionList.isEmpty()) {
            selectSession(mSessionList.get(0));
        }

        // 监听软键盘弹出/收起
        setupKeyboardListener();

        // 输入框焦点监听：软键盘弹出时滚动到最新消息
        etInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (mChatAdapter.getItemCount() > 0) {
                        rvChat.scrollToPosition(mChatAdapter.getItemCount() - 1);
                    }
                }, 200);
            }
        });

        // 输入框回车键发送
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    // 初始化控件
    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        rvSessionList = findViewById(R.id.rv_session_list);
        rvChat = findViewById(R.id.rv_chat);
        LinearLayout llTitleContainer = findViewById(R.id.ll_title_container);
        tvSessionName = findViewById(R.id.tv_session_name);
        tvModelName = findViewById(R.id.tv_model_name);
        etInput = findViewById(R.id.et_input);
        ImageView ivMenu = findViewById(R.id.iv_menu);
        ImageView ivMenuAdd = findViewById(R.id.iv_menu_add);
        Button btnSend = findViewById(R.id.btn_send);
        LinearLayout inputLayout = findViewById(R.id.input_layout);

        // 侧边栏开关事件
        ivMenu.setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        // 新建会话按钮
        ivMenuAdd.setOnClickListener(v -> createNewSession());

        // 标题栏点击事件 - 切换模型
        llTitleContainer.setOnClickListener(v -> showModelSwitchDialog());

        // 发送按钮点击事件
        btnSend.setOnClickListener(v -> sendMessage());

        // 新增：长按发送按钮触发流式（不用改布局，测试用）
        btnSend.setOnLongClickListener(v -> {
            sendStreamMessage();
            return true;
        });
    }

    // 初始化适配器
    private void initAdapter() {
        // 聊天消息适配器（注入重发回调）
        mChatAdapter = new ChatAdapter(this, mMessageList, rvChat,
                content -> etInput.setText(content));
        LinearLayoutManager chatLayoutManager = new LinearLayoutManager(this);
        chatLayoutManager.setStackFromEnd(true); // 从底部开始显示
        rvChat.setLayoutManager(chatLayoutManager);
        rvChat.setAdapter(mChatAdapter);

        // 会话列表适配器
        rvSessionList.setLayoutManager(new LinearLayoutManager(this));
    }

    // 初始化会话列表
    private void initSessionList() {
        mSessionList = getAllSessionsFromDb();
        mSessionAdapter = new SessionAdapter(mSessionList, this::selectSession, this::showSessionLongClickMenu,
                this::getConfigTypeByConfigIdFromDb, this::getModelNameByConfigIdFromDb);
        rvSessionList.setAdapter(mSessionAdapter);
    }

    // 选择会话：加载对应聊天记录+更新标题
    private void selectSession(ChatSessionBean session) {
        mCurrentSessionId = session.getId();
        // 通过configId获取模型名称，显示在标题栏
        String modelName = getModelNameByConfigIdFromDb(session.getConfigId());
        tvSessionName.setText(session.getSessionName());
        tvModelName.setText(modelName);
        // 获取当前会话的配置（含模型、服务器地址等）
        mCurrentConfig = getConfigByIdFromDb(session.getConfigId());
        // 加载聊天记录
        loadChatRecords(session.getId());
        // 关闭侧边栏
        drawerLayout.closeDrawer(GravityCompat.START);
    }

    // 加载聊天记录（通过会话ID）
    private void loadChatRecords(int sessionId) {
        List<ChatRecordBean> records = getRecordsBySessionIdFromDb(sessionId);
        mMessageList.clear();

        // 预加载工具调用日志（按 record_id 索引，方便查找）
        java.util.Map<Integer, ToolCallLogBean> toolCallMap = new java.util.HashMap<>();
        if (mToolCallDbHelper != null) {
            for (ToolCallLogBean tc : mToolCallDbHelper.getBySessionId(sessionId)) {
                toolCallMap.put(tc.getId(), tc);
            }
        }
        // 记录 chat_record 中工具标记的插入顺序，用于回填序号
        java.util.Map<Integer, Integer> markerOrder = new java.util.HashMap<>();
        int markerSeq = 0;

        // 加载聊天文本记录
        for (ChatRecordBean record : records) {
            if (TextUtils.isEmpty(record.getContent())) continue;

            String content = record.getContent();
            int type;

            // 检测工具调用标记：[tool_call]工具名
            if (record.getMsgType() == 2 || content.startsWith("[tool_call]")) {
                String toolName = content.startsWith("[tool_call]")
                        ? content.substring(11) : "unknown";
                type = ChatMessage.TYPE_TOOL_CALL;

                // 从 tool_call_log 按顺序获取对应记录
                ToolCallLogBean tc = null;
                int seq = markerSeq++;
                markerOrder.put(record.getId(), seq);

                // 按顺序在 toolCallMap 中找到对应序号的记录
                int found = 0;
                for (ToolCallLogBean t : mToolCallDbHelper.getBySessionId(sessionId)) {
                    if (found == seq) { tc = t; break; }
                    found++;
                }

                if (tc != null) {
                    try {
                        JSONObject card = new JSONObject();
                        card.put("toolName", tc.getToolName() != null ? tc.getToolName() : toolName);
                        card.put("args", tc.getArguments() != null ? tc.getArguments() : "");
                        card.put("request", "tool_call_id: " + (tc.getToolCallId() != null ? tc.getToolCallId() : "")
                                + "\narguments: " + (tc.getArguments() != null ? tc.getArguments() : ""));
                        card.put("response", "status: " + (tc.getStatus() != null ? tc.getStatus() : "unknown")
                                + "\n" + (tc.getResult() != null ? tc.getResult() : ""));
                        card.put("log", "耗时: " + tc.getDurationMs() + "ms"
                                + (tc.getErrorMessage() != null ? "\n错误: " + tc.getErrorMessage() : "")
                                + "\nDB ID: #" + tc.getId());
                        card.put("status", "success".equals(tc.getStatus()) ? "✅"
                                : "error".equals(tc.getStatus()) ? "❌" : "⏳");
                        card.put("duration", tc.getDurationMs() > 0 ? (tc.getDurationMs() / 1000.0) + "s" : "");
                        mMessageList.add(new ChatMessage(card.toString(), ChatMessage.TYPE_TOOL_CALL, false));
                        continue;
                    } catch (Exception e) {
                        Log.w(TAG, "构建工具卡片失败", e);
                    }
                }

                // 没有详细数据时，显示简版标记
                JSONObject simpleCard = new JSONObject();
                try {
                    simpleCard.put("toolName", toolName);
                    simpleCard.put("args", "");
                    simpleCard.put("request", "");
                    simpleCard.put("response", "");
                    simpleCard.put("log", "无详细日志");
                    simpleCard.put("status", "✅");
                    simpleCard.put("duration", "");
                } catch (Exception ignored) {}
                mMessageList.add(new ChatMessage(simpleCard.toString(), ChatMessage.TYPE_TOOL_CALL, false));
                continue;
            }

            type = record.getMsgType() == 0 ? ChatMessage.TYPE_USER : ChatMessage.TYPE_AI;
            mMessageList.add(new ChatMessage(content, type, false));
        }

        mChatAdapter.notifyDataSetChanged();
        if (mChatAdapter.getItemCount() > 0) {
            rvChat.scrollToPosition(mChatAdapter.getItemCount() - 1);
        }
    }


    // 发送消息（统一走流式路径）
    private void sendMessage() {
        sendStreamMessage();
    }

    // 流式发送消息
    private void sendStreamMessage() {
        String content = etInput.getText().toString().trim();
        if (TextUtils.isEmpty(content) || mCurrentSessionId == -1 || mCurrentConfig == null) {
            String toastMsg = TextUtils.isEmpty(content) ? "输入不能为空" : "请选择会话配置";
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
            Log.w(TAG, "触发流式发送失败：" + toastMsg);
            return;
        }
        // 校验用户输入长度
        int charLimit = mCurrentConfig.getUserInputCharLimit();
        if (content.length() > charLimit) {
            Toast.makeText(this, "输入内容超过限制（最多" + charLimit + "字符），请缩短内容", Toast.LENGTH_SHORT).show();
            return;
        }

        // 重置流式状态
        resetStreamLoadingState();


        // 日志打印：流式发送触发信息
        Log.d(TAG, "触发流式发送，会话ID：" + mCurrentSessionId + "，输入内容长度：" + content.length());

        // 添加用户消息到UI
        mMessageList.add(new ChatMessage(content, ChatMessage.TYPE_USER, false));
        mChatAdapter.notifyItemInserted(mMessageList.size() - 1);
        rvChat.scrollToPosition(mMessageList.size() - 1);

        // 保存用户消息到数据库
        ChatRecordBean userRecord = new ChatRecordBean(
                mCurrentSessionId, 0, "我", content, System.currentTimeMillis()
        );
        addRecordToDb(userRecord);
        etInput.setText("");

        // 添加AI占位消息（显示loading）
        mAiMessagePosition = mMessageList.size();
        mMessageList.add(new ChatMessage("", ChatMessage.TYPE_AI, true));
        mChatAdapter.notifyItemInserted(mAiMessagePosition);
        rvChat.scrollToPosition(mAiMessagePosition);

        // 保存loading记录到数据库
        ChatRecordBean loadingRecord = new ChatRecordBean(
                mCurrentSessionId, 1, mCurrentConfig.getType() + "[流式]", "", System.currentTimeMillis()
        );
        mStreamRecordId = addRecordToDb(loadingRecord);
        Log.d(TAG, "流式loading记录入库，recordId=" + mStreamRecordId);

        // 标记流式加载中
        isStreamLoading = true;

        // 使用提示词服务发起流式请求
        mPromptService.sendStreamMessage(mCurrentConfig, mCurrentSessionId, content, (eventType, data) -> {
            runOnUiThread(() -> handleStreamEvent(data));
        });
    }

    // 流式事件处理方法
    private void handleStreamEvent(ChatStreamEventBus.StreamEventData data) {
        // ========== 异常处理 ==========
        if (data.isError()) {
            Log.e(TAG, "流式错误：" + data.getErrorMsg());

            if ((data.getErrorMsg().contains("503") || data.getErrorMsg().contains("[超时重试]")) && retryCount < 3) {
                retryCount++;
                Log.d(TAG, "503服务不可用，第" + retryCount + "次重试...");
                mStreamHandler.postDelayed(() -> {
                    mPromptService.sendStreamMessage(mCurrentConfig, mCurrentSessionId, etInput.getText().toString().trim(), (eventType, retryData) -> {
                        runOnUiThread(() -> handleStreamEvent(retryData));
                    });
                }, 1000L * retryCount);
                return;
            }

            String errorContent = "[流式异常] " + data.getErrorMsg();
            writeStreamRecordToDb(errorContent);
            handleStreamFinish();
            if (mAiMessagePosition != -1) {
                mChatAdapter.updateAiMessage(mAiMessagePosition, errorContent, false);
            }
            Toast.makeText(this, data.getErrorMsg(), Toast.LENGTH_SHORT).show();
            retryCount = 0;
            return;
        }

        // ========== Think 块事件 ==========
        if (data.getFullContent() != null && data.isFinish()
                && data.getToolCallsJson() == null
                && !TextUtils.isEmpty(data.getFullContent())
                && mThinkMessagePosition >= 0) {
            // Think 块结束 → 自动折叠
            mChatAdapter.updateThinkMessage(mThinkMessagePosition, data.getFullContent(), true);
            return;
        }

        // ========== 工具调用事件 ==========
        if (data.getToolCallsJson() != null && !data.getToolCallsJson().isEmpty()) {
            Log.d(TAG, "🛠️ 收到工具调用事件，启动协调器");

            try {
                JSONArray toolCalls = new JSONArray(data.getToolCallsJson());

                // 先保存起始位置（必须在 handleStreamFinish 之前，因为它会重置）
                final int toolCallStartPos = mMessageList.size();

                // 结束流式状态，重置相关位置（但 mToolCallStartPosition 不在这里用了）
                handleStreamFinish();

                // 为每个工具添加 TYPE_TOOL_CALL 占位消息 + 写入位置标记
                for (int i = 0; i < toolCalls.length(); i++) {
                    String toolName = toolCalls.getJSONObject(i)
                            .getJSONObject("function").optString("name", "unknown");
                    JSONObject placeholder = new JSONObject();
                    placeholder.put("toolName", toolName);
                    placeholder.put("status", "⏳");
                    placeholder.put("duration", "");
                    placeholder.put("args", "");
                    placeholder.put("request", "");
                    placeholder.put("response", "");
                    placeholder.put("log", "执行中...");

                    mMessageList.add(new ChatMessage(
                            placeholder.toString(), ChatMessage.TYPE_TOOL_CALL, true));
                    mChatAdapter.notifyItemInserted(mMessageList.size() - 1);

                    // 写入位置标记到聊天记录（用于恢复时定位）
                    ChatRecordBean marker = new ChatRecordBean(
                            mCurrentSessionId, 2, "tool", "[tool_call]" + toolName, System.currentTimeMillis());
                    addRecordToDb(marker);
                }
                rvChat.scrollToPosition(mMessageList.size() - 1);

                // 用局部变量捕获起始位置，回调不再依赖成员变量
                final int capturedStartPos = toolCallStartPos;

                // 启动工具调用协调器（带卡片更新回调）
                ToolCallCoordinator coordinator = new ToolCallCoordinator(
                        this, mCurrentConfig, mCurrentSessionId,
                        buildCurrentMessages(), (eventType, eventData) -> {
                    runOnUiThread(() -> handleStreamEvent(eventData));
                }, (messageIndex, cardJson, isExecuting) -> {
                    // 工具卡片 UI 更新回调 — 必须在主线程执行
                    runOnUiThread(() -> {
                        int pos = capturedStartPos + messageIndex;
                        if (pos >= 0 && pos < mMessageList.size()) {
                            mChatAdapter.updateToolCallMessage(pos, cardJson, isExecuting);
                        }
                    });
                });
                coordinator.execute(toolCalls);

            } catch (Exception e) {
                Log.e(TAG, "解析工具调用失败", e);
            }
            return;
        }

        // ========== Think 块增量 ==========
        if (!data.isFinish() && !TextUtils.isEmpty(data.getChunkContent())) {
            // 检测是否包含 Think 起始标记
            String chunk = data.getChunkContent();
            if (chunk.contains("<think>") && mThinkMessagePosition == -1) {
                // 创建 Think 块消息
                mThinkMessagePosition = mMessageList.size();
                mMessageList.add(new ChatMessage("", ChatMessage.TYPE_THINK, false));
                mChatAdapter.notifyItemInserted(mThinkMessagePosition);
                rvChat.scrollToPosition(mThinkMessagePosition);
            }

            if (mThinkMessagePosition >= 0) {
                // 正在 Think 阶段，积累到 Think 内容
                // AI 文本内容不显示（等待 Think 结束后显示）
                return;
            }
        }

        // ========== 普通文本增量 ==========
        if (!data.isFinish() && !TextUtils.isEmpty(data.getChunkContent())) {
            // 工具调用循环后重新生成时，创建新的 AI 消息位置
            if (mAiMessagePosition == -1 && mThinkMessagePosition == -1) {
                mAiMessagePosition = mMessageList.size();
                mMessageList.add(new ChatMessage("", ChatMessage.TYPE_AI, true));
                mChatAdapter.notifyItemInserted(mAiMessagePosition);
                rvChat.scrollToPosition(mAiMessagePosition);
            }

            mStreamContentBuffer.append(data.getChunkContent());
            if (mStreamContentBuffer.length() % 100 < 10) {
                Log.d(TAG, "流式缓存长度：" + mStreamContentBuffer.length());
            }
            mChatAdapter.updateAiMessage(mAiMessagePosition, mStreamContentBuffer.toString(), true);
            rvChat.scrollToPosition(mAiMessagePosition);
            return;
        }

        // ========== 流式结束（普通文本） ==========
        if (data.isFinish() && data.getToolCallsJson() == null) {
            Log.d(TAG, "流式完成，总长度：" + mStreamContentBuffer.length());
            String finalContent = data.getFullContent() != null
                    ? data.getFullContent().trim()
                    : mStreamContentBuffer.toString().trim();

            if (TextUtils.isEmpty(finalContent)) {
                finalContent = "[空回复] 服务端未返回有效内容";
                Log.w(TAG, "流式完成但内容为空");
            }

            writeStreamRecordToDb(finalContent);
            handleStreamFinish();
            if (mAiMessagePosition != -1) {
                mChatAdapter.updateAiMessage(mAiMessagePosition, finalContent, false);
            }
            Toast.makeText(this, "流式回复完成（已保存到数据库）", Toast.LENGTH_SHORT).show();
        }
    }

    /** 构建当前完整消息历史（用于工具调用回注）。 */
    private JSONArray buildCurrentMessages() {
        try {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "你是一个AI助手，回答简洁专业。"));
            // 添加最后几轮对话历史
            List<ChatRecordBean> history = mPromptService != null
                    ? mPromptService.getChatHistory(mCurrentSessionId)
                    : new ArrayList<>();
            int start = Math.max(0, history.size() - 10);
            for (int i = start; i < history.size(); i++) {
                ChatRecordBean r = history.get(i);
                String role = r.getMsgType() == 0 ? "user" : "assistant";
                arr.put(new JSONObject()
                        .put("role", role)
                        .put("content", r.getContent()));
            }
            arr.put(new JSONObject()
                    .put("role", "user")
                    .put("content", etInput.getText().toString().trim()));
            return arr;
        } catch (Exception e) {
            Log.e(TAG, "构建消息历史失败", e);
            return new JSONArray();
        }
    }

    // 流式完成的处理方法
    private void handleStreamFinish() {
        isStreamFinished = true;
        mThinkMessagePosition = -1; // 重置 Think 块位置
        mToolCallStartPosition = -1; // 重置工具卡片起始位置
        // 超时检测任务已由OpenAIStreamNetHelper管理

        // 核心修复：直接调用适配器，强制隐藏转圈
        if (mAiMessagePosition != -1) {
            mChatAdapter.updateAiMessage(mAiMessagePosition, mStreamContentBuffer.toString(), false);
        }

        // 额外兜底：直接找到控件隐藏（终极保险）
        hideLoadingForce();

        // 注意：这里不再调用resetStreamLoadingState()，避免提前重置mStreamRecordId
        // 重置操作移到最后，且保留mStreamRecordId直到数据库写入完成
        resetStreamLoadingStateSafe(); // 改用安全的重置方法
    }

    // 新增：终极保险方法，强制隐藏loading
    private void hideLoadingForce() {
        if (rvChat == null || mAiMessagePosition == -1) return;
        RecyclerView.ViewHolder holder = rvChat.findViewHolderForAdapterPosition(mAiMessagePosition);
        if (holder instanceof ChatAdapter.AiViewHolder) {
            ChatAdapter.AiViewHolder aiHolder = (ChatAdapter.AiViewHolder) holder;
            aiHolder.llAiLoading.setVisibility(View.GONE);
            aiHolder.pbLoadingCircle.clearAnimation();
            aiHolder.pbLoadingCircle.setVisibility(View.GONE);
        }
    }

    // 安全的流式状态重置（先写库，后重置ID）
    private void resetStreamLoadingStateSafe() {
        isStreamLoading = false;
        // 清空缓存
        mStreamContentBuffer.setLength(0);
        // 重置AI消息位置（保留recordId直到数据库写入完成）
        mAiMessagePosition = -1;

        // 最后再重置recordId（确保数据库已写入）
        mStreamRecordId = -1;
        Log.d(TAG, "流式状态安全重置完成");
    }

    // 重置流式加载状态（原有方法，修复ID重置时机）
    private void resetStreamLoadingState() {
        isStreamLoading = false;
        // 清空缓存
        mStreamContentBuffer.setLength(0);
        // 重置AI消息位置（先不重置recordId）
        mAiMessagePosition = -1;

        // 注意：这里不再主动重置mStreamRecordId，由resetStreamLoadingStateSafe()处理
    }

    // 新增：抽离写库逻辑，统一正常/异常场景，减少冗余
    private void writeStreamRecordToDb(String content) {
        // 估算内容的token数
        int estimatedTokens = TokenEstimator.estimateTokens(content);

        if (mStreamRecordId != -1) {
            // 更新现有记录的内容和token信息
            updateRecordContentInDb(mStreamRecordId, content);
            // 更新token信息（对于流式AI回复，tokenCount就是估算的token数）
            if (mChatDbHelper != null) {
                mChatDbHelper.updateRecordTokens(mStreamRecordId, estimatedTokens, 0, estimatedTokens, estimatedTokens);
            }
        } else {
            // 创建新记录，设置token信息
            ChatRecordBean record = new ChatRecordBean(
                    mCurrentSessionId,
                    1,
                    mCurrentConfig.getType() + "[流式]",
                    content,
                    System.currentTimeMillis()
            );
            // 设置token信息（对于流式AI回复，使用估算值）
            record.setTokenCount(estimatedTokens);
            record.setCompletionTokens(estimatedTokens);
            record.setTotalTokens(estimatedTokens);
            addRecordToDb(record);
        }
    }

    // 新建会话：选择AI配置（含模型）
    private void createNewSession() {
        List<ConfigBean> configList = getAllConfigsFromDb();
        if (configList.isEmpty()) {
            Toast.makeText(this, "暂无AI配置，请先添加", Toast.LENGTH_SHORT).show();
            return;
        }

        // 构建配置选择列表（显示类型+模型）
        String[] configNames = new String[configList.size()];
        for (int i = 0; i < configList.size(); i++) {
            ConfigBean config = configList.get(i);
            configNames[i] = config.getType() + "(" + config.getModel() + ")";
        }

        // 弹窗选择配置
        new AlertDialog.Builder(this)
                .setTitle("选择AI服务（含模型）")
                .setItems(configNames, (dialog, which) -> {
                    ConfigBean selectConfig = configList.get(which);
                    // 输入会话名称
                    EditText etName = new EditText(this);
                    etName.setHint("请输入会话名称");
                    new AlertDialog.Builder(this)
                            .setTitle("新建会话")
                            .setView(etName)
                            .setPositiveButton("创建", (d, w) -> {
                                String sessionName = etName.getText().toString().trim();
                                if (sessionName.isEmpty()) {
                                    sessionName = "新会话-" + System.currentTimeMillis();
                                }
                                // 新建会话（绑定configId）
                                ChatSessionBean session = new ChatSessionBean(
                                        sessionName,
                                        selectConfig.getId(),
                                        System.currentTimeMillis()
                                );
                                long sessionId = addSessionToDb(session);
                                // 刷新会话列表并选中新会话
                                initSessionList();
                                for (ChatSessionBean s : mSessionList) {
                                    if (s.getId() == sessionId) {
                                        selectSession(s);
                                        break;
                                    }
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .show();
    }

    // 显示模型切换对话框
    private void showModelSwitchDialog() {
        if (mCurrentSessionId == -1) {
            Toast.makeText(this, "请先选择一个会话", Toast.LENGTH_SHORT).show();
            return;
        }

        List<ConfigBean> configList = getAllConfigsFromDb();
        if (configList.isEmpty()) {
            Toast.makeText(this, "暂无AI配置，请先添加", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] configNames = new String[configList.size()];
        for (int i = 0; i < configList.size(); i++) {
            ConfigBean config = configList.get(i);
            configNames[i] = config.getType() + "(" + config.getModel() + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("切换模型")
                .setItems(configNames, (dialog, which) -> {
                    ConfigBean selectedConfig = configList.get(which);
                    updateSessionConfig(selectedConfig);
                })
                .show();
    }

    // 更新会话配置
    private void updateSessionConfig(ConfigBean newConfig) {
        ChatSessionBean currentSession = getSessionById(mCurrentSessionId);
        if (currentSession != null) {
            currentSession.setConfigId(newConfig.getId());
            // 更新数据库
            updateSessionInDb(currentSession);
            // 刷新会话列表侧边栏
            initSessionList();
            // 重新加载当前会话
            selectSession(currentSession);
            Toast.makeText(this, "已切换模型为：" + newConfig.getModel(), Toast.LENGTH_SHORT).show();
        }
    }

    // 获取当前会话对象
    private ChatSessionBean getSessionById(int sessionId) {
        for (ChatSessionBean session : mSessionList) {
            if (session.getId() == sessionId) {
                return session;
            }
        }
        return null;
    }


    // 显示会话长按菜单 - 修复锚点问题
    private void showSessionLongClickMenu(ChatSessionBean session, int position) {
        // 获取对应位置的视图
        View itemView = null;

        // 方法1：通过LayoutManager找到视图
        RecyclerView.LayoutManager layoutManager = rvSessionList.getLayoutManager();
        if (layoutManager != null) {
            View view = layoutManager.findViewByPosition(position);
            if (view != null) {
                itemView = view;
            }
        }

        // 方法2：如果找不到，使用第一个可见项作为备用
        if (itemView == null && rvSessionList.getChildCount() > 0) {
            itemView = rvSessionList.getChildAt(0);
        }

        // 方法3：如果还找不到，使用RecyclerView本身
        if (itemView == null) {
            itemView = rvSessionList;
        }

        PopupMenu popupMenu = new PopupMenu(this, itemView);
        popupMenu.getMenuInflater().inflate(R.menu.session_long_click_menu, popupMenu.getMenu());

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_rename) {
                renameSession(session);
                return true;
            } else if (itemId == R.id.menu_delete) {
                deleteSession(session);
                return true;
            } else if (itemId == R.id.menu_switch_model) {
                showModelSwitchDialog();
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    // 重命名会话 - 修复数据库更新
    private void renameSession(ChatSessionBean session) {
        if (session == null) {
            Toast.makeText(this, "会话不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        EditText etName = new EditText(this);
        etName.setText(session.getSessionName());
        etName.setSelection(etName.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("重命名会话")
                .setView(etName)
                .setPositiveButton("确定", (dialog, which) -> {
                    String newName = etName.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "会话名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 更新会话名称
                    session.setSessionName(newName);

                    // 更新数据库
                    updateSessionInDb(session);

                    // 刷新会话列表
                    initSessionList();

                    // 如果当前会话是此会话，更新标题
                    if (mCurrentSessionId == session.getId()) {
                        tvSessionName.setText(newName);
                    }

                    Toast.makeText(this, "重命名成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 删除会话 - 修复数据库删除
    private void deleteSession(ChatSessionBean session) {
        if (session == null) {
            Toast.makeText(this, "会话不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("删除会话")
                .setMessage("确定删除会话《" + session.getSessionName() + "》吗？删除后无法恢复。")
                .setPositiveButton("删除", (dialog, which) -> {
                    // 从数据库中删除
                    deleteSessionFromDb(session.getId());

                    // 从内存列表中移除
                    for (int i = 0; i < mSessionList.size(); i++) {
                        if (mSessionList.get(i).getId() == session.getId()) {
                            mSessionList.remove(i);
                            if (mSessionAdapter != null) {
                                mSessionAdapter.notifyItemRemoved(i);
                            }
                            break;
                        }
                    }

                    // 如果删除的是当前会话，需要清空聊天记录并显示提示
                    if (mCurrentSessionId == session.getId()) {
                        mCurrentSessionId = -1;
                        mCurrentConfig = null;
                        mMessageList.clear();
                        mChatAdapter.notifyDataSetChanged();
                        tvSessionName.setText("无会话");
                        tvModelName.setText("无模型");
                        Toast.makeText(this, "当前会话已删除", Toast.LENGTH_SHORT).show();
                    }

                    Toast.makeText(this, "会话已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 设置软键盘监听
    private void setupKeyboardListener() {
        mGlobalLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            private final Rect rect = new Rect();
            private int lastVisibleHeight = 0;

            @Override
            public void onGlobalLayout() {
                drawerLayout.getWindowVisibleDisplayFrame(rect);
                int visibleHeight = rect.height();

                if (lastVisibleHeight == 0) {
                    lastVisibleHeight = visibleHeight;
                    return;
                }

                int heightDiff = Math.abs(lastVisibleHeight - visibleHeight);

                // 键盘状态变化
                if (heightDiff > 200) { // 键盘弹出或收起
                    if (visibleHeight < lastVisibleHeight) {
                        // 键盘弹出
                        isKeyboardShowing = true;
                        // 滚动到最新消息
                        if (mChatAdapter.getItemCount() > 0) {
                            rvChat.smoothScrollToPosition(mChatAdapter.getItemCount() - 1);
                        }
                    } else {
                        // 键盘收起
                        isKeyboardShowing = false;
                    }
                    lastVisibleHeight = visibleHeight;
                }
            }
        };

        drawerLayout.getViewTreeObserver().addOnGlobalLayoutListener(mGlobalLayoutListener);
    }

    @Override
    public void onBackPressed() {
        // 执行自定义逻辑（如保存数据）
        super.onBackPressed();
        AiConfigActivity.ActivityStateManager.getInstance().setChatActivityVisible(false);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AiConfigActivity.ActivityStateManager.getInstance().setChatActivityVisible(false);
        // 移除监听器
        if (mGlobalLayoutListener != null) {
            drawerLayout.getViewTreeObserver().removeOnGlobalLayoutListener(mGlobalLayoutListener);
        }

        // 取消流式请求，防止内存泄漏
        if (mPromptService != null) {
            mPromptService.cancelStream();
        }

        // 移除所有handler回调
        mStreamHandler.removeCallbacksAndMessages(null);
        resetStreamLoadingState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 确保状态正确
        AiConfigActivity.ActivityStateManager.getInstance().setChatActivityVisible(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 暂停时标记为不可见
        AiConfigActivity.ActivityStateManager.getInstance().setChatActivityVisible(false);
    }

    // ==============================================
    // 数据库操作封装（增加安全性和封装性）
    // ==============================================

    /**
     * 获取所有会话列表
     */
    private List<ChatSessionBean> getAllSessionsFromDb() {
        if (mChatDbHelper == null) {
            Log.e(TAG, "ChatDBHelper未初始化");
            return new ArrayList<>();
        }
        try {
            return mChatDbHelper.getAllSessions();
        } catch (Exception e) {
            Log.e(TAG, "获取会话列表失败", e);
            Toast.makeText(this, "加载会话列表失败", Toast.LENGTH_SHORT).show();
            return new ArrayList<>();
        }
    }

    /**
     * 根据会话ID获取聊天记录
     */
    private List<ChatRecordBean> getRecordsBySessionIdFromDb(int sessionId) {
        if (mChatDbHelper == null) {
            Log.e(TAG, "ChatDBHelper未初始化");
            return new ArrayList<>();
        }
        try {
            return mChatDbHelper.getRecordsBySessionId(sessionId);
        } catch (Exception e) {
            Log.e(TAG, "获取聊天记录失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 添加聊天记录到数据库
     * @return 记录ID，失败返回-1
     */
    private long addRecordToDb(ChatRecordBean record) {
        if (mChatDbHelper == null || record == null) {
            Log.e(TAG, "参数错误或ChatDBHelper未初始化");
            return -1;
        }
        try {
            return mChatDbHelper.addRecord(record);
        } catch (Exception e) {
            Log.e(TAG, "添加聊天记录失败", e);
            return -1;
        }
    }

    /**
     * 更新聊天记录内容
     */
    private void updateRecordContentInDb(long recordId, String newContent) {
        if (mChatDbHelper == null || recordId <= 0 || newContent == null) {
            Log.e(TAG, "参数错误或ChatDBHelper未初始化");
            return;
        }
        try {
            mChatDbHelper.updateRecordContent(recordId, newContent);
        } catch (Exception e) {
            Log.e(TAG, "更新聊天记录内容失败", e);
        }
    }

    /**
     * 添加新会话
     * @return 会话ID，失败返回-1
     */
    private long addSessionToDb(ChatSessionBean session) {
        if (mChatDbHelper == null || session == null) {
            Log.e(TAG, "参数错误或ChatDBHelper未初始化");
            return -1;
        }
        try {
            return mChatDbHelper.addSession(session);
        } catch (Exception e) {
            Log.e(TAG, "添加会话失败", e);
            return -1;
        }
    }

    /**
     * 更新会话信息
     */
    private void updateSessionInDb(ChatSessionBean session) {
        if (mChatDbHelper == null || session == null) {
            Log.e(TAG, "参数错误或ChatDBHelper未初始化");
            return;
        }
        try {
            mChatDbHelper.updateSession(session);
        } catch (Exception e) {
            Log.e(TAG, "更新会话失败", e);
        }
    }

    /**
     * 删除会话（包括关联的聊天记录）
     */
    private void deleteSessionFromDb(int sessionId) {
        if (mChatDbHelper == null || sessionId <= 0) {
            Log.e(TAG, "参数错误或ChatDBHelper未初始化");
            return;
        }
        try {
            mChatDbHelper.deleteSession(sessionId);
        } catch (Exception e) {
            Log.e(TAG, "删除会话失败", e);
            Toast.makeText(this, "删除会话失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 获取所有AI配置
     */
    private List<ConfigBean> getAllConfigsFromDb() {
        if (mConfigDbHelper == null) {
            Log.e(TAG, "ConfigDBHelper未初始化");
            return new ArrayList<>();
        }
        try {
            return mConfigDbHelper.getAllConfigs();
        } catch (Exception e) {
            Log.e(TAG, "获取AI配置失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 根据配置ID获取AI配置
     */
    private ConfigBean getConfigByIdFromDb(int configId) {
        if (mConfigDbHelper == null || configId <= 0) {
            Log.e(TAG, "参数错误或ConfigDBHelper未初始化");
            return null;
        }
        try {
            return mConfigDbHelper.getConfigById(configId);
        } catch (Exception e) {
            Log.e(TAG, "获取AI配置失败", e);
            return null;
        }
    }

    /**
     * 根据配置ID获取模型名称
     */
    private String getModelNameByConfigIdFromDb(int configId) {
        if (mConfigDbHelper == null || configId <= 0) {
            Log.e(TAG, "参数错误或ConfigDBHelper未初始化");
            return "未知模型";
        }
        try {
            return mConfigDbHelper.getModelNameByConfigId(configId);
        } catch (Exception e) {
            Log.e(TAG, "获取模型名称失败", e);
            return "未知模型";
        }
    }

    /**
     * 根据配置ID获取AI类型
     */
    private String getConfigTypeByConfigIdFromDb(int configId) {
        if (mConfigDbHelper == null || configId <= 0) {
            Log.e(TAG, "参数错误或ConfigDBHelper未初始化");
            return "未知类型";
        }
        try {
            return mConfigDbHelper.getConfigTypeByConfigId(configId);
        } catch (Exception e) {
            Log.e(TAG, "获取AI类型失败", e);
            return "未知类型";
        }
    }

    // ==============================================
    // 菜单：新建会话入口
    // ==============================================
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_new_session) {
            createNewSession();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public boolean isKeyboardShowing() {
        return isKeyboardShowing;
    }

    public void setKeyboardShowing(boolean keyboardShowing) {
        isKeyboardShowing = keyboardShowing;
    }
}