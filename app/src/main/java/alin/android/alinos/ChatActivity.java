package alin.android.alinos;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.bean.ChatRecordBean;
import alin.android.alinos.bean.ChatSessionBean;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ChatDBHelper;
import alin.android.alinos.db.ConfigDBHelper;

import alin.android.alinos.manager.EventBus;
import alin.android.alinos.net.BaseNetHelper;
import alin.android.alinos.net.MessageSender;
import alin.android.alinos.net.NetHelperFactory;
import alin.android.alinos.net.OpenAIStreamNetHelper;
import alin.android.alinos.manager.ChatStreamEventBus;

public class ChatActivity extends AppCompatActivity implements EventBus.EventListener {

    // 新增日志TAG
    private static final String TAG = "ChatActivity_Stream";

    ////ChatActivity是原本的克隆,流式式聊天界面,用于测试和备用////
    // 控件声明
    private DrawerLayout drawerLayout;
    private RecyclerView rvSessionList, rvChat;
    private TextView tvSessionName, tvModelName;
    private EditText etInput;

    // 数据库 & 数据相关
    private ChatDBHelper mChatDbHelper;
    private static ConfigDBHelper mConfigDbHelper;
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
    private OpenAIStreamNetHelper mStreamNetHelper;
    private long mStreamRecordId = -1; // 流式消息ID（隔离原有ID）
    private final StringBuilder mStreamContentBuffer = new StringBuilder();
    private int mAiMessagePosition = -1; // AI消息在列表中的位置
    private boolean isStreamLoading = false;
    private boolean isStreamFinished = false;   // 标记流式是否真正完成
    private final Handler mStreamHandler = new Handler(Looper.getMainLooper()); // 用于错误重试

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        AiConfigActivity.ActivityStateManager.getInstance().setChatActivityVisible(true);
        // 初始化数据库
        mChatDbHelper = new ChatDBHelper(this);
        mConfigDbHelper = new ConfigDBHelper(this);

        // 注册事件监听器
        EventBus.getInstance().register(this);
        // 初始化所有控件
        initViews();
        // 初始化适配器
        initAdapter();
        // 初始化超时Runnable
        initTimeoutRunnable();
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
        // 聊天消息适配器
        mChatAdapter = new ChatAdapter(this, mMessageList, rvChat);
        LinearLayoutManager chatLayoutManager = new LinearLayoutManager(this);
        chatLayoutManager.setStackFromEnd(true); // 从底部开始显示
        rvChat.setLayoutManager(chatLayoutManager);
        rvChat.setAdapter(mChatAdapter);

        // 会话列表适配器
        rvSessionList.setLayoutManager(new LinearLayoutManager(this));
    }

    // 初始化超时提示Runnable
                    if (holder instanceof ChatAdapter.AiViewHolder) {
                        ((ChatAdapter.AiViewHolder) holder).tvLoadingTips.setText("响应时间较长，请耐心等待...");
                    }
                });
            }
        };


    // 初始化会话列表
    private void initSessionList() {
        mSessionList = mChatDbHelper.getAllSessions();
        mSessionAdapter = new SessionAdapter(mSessionList, this::selectSession, this::showSessionLongClickMenu);
        rvSessionList.setAdapter(mSessionAdapter);
    }

    // 选择会话：加载对应聊天记录+更新标题
    private void selectSession(ChatSessionBean session) {
        mCurrentSessionId = session.getId();
        // 通过configId获取模型名称，显示在标题栏
        String modelName = mConfigDbHelper.getModelNameByConfigId(session.getConfigId());
        tvSessionName.setText(session.getSessionName());
        tvModelName.setText(modelName);
        // 获取当前会话的配置（含模型、服务器地址等）
        mCurrentConfig = mConfigDbHelper.getConfigById(session.getConfigId());
        // 加载聊天记录
        loadChatRecords(session.getId());
        // 关闭侧边栏
        drawerLayout.closeDrawer(GravityCompat.START);
    }

    // 加载聊天记录（通过会话ID）
    private void loadChatRecords(int sessionId) {
        List<ChatRecordBean> records = mChatDbHelper.getRecordsBySessionId(sessionId);
        mMessageList.clear();
        for (ChatRecordBean record : records) {
            if (TextUtils.isEmpty(record.getContent())) continue;
            int type = record.getMsgType() == 0 ? ChatMessage.TYPE_USER : ChatMessage.TYPE_AI;
            mMessageList.add(new ChatMessage(record.getContent(), type, false));
        }
        mChatAdapter.notifyDataSetChanged();
        // 滚动到最新消息
        if (mChatAdapter.getItemCount() > 0) {
            rvChat.scrollToPosition(mChatAdapter.getItemCount() - 1);
        }
    }

    /**
     * 生成AI回复 - 普通请求
     */
    private String generateAIResponse(String userInput) {
        if (mCurrentConfig == null) {
            return "[错误] 未配置AI服务";
        }

        // 检查是否有网络助手
        BaseNetHelper netHelper = null;
        try {
            netHelper = NetHelperFactory.createNetHelper(this, mCurrentConfig);
        } catch (Exception e) {
            return "[错误] 创建网络助手失败: " + e.getMessage();
        }

        if (netHelper == null) {
            return "[错误] 不支持的AI服务类型：" + mCurrentConfig.getType();
        }

        try {
            // 调用网络接口
            String response = netHelper.sendMessage(userInput);

            // 检查是否是错误响应
            if (response == null || response.trim().isEmpty()) {
                return "[错误] AI返回空响应";
            }

            // 检查错误格式
            if (response.startsWith("[错误]") || response.startsWith("[配置错误]")) {
                return response;
            }

            return response;

        } catch (Exception e) {
            e.printStackTrace();
            return "[错误] AI服务异常：" + e.getMessage();
        }
    }

    // 普通发送消息
    private void sendMessage() {
        String content = etInput.getText().toString().trim();

        // 非空校验
        if (TextUtils.isEmpty(content)) {
            Toast.makeText(this, "输入内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mCurrentSessionId == -1 || mCurrentConfig == null) {
            Toast.makeText(this, "请先选择会话并配置AI服务", Toast.LENGTH_SHORT).show();
            return;
        }

        // 添加用户消息到UI
        mMessageList.add(new ChatMessage(content, ChatMessage.TYPE_USER, false));
        mChatAdapter.notifyItemInserted(mMessageList.size() - 1);
        rvChat.scrollToPosition(mMessageList.size() - 1);

        // 保存用户消息到数据库
        ChatRecordBean userRecord = new ChatRecordBean(
                mCurrentSessionId,
                0,
                "我",
                content,
                System.currentTimeMillis()
        );
        mChatDbHelper.addRecord(userRecord);
        etInput.setText("");

        // 在新线程中生成AI回复
        new Thread(() -> {
            String aiReply = generateAIResponse(content);

            // 回到主线程处理
            runOnUiThread(() -> {
                // 添加AI回复到UI
                mMessageList.add(new ChatMessage(aiReply, ChatMessage.TYPE_AI, false));
                mChatAdapter.notifyItemInserted(mMessageList.size() - 1);
                rvChat.scrollToPosition(mMessageList.size() - 1);

                // 保存AI回复到数据库
                ChatRecordBean aiRecord = new ChatRecordBean(
                        mCurrentSessionId,
                        1,
                        mCurrentConfig.getType(),
                        aiReply,
                        System.currentTimeMillis()
                );
                mChatDbHelper.addRecord(aiRecord);
            });
        }).start();
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

        // 重置流式状态
        resetStreamLoadingState();

        // ===================== 核心2行：识别 + 正则替换 =====================
        String originType = mCurrentConfig.getType().toLowerCase();
        String targetType = originType.replaceAll("^openai$", "openai_stream");
        ConfigBean streamConfig = new ConfigBean(mCurrentConfig);
        streamConfig.setType(targetType);
        // ==================================================================

        // 校验：处理替换后的type，兼容原有逻辑
        if (!"openai_stream".equalsIgnoreCase(streamConfig.getType())) {
            String errorMsg = "仅支持type=openai/openai_stream的配置，当前type：" + originType;
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "触发流式发送失败：" + errorMsg);
            return;
        }

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
        mChatDbHelper.addRecord(userRecord);
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
        mStreamRecordId = mChatDbHelper.addRecord(loadingRecord);

        // 新增校验：确保recordId生成成功
        if (mStreamRecordId == -1) {
            Log.e(TAG, "流式记录入库失败：addRecord返回-1");
            Toast.makeText(this, "流式记录入库失败", Toast.LENGTH_SHORT).show();
        } else {
            Log.d(TAG, "流式loading记录入库成功，recordId=" + mStreamRecordId);
        }

        // 标记流式加载中
        isStreamLoading = true;

        // 调用工厂原有方法，传入替换后的配置
        mStreamNetHelper = (OpenAIStreamNetHelper) NetHelperFactory.createNetHelper(this, streamConfig);
        if (mStreamNetHelper == null) {
            resetStreamLoadingState();
            mChatAdapter.updateAiMessage(mAiMessagePosition, "[错误] 不支持OpenAI流式请求", false);
            mChatDbHelper.updateRecordContent(mStreamRecordId, "[错误] 不支持OpenAI流式请求");
            return;
        }

        // 发起流式请求：仅传必要参数，回调处理UI
        mStreamNetHelper.sendStreamMessage(mCurrentSessionId, content, (eventType, data) -> {
            runOnUiThread(() -> handleStreamEvent(data));
        });
    }

    // 流式事件处理方法
    private void handleStreamEvent(ChatStreamEventBus.StreamEventData data) {
        // 异常处理
        if (data.isError()) {
            Log.e(TAG, "流式错误：" + data.getErrorMsg());

            // 处理503错误的重试逻辑
            if (data.getErrorMsg().contains("503") && retryCount < 3) {
                retryCount++;
                Log.d(TAG, "503服务不可用，第" + retryCount + "次重试...");
                mStreamHandler.postDelayed(() -> {
                    mStreamNetHelper.sendStreamMessage(mCurrentSessionId, etInput.getText().toString().trim(), (eventType, retryData) -> {
                        runOnUiThread(() -> handleStreamEvent(retryData));
                    });
                }, 1000L * retryCount);
                return;
            }

            String errorContent = "[流式异常] " + data.getErrorMsg();
            writeStreamRecordToDb(errorContent); // 复用方法
            handleStreamFinish();
            if (mAiMessagePosition != -1) {
                mChatAdapter.updateAiMessage(mAiMessagePosition, errorContent, false);
            }
            Toast.makeText(this, data.getErrorMsg(), Toast.LENGTH_SHORT).show();
            retryCount = 0; // 重置重试计数
            return;
        }

        // 增量缓存，不立即更库
        if (!data.isFinish() && !TextUtils.isEmpty(data.getChunkContent())) {
            long currentReceiveTime = System.currentTimeMillis();

            // 超时管理已迁移到OpenAIStreamNetHelper

            // 正常接收分片：缓存+更新UI
            mStreamContentBuffer.append(data.getChunkContent());
            Log.d(TAG, "流式缓存长度：" + mStreamContentBuffer.length());
            mChatAdapter.updateAiMessage(mAiMessagePosition, mStreamContentBuffer.toString(), true);
            rvChat.scrollToPosition(mAiMessagePosition);



        }

        // 流式结束：先更新数据库，再处理UI（核心修复）
        if (data.isFinish()) {
            Log.d(TAG, "流式完成，总长度：" + mStreamContentBuffer.length());
            String finalContent = mStreamContentBuffer.toString().trim();
            if (TextUtils.isEmpty(finalContent)) {
                finalContent = "[空回复] 服务端未返回有效内容";
            }
            writeStreamRecordToDb(finalContent); // 复用方法
            handleStreamFinish();
            if (mAiMessagePosition != -1) {
                mChatAdapter.updateAiMessage(mAiMessagePosition, finalContent, false);
            }
            Toast.makeText(this, "流式回复完成（已保存到数据库）", Toast.LENGTH_SHORT).show();
        }
    }


    // 流式完成的处理方法
    private void handleStreamFinish() {
        isStreamFinished = true;
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
        if (mStreamRecordId != -1) {
            mChatDbHelper.updateRecordContent(mStreamRecordId, content);
        } else {
            ChatRecordBean record = new ChatRecordBean(
                    mCurrentSessionId,
                    1,
                    mCurrentConfig.getType() + "[流式]",
                    content,
                    System.currentTimeMillis()
            );
            mChatDbHelper.addRecord(record);
        }
    }

    // 新建会话：选择AI配置（含模型）
    private void createNewSession() {
        List<ConfigBean> configList = mConfigDbHelper.getAllConfigs();
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
                                long sessionId = mChatDbHelper.addSession(session);
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

        List<ConfigBean> configList = mConfigDbHelper.getAllConfigs();
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

    // 更新数据库中的会话
    private void updateSessionInDb(ChatSessionBean session) {
        if (session == null || mChatDbHelper == null) return;

        // 使用新增的updateSession方法
        mChatDbHelper.updateSession(session);

        // 刷新列表
        initSessionList();
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
                    mChatDbHelper.updateSession(session);

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
                    mChatDbHelper.deleteSession(session.getId());

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
        EventBus.getInstance().unregister(this);
        AiConfigActivity.ActivityStateManager.getInstance().setChatActivityVisible(false);
        // 移除监听器
        if (mGlobalLayoutListener != null) {
            drawerLayout.getViewTreeObserver().removeOnGlobalLayoutListener(mGlobalLayoutListener);
        }

        // 取消流式请求，防止内存泄漏
        if (mStreamNetHelper != null) {
            mStreamNetHelper.cancelStream();
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

    @Override
    public void onEvent(String eventType, Object data) {
        if (MessageSender.EVENT_AI_RESPONSE_RECEIVED.equals(eventType) && data instanceof MessageSender.EventMessage) {
            MessageSender.EventMessage event = (MessageSender.EventMessage) data;

            // 如果事件中的会话ID与当前会话相同，则刷新界面
            if (event.getSessionId() == mCurrentSessionId) {
                runOnUiThread(() -> {
                    // 重新加载聊天记录
                    loadChatRecords(mCurrentSessionId);
                    Toast.makeText(this, "收到新消息", Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

    // 菜单：新建会话入口
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

    // -------------------- 会话列表适配器 --------------------
    private static class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {
        private final List<ChatSessionBean> mList;
        private final OnSessionSelectListener mSelectListener;
        private final OnSessionLongClickListener mLongClickListener;

        public SessionAdapter(List<ChatSessionBean> list,
                              OnSessionSelectListener selectListener,
                              OnSessionLongClickListener longClickListener) {
            mList = list;
            mSelectListener = selectListener;
            mLongClickListener = longClickListener;
        }

        @NonNull
        @Override
        public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_session, parent, false);
            return new SessionViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
            ChatSessionBean session = mList.get(position);
            holder.tvSessionName.setText(session.getSessionName());

            // 创建ConfigDBHelper实例
            ConfigDBHelper configDbHelper = new ConfigDBHelper(holder.itemView.getContext());

            // 通过configId获取AI类型和模型名称
            String configType = configDbHelper.getConfigTypeByConfigId(session.getConfigId());
            String modelName = configDbHelper.getModelNameByConfigId(session.getConfigId());
            holder.tvConfigType.setText("AI类型：" + configType + " | 模型：" + modelName);

            // 点击选中会话
            holder.itemView.setOnClickListener(v -> mSelectListener.onSelect(session));

            // 长按弹出菜单
            final int pos = holder.getAdapterPosition();
            holder.itemView.setOnLongClickListener(v -> {
                if (pos != RecyclerView.NO_POSITION) {
                    mLongClickListener.onLongClick(session, pos);
                }
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mList == null ? 0 : mList.size();
        }

        static class SessionViewHolder extends RecyclerView.ViewHolder {
            TextView tvSessionName, tvConfigType;

            public SessionViewHolder(@NonNull View itemView) {
                super(itemView);
                tvSessionName = itemView.findViewById(R.id.tv_session_name);
                tvConfigType = itemView.findViewById(R.id.tv_config_type);
            }
        }

        interface OnSessionSelectListener {
            void onSelect(ChatSessionBean session);
        }

        interface OnSessionLongClickListener {
            void onLongClick(ChatSessionBean session, int position);
        }
    }

    // -------------------- 聊天消息实体类 --------------------
    public static class ChatMessage {
        public static final int TYPE_USER = 1;   // 用户消息
        public static final int TYPE_AI = 2;     // AI消息
        public String content;                  // 消息内容
        public int type;                        // 消息类型
        public boolean isLoading;               // AI是否加载中

        public ChatMessage(String content, int type, boolean isLoading) {
            this.content = content;
            this.type = type;
            this.isLoading = isLoading;
        }
    }

    // -------------------- 聊天适配器 --------------------
    public static class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context mContext;
        private final List<ChatMessage> mMessageList;
        private final RecyclerView mRecyclerView;

        // 构造方法
        public ChatAdapter(Context context, List<ChatMessage> messageList, RecyclerView recyclerView) {
            this.mContext = context;
            this.mMessageList = messageList;
            this.mRecyclerView = recyclerView; // 传入RecyclerView引用
        }

        // 刷新单条AI消息（流式增量更新）
        public void updateAiMessage(int position, String newContent, boolean isLoading) {
            if (position >= 0 && position < mMessageList.size()) {
                ChatMessage msg = mMessageList.get(position);
                if (msg.type == ChatMessage.TYPE_AI) {
                    msg.content = newContent;
                    msg.isLoading = isLoading;
                    notifyItemChanged(position); // 局部刷新，避免闪烁

                    // 核心修复：直接获取当前显示的ViewHolder，强制控制控件
                    RecyclerView.ViewHolder holder = mRecyclerView.findViewHolderForAdapterPosition(position);
                    if (holder instanceof AiViewHolder) {
                        AiViewHolder aiHolder = (AiViewHolder) holder;
                        // 强制隐藏/显示loading布局和转圈
                        aiHolder.llAiLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
                        // 额外：停止ProgressBar动画（防止动画残留）
                        if (!isLoading) {
                            aiHolder.pbLoadingCircle.clearAnimation();
                            aiHolder.pbLoadingCircle.setVisibility(View.GONE);
                        } else {
                            aiHolder.pbLoadingCircle.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            if (viewType == ChatMessage.TYPE_USER) {
                // 用户消息布局
                View view = inflater.inflate(R.layout.item_chat_user, parent, false);
                return new UserViewHolder(view);
            } else {
                // AI消息布局（核心）
                View view = inflater.inflate(R.layout.item_chat_ai, parent, false);
                return new AiViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ChatMessage message = mMessageList.get(position);
            if (holder instanceof AiViewHolder) {
                AiViewHolder aiHolder = (AiViewHolder) holder;
                // 设置AI消息内容
                aiHolder.tvAiContent.setText(message.content);
                // 强制同步loading状态（核心：覆盖所有复用情况）
                boolean isLoading = message.isLoading;
                aiHolder.llAiLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
                aiHolder.pbLoadingCircle.setVisibility(isLoading ? View.VISIBLE : View.GONE);
                if (!isLoading) {
                    aiHolder.pbLoadingCircle.clearAnimation(); // 停止动画
                }
                // 同步提示文案
                if (isLoading) {
                    aiHolder.tvLoadingTips.setText("模型正在思考中...");
                }
            } else if (holder instanceof UserViewHolder) {
                UserViewHolder userHolder = (UserViewHolder) holder;
                userHolder.tvUserContent.setText(message.content);

                // 用户消息长按事件
                holder.itemView.setOnLongClickListener(v -> {
                    PopupMenu popupMenu = new PopupMenu(mContext, v);
                    popupMenu.getMenuInflater().inflate(R.menu.user_message_menu, popupMenu.getMenu());
                    popupMenu.setOnMenuItemClickListener(item -> {
                        int itemId = item.getItemId();
                        if (itemId == R.id.menu_copy) {
                            copyToClipboard(mContext, message.content);
                            return true;
                        } else if (itemId == R.id.menu_resend) {
                            ((ChatActivity)mContext).etInput.setText(message.content);
                            Toast.makeText(mContext, "已复制到输入框", Toast.LENGTH_SHORT).show();
                            return true;
                        } else if (itemId == R.id.menu_delete) {
                            Toast.makeText(mContext, "删除消息功能待实现", Toast.LENGTH_SHORT).show();
                            return true;
                        }
                        return false;
                    });
                    popupMenu.show();
                    return true;
                });
            }
        }

        // 复制到剪贴板
        private void copyToClipboard(Context context, String text) {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("聊天消息", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }

        @Override
        public int getItemViewType(int position) {
            return mMessageList.get(position).type;
        }

        @Override
        public int getItemCount() {
            return mMessageList.size();
        }

        // AI消息ViewHolder
        static class AiViewHolder extends RecyclerView.ViewHolder {
            TextView tvAiContent;
            LinearLayout llAiLoading;
            TextView tvLoadingTips;
            ProgressBar pbLoadingCircle;

            public AiViewHolder(@NonNull View itemView) {
                super(itemView);
                tvAiContent = itemView.findViewById(R.id.tv_ai_content);
                llAiLoading = itemView.findViewById(R.id.ll_ai_loading);
                tvLoadingTips = itemView.findViewById(R.id.tv_loading_tips);
                pbLoadingCircle = itemView.findViewById(R.id.pb_loading_circle);

                // AI消息长按事件
                itemView.setOnLongClickListener(v -> {
                    Context context = itemView.getContext();
                    PopupMenu popupMenu = new PopupMenu(context, v);
                    popupMenu.getMenuInflater().inflate(R.menu.ai_message_menu, popupMenu.getMenu());
                    popupMenu.setOnMenuItemClickListener(item -> {
                        int itemId = item.getItemId();
                        if (itemId == R.id.menu_copy) {
                            copyToClipboard(context, tvAiContent.getText().toString());
                            return true;
                        } else if (itemId == R.id.menu_regenerate) {
                            Toast.makeText(context, "重新生成中...", Toast.LENGTH_SHORT).show();
                            return true;
                        } else if (itemId == R.id.menu_delete) {
                            Toast.makeText(context, "删除消息功能待实现", Toast.LENGTH_SHORT).show();
                            return true;
                        }
                        return false;
                    });
                    popupMenu.show();
                    return true;
                });
            }

            // 复制到剪贴板
            private void copyToClipboard(Context context, String text) {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("聊天消息", text);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        }

        // 用户消息ViewHolder
        static class UserViewHolder extends RecyclerView.ViewHolder {
            TextView tvUserContent;

            public UserViewHolder(@NonNull View itemView) {
                super(itemView);
                tvUserContent = itemView.findViewById(R.id.tv_user_content);
            }
        }
    }
}