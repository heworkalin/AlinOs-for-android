package alin.android.alinos;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
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
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
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

public class ChatActivity extends AppCompatActivity implements EventBus.EventListener {
    // 控件声明
    private DrawerLayout drawerLayout;
    private RecyclerView rvSessionList, rvChat;
    private TextView tvSessionName, tvModelName;
    private LinearLayout llTitleContainer;
    private EditText etInput;
    private ImageView ivMenu;
    private ImageView ivMenuAdd;
    private Button btnSend;
    private LinearLayout inputLayout;

    // 数据库 & 数据相关
    private ChatDBHelper mChatDbHelper;
    private static ConfigDBHelper mConfigDbHelper;
    private List<ChatSessionBean> mSessionList;
    private SessionAdapter mSessionAdapter;
    private ChatMsgAdapter mChatMsgAdapter;
    private int mCurrentSessionId = -1;
    private ConfigBean mCurrentConfig;

    // 软键盘监听相关
    private ViewTreeObserver.OnGlobalLayoutListener mGlobalLayoutListener;
    private boolean isKeyboardShowing = false;

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

        // 初始化会话列表
        initSessionList();

        // 默认选中第一个会话（如有）
        if (mSessionList != null && mSessionList.size() > 0) {
            selectSession(mSessionList.get(0));
        }

        // 监听软键盘弹出/收起
        setupKeyboardListener();

        // 输入框焦点监听：软键盘弹出时滚动到最新消息
        etInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (mChatMsgAdapter.getItemCount() > 0) {
                        rvChat.scrollToPosition(mChatMsgAdapter.getItemCount() - 1);
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
        llTitleContainer = findViewById(R.id.ll_title_container);
        tvSessionName = findViewById(R.id.tv_session_name);
        tvModelName = findViewById(R.id.tv_model_name);
        etInput = findViewById(R.id.et_input);
        ivMenu = findViewById(R.id.iv_menu);
        ivMenuAdd = findViewById(R.id.iv_menu_add);
        btnSend = findViewById(R.id.btn_send);
        inputLayout = findViewById(R.id.input_layout);

        // 侧边栏开关事件
        ivMenu.setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(Gravity.START)) {
                drawerLayout.closeDrawer(Gravity.START);
            } else {
                drawerLayout.openDrawer(Gravity.START);
            }
        });

        // 新建会话按钮
        ivMenuAdd.setOnClickListener(v -> createNewSession());

        // 标题栏点击事件 - 切换模型
        llTitleContainer.setOnClickListener(v -> showModelSwitchDialog());

        // 发送按钮点击事件
        btnSend.setOnClickListener(v -> sendMessage());

        // 配置聊天列表
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        mChatMsgAdapter = new ChatMsgAdapter();
        rvChat.setAdapter(mChatMsgAdapter);

        // 配置会话列表
        rvSessionList.setLayoutManager(new LinearLayoutManager(this));
    }

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
        drawerLayout.closeDrawer(Gravity.START);
    }

    // 加载聊天记录（通过会话ID）
    private void loadChatRecords(int sessionId) {
        List<ChatRecordBean> records = mChatDbHelper.getRecordsBySessionId(sessionId);
        mChatMsgAdapter.setData(convertRecordToChatMsg(records));
        mChatMsgAdapter.notifyDataSetChanged();
        // 滚动到最新消息
        if (mChatMsgAdapter.getItemCount() > 0) {
            rvChat.scrollToPosition(mChatMsgAdapter.getItemCount() - 1);
        }
    }

    /**
     * 生成AI回复 - 修复缺失的方法
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

    // 修改sendMessage方法中的AI回复处理
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

    // 保存用户消息
    ChatRecordBean userRecord = new ChatRecordBean(
            mCurrentSessionId,
            0,
            "我",  // 修改发送者为"我"
            content,
            System.currentTimeMillis()
    );
    mChatDbHelper.addRecord(userRecord);

    // 清空输入框并立即显示用户消息
    etInput.setText("");
    loadChatRecords(mCurrentSessionId);

    // 在新线程中生成AI回复
    new Thread(() -> {
        String aiReply = generateAIResponse(content);
        
        // 回到主线程处理
        runOnUiThread(() -> {
            // 保存AI回复
            ChatRecordBean aiRecord = new ChatRecordBean(
                    mCurrentSessionId,
                    1,
                    mCurrentConfig.getType(),
                    aiReply,
                    System.currentTimeMillis()
            );
            mChatDbHelper.addRecord(aiRecord);
            loadChatRecords(mCurrentSessionId);
        });
    }).start();
}

    // 创建加载中的消息
    private ChatMsg createLoadingMessage() {
        ChatMsg msg = new ChatMsg();
        msg.setSender(mCurrentConfig.getType());
        msg.setContent("[AI正在思考中...]");
        msg.setType(ChatMsg.TYPE_RECEIVE);
        msg.setTime(System.currentTimeMillis());
        return msg;
    }

    // 保存正常的AI回复
    private void saveAIResponse(String content) {
        ChatRecordBean aiRecord = new ChatRecordBean(
                mCurrentSessionId,
                1,
                mCurrentConfig.getType(),
                content,
                System.currentTimeMillis()
        );
        mChatDbHelper.addRecord(aiRecord);
        loadChatRecords(mCurrentSessionId);
    }

    // 保存错误回复（红色显示）
    private void saveErrorResponse(String error) {
        ChatRecordBean errorRecord = new ChatRecordBean(
                mCurrentSessionId,
                2,
                "系统提示",
                error,
                System.currentTimeMillis()
        );
        mChatDbHelper.addRecord(errorRecord);
        loadChatRecords(mCurrentSessionId);
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
                        if (mChatMsgAdapter != null) {
                            mChatMsgAdapter.setData(new ArrayList<>());
                            mChatMsgAdapter.notifyDataSetChanged();
                        }
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
                        if (mChatMsgAdapter.getItemCount() > 0) {
                            rvChat.smoothScrollToPosition(mChatMsgAdapter.getItemCount() - 1);
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
       // saveSubActivityData();
        // 销毁当前子 Activity
        AiConfigActivity.ActivityStateManager.getInstance().setChatActivityVisible(false);
        finish();
        // 如需阻止返回上一级，可去掉 super.onBackPressed()
        // super.onBackPressed();
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
                    
                    // 显示一个简短的Toast提示
                    Toast.makeText(this, "收到新消息", Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

    // 转换数据库记录为聊天消息实体
    private List<ChatMsg> convertRecordToChatMsg(List<ChatRecordBean> records) {
        List<ChatMsg> msgList = new ArrayList<>();
        for (ChatRecordBean record : records) {
            if (TextUtils.isEmpty(record.getContent())) {
                continue;
            }
            
            ChatMsg msg = new ChatMsg();
            msg.setSender(record.getSender());
            msg.setContent(record.getContent());
            msg.setTime(record.getSendTime());
            
            // 根据msgType设置消息类型
            int msgType = record.getMsgType();
            if (msgType == 0) {
                msg.setType(ChatMsg.TYPE_SEND); // 发送
            } else if (msgType == 1) {
                msg.setType(ChatMsg.TYPE_RECEIVE); // 接收
            } else if (msgType == 2) {
                msg.setType(ChatMsg.TYPE_RECEIVE); // 错误也视为接收类型
            } else {
                msg.setType(ChatMsg.TYPE_RECEIVE); // 默认
            }
            
            msgList.add(msg);
        }
        return msgList;
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

    // 复制到剪贴板
    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("聊天消息", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(ChatActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }
    
    // 重新生成AI回复
    private void regenerateAIResponse(String content) {
        Toast.makeText(ChatActivity.this, "重新生成中...", Toast.LENGTH_SHORT).show();
    }
    
    // 删除消息
    private void deleteMessage(ChatMsg msg, int position) {
        Toast.makeText(ChatActivity.this, "删除消息功能待实现", Toast.LENGTH_SHORT).show();
    }

    // -------------------- 会话列表适配器 --------------------
    private static class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {
        private List<ChatSessionBean> mList;
        private OnSessionSelectListener mSelectListener;
        private OnSessionLongClickListener mLongClickListener;

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

        class SessionViewHolder extends RecyclerView.ViewHolder {
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

    // -------------------- 聊天消息适配器 --------------------
    private class ChatMsgAdapter extends RecyclerView.Adapter<ChatMsgAdapter.ChatMsgViewHolder> {
        private List<ChatMsg> mData = new ArrayList<>();

        public void setData(List<ChatMsg> data) {
            mData = data;
        }

        @NonNull
        @Override
        public ChatMsgViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_msg, parent, false);
            return new ChatMsgViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ChatMsgViewHolder holder, int position) {
            ChatMsg message = mData.get(position);
    
            holder.tvSender.setText(message.getSender());
            holder.tvContent.setText(message.getContent());
            
            // 检查是否是错误消息（以[开头，以]结尾）
            boolean isErrorMessage = message.getContent().startsWith("[") && message.getContent().endsWith("]");
            
            if (message.getType() == ChatMsg.TYPE_SEND) {
                // 用户消息 - 右对齐
                holder.msgContainer.setGravity(Gravity.END);
                holder.tvContent.setBackgroundResource(R.drawable.shape_chat_msg_user);
                holder.tvContent.setTextColor(Color.WHITE);
                
                holder.tvSender.setText(" ");
                holder.tvSender.setGravity(Gravity.END);
                
            } else if (isErrorMessage) {
                // 错误消息 - 居中显示，红色背景
                holder.msgContainer.setGravity(Gravity.CENTER);
                holder.tvContent.setBackgroundResource(R.drawable.shape_chat_msg_error);
                holder.tvContent.setTextColor(Color.WHITE);
                holder.tvContent.setTextSize(12);
                holder.tvSender.setVisibility(View.GONE);
                
            } else {
                // AI消息 - 左对齐
                holder.msgContainer.setGravity(Gravity.START);
                holder.tvContent.setBackgroundResource(R.drawable.shape_chat_msg_ai);
                holder.tvContent.setTextColor(Color.BLACK);
                
                holder.tvSender.setText(message.getSender());
                holder.tvSender.setGravity(Gravity.START);
            }
            
            // 长按事件 - 直接在消息项上显示菜单
            holder.itemView.setOnLongClickListener(v -> {
                PopupMenu popupMenu = new PopupMenu(ChatActivity.this, v);
                
                if (message.getType() == ChatMsg.TYPE_SEND) {
                    popupMenu.getMenuInflater().inflate(R.menu.user_message_menu, popupMenu.getMenu());
                } else {
                    popupMenu.getMenuInflater().inflate(R.menu.ai_message_menu, popupMenu.getMenu());
                }
                
                popupMenu.setOnMenuItemClickListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.menu_copy) {
                        copyToClipboard(message.getContent());
                        return true;
                    } else if (itemId == R.id.menu_resend) {
                        etInput.setText(message.getContent());
                        Toast.makeText(ChatActivity.this, "已复制到输入框", Toast.LENGTH_SHORT).show();
                        return true;
                    } else if (itemId == R.id.menu_regenerate) {
                        regenerateAIResponse(message.getContent());
                        return true;
                    } else if (itemId == R.id.menu_delete) {
                        deleteMessage(message, position);
                        return true;
                    }
                    return false;
                });
                
                popupMenu.show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }

        class ChatMsgViewHolder extends RecyclerView.ViewHolder {
            TextView tvSender, tvContent;
            LinearLayout msgContainer;
            public ChatMsgViewHolder(@NonNull View itemView) {
                super(itemView);
                tvSender = itemView.findViewById(R.id.tv_sender);
                tvContent = itemView.findViewById(R.id.tv_content);
                msgContainer = itemView.findViewById(R.id.msg_container);
            }
        }
    }

    // -------------------- 聊天消息实体类 --------------------
    public static class ChatMsg {
        public static final int TYPE_SEND = 0; // 发送消息
        public static final int TYPE_RECEIVE = 1; // 接收消息
        private String sender;
        private String content;
        private long time;
        private int type;

        // Getter & Setter
        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public long getTime() { return time; }
        public void setTime(long time) { this.time = time; }
        public int getType() { return type; }
        public void setType(int type) { this.type = type; }
    }
}