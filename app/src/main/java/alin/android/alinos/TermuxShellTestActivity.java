package alin.android.alinos;

import android.os.Build;
import android.os.Bundle;
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

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class TermuxShellTestActivity extends AppCompatActivity {

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
    private Button mBtnKeyDel;
    private Button mBtnKeyBksp;
    private EditText mEtCommand;
    private Button mBtnInsert;
    private TextView mTvResult;
    private ScrollView mSvResult;

    // ---- State ----
    private boolean mIsTermuxReady = false;
    private TermuxShellEnvironment mTermuxEnv;

    // ---- Modifier Keys (toggle state) ----
    private boolean mCtrlActive = false;
    private boolean mAltActive = false;
    private boolean mFnActive = false;
    private boolean mInputFocused = false; // 输入框焦点状态独立追踪

    // ---- Persistent Sessions ----
    private static final HashMap<String, PtySession> sSessions = new HashMap<>();
    private PtySession mCurrentSession;

    /** 终端屏幕状态变更回调（用于结构化输出实时刷新） */
    interface ScreenCallback {
        /** 后台线程调用，携带当前完整终端画布的结构化文本 */
        void onScreenUpdated(String styledText);
    }

    // ================================================================
    //  PtySession — 基于 PTY (TerminalSession) 的长驻 bash 会话
    //  TerminalSession 通过 JNI 创建伪终端，自动读取输出并解析 ANSI，
    //  无需手动管理 stdin/stdout 管道。
    // ================================================================
    static class PtySession {
        final String id;
        final TerminalSession session;
        final TerminalEmulator emulator;

        private volatile ScreenCallback screenCallback;

        /** 设置结构化画布更新回调（在 onTextChanged 主线程中触发） */
        void setScreenCallback(ScreenCallback cb) { this.screenCallback = cb; }

        private final TerminalSessionClient sessionClient = new TerminalSessionClient() {
            @Override public void onTextChanged(TerminalSession s) {
                if (screenCallback != null) screenCallback.onScreenUpdated(dumpStyledScreen());
            }
            @Override public void onSessionFinished(TerminalSession s) {
                if (screenCallback != null) screenCallback.onScreenUpdated(dumpStyledScreen());
            }
            @Override public void onTitleChanged(TerminalSession s) {}
            @Override public void onCopyTextToClipboard(TerminalSession s, String t) {}
            @Override public void onPasteTextFromClipboard(TerminalSession s) {}
            @Override public void onBell(TerminalSession s) {}
            @Override public void onColorsChanged(TerminalSession s) {}
            @Override public void onTerminalCursorStateChange(boolean state) {}
            @Override public void setTerminalShellPid(TerminalSession s, int pid) {}
            @Override public Integer getTerminalCursorStyle() { return null; }
            @Override public void logError(String t, String m) {}
            @Override public void logWarn(String t, String m) {}
            @Override public void logInfo(String t, String m) {}
            @Override public void logDebug(String t, String m) {}
            @Override public void logVerbose(String t, String m) {}
            @Override public void logStackTraceWithMessage(String t, String m, Exception e) {}
            @Override public void logStackTrace(String t, Exception e) {}
        };

        PtySession(String id, String shellPath, String cwd, String[] args, String[] env) {
            this.id = id;
            this.session = new TerminalSession(shellPath, cwd, args, env,
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, sessionClient);
            // updateSize() 触发 initializeEmulator() → JNI.createSubprocess() 创建 PTY + 子进程
            this.session.updateSize(80, 24);
            this.emulator = session.getEmulator();
        }

        // ================================================================
        //  输入
        // ================================================================

        /** 发送按键序列（通过 PTY 传递给 bash/交互程序），非阻塞 */
        void sendKey(String sequence) {
            if (!session.isRunning()) return;
            session.write(sequence);
        }

        /** 向终端写入命令（非阻塞——仅写入 PTY，输出通过 ScreenCallback 异步到达） */
        void execute(String command) {
            if (!session.isRunning()) return;
            session.write(command + "\n");
        }

        boolean isAlive() { return session != null && session.isRunning(); }

        /** 强制触发屏幕内容刷新（切换模式时调用） */
        void refreshScreen() {
            if (screenCallback != null) screenCallback.onScreenUpdated(dumpStyledScreen());
        }

        synchronized void close() {
            session.finishIfRunning();
        }

        // ================================================================
        //  TerminalEmulator 样式化输出
        // ================================================================

        private static final String[] ANSI_COLOR_NAMES = {
            "黑色", "红色", "绿色", "黄色", "蓝色", "紫色", "青色", "白色",
            "暗灰", "亮红", "亮绿", "亮黄", "亮蓝", "亮紫", "亮青", "亮白"
        };

        private String dumpStyledScreen() {
            if (emulator == null) return "";
            TerminalBuffer screen = emulator.getScreen();
            if (screen == null) return "";

            int transcriptRows = screen.getActiveTranscriptRows();
            int screenRows = screen.getActiveRows() - transcriptRows;
            int cols = screen.getColumns();
            int cursorRow = emulator.getCursorRow();
            int cursorCol = emulator.getCursorCol();

            StringBuilder result = new StringBuilder();
            int displayLine = 0;

            for (int extRow = -transcriptRows; extRow < screenRows; extRow++) {
                TerminalRow row = screen.getRowOrNull(extRow);
                if (row == null) continue;
                if (isRowBlank(row, cols)) {
                    // 空行但光标在此行 → 渲染 █ 占位符
                    if (extRow == cursorRow) {
                        result.append("[").append(displayLine).append("] ");
                        for (int i = 0; i < cursorCol; i++) result.append(' ');
                        result.append("█\n");
                        displayLine++;
                    }
                    continue;
                }

                String rowText = buildRowPlainText(row, cols);
                if (rowText.trim().isEmpty()) continue;

                boolean isCursorRow = (extRow == cursorRow);

                // 如果此行是光标所在行，扫描找出光标所在单词的起止列
                int cursorWordStart = -1, cursorWordEnd = -1;
                boolean cursorInsideWord = false;
                if (isCursorRow) {
                    int c = 0;
                    while (c < cols) {
                        int ci = row.findStartOfColumn(c);
                        char cc = (ci >= 0 && ci < row.mText.length) ? row.mText[ci] : ' ';
                        if (cc != ' ') {
                            int ws = c;
                            while (c < cols) {
                                ci = row.findStartOfColumn(c);
                                cc = (ci >= 0 && ci < row.mText.length) ? row.mText[ci] : ' ';
                                if (cc == ' ') break;
                                c += charWidth(cc);
                            }
                            int we = c;
                            // 光标列在此单词范围内（含末尾）
                            if (cursorCol >= ws && cursorCol <= we) {
                                cursorWordStart = ws;
                                cursorWordEnd = we;
                                cursorInsideWord = (cursorCol < we);
                                break;
                            }
                        } else {
                            c++;
                        }
                    }
                }

                result.append("[").append(displayLine).append("] ");

                StringBuilder segText = new StringBuilder();
                long prevStyle = -1;
                boolean started = false;
                boolean bracketOpened = false;

                for (int col = 0; col < cols; ) {
                    long style = row.getStyle(col);
                    int charIdx = row.findStartOfColumn(col);
                    char ch = (charIdx >= 0 && charIdx < row.mText.length) ? row.mText[charIdx] : ' ';
                    int w = charWidth(ch);

                    if (ch != '\0') {
                        if (started && style != prevStyle) {
                            // 在 style 切换前关闭未闭合的括号
                            if (bracketOpened) { segText.append("]"); bracketOpened = false; }
                            flushStyledSegment(result, segText, prevStyle);
                            segText = new StringBuilder();
                        }
                        started = true;
                        prevStyle = style;

                        // 光标在单词内部且当前列是单词首列 → 开括号
                        if (isCursorRow && cursorInsideWord && col == cursorWordStart) {
                            segText.append("[");
                            bracketOpened = true;
                        }

                        segText.append(ch);

                        // 光标落在空格上 → 替换为 █
                        if (isCursorRow && col == cursorCol && ch == ' ') {
                            segText.setCharAt(segText.length() - 1, '█');
                        }

                        // 光标标注：当前字符右侧即光标位置 → 插入 (标)
                        if (isCursorRow && cursorWordStart >= 0 && ch != ' ') {
                            int nextCol = col + (w > 1 ? w : 1);
                            if (nextCol == cursorCol) {
                                segText.append("(标)");
                            }
                        }

                        // 光标在单词内部且当前列是单词末列 → 关括号
                        if (isCursorRow && bracketOpened) {
                            int nextCol = col + (w > 1 ? w : 1);
                            if (nextCol >= cursorWordEnd) {
                                segText.append("]");
                                bracketOpened = false;
                            }
                        }
                    } else if (isCursorRow && col == cursorCol) {
                        // 光标在空数据 (\0) 上 → 刷出前一段后渲染 █
                        if (started) {
                            flushStyledSegment(result, segText, prevStyle);
                            segText = new StringBuilder();
                            started = false;
                        }
                        prevStyle = style;
                        segText.append('█');
                        flushStyledSegment(result, segText, prevStyle);
                        segText = new StringBuilder();
                    }
                    col += (w > 1) ? w : 1;
                }
                if (bracketOpened) { segText.append("]"); bracketOpened = false; }
                flushStyledSegment(result, segText, prevStyle);

                result.append("\n");
                displayLine++;
            }

            return result.toString();
        }

        private static boolean isRowBlank(TerminalRow row, int cols) {
            for (int col = 0; col < cols; col++) {
                int idx = row.findStartOfColumn(col);
                if (idx >= 0 && idx < row.mText.length && row.mText[idx] != ' ') return false;
            }
            return true;
        }

        private static String buildRowPlainText(TerminalRow row, int cols) {
            StringBuilder sb = new StringBuilder();
            for (int col = 0; col < cols; col++) {
                int idx = row.findStartOfColumn(col);
                if (idx >= 0 && idx < row.mText.length) {
                    char ch = row.mText[idx];
                    if (ch != '\0') sb.append(ch);
                }
            }
            return sb.toString();
        }

        private static int charWidth(char ch) {
            int w = WcWidth.width((int) ch);
            return w < 1 ? 1 : w;
        }

        private static void flushStyledSegment(StringBuilder result, StringBuilder text, long style) {
            String t = text.toString().replaceAll("\\s+$", "");
            if (t.isEmpty()) return;
            result.append("[").append(styleToString(style)).append("] ").append(t).append(" ");
        }

        private static String styleToString(long style) {
            int fg = TextStyle.decodeForeColor(style);
            int effect = TextStyle.decodeEffect(style);

            StringBuilder sb = new StringBuilder();

            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0) sb.append("粗体");
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) {
                if (sb.length() > 0) sb.append("+");
                sb.append("暗");
            }
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0) {
                if (sb.length() > 0) sb.append("+");
                sb.append("斜体");
            }
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) {
                if (sb.length() > 0) sb.append("+");
                sb.append("下划线");
            }
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0) {
                if (sb.length() > 0) sb.append("+");
                sb.append("反色");
            }
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0) {
                if (sb.length() > 0) sb.append("+");
                sb.append("闪烁");
            }
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0) {
                if (sb.length() > 0) sb.append("+");
                sb.append("删除线");
            }

            if (fg >= 0 && fg < ANSI_COLOR_NAMES.length) {
                if (sb.length() > 0) sb.append("|");
                sb.append(ANSI_COLOR_NAMES[fg]);
            } else if (fg != TextStyle.COLOR_INDEX_FOREGROUND) {
                if (sb.length() > 0) sb.append("|");
                sb.append("色").append(fg);
            }

            if (sb.length() == 0) sb.append("默认");
            return sb.toString();
        }
    }

    // ================================================================
    //  Lifecycle
    // ================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux_shell_test);

        TermuxShellEnvironment.init(this);
        mTermuxEnv = new TermuxShellEnvironment();

        bindViews();
        checkTermuxEnvironment();
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

        // 模式切换 —— 显示/隐藏 会话管理区 & 虚拟按键，同步按钮文字
        mRgMode.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isPerm = checkedId == R.id.rb_perm;
            mCardKeypad.setVisibility(isPerm ? View.VISIBLE : View.GONE);
            mBtnInsert.setText(isPerm ? "插入" : "发送");
            setResultText("");
            refreshSessionInfo();
            // 模式变化后统一刷新会话管理可见性（与焦点状态联动）
            updateSessionCardVisibility();

            // 切换到永久模式时自动创建或刷新会话
            if (isPerm && mIsTermuxReady) {
                if (mCurrentSession != null && mCurrentSession.isAlive()) {
                    mCurrentSession.refreshScreen();
                } else {
                    createNewSession();
                }
            }
        });

        // 初始状态：临时模式（默认选中），按钮显示「发送」
        mBtnInsert.setText("发送");
        mBtnNewSession.setOnClickListener(v -> createNewSession());
        mBtnConnectSession.setOnClickListener(v -> connectSession());
        mBtnCloseSession.setOnClickListener(v -> closeCurrentSession());

        // 方向编辑键
        mBtnKeyUp.setOnClickListener(v -> sendKeyToSession("\033[A"));
        mBtnKeyDown.setOnClickListener(v -> sendKeyToSession("\033[B"));
        mBtnKeyLeft.setOnClickListener(v -> sendKeyToSession("\033[D"));
        mBtnKeyRight.setOnClickListener(v -> sendKeyToSession("\033[C"));

        // 页控定位键
        mBtnKeyHome.setOnClickListener(v -> sendKeyToSession("\033[H"));
        mBtnKeyEnd.setOnClickListener(v -> sendKeyToSession("\033[F"));
        mBtnKeyPgUp.setOnClickListener(v -> sendKeyToSession("\033[5~"));
        mBtnKeyPgDn.setOnClickListener(v -> sendKeyToSession("\033[6~"));

        // 基础功能键
        mBtnKeyTab.setOnClickListener(v -> sendKeyToSession("\t"));
        mBtnKeyEsc.setOnClickListener(v -> sendKeyToSession("\033"));
        mBtnKeyDel.setOnClickListener(v -> sendKeyToSession("\033[3~"));

        // BKSP —— 左箭头删除（退格），发送 DEL (0x7f) 字符
        mBtnKeyBksp.setOnClickListener(v -> sendKeyToSession("\177"));

        // Enter —— 真实回车，不经过修饰键映射
        mBtnKeyEnter.setOnClickListener(v -> {
            if (mCurrentSession != null && mCurrentSession.isAlive())
                mCurrentSession.sendKey("\r");
        });

        // 修饰键：点击切换激活状态
        mBtnKeyCtrl.setOnClickListener(v -> toggleModifier("ctrl"));
        mBtnKeyAlt.setOnClickListener(v -> toggleModifier("alt"));
        mBtnKeyFn.setOnClickListener(v -> toggleModifier("fn"));

        // 执行
        mBtnInsert.setOnClickListener(v -> onInsertClick());

        // 输入框焦点监听 —— 独立追踪焦点状态，刷新会话管理可见性
        mEtCommand.setOnFocusChangeListener((v, hasFocus) -> {
            mInputFocused = hasFocus;
            updateSessionCardVisibility();
            if (hasFocus) {
                scrollResultToBottom();
            }
        });
    }

    // ================================================================
    //  环境检测
    // ================================================================
    private void checkTermuxEnvironment() {
        new Thread(() -> {
            boolean ready = FileUtils.directoryFileExists(TermuxConstants.TERMUX_PREFIX_DIR_PATH, true);

            runOnUiThread(() -> {
                mIsTermuxReady = ready;
                if (ready) {
                    mEnvIndicator.setBackgroundResource(R.drawable.circle_dot_green);
                    mTvEnvStatus.setText("Termux 环境已就绪");
                    mTvEnvStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
                    mBtnInsert.setEnabled(true);
                    setResultText("环境正常，请选择模式后输入命令");
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
    //  会话管理
    // ================================================================
    private void createNewSession() {
        if (!mIsTermuxReady) return;
        String rawSid = mEtSessionId.getText().toString().trim();
        if (TextUtils.isEmpty(rawSid)) {
            rawSid = "SESS_" + java.util.UUID.randomUUID().toString().substring(0, 8);
            mEtSessionId.setText(rawSid);
        }

        final String sid = rawSid;

        if (sSessions.containsKey(sid)) {
            appendResult("会话 " + sid + " 已存在，已切换至此会话");
            mCurrentSession = sSessions.get(sid);
            refreshSessionInfo();
            return;
        }

        setResultText("正在创建会话 " + sid + " ...");

        new Thread(() -> {
            try {
                String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                String[] cmdArray = mTermuxEnv.setupShellCommandArguments(bashPath, null);
                String[] envArray = toEnvArray(mTermuxEnv.getEnvironment(this, false));
                String cwd = TermuxConstants.TERMUX_HOME_DIR_PATH;

                final PtySession session = new PtySession(sid, cmdArray[0], cwd, cmdArray, envArray);
                session.setScreenCallback(styled -> runOnUiThread(() -> updateStructuredOutput(styled)));
                synchronized (sSessions) { sSessions.put(sid, session); }
                mCurrentSession = session;

                runOnUiThread(() -> {
                    appendResult("会话 " + sid + " 创建成功");
                    refreshSessionInfo();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendResult("创建会话失败: " + e.getMessage());
                });
            }
        }).start();
    }

    private void connectSession() {
        String sid = mEtSessionId.getText().toString().trim();
        if (TextUtils.isEmpty(sid)) {
            appendResult("请输入要连接的会话ID");
            return;
        }
        PtySession s = sSessions.get(sid);
        if (s == null || !s.isAlive()) {
            appendResult("会话 " + sid + " 不存在或已关闭");
            if (s != null) sSessions.remove(sid);
            return;
        }
        mCurrentSession = s;
        appendResult("已连接到会话 " + sid);
        refreshSessionInfo();
    }

    private void closeCurrentSession() {
        if (mCurrentSession != null) {
            mCurrentSession.close();
            sSessions.remove(mCurrentSession.id);
            String closedId = mCurrentSession.id;
            mCurrentSession = null;
            appendResult("会话 " + closedId + " 已关闭");
            refreshSessionInfo();
        }
    }

    private void refreshSessionInfo() {
        StringBuilder sb = new StringBuilder();
        int aliveCount = 0;
        synchronized (sSessions) {
            for (Map.Entry<String, PtySession> e : sSessions.entrySet()) {
                if (e.getValue().isAlive()) {
                    aliveCount++;
                    boolean isCurrent = mCurrentSession != null && mCurrentSession.id.equals(e.getKey());
                    sb.append(isCurrent ? "▶ " : "  ").append(e.getKey()).append("\n");
                }
            }
        }
        mTvActiveSessions.setText(aliveCount == 0
                ? "当前无活跃会话"
                : sb.toString().trim());
    }

    // ================================================================
    //  虚拟按键 → 当前会话
    // ================================================================
    private void sendKeyToSession(String sequence) {
        if (mCurrentSession == null || !mCurrentSession.isAlive()) {
            appendResult("没有活跃会话，请先新建/连接会话");
            return;
        }
        // 应用修饰键（CTRL/ALT/FN）映射
        sequence = applyModifierMapping(sequence);
        mCurrentSession.sendKey(sequence);
    }

    /** 将修饰键（CTRL/ALT/FN）状态映射到按键序列 */
    private String applyModifierMapping(String seq) {
        if (mAltActive) {
            seq = "\033" + seq; // ALT = 前置 ESC
        }
        // CTRL 修饰：仅作用于单字符字母
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

    // ================================================================
    //  修饰键切换
    // ================================================================
    private void toggleModifier(String mod) {
        boolean active;
        int bgColor, fgColor;
        Button btn;
        switch (mod) {
            case "ctrl":
                mCtrlActive = !mCtrlActive; active = mCtrlActive;
                bgColor = active ? 0xFF42A5F5 : 0xFFE0E0E0; // 蓝 / 灰
                fgColor = active ? 0xFFFFFFFF : 0xFF333333;
                btn = mBtnKeyCtrl; break;
            case "alt":
                mAltActive = !mAltActive; active = mAltActive;
                bgColor = active ? 0xFFFFA726 : 0xFFE0E0E0; // 橙 / 灰
                fgColor = active ? 0xFFFFFFFF : 0xFF333333;
                btn = mBtnKeyAlt; break;
            case "fn":
                mFnActive = !mFnActive; active = mFnActive;
                bgColor = active ? 0xFFAB47BC : 0xFFE0E0E0; // 紫 / 灰
                fgColor = active ? 0xFFFFFFFF : 0xFF333333;
                btn = mBtnKeyFn; break;
            default: return;
        }
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bgColor));
        btn.setTextColor(fgColor);
    }

    /** 清除所有修饰键激活状态并恢复按钮颜色 */
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

    // ================================================================
    //  入口 —— 临时模式「发送」/ 永久模式「插入」
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

    // ================================================================
    //  永久模式 —— 插入文本 / 修饰键组合（支持 PTY 会话）
    // ================================================================
    private void handlePermanentInsert(String text) {
        // ── 修饰键组合：仅取首字符 ──
        if (mCtrlActive || mAltActive || mFnActive) {
            if (mCurrentSession == null || !mCurrentSession.isAlive()) {
                appendResult("没有活跃会话，组合键发送失败");
                return;
            }
            String firstChar = text.substring(0, 1);
            String mapped = applyModifierMapping(firstChar);
            mCurrentSession.sendKey(mapped);
            clearModifiers();
            mEtCommand.setText("");
            scrollResultToBottom();
            return;
        }

        // ── 无活跃会话 → 自动创建 ──
        if (mCurrentSession == null || !mCurrentSession.isAlive()) {
            autoCreateAndSend(text);
            return;
        }

        // ── 纯文本插入（不含回车） ──
        mCurrentSession.sendKey(text);
        mEtCommand.setText("");
        scrollResultToBottom();
    }

    /** 自动创建 PTY 会话，创建后插入文本 */
    private void autoCreateAndSend(String text) {
        String sid = "SESS_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        mEtSessionId.setText(sid);
        setResultText("正在启动会话...");

        new Thread(() -> {
            try {
                String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                String[] cmdArray = mTermuxEnv.setupShellCommandArguments(bashPath, null);
                String[] envArray = toEnvArray(mTermuxEnv.getEnvironment(this, false));
                String cwd = TermuxConstants.TERMUX_HOME_DIR_PATH;

                PtySession session = new PtySession(sid, cmdArray[0], cwd, cmdArray, envArray);
                session.setScreenCallback(styled -> runOnUiThread(() -> updateStructuredOutput(styled)));
                synchronized (sSessions) { sSessions.put(sid, session); }
                mCurrentSession = session;

                mCurrentSession.sendKey(text);

                runOnUiThread(() -> {
                    mEtCommand.setText("");
                    scrollResultToBottom();
                    refreshSessionInfo();
                });
            } catch (Exception e) {
                runOnUiThread(() -> setResultText("启动会话失败: " + e.getMessage()));
            }
        }).start();
    }

    // ================================================================
    //  临时模式 —— 一次性 bash -c 调用 + 3s 超时（无 PTY / 无会话）
    // ================================================================
    private void executeTemporary(String command) {
        mBtnInsert.setEnabled(false);

        new Thread(() -> {
            Process process = null;
            try {
                String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                String[] cmdArray = mTermuxEnv.setupShellCommandArguments(
                        bashPath, new String[]{"-c", command});
                HashMap<String, String> envMap = mTermuxEnv.getEnvironment(this, false);
                String[] envArray = toEnvArray(envMap);
                File workDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);

                process = Runtime.getRuntime().exec(cmdArray, envArray, workDir);

                boolean finished;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    finished = process.waitFor(3, TimeUnit.SECONDS);
                } else {
                    finished = false;
                }

                if (!finished) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        process.destroyForcibly();
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        process.waitFor(1, TimeUnit.SECONDS);
                    }
                }

                String stdoutText = readStreamFully(process.getInputStream());
                String stderrText = readStreamFully(process.getErrorStream());
                int exitCode = process.exitValue();

                runOnUiThread(() -> {
                    StringBuilder r = new StringBuilder();
                    r.append("$ ").append(command).append("  [临时模式]\n");

                    if (!finished) {
                        r.append("── 超时 (3s) ──\n");
                        r.append("命令未在 3 秒内完成，已强制终止。\n");
                        r.append("exit code: ").append(exitCode).append("\n");
                        r.append("⚠ 该命令可能需要持续交互，请切换到「永久模式」执行");
                    } else {
                        if (!TextUtils.isEmpty(stdoutText)) {
                            r.append("── stdout ──\n").append(stdoutText).append("\n");
                        }
                        if (!TextUtils.isEmpty(stderrText)) {
                            r.append("── stderr ──\n").append(stderrText).append("\n");
                        }
                        r.append("── 返回 ──\n");
                        r.append("exit code: ").append(exitCode);
                    }

                    appendResult(r.toString());
                    mBtnInsert.setEnabled(true);
                    mBtnInsert.setText("发送");
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendResult("$ " + command + "  [临时模式]\n── 异常 ──\n" + e.getMessage());
                    mBtnInsert.setEnabled(true);
                    mBtnInsert.setText("发送");
                });
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }).start();
    }

    private static String readStreamFully(java.io.InputStream is) {
        if (is == null) return "";
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (true) {
                int n = is.read(buf);
                if (n == -1) break;
                baos.write(buf, 0, n);
            }
            return baos.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 结构化输出渲染：全量替换终端画布内容。
     * 终端本质是全屏字符矩阵，必须使用 setText() 而非 append() 来
     * 确保清屏（\033[2J）、光标定位、行覆盖等 ANSI 指令正确反映在 UI 上。
     * 即使 styledText 为空，也要清除旧画布（表明终端已清屏）。
     */
    private void updateStructuredOutput(String styledText) {
        mTvResult.setText(styledText != null ? styledText : "");
        scrollResultToBottom();
    }

    /** 流式追加文本到结果区并滚动到底部（仅用于非结构化/临时模式） */
    private void streamResult(String text) {
        mTvResult.append(text);
        scrollResultToBottom();
    }

    // ================================================================
    //  Utils
    // ================================================================
    /** 设置结果文本并自动滚动到底部（替代直接 setText 确保始终显示最新内容） */
    private void setResultText(String text) {
        mTvResult.setText(text);
        scrollResultToBottom();
    }

    /** 滚动输出区到底部——使用 scrollTo 避免 fullScroll 劫持输入框焦点 */
    private void scrollResultToBottom() {
        if (mSvResult == null || mSvResult.getChildCount() == 0) return;
        mSvResult.post(() -> {
            int scrollTarget = Math.max(0,
                mSvResult.getChildAt(0).getHeight() - mSvResult.getHeight());
            mSvResult.scrollTo(0, scrollTarget);
        });
    }

    private void appendResult(String text) {
        String prev = mTvResult.getText().toString();
        if (prev.equals("等待测试...") || prev.startsWith("环境正常")) {
            setResultText(text);
        } else {
            setResultText(prev + "\n\n" + text);
        }
    }

    /** 统一控制会话管理卡片可见性 —— 仅永久模式且输入框无焦点时显示 */
    private void updateSessionCardVisibility() {
        boolean isPerm = mRgMode.getCheckedRadioButtonId() == R.id.rb_perm;
        mCardSession.setVisibility(isPerm && !mInputFocused ? View.VISIBLE : View.GONE);
    }

    private static String[] toEnvArray(HashMap<String, String> envMap) {
        String[] arr = new String[envMap.size()];
        int i = 0;
        for (Map.Entry<String, String> e : envMap.entrySet()) {
            arr[i++] = e.getKey() + "=" + e.getValue();
        }
        return arr;
    }
}
