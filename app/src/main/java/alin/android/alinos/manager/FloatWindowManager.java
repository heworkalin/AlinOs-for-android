package alin.android.alinos.manager;

import static androidx.core.content.ContextCompat.startActivity;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import alin.android.alinos.AiConfigActivity;
import alin.android.alinos.ChatActivity;
import alin.android.alinos.R;
import alin.android.alinos.manager.VoiceRecognitionTool;
import alin.android.alinos.bean.ChatRecordBean;
import alin.android.alinos.bean.ChatSessionBean;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ChatDBHelper;
import alin.android.alinos.db.ConfigDBHelper;
import alin.android.alinos.net.BaseNetHelper;
import alin.android.alinos.net.NetHelperFactory;
import alin.android.alinos.net.MessageSender;

public class FloatWindowManager {
    private static final String TAG = "FloatWindowManager";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 1001;
    private static final int RESPONSE_LENGTH_THRESHOLD = 100;
    private static final int POPUP_WIDTH_RESERVE = 300;
    private static final int POPUP_AUTO_CLOSE_DELAY = 5000;
    private static final int DRAG_THRESHOLD = 10; // 拖动阈值（像素）
    
    private static FloatWindowManager instance;
    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ReentrantLock resultLock = new ReentrantLock(); // 线程安全锁
    
    private WindowManager.LayoutParams layoutParams;
    private View floatView;
    private boolean isShowing = false;

    // 模式常量
    private static final int MODE_VOICE = 0;
    private static final int MODE_INPUT = 1;
    private int currentMode = MODE_VOICE;

    // 视图组件
    private ImageView ivRecordCircle;
    private LinearLayout voiceContainer;
    private LinearLayout inputContainer;
    private ImageView ivVoiceMode;
    private ImageView ivInputMode;
    private ImageView ivClose;
    private ImageView ivOpenChat;
    private ImageView ivExpand;
    private EditText etInput;

    // 数据库相关
    private ChatDBHelper chatDBHelper;
    private ConfigDBHelper configDBHelper;
    private ChatSessionBean floatWindowSession;

    // 语音识别相关
    private VoiceRecognitionTool voiceRecognitionTool;
    private volatile boolean isRecording = false;
    private volatile boolean isRecognizing = false;
    private long recordingStartTime = 0;
    private String currentModel = "vosk-model-small-cn-0.22";
    private boolean hasRecordPermission = false;
    private volatile boolean hasValidRecognitionResult = false;
    
    // 线程安全的识别结果
    private String currentRecognitionResult = "";

    // 位置记录
    private float startX, startY;
    private float touchX, touchY;
    private boolean isDragging = false;

    private FloatWindowManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.voiceRecognitionTool = VoiceRecognitionTool.getInstance(context);
        
        // 初始化数据库
        this.chatDBHelper = new ChatDBHelper(context);
        this.configDBHelper = new ConfigDBHelper(context);
        
        // 权限检查（先检查录音权限）
        checkRecordPermission();
        // 初始化布局参数
        initLayoutParams();
        // 初始化语音识别
        initVoiceRecognition();
        // 获取/创建悬浮窗会话
        initFloatWindowSession();
    }

    public static synchronized FloatWindowManager getInstance(Context context) {
        if (instance == null) {
            instance = new FloatWindowManager(context);
        }
        return instance;
    }

    // -------------------- 核心修复：初始化相关 --------------------
    private void initLayoutParams() {
        layoutParams = new WindowManager.LayoutParams();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        
        layoutParams.format = PixelFormat.RGBA_8888;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                           WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                           WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; // 默认不获取焦点
        
        layoutParams.gravity = Gravity.START | Gravity.TOP;
        layoutParams.x = 100;
        layoutParams.y = 100;
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        
        // 软键盘模式初始化
        layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                                   WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
    }

    private void initFloatWindowSession() {
        floatWindowSession = chatDBHelper.getOrCreateFloatWindowSession();
        if (floatWindowSession == null) {
            Log.e(TAG, "悬浮窗会话初始化失败");
        }
    }

    private void initVoiceRecognition() {
        voiceRecognitionTool.setRecognitionCallback(new VoiceRecognitionTool.RecognitionCallback() {
            @Override
            public void onModelInitialized() {
                mainHandler.post(() -> {
                    Toast.makeText(context, "语音识别模型加载成功", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "模型初始化成功");
                });
            }

            @Override
            public void onModelError(String errorMessage) {
                // 同步更新状态
                resultLock.lock();
                try {
                    isRecognizing = false;
                    isRecording = false;
                } finally {
                    resultLock.unlock();
                }

                // 取消可能正在进行的识别
                if (voiceRecognitionTool != null) {
                    voiceRecognitionTool.cancelRecognition();
                }

                // 立即启用按钮
                mainHandler.post(() -> {
                    setModeButtonsEnabled(true);
                });

                mainHandler.post(() -> {
                    Toast.makeText(context, "模型加载失败: " + errorMessage, Toast.LENGTH_LONG).show();
                    Log.e(TAG, "模型错误: " + errorMessage);
                    resetRecognitionStatesOnly();
                });
            }

            @Override
            public void onRecordingStarted() {
                mainHandler.post(() -> {
                    isRecording = true;
                    isRecognizing = false;
                    recordingStartTime = System.currentTimeMillis();
                    if (ivRecordCircle != null) {
                        ivRecordCircle.setBackgroundResource(R.drawable.recording_circle_active);
                    }
                    Toast.makeText(context, "开始录音", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "开始录音");
                    
                    // 线程安全重置识别结果
                    resultLock.lock();
                    try {
                        currentRecognitionResult = "";
                        hasValidRecognitionResult = false;
                    } finally {
                        resultLock.unlock();
                    }
                    
                    setModeButtonsEnabled(false);
                });
            }

            @Override
            public void onRecordingStopped(long duration, String filePath) {
                mainHandler.post(() -> {
                    isRecording = false;
                    isRecognizing = true;
                    if (ivRecordCircle != null) {
                        ivRecordCircle.setBackgroundResource(R.drawable.circle_background);
                    }
                    long seconds = duration / 1000;
                    Toast.makeText(context, "录音结束，时长: " + seconds + "秒\n正在识别...", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "录音结束，时长: " + seconds + "秒，文件: " + filePath);
                    setModeButtonsEnabled(false);
                });
            }

            @Override
            public void onVolumeUpdated(double decibels, int progress) {
                Log.d(TAG, "音量: " + decibels + "dB，进度: " + progress);
            }

            @Override
            public void onPartialResult(String text, String jsonRaw) {
                if (!TextUtils.isEmpty(text)) {
                    resultLock.lock();
                    try {
                        currentRecognitionResult = text;
                    } finally {
                        resultLock.unlock();
                    }
                    Log.d(TAG, "中间识别结果: " + text);
                    
                    mainHandler.post(() -> {
                        if (currentMode == MODE_INPUT && etInput != null) {
                            etInput.setText(currentRecognitionResult);
                            etInput.setSelection(currentRecognitionResult.length());
                        }
                    });
                }
            }

            @Override
            public void onFinalResult(String text, String jsonRaw) {
                // 同步更新识别状态和结果
                boolean shouldProcess = false;
                boolean hasValidResult = false;
                String finalResult = "";

                resultLock.lock();
                try {
                    // 记录当前状态以便日志
                    boolean wasRecording = isRecording;
                    boolean wasRecognizing = isRecognizing;

                    Log.d(TAG, "onFinalResult调用 - 录音状态: " + wasRecording + ", 识别状态: " + wasRecognizing +
                          ", 结果: " + (TextUtils.isEmpty(text) ? "空" : text));

                    // 无论当前状态如何，都处理最终结果（防止状态不一致）
                    // 总是更新识别状态为false
                    isRecognizing = false;

                    Log.d(TAG, "最终识别结果: " + (TextUtils.isEmpty(text) ? "无" : text));

                    // 只更新非空结果，忽略cancel触发的空结果
                    if (!TextUtils.isEmpty(text)) {
                        currentRecognitionResult = text;
                        hasValidRecognitionResult = true;
                        hasValidResult = true;
                        finalResult = text;
                    } else if (!hasValidRecognitionResult) {
                        currentRecognitionResult = "";
                        finalResult = "";
                    } else {
                        Log.d(TAG, "忽略空结果，保留之前的有效结果: " + currentRecognitionResult);
                        hasValidResult = true;
                        finalResult = currentRecognitionResult;
                    }

                    shouldProcess = true;
                } finally {
                    resultLock.unlock();
                }

                if (!shouldProcess) {
                    Log.w(TAG, "onFinalResult跳过处理");
                    return;
                }

                // 在主线程中执行所有UI更新（确保原子性）
                boolean finalHasValidResult = hasValidResult;
                String finalResult2 = finalResult;
                var ref = new Object() {
                    final String finalResult1 = finalResult2;
                };
                mainHandler.post(() -> {
                    // 立即启用按钮
                    setModeButtonsEnabled(true);

                    // 切换到输入模式并填充结果
                    switchMode(MODE_INPUT, finalHasValidResult);

                    // 提示结果（日志与Toast信息一致）
                    if (!TextUtils.isEmpty(ref.finalResult1)) {
                        Toast.makeText(context, "识别完成: " + ref.finalResult1, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "识别完成: " + ref.finalResult1);
                    } else {
                        // 只有在确实有识别过程但没有结果时才显示提示
                        String emptyTip = "未识别到有效语音（可能音量过小或模型不匹配）";
                        Toast.makeText(context, emptyTip, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, emptyTip);
                    }

                    // 重要：识别完成后只重置识别相关状态，不清除输入模式状态
                    // 不清除输入框焦点，不隐藏键盘（用户可能需要输入或编辑）
                    resetRecognitionStatesOnly();
                });
            }

            @Override
            public void onRecognitionError(String errorMessage) {
                // 同步更新识别状态
                resultLock.lock();
                try {
                    isRecognizing = false;
                } finally {
                    resultLock.unlock();
                }

                // 取消可能正在进行的识别
                if (voiceRecognitionTool != null) {
                    voiceRecognitionTool.cancelRecognition();
                }

                // 立即启用按钮
                mainHandler.post(() -> {
                    setModeButtonsEnabled(true);
                });

                mainHandler.post(() -> {
                    Log.e(TAG, "识别错误: " + errorMessage);
                    Toast.makeText(context, "识别错误: " + errorMessage, Toast.LENGTH_SHORT).show();
                    switchMode(MODE_INPUT, false);
                    resetRecognitionStatesOnly();
                });
            }

            @Override
            public void onRecordingTimeUpdate(int seconds) {}

            @Override
            public void onPlaybackState(boolean isPlaying, String filePath) {}
        });

        voiceRecognitionTool.initializeModel(currentModel, null);
    }

    // -------------------- 核心修复：权限检查 --------------------
    private void checkRecordPermission() {
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            hasRecordPermission = true;
            Log.d(TAG, "已拥有录音权限");
        } else {
            hasRecordPermission = false;
            Log.d(TAG, "未拥有录音权限");
            // 不再自动请求权限，由MainActivity处理权限请求
        }
    }

    public void requestRecordPermission(Context activityContext) {
        if (!hasRecordPermission) {
            // 修复：增加上下文类型检查
            if (activityContext instanceof android.app.Activity) {
                ActivityCompat.requestPermissions(
                        (android.app.Activity) activityContext,
                        new String[]{android.Manifest.permission.RECORD_AUDIO},
                        REQUEST_RECORD_AUDIO_PERMISSION
                );
            } else {
                Log.e(TAG, "请求录音权限失败：上下文不是Activity");
                Toast.makeText(context, "权限申请失败，请在Activity中操作", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                hasRecordPermission = true;
                Toast.makeText(context, "录音权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                hasRecordPermission = false;
                Toast.makeText(context, "拒绝录音权限将无法使用语音识别", Toast.LENGTH_LONG).show();
            }
        }
    }

    // -------------------- 核心修复：悬浮窗显示/隐藏 --------------------
    public void showFloatWindow() {
        if (isShowing) return;
        
        // 修复：先检查录音权限，再检查悬浮窗权限
        if (!hasRecordPermission) {
            Toast.makeText(context, "请先授予录音权限", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!checkFloatWindowPermission()) {
            Toast.makeText(context, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(context);
        floatView = inflater.inflate(R.layout.float_window_layout, null);
        initViews();
        setupListeners();

        try {
            windowManager.addView(floatView, layoutParams);
            isShowing = true;
            resetAllStates();
            switchMode(MODE_VOICE, false);
            Toast.makeText(context, "悬浮窗已显示，语音识别初始化中...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "添加悬浮窗失败", e);
            Toast.makeText(context, "悬浮窗显示失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean checkFloatWindowPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    /**
     * 核心优化：隐藏悬浮窗时强制终止所有线程
     */
    public void hideFloatWindow() {
        if (isShowing && floatView != null) {
            hideKeyboard();
            resetAllStates();
            
            // 强制终止识别线程（双重保障）
            if (voiceRecognitionTool != null) {
                voiceRecognitionTool.cancelRecognition();
                if (voiceRecognitionTool.isRecording()) {
                    voiceRecognitionTool.stopRecording();
                }
                if (voiceRecognitionTool.isPlaying()) {
                    voiceRecognitionTool.stopPlayback();
                }
            }
            
            try {
                if (floatView.isAttachedToWindow()) {
                    windowManager.removeView(floatView);
                }
            } catch (Exception e) {
                Log.e(TAG, "移除悬浮窗失败", e);
            }
            
            floatView = null;
            isShowing = false;
        }
    }

    // -------------------- 核心修复：视图初始化与事件监听 --------------------
    private void initViews() {
        ivRecordCircle = floatView.findViewById(R.id.iv_record_circle);
        voiceContainer = floatView.findViewById(R.id.voice_container);
        inputContainer = floatView.findViewById(R.id.input_container);
        ivVoiceMode = floatView.findViewById(R.id.iv_voice_mode);
        ivInputMode = floatView.findViewById(R.id.iv_input_mode);
        ivClose = floatView.findViewById(R.id.iv_close);
        ivOpenChat = floatView.findViewById(R.id.iv_open_chat);
        ivExpand = floatView.findViewById(R.id.iv_expand);
        etInput = floatView.findViewById(R.id.et_input);

        if (etInput != null) {
            etInput.setClickable(true);
            etInput.setFocusable(true);
            etInput.setFocusableInTouchMode(true);
            
            etInput.setOnClickListener(v -> {
                if (currentMode == MODE_INPUT) {
                    showKeyboard();
                }
            });
        }
        
        switchMode(MODE_VOICE, false);
    }

    private void setupListeners() {
        // 优化拖动逻辑：允许按钮点击，只有真正拖动时才消费事件
        // 修改：只在录音时禁止拖动，识别过程中允许移动
        floatView.setOnTouchListener((v, event) -> {
            if (isRecording) return false;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    touchX = layoutParams.x;
                    touchY = layoutParams.y;
                    isDragging = false;
                    return false; // 让子视图有机会处理点击事件

                case MotionEvent.ACTION_MOVE:
                    if (!isDragging) {
                        // 检查移动距离是否超过阈值
                        float dx = event.getRawX() - startX;
                        float dy = event.getRawY() - startY;
                        float distance = (float) Math.sqrt(dx * dx + dy * dy);
                        if (distance > DRAG_THRESHOLD) {
                            isDragging = true;
                        }
                    }

                    if (isDragging) {
                        float dx = event.getRawX() - startX;
                        float dy = event.getRawY() - startY;
                        layoutParams.x = (int) (touchX + dx);
                        layoutParams.y = (int) (touchY + dy);
                        try {
                            if (floatView != null && floatView.isAttachedToWindow()) {
                                windowManager.updateViewLayout(floatView, layoutParams);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "移动悬浮窗失败", e);
                        }
                        return true; // 拖动时消费事件
                    }
                    return false; // 未达到拖动阈值，继续传递事件

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isDragging = false;
                    return false;

                default:
                    return false;
            }
        });

        ivClose.setOnClickListener(v -> {
            hideFloatWindow();
            Toast.makeText(context, "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
        });

        ivOpenChat.setOnClickListener(v -> {
            if (AiConfigActivity.ActivityStateManager.getInstance().isChatActivityVisible()) {
                Toast.makeText(context, "已在聊天界面", Toast.LENGTH_SHORT).show();
                return;
            } else if (!isAlreadyInChatActivity()) {
                Intent intent = new Intent(context, ChatActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(context, intent, null);
            } else {
                Toast.makeText(context, "已在聊天界面", Toast.LENGTH_SHORT).show();
            }
        });

        ivExpand.setOnClickListener(v -> toggleExpand());

        // 模式切换（增加状态检查）
        ivVoiceMode.setOnClickListener(v -> {
            // 只在录音时禁止切换，识别过程中允许切换（会自动取消识别）
            if (!isRecording) {
                // 检查当前是否为输入模式
                if (currentMode == MODE_INPUT && etInput != null) {
                    String inputText = etInput.getText().toString();
                    if (!TextUtils.isEmpty(inputText)) {
                        // 输入框有内容，提示用户需要清空才能切换
                        Toast.makeText(context, "请先清空输入框内容才能切换到语音模式", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                // 如果正在识别，先取消识别
                if (isRecognizing && voiceRecognitionTool != null) {
                    voiceRecognitionTool.cancelRecognition();
                    isRecognizing = false;
                }
                // 输入框为空或当前已经是语音模式，切换并重置状态
                switchMode(MODE_VOICE, false);
                // 重要：执行和发送按钮一致的重置逻辑，确保状态完全重置
                resetAllStates();
            } else {
                Toast.makeText(context, "正在录音中，无法切换模式", Toast.LENGTH_SHORT).show();
            }
        });
        
        ivInputMode.setOnClickListener(v -> {
            // 只在录音时禁止切换，识别过程中允许切换（会自动取消识别）
            if (!isRecording) {
                // 如果正在识别，先取消识别
                if (isRecognizing && voiceRecognitionTool != null) {
                    voiceRecognitionTool.cancelRecognition();
                    isRecognizing = false;
                }
                switchMode(MODE_INPUT, false);
                // 切换到输入模式时重置识别状态
                resetRecognitionStatesOnly();
            } else {
                Toast.makeText(context, "正在录音中，无法切换模式", Toast.LENGTH_SHORT).show();
            }
        });

        setupRecordingAnimation();
        setupInputModeButtons();
    }

    // -------------------- 核心修复：模式切换 --------------------
    private void switchMode(int mode, boolean isFromRecognition) {
        Log.d(TAG, "切换模式: " + (mode == MODE_VOICE ? "语音" : "输入") + ", 识别后切换: " + isFromRecognition);
        
        currentMode = mode;
        
        if (mode == MODE_VOICE) {
            voiceContainer.setVisibility(View.VISIBLE);
            inputContainer.setVisibility(View.GONE);
            ivVoiceMode.setBackgroundResource(R.drawable.mode_button_bg_selected);
            ivInputMode.setBackgroundResource(R.drawable.mode_button_bg_normal);
            hideKeyboard();
        } else {
            voiceContainer.setVisibility(View.GONE);
            inputContainer.setVisibility(View.VISIBLE);
            ivVoiceMode.setBackgroundResource(R.drawable.mode_button_bg_normal);
            ivInputMode.setBackgroundResource(R.drawable.mode_button_bg_selected);
            
            if (etInput != null) {
                if (isFromRecognition) {
                    // 线程安全获取识别结果
                    resultLock.lock();
                    try {
                        etInput.setText(currentRecognitionResult);
                        etInput.setSelection(currentRecognitionResult.length());
                    } finally {
                        resultLock.unlock();
                    }
                    Log.d(TAG, "填充识别结果: " + currentRecognitionResult);
                } else {
                    etInput.setText("");
                }
            }
            
            // 修复：添加Window附加状态检查
            mainHandler.postDelayed(() -> {
                if (floatView != null && floatView.isAttachedToWindow() 
                        && etInput != null && currentMode == MODE_INPUT) {
                    etInput.requestFocus();
                    showKeyboard();
                }
            }, 200);
        }
    }

    // -------------------- 核心修复：录音按钮逻辑 --------------------
    private void setupRecordingAnimation() {
        ivRecordCircle.setOnTouchListener((v, event) -> {
            if (isRecognizing) return true;
            
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (!hasRecordPermission) {
                        Toast.makeText(context, "请先授予录音权限", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    
                    if (voiceRecognitionTool.isModelLoaded()) {
                        // 检查线程状态，确保无残留线程
                        if (!isRecording && !voiceRecognitionTool.isThreadRunning()) {
                            voiceRecognitionTool.startRecording();
                        } else {
                            Toast.makeText(context, "当前有未完成的操作，请稍后", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    } else {
                        Toast.makeText(context, "语音识别模型加载中，请稍后", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isRecording) {
                        voiceRecognitionTool.stopRecording();
                    }
                    break;
            }
            return true;
        });
    }

    // -------------------- 核心修复：AI消息发送 --------------------
    private void sendMessageToAI(String content) {
        Log.d(TAG, "悬浮窗发送消息: " + content);
        
        // 核心修复：空会话防护
        if (floatWindowSession == null) {
            floatWindowSession = chatDBHelper.getOrCreateFloatWindowSession();
            if (floatWindowSession == null) {
                Toast.makeText(context, "创建会话失败", Toast.LENGTH_SHORT).show();
                return; // 终止执行，防止空指针
            }
        }

        // 获取AI配置
        ConfigBean currentConfig = configDBHelper.getConfigById(floatWindowSession.getConfigId());
        if (currentConfig == null) {
            Toast.makeText(context, "未找到AI配置", Toast.LENGTH_SHORT).show();
            return;
        }

        // 保存用户消息
        ChatRecordBean userRecord = new ChatRecordBean(
                floatWindowSession.getId(),
                0, // 0=用户发送
                "我",
                content,
                System.currentTimeMillis()
        );
        long userRecordId = chatDBHelper.addRecord(userRecord);
        if (userRecordId == -1) {
            Toast.makeText(context, "消息保存失败", Toast.LENGTH_SHORT).show();
            return;
        }

        // 【完全保留你原来的EventBus逻辑】不修改事件类型，只修复AI回复内容传递
        EventBus.getInstance().post(
                MessageSender.EVENT_AI_RESPONSE_RECEIVED, 
                new MessageSender.EventMessage(floatWindowSession.getId(), content)
        );

        // UI更新
        mainHandler.post(() -> {
            if (etInput != null) {
                etInput.setText("");
            }
            Toast.makeText(context, "消息已发送，等待AI回复...", Toast.LENGTH_SHORT).show();
            hideKeyboard();
            switchMode(MODE_VOICE, false);
            resetAllStates();
        });

        // 异步调用AI接口
        new Thread(() -> {
            String aiReply = generateAIResponse(content, currentConfig);
            
            // 保存AI回复
            ChatRecordBean aiRecord = new ChatRecordBean(
                    floatWindowSession.getId(),
                    1, // 1=AI回复
                    currentConfig.getType(),
                    aiReply,
                    System.currentTimeMillis()
            );
            chatDBHelper.addRecord(aiRecord);

            // 【核心修复】只修改传递的内容为aiReply，事件类型完全保留
            EventBus.getInstance().post(
                    MessageSender.EVENT_AI_RESPONSE_RECEIVED,
                    new MessageSender.EventMessage(floatWindowSession.getId(), aiReply) // 修复：传递aiReply而非content
            );

            // 显示AI回复弹窗
            mainHandler.post(() -> showAIResponsePopup(aiReply));
        }).start();
    }

    // -------------------- 核心修复：AI回复弹窗 --------------------
    private void showAIResponsePopup(String aiReply) {
        View popupView = LayoutInflater.from(context).inflate(R.layout.float_ai_response_popup, null);
        TextView tvAiResponse = popupView.findViewById(R.id.tv_ai_response);
        Button btnClose = popupView.findViewById(R.id.btn_close_popup);
        Button btnViewFull = popupView.findViewById(R.id.btn_view_full);

        // 设置回复内容
        tvAiResponse.setText(aiReply);

        // 内容长度判断
        boolean isContentExceed = aiReply.length() > RESPONSE_LENGTH_THRESHOLD;
        btnViewFull.setVisibility(isContentExceed ? View.VISIBLE : View.GONE);

        // 弹窗参数配置
        WindowManager.LayoutParams popupParams = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            popupParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            popupParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        popupParams.format = PixelFormat.RGBA_8888;
        
        // 核心修复：添加FLAG_NOT_FOCUSABLE，使ACTION_OUTSIDE生效
        popupParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        
        popupParams.gravity = Gravity.TOP | Gravity.START;
        
        // 核心修复：弹窗位置边界校验
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int maxX = metrics.widthPixels - POPUP_WIDTH_RESERVE;
        popupParams.x = Math.min(layoutParams.x + 200, maxX);
        popupParams.y = layoutParams.y;
        
        popupParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        popupParams.height = WindowManager.LayoutParams.WRAP_CONTENT;

        // 添加弹窗
        try {
            windowManager.addView(popupView, popupParams);
        } catch (Exception e) {
            Log.e(TAG, "添加AI回复弹窗失败", e);
            return;
        }

        // 超时关闭回调
        Runnable closeRunnable = () -> {
            if (popupView.isAttachedToWindow()) {
                try {
                    windowManager.removeView(popupView);
                } catch (Exception e) {
                    Log.e(TAG, "关闭弹窗失败", e);
                }
            }
        };
        mainHandler.postDelayed(closeRunnable, POPUP_AUTO_CLOSE_DELAY);

        // 关闭按钮（核心修复：移除超时回调）
        btnClose.setOnClickListener(v -> {
            mainHandler.removeCallbacks(closeRunnable); // 移除超时回调
            if (popupView.isAttachedToWindow()) {
                try {
                    windowManager.removeView(popupView);
                } catch (Exception e) {
                    Log.e(TAG, "手动关闭弹窗失败", e);
                }
            }
        });

        // 查看完整会话
        btnViewFull.setOnClickListener(v -> {
            mainHandler.removeCallbacks(closeRunnable); // 移除超时回调
            if (popupView.isAttachedToWindow()) {
                windowManager.removeView(popupView);
            }
            Intent intent = new Intent(context, ChatActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("TARGET_SESSION_NAME", "悬浮窗会话");
            context.startActivity(intent);
        });

        // 外部点击关闭
        popupView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                mainHandler.removeCallbacks(closeRunnable); // 移除超时回调
                if (popupView.isAttachedToWindow()) {
                    try {
                        windowManager.removeView(popupView);
                    } catch (Exception e) {
                        Log.e(TAG, "外部点击关闭弹窗失败", e);
                    }
                }
                return true;
            }
            return false;
        });
    }

    // -------------------- 工具方法 --------------------
    private String generateAIResponse(String userInput, ConfigBean config) {
        if (config == null) {
            return "[错误] 未配置AI服务";
        }
        
        BaseNetHelper netHelper = null;
        try {
            netHelper = NetHelperFactory.createNetHelper(context, config);
        } catch (Exception e) {
            return "[错误] 创建网络助手失败: " + e.getMessage();
        }
        
        if (netHelper == null) {
            return "[错误] 不支持的AI服务类型：" + config.getType();
        }
        
        try {
            String response = netHelper.sendMessage(userInput);
            
            if (response == null || response.trim().isEmpty()) {
                return "[错误] AI返回空响应";
            }
            
            if (response.startsWith("[错误]") || response.startsWith("[配置错误]")) {
                return response;
            }
            
            return response;
            
        } catch (Exception e) {
            e.printStackTrace();
            return "[错误] AI服务异常：" + e.getMessage();
        }
    }

    private void setupInputModeButtons() {
        ImageView copyBtn = floatView.findViewById(R.id.iv_copy);
        if (copyBtn != null) {
            copyBtn.setOnClickListener(v -> {
                if (etInput != null) {
                    String text = etInput.getText().toString();
                    if (!TextUtils.isEmpty(text)) {
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("AI输入", text);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        ImageView pasteBtn = floatView.findViewById(R.id.iv_paste);
        if (pasteBtn != null) {
            pasteBtn.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard.hasPrimaryClip()) {
                    ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                    String text = item.getText().toString();
                    if (etInput != null) {
                        etInput.setText(text);
                        etInput.setSelection(text.length());
                    }
                }
            });
        }

        ImageView sendBtn = floatView.findViewById(R.id.iv_send);
        if (sendBtn != null) {
            sendBtn.setOnClickListener(v -> {
                if (etInput != null) {
                    String text = etInput.getText().toString();
                    if (!TextUtils.isEmpty(text)) {
                        sendMessageToAI(text);
                    } else {
                        Toast.makeText(context, "请输入内容", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (etInput != null) {
            etInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    String text = etInput.getText().toString();
                    if (!TextUtils.isEmpty(text)) {
                        sendMessageToAI(text);
                        return true;
                    }
                }
                return false;
            });
        }
    }

    private void showKeyboard() {
        if (floatView == null || etInput == null || !floatView.isAttachedToWindow()) return;
        
        try {
            // 核心修复：重置软键盘模式
            layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE |
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
            
            windowManager.updateViewLayout(floatView, layoutParams);
            etInput.requestFocus();
            
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Exception e) {
            Log.e(TAG, "显示键盘失败", e);
        }
    }

    private void hideKeyboard() {
        if (etInput == null) return;
        
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
        }
        
        try {
            layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
            if (floatView != null && floatView.isAttachedToWindow()) {
                windowManager.updateViewLayout(floatView, layoutParams);
            }
        } catch (Exception e) {
            Log.e(TAG, "隐藏键盘失败", e);
        }
    }

    private void resetAllStates() {
        Log.d(TAG, "执行状态重置");

        // 先取消可能正在进行的识别
        if (voiceRecognitionTool != null) {
            if (isRecording || isRecognizing) {
                Log.w(TAG, "强制终止正在进行的录音/识别");
                voiceRecognitionTool.cancelRecognition();
            }
        }

        // 重置本地状态
        isRecording = false;
        isRecognizing = false;
        recordingStartTime = 0;
        isDragging = false;

        // 启用按钮
        setModeButtonsEnabled(true);

        // 重置UI
        if (ivRecordCircle != null) {
            ivRecordCircle.setBackgroundResource(R.drawable.circle_background);
        }

        // 重置输入框
        if (etInput != null) {
            etInput.clearFocus();
        }

        // 隐藏键盘
        hideKeyboard();

        // 延迟重置有效结果标记
        mainHandler.postDelayed(() -> hasValidRecognitionResult = false, 500);
    }

    /**
     * 只重置识别相关状态，不影响输入模式
     * 注意：调用者负责取消正在进行的识别
     */
    private void resetRecognitionStatesOnly() {
        Log.d(TAG, "执行识别状态重置（仅识别相关）");

        // 重置识别相关状态（不取消识别，调用者负责）
        isRecording = false;
        isRecognizing = false;
        recordingStartTime = 0;

        // 重置录音圈UI
        if (ivRecordCircle != null) {
            ivRecordCircle.setBackgroundResource(R.drawable.circle_background);
        }

        // 延迟重置有效结果标记
        mainHandler.postDelayed(() -> hasValidRecognitionResult = false, 500);

        Log.d(TAG, "识别状态重置完成");
    }

    private void setModeButtonsEnabled(boolean enabled) {
        if (ivVoiceMode != null) ivVoiceMode.setEnabled(enabled);
        if (ivInputMode != null) ivInputMode.setEnabled(enabled);
        if (ivRecordCircle != null) ivRecordCircle.setEnabled(enabled);
        if (ivExpand != null) ivExpand.setEnabled(enabled);
        Log.d(TAG, "模式按钮启用状态: " + enabled);
    }

    private void toggleExpand() {
        Toast.makeText(context, "展开/收起功能", Toast.LENGTH_SHORT).show();
    }

    private boolean isAlreadyInChatActivity() {
        try {
            ActivityManager activityManager = 
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            
            if (activityManager != null) {
                List<ActivityManager.AppTask> tasks = activityManager.getAppTasks();
                if (tasks != null) {
                    for (ActivityManager.AppTask task : tasks) {
                        ActivityManager.RecentTaskInfo taskInfo = task.getTaskInfo();
                        if (taskInfo != null && taskInfo.baseActivity != null) {
                            String activityName = taskInfo.baseActivity.getClassName();
                            if (activityName.contains("ChatActivity")) {
                                return true;
                            }
                        }
                    }
                }
            }
            
            return isActivityRunning();
            
        } catch (Exception e) {
            Log.e(TAG, "检查ChatActivity状态失败", e);
            return false;
        }
    }

    private boolean isActivityRunning() {
        try {
            ActivityManager am = 
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                List<ActivityManager.AppTask> tasks = am.getAppTasks();
                for (ActivityManager.AppTask task : tasks) {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (info != null) {
                        ComponentName component = info.baseIntent.getComponent();
                        if (component != null &&
                            component.getPackageName().equals("alin.android.alinos") &&
                            component.getClassName().equals("alin.android.alinos.ChatActivity")) {
                            return true;
                        }
                    }
                }
            } else {
                List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(10);
                for (ActivityManager.RunningTaskInfo task : tasks) {
                    if (task.baseActivity != null &&
                        task.baseActivity.getPackageName().equals("alin.android.alinos") &&
                        task.baseActivity.getClassName().equals("alin.android.alinos.ChatActivity")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查Activity运行状态失败", e);
        }
        return false;
    }

    // -------------------- 核心修复：资源释放 --------------------
    public void release() {
        hideFloatWindow();
        
        // 终止语音识别
        if (voiceRecognitionTool != null) {
            voiceRecognitionTool.cancelRecognition();
            voiceRecognitionTool.release();
        }
        
        // 关闭数据库
        if (chatDBHelper != null) {
            chatDBHelper.close();
            chatDBHelper = null;
        }
        if (configDBHelper != null) {
            configDBHelper.close();
            configDBHelper = null;
        }
        
        // 移除所有Handler回调
        mainHandler.removeCallbacksAndMessages(null);
        
        // 清空静态实例
        instance = null;
        
        Log.d(TAG, "资源已完全释放");
    }

    // -------------------- 对外接口 --------------------
    public boolean isShowing() {
        return isShowing;
    }
    
    public void switchModel(String modelName) {
        this.currentModel = modelName;
        if (voiceRecognitionTool != null) {
            resetAllStates();
            voiceRecognitionTool.release();
            voiceRecognitionTool.initializeModel(modelName, null);
        }
    }
    
    public String getCurrentModel() {
        return currentModel;
    }
    
    public boolean hasRecordPermission() {
        return hasRecordPermission;
    }
}