package alin.android.alinos;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.termux.app.TermuxApplication;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.util.Map;

import alin.android.alinos.tools.TerminalTermuxExecutor;

public class TermuxShellTestActivity extends AppCompatActivity implements ServiceConnection {

    // ---- 常量 ----
    private static final String DEFAULT_SESSION_ID = "default";

    // ---- UI ----
    private View mEnvIndicator;
    private TextView mTvEnvStatus;
    private RadioGroup mRgMode;
    private CardView mCardSession;
    private EditText mEtSessionId;
    private Button mBtnNewSession;
    private Button mBtnConnectSession;
    private Button mBtnCloseSession;
    private TextView mTvActiveSessions;
    private CardView mCardKeypad;
    private Button mBtnKeyUp, mBtnKeyDown, mBtnKeyLeft, mBtnKeyRight;
    private Button mBtnKeyTab, mBtnKeyEsc, mBtnKeyEnter;
    private Button mBtnKeyCtrl, mBtnKeyAlt, mBtnKeyFn;
    private Button mBtnKeyHome, mBtnKeyEnd, mBtnKeyPgUp, mBtnKeyPgDn;
    private Button mBtnKeyDel, mBtnKeyBksp;
    private EditText mEtCommand;
    private Button mBtnInsert;
    private TextView mTvResult;
    private ScrollView mSvResult;

    // ---- 核心状态 ----
    private TermuxService mTermuxService;
    private boolean mServiceBound = false;
    private TerminalTermuxExecutor mExecutor;
    private String mCurrentSessionId = DEFAULT_SESSION_ID;
    private boolean mInputFocused = false;
    private boolean mIsTermuxReady = false;

    // ---- 修饰键 ----
    private boolean mCtrlActive = false;
    private boolean mAltActive = false;
    private boolean mFnActive = false;

    // ---- 屏幕轮询（永久模式结构化画布实时刷新） ----
    private final Handler mScreenPollHandler = new Handler(Looper.getMainLooper());
    private boolean mScreenPolling = false;
    private static final long SCREEN_POLL_INTERVAL_MS = 250;
    private final Runnable mScreenPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mScreenPolling || mExecutor == null) return;
            if (mRgMode.getCheckedRadioButtonId() != R.id.rb_perm) {
                mScreenPollHandler.postDelayed(this, SCREEN_POLL_INTERVAL_MS);
                return;
            }
            String styled = mExecutor.getSessionStyledScreen(mCurrentSessionId, true);
            if (styled != null) updateStructuredOutput(styled);
            mScreenPollHandler.postDelayed(this, SCREEN_POLL_INTERVAL_MS);
        }
    };

    // ================================================================
    //  ServiceConnection
    // ================================================================

    private void doBindService() {
        if (mServiceBound) return;
        Intent intent = new Intent(this, TermuxService.class);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    private void doUnbindService() {
        if (mServiceBound) {
            unbindService(this);
            mServiceBound = false;
        }
        mTermuxService = null;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        mTermuxService = ((TermuxService.LocalBinder) service).service;
        mServiceBound = true;
        initExecutor();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mTermuxService = null;
        mServiceBound = false;
    }

    // ================================================================
    //  Lifecycle
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux_shell_test);

        TermuxApplication.init(this);
        TermuxShellEnvironment.init(this);

        bindViews();
        checkTermuxEnvironment();
        doBindService();
    }

    @Override
    protected void onStop() {
        stopScreenPolling();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        doUnbindService();
        stopScreenPolling();
        super.onDestroy();
    }

    // ================================================================
    //  初始化
    // ================================================================

    private void initExecutor() {
        if (!mServiceBound || !mIsTermuxReady) return;

        // 获取全局单例，sessionPool 在进程内一直保留
        mExecutor = TerminalTermuxExecutor.getInstance();
        boolean ready = mExecutor.init(this, mTermuxService);
        if (!ready) return;

        // 仅当会话池中尚无 default 会话时才创建，避免 Activity 重建后重复创建
        Map<String, Boolean> status = mExecutor.getSessionStatus();
        if (!Boolean.TRUE.equals(status.get(DEFAULT_SESSION_ID))) {
            mExecutor.createPersistentSession(DEFAULT_SESSION_ID);
        }
        mCurrentSessionId = DEFAULT_SESSION_ID;
        // 初始刷新画布
        String screen = mExecutor.getSessionStyledScreen(DEFAULT_SESSION_ID, true);
        if (screen != null && !screen.isEmpty()) {
            setResultText(screen);
        }
        startScreenPolling();
        refreshSessionInfo();
    }

    private void checkTermuxEnvironment() {
        new Thread(() -> {
            boolean ready = new java.io.File(TermuxConstants.TERMUX_PREFIX_DIR_PATH).isDirectory();
            runOnUiThread(() -> {
                mIsTermuxReady = ready;
                if (ready) {
                    mEnvIndicator.setBackgroundResource(R.drawable.circle_dot_green);
                    mTvEnvStatus.setText("Termux 环境已就绪");
                    mTvEnvStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
                    mBtnInsert.setEnabled(true);
                    setResultText("环境正常，请选择模式后输入命令");
                    mIsTermuxReady = true;
                    initExecutor(); // 若 Service 已连接则完成初始化
                } else {
                    mEnvIndicator.setBackgroundResource(R.drawable.circle_dot_red);
                    mTvEnvStatus.setText("Termux 环境未初始化");
                    mTvEnvStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
                    mBtnInsert.setEnabled(false);
                    setResultText("Termux 尚未完成初始化，请先在「Agent Main」中完成。\n"
                            + "预期路径: " + TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                }
            });
        }).start();
    }

    // ================================================================
    //  UI Binding
    // ================================================================

    private void bindViews() {
        mEnvIndicator     = findViewById(R.id.view_env_indicator);
        mTvEnvStatus      = findViewById(R.id.tv_env_status);
        mRgMode           = findViewById(R.id.rg_mode);
        mCardSession      = findViewById(R.id.card_session);
        mEtSessionId      = findViewById(R.id.et_session_id);
        mBtnNewSession    = findViewById(R.id.btn_new_session);
        mBtnConnectSession= findViewById(R.id.btn_connect_session);
        mBtnCloseSession  = findViewById(R.id.btn_close_session);
        mTvActiveSessions = findViewById(R.id.tv_active_sessions);

        mCardKeypad       = findViewById(R.id.card_keypad);
        mBtnKeyUp         = findViewById(R.id.btn_key_up);
        mBtnKeyDown       = findViewById(R.id.btn_key_down);
        mBtnKeyLeft       = findViewById(R.id.btn_key_left);
        mBtnKeyRight      = findViewById(R.id.btn_key_right);
        mBtnKeyTab        = findViewById(R.id.btn_key_tab);
        mBtnKeyEsc        = findViewById(R.id.btn_key_esc);
        mBtnKeyEnter      = findViewById(R.id.btn_key_enter);
        mBtnKeyCtrl       = findViewById(R.id.btn_key_ctrl);
        mBtnKeyAlt        = findViewById(R.id.btn_key_alt);
        mBtnKeyFn         = findViewById(R.id.btn_key_fn);
        mBtnKeyHome       = findViewById(R.id.btn_key_home);
        mBtnKeyEnd        = findViewById(R.id.btn_key_end);
        mBtnKeyPgUp       = findViewById(R.id.btn_key_pgup);
        mBtnKeyPgDn       = findViewById(R.id.btn_key_pgdn);
        mBtnKeyDel        = findViewById(R.id.btn_key_del);
        mBtnKeyBksp       = findViewById(R.id.btn_key_bksp);

        mEtCommand        = findViewById(R.id.et_command);
        mBtnInsert        = findViewById(R.id.btn_test);
        mTvResult         = findViewById(R.id.tv_result);
        mSvResult         = findViewById(R.id.sv_result);
        mTvResult.setTextIsSelectable(true);

        // ── 模式切换 ──
        mRgMode.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isPerm = checkedId == R.id.rb_perm;
            mCardKeypad.setVisibility(isPerm ? View.VISIBLE : View.GONE);
            mBtnInsert.setText(isPerm ? "插入" : "发送");
            if (!isPerm) {
                stopScreenPolling();
            } else {
                startScreenPolling();
            }
            updateSessionCardVisibility();
            refreshSessionInfo();
        });

        // 初始状态：临时模式
        mBtnInsert.setText("发送");

        // ── 会话管理 ──
        mBtnNewSession.setOnClickListener(v -> createNewSession());
        mBtnConnectSession.setOnClickListener(v -> connectSession());
        mBtnCloseSession.setOnClickListener(v -> closeCurrentSession());

        // ── 方向编辑键 ──
        mBtnKeyUp.setOnClickListener(v -> sendKeyToSession("\033[A"));
        mBtnKeyDown.setOnClickListener(v -> sendKeyToSession("\033[B"));
        mBtnKeyLeft.setOnClickListener(v -> sendKeyToSession("\033[D"));
        mBtnKeyRight.setOnClickListener(v -> sendKeyToSession("\033[C"));

        // ── 页控定位键 ──
        mBtnKeyHome.setOnClickListener(v -> sendKeyToSession("\033[H"));
        mBtnKeyEnd.setOnClickListener(v -> sendKeyToSession("\033[F"));
        mBtnKeyPgUp.setOnClickListener(v -> sendKeyToSession("\033[5~"));
        mBtnKeyPgDn.setOnClickListener(v -> sendKeyToSession("\033[6~"));

        // ── 基础功能键 ──
        mBtnKeyTab.setOnClickListener(v -> sendKeyToSession("\t"));
        mBtnKeyEsc.setOnClickListener(v -> sendKeyToSession("\033"));
        mBtnKeyDel.setOnClickListener(v -> sendKeyToSession("\033[3~"));
        mBtnKeyBksp.setOnClickListener(v -> sendKeyToSession("\177"));
        mBtnKeyEnter.setOnClickListener(v -> sendKeyToSession("\r"));

        // ── 修饰键 ──
        mBtnKeyCtrl.setOnClickListener(v -> toggleModifier("ctrl"));
        mBtnKeyAlt.setOnClickListener(v -> toggleModifier("alt"));
        mBtnKeyFn.setOnClickListener(v -> toggleModifier("fn"));

        // ── 执行 ──
        mBtnInsert.setOnClickListener(v -> onInsertClick());

        // ── 输入框焦点 ──
        mEtCommand.setOnFocusChangeListener((v, hasFocus) -> {
            mInputFocused = hasFocus;
            updateSessionCardVisibility();
            if (hasFocus) scrollResultToBottom();
        });
    }

    // ================================================================
    //  永久模式：插入文本 / 按键
    // ================================================================

    private void onInsertClick() {
        String text = mEtCommand.getText().toString();
        if (TextUtils.isEmpty(text)) return;
        if (!mIsTermuxReady) {
            setResultText("Termux 环境未就绪");
            return;
        }
        if (mRgMode.getCheckedRadioButtonId() == R.id.rb_perm) {
            handlePermanentInsert(text);
        } else {
            executeTemporary(text);
        }
    }

    /** 永久模式插入文本 */
    private void handlePermanentInsert(String text) {
        // 修饰键组合：仅取首字符
        if (mCtrlActive || mAltActive || mFnActive) {
            String firstChar = text.substring(0, 1);
            String mapped = applyModifierMapping(firstChar);
            clearModifiers();
            sendKeyToSession(mapped);
            mEtCommand.setText("");
            return;
        }

        // 在后台线程发送文本，发完后取回最后 20 行画面
        mEtCommand.setText("");
        final String cmd = text;
        new Thread(() -> {
            String result = mExecutor.execPersistentShell(
                    mCurrentSessionId, cmd, false, null, 200, 20, false, true);
            if (result != null && !result.isEmpty()) {
                runOnUiThread(() -> updateStructuredOutput(result));
            }
        }).start();
        scrollResultToBottom();
    }

    /** 向当前会话发送按键序列（后台线程） */
    private void sendKeyToSession(String sequence) {
        if (mExecutor == null) return;
        // 应用修饰键映射
        sequence = applyModifierMapping(sequence);
        final String seq = sequence;
        new Thread(() -> {
            String result = mExecutor.execPersistentShell(
                    mCurrentSessionId, "", false, seq, 80, 20, false, true);
            if (result != null && !result.isEmpty()) {
                runOnUiThread(() -> updateStructuredOutput(result));
            }
        }).start();
    }

    // ================================================================
    //  永久模式：会话管理
    // ================================================================

    private void createNewSession() {
        if (!mIsTermuxReady || mExecutor == null) return;
        String rawSid = mEtSessionId.getText().toString().trim();
        if (TextUtils.isEmpty(rawSid)) rawSid = DEFAULT_SESSION_ID;
        final String sid = rawSid;

        mExecutor.createPersistentSession(sid);
        mCurrentSessionId = sid;
        // 刷新画布
        String screen = mExecutor.getSessionStyledScreen(sid, true);
        if (screen != null && !screen.isEmpty()) setResultText(screen);
        refreshSessionInfo();
        startScreenPolling();
        appendResult("会话 " + sid + " 已创建" + (sid.equals(DEFAULT_SESSION_ID) ? "（默认）" : ""));
    }

    private void connectSession() {
        String sid = mEtSessionId.getText().toString().trim();
        if (TextUtils.isEmpty(sid)) {
            appendResult("请输入要连接的会话ID");
            return;
        }
        Map<String, Boolean> status = mExecutor.getSessionStatus();
        Boolean alive = status.get(sid);
        if (alive != null && alive) {
            mCurrentSessionId = sid;
            String screen = mExecutor.getSessionStyledScreen(sid, true);
            if (screen != null && !screen.isEmpty()) setResultText(screen);
            refreshSessionInfo();
            startScreenPolling();
            appendResult("已连接到会话 " + sid);
        } else {
            appendResult("会话 " + sid + " 不存在或已关闭");
        }
    }

    private void closeCurrentSession() {
        if (mExecutor == null) return;
        mExecutor.closePersistentSession(mCurrentSessionId);
        mCurrentSessionId = DEFAULT_SESSION_ID;
        // 确保默认会话存在
        mExecutor.createPersistentSession(DEFAULT_SESSION_ID);
        refreshSessionInfo();
        appendResult("当前会话已关闭，已切换至 default");
    }

    private void refreshSessionInfo() {
        if (mExecutor == null) return;
        Map<String, Boolean> status = mExecutor.getSessionStatus();
        StringBuilder sb = new StringBuilder();
        int aliveCount = 0;
        for (Map.Entry<String, Boolean> e : status.entrySet()) {
            if (e.getValue()) {
                aliveCount++;
                boolean isCurrent = mCurrentSessionId != null
                        && mCurrentSessionId.equals(e.getKey());
                sb.append(isCurrent ? "▶ " : "  ").append(e.getKey()).append("\n");
            }
        }
        mTvActiveSessions.setText(aliveCount == 0
                ? "当前无活跃会话"
                : sb.toString().trim());
    }

    // ================================================================
    //  临时模式：一次性 bash -c 调用
    // ================================================================

    private void executeTemporary(String command) {
        mBtnInsert.setEnabled(false);
        new Thread(() -> {
            String result = mExecutor.execTemporaryShell(command, false);
            runOnUiThread(() -> {
                appendResult(result);
                mBtnInsert.setEnabled(true);
                mBtnInsert.setText("发送");
            });
        }).start();
    }

    // ================================================================
    //  画面更新
    // ================================================================

    private void updateStructuredOutput(String styledText) {
        if (mRgMode.getCheckedRadioButtonId() != R.id.rb_perm) return;
        if (mTvResult.hasSelection()) return;
        String current = mTvResult.getText().toString();
        if (styledText != null && !styledText.equals(current)) {
            mTvResult.setText(styledText);
            scrollResultToBottom();
        }
    }

    private void setResultText(String text) {
        mTvResult.setText(text);
        scrollResultToBottom();
    }

    private void appendResult(String text) {
        String prev = mTvResult.getText().toString();
        if (prev.equals("等待测试...") || prev.startsWith("环境正常")) {
            setResultText(text);
        } else {
            setResultText(prev + "\n\n" + text);
        }
    }

    private void scrollResultToBottom() {
        if (mSvResult == null || mSvResult.getChildCount() == 0) return;
        mSvResult.post(() -> {
            int scrollTarget = Math.max(0,
                    mSvResult.getChildAt(0).getHeight() - mSvResult.getHeight());
            mSvResult.scrollTo(0, scrollTarget);
        });
    }

    // ================================================================
    //  屏幕轮询
    // ================================================================

    private void startScreenPolling() {
        if (mScreenPolling) return;
        mScreenPolling = true;
        mScreenPollHandler.post(mScreenPollRunnable);
    }

    private void stopScreenPolling() {
        mScreenPolling = false;
        mScreenPollHandler.removeCallbacks(mScreenPollRunnable);
    }

    // ================================================================
    //  修饰键
    // ================================================================

    private String applyModifierMapping(String seq) {
        if (mAltActive) seq = "\033" + seq;
        if (mCtrlActive && seq.length() == 1) {
            char ch = seq.charAt(0);
            if (ch >= 'a' && ch <= 'z') {
                seq = String.valueOf((char) (ch - 'a' + 1));
            } else if (ch >= 'A' && ch <= 'Z') {
                seq = String.valueOf((char) (ch - 'A' + 1));
            }
        }
        return seq;
    }

    private void toggleModifier(String mod) {
        boolean active;
        int bgColor, fgColor;
        Button btn;
        switch (mod) {
            case "ctrl":
                mCtrlActive = !mCtrlActive; active = mCtrlActive;
                bgColor = active ? 0xFF42A5F5 : 0xFFE0E0E0;
                fgColor = active ? 0xFFFFFFFF : 0xFF333333;
                btn = mBtnKeyCtrl; break;
            case "alt":
                mAltActive = !mAltActive; active = mAltActive;
                bgColor = active ? 0xFFFFA726 : 0xFFE0E0E0;
                fgColor = active ? 0xFFFFFFFF : 0xFF333333;
                btn = mBtnKeyAlt; break;
            case "fn":
                mFnActive = !mFnActive; active = mFnActive;
                bgColor = active ? 0xFFAB47BC : 0xFFE0E0E0;
                fgColor = active ? 0xFFFFFFFF : 0xFF333333;
                btn = mBtnKeyFn; break;
            default: return;
        }
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bgColor));
        btn.setTextColor(fgColor);
    }

    private void clearModifiers() {
        mCtrlActive = false;
        mAltActive = false;
        mFnActive = false;
        mBtnKeyCtrl.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFE0E0E0));
        mBtnKeyCtrl.setTextColor(0xFF333333);
        mBtnKeyAlt.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFE0E0E0));
        mBtnKeyAlt.setTextColor(0xFF333333);
        mBtnKeyFn.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFE0E0E0));
        mBtnKeyFn.setTextColor(0xFF333333);
    }

    // ── 会话管理卡片可见性 ──
    private void updateSessionCardVisibility() {
        boolean isPerm = mRgMode.getCheckedRadioButtonId() == R.id.rb_perm;
        mCardSession.setVisibility(isPerm && !mInputFocused ? View.VISIBLE : View.GONE);
    }
}
