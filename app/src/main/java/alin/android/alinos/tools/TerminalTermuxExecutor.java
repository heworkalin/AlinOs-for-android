package alin.android.alinos.tools;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/**
 * Termux Shell 面向 Agent 的工具集。
 *
 * <p>彻底移除临时一次性执行（{@code bash -c}），全部操作收敛到永久 PTY 会话。
 * 所有接口返回结构化 JSON，面向 Agent 调用设计。
 *
 * <p>设计原则：
 * <ul>
 *   <li>纯文本优先 — 默认无颜色/行号/标记</li>
 *   <li>结构化响应 — 至少包含 {@code status} 字段</li>
 *   <li>显式生命周期 — 会话必须显式创建/销毁</li>
 *   <li>枚举式按键 — 控制键/方向键使用枚举</li>
 *   <li>智能等待 — 检测提示符而非盲睡</li>
 * </ul>
 *
 * <p>使用前需确保：
 * {@code TermuxApplication.init(context)}、{@code TermuxShellEnvironment.init(context)}、
 * {@link #init(Context, TermuxService)} 均已完成。
 */
public class TerminalTermuxExecutor {

    private static final String DEFAULT_SESSION_ID = "default";
    private static final long SHELL_EXEC_POLL_MS = 80;
    private static final long SHELL_EXEC_DEFAULT_TIMEOUT_MS = 10000;
    private static final Pattern EXIT_MARKER = Pattern.compile("__ALINOS_EXIT__:(\\d+)");

    private static final String[] ANSI_COLOR_NAMES = {
        "黑色", "红色", "绿色", "黄色", "蓝色", "紫色", "青色", "白色",
        "暗灰", "亮红", "亮绿", "亮黄", "亮蓝", "亮紫", "亮青", "亮白"
    };

    // ── 按键枚举 → ANSI 序列映射 ──
    private static final Map<String, String> KEY_ANSI_MAP = new HashMap<>();
    static {
        KEY_ANSI_MAP.put("CTRL_C", "\003");
        KEY_ANSI_MAP.put("CTRL_D", "\004");
        KEY_ANSI_MAP.put("CTRL_Z", "\026");
        KEY_ANSI_MAP.put("TAB", "\t");
        KEY_ANSI_MAP.put("ENTER", "\r");
        KEY_ANSI_MAP.put("ESCAPE", "\033");
        KEY_ANSI_MAP.put("UP", "\033[A");
        KEY_ANSI_MAP.put("DOWN", "\033[B");
        KEY_ANSI_MAP.put("LEFT", "\033[D");
        KEY_ANSI_MAP.put("RIGHT", "\033[C");
    }

    // ── 单例 ──
    private static volatile TerminalTermuxExecutor instance;

    private TerminalTermuxExecutor() {}

    public static TerminalTermuxExecutor getInstance() {
        if (instance == null) {
            synchronized (TerminalTermuxExecutor.class) {
                if (instance == null) {
                    instance = new TerminalTermuxExecutor();
                }
            }
        }
        return instance;
    }

    // ────────────────────────────────────────────────────────────────
    //  实例状态
    // ────────────────────────────────────────────────────────────────

    private final HashMap<String, PtySession> sessionPool = new HashMap<>();
    /** 每个会话的「增量读取」位置追踪 */
    private final HashMap<String, Integer> lastReadLineCount = new HashMap<>();

    private Context context;
    private TermuxService termuxService;
    private TermuxShellEnvironment termuxEnv;
    private volatile boolean initialized = false;
    private volatile boolean envReady = false;

    private static final String MSG_ENV_NOT_READY =
            "Termux 环境尚未初始化，请先在「Agent Main」中完成初始化。\n"
          + "预期路径: " + TermuxConstants.TERMUX_PREFIX_DIR_PATH;

    // ================================================================
    //  PtySession
    // ================================================================

    static class PtySession {
        final String id;
        final TerminalSession session;
        final TerminalEmulator emulator;
        final Object lock = new Object();

        private final TerminalSessionClient sessionClient = new TerminalSessionClient() {
            @Override public void onTextChanged(TerminalSession s) {}
            @Override public void onSessionFinished(TerminalSession s) {}
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

        PtySession(String id, String shellPath, String cwd, String[] env) {
            this.id = id;
            this.session = new TerminalSession(shellPath, cwd, null, env,
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, sessionClient);
            this.session.updateSize(80, 24);
            this.emulator = session.getEmulator();
        }

        PtySession(String id, TerminalSession existingSession) {
            this.id = id;
            this.session = existingSession;
            this.session.updateSize(80, 24);
            this.emulator = session.getEmulator();
        }

        void sendKey(String sequence) {
            if (session.isRunning()) session.write(sequence);
        }

        boolean isAlive() {
            return session != null && session.isRunning();
        }

        synchronized void close() {
            session.finishIfRunning();
        }
    }

    // ================================================================
    //  初始化
    // ================================================================

    public boolean init(Context context, TermuxService service) {
        this.context = context.getApplicationContext();
        this.termuxService = service;
        this.termuxEnv = new TermuxShellEnvironment();
        this.initialized = true;
        envReady = checkEnvironmentReady();
        return envReady;
    }

    public boolean isInitialized() { return initialized; }

    public boolean isEnvironmentReady() { return initialized && envReady; }

    private boolean checkEnvironmentReady() {
        try {
            return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH).isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================
    //  内部辅助：构建 JSON 响应
    // ================================================================

    private static JSONObject okJson() {
        return okJson(null);
    }

    private static JSONObject okJson(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", "ok");
            if (message != null) o.put("message", message);
            return o;
        } catch (Exception e) {
            return errorJson("INTERNAL_ERROR", e.getMessage());
        }
    }

    private static JSONObject errorJson(String code, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", "error");
            o.put("error_code", code != null ? code : "UNKNOWN");
            o.put("message", message != null ? message : "");
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ================================================================
    //  会话管理 — Agent API
    // ================================================================

    // ── create_session ──

    /**
     * 创建一个新的永久 PTY 会话。若 ID 已存在则返回错误。
     */
    public JSONObject create_session(String sessionId) {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return errorJson("INVALID_SESSION_ID", "session_id cannot be empty");
        }

        String sid = sessionId.trim();
        synchronized (sessionPool) {
            PtySession existing = sessionPool.get(sid);
            if (existing != null && existing.isAlive()) {
                return errorJson("ALREADY_EXISTS", "Session already exists: " + sid);
            }
        }

        try {
            PtySession session;
            if (termuxService != null) {
                session = createViaService(sid);
            } else {
                session = createDirect(sid);
            }
            if (session == null) {
                return errorJson("CREATE_FAILED", "Failed to create session: " + sid);
            }
            return okJson("Session created: " + sid);
        } catch (Exception e) {
            return errorJson("CREATE_FAILED", e.getMessage());
        }
    }

    // ── destroy_session ──

    /**
     * 关闭指定会话并释放所有资源。会话不存在则返回错误。
     */
    public JSONObject destroy_session(String sessionId) {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        if (sessionId == null) {
            return errorJson("INVALID_SESSION_ID", "session_id cannot be null");
        }

        PtySession session;
        synchronized (sessionPool) {
            session = sessionPool.remove(sessionId);
        }
        if (session == null) {
            return errorJson("NOT_FOUND", "Session not found: " + sessionId);
        }

        synchronized (session.lock) {
            if (termuxService != null) {
                termuxService.removePermanentSessionByName(session.id);
            }
            session.close();
        }
        lastReadLineCount.remove(sessionId);
        return okJson("Session destroyed: " + sessionId);
    }

    // ── list_sessions ──

    /**
     * 列出当前所有会话及其存活状态。
     */
    public JSONObject list_sessions() {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);

        try {
            JSONArray arr = new JSONArray();
            synchronized (sessionPool) {
                for (Map.Entry<String, PtySession> e : sessionPool.entrySet()) {
                    JSONObject item = new JSONObject();
                    item.put("session_id", e.getKey());
                    item.put("alive", e.getValue().isAlive());
                    item.put("cwd", getCwd(e.getValue()));
                    arr.put(item);
                }
            }
            JSONObject result = okJson();
            result.put("sessions", arr);
            return result;
        } catch (Exception e) {
            return errorJson("INTERNAL_ERROR", e.getMessage());
        }
    }

    // ── session_status ──

    /**
     * 查询指定会话的详细信息：存活状态、当前工作目录、是否有运行中的命令。
     */
    public JSONObject session_status(String sessionId) {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        if (sessionId == null) {
            return errorJson("INVALID_SESSION_ID", "session_id cannot be null");
        }

        PtySession session;
        synchronized (sessionPool) {
            session = sessionPool.get(sessionId);
        }
        if (session == null) {
            return errorJson("NOT_FOUND", "Session not found: " + sessionId);
        }

        try {
            JSONObject data = new JSONObject();
            boolean alive = session.isAlive();
            data.put("alive", alive);
            data.put("cwd", alive ? getCwd(session) : "");
            data.put("has_running_command", alive && hasRunningCommand(session));

            JSONObject result = okJson();
            result.put("data", data);
            return result;
        } catch (Exception e) {
            return errorJson("INTERNAL_ERROR", e.getMessage());
        }
    }

    // ================================================================
    //  命令执行 — Agent API
    // ================================================================

    // ── shell_exec ──

    /**
     * 在指定会话中执行一条 Shell 命令，自动追加回车，等待命令结束（提示符重新出现或超时），
     * 返回纯净文本输出和退出码。
     *
     * <p>内部通过追加 {@code ;echo __ALINOS_EXIT__:$?} 标记来获取退出码。
     *
     * @param sessionId 目标会话 ID
     * @param command   要执行的 Shell 命令
     * @param timeoutMs 最长等待毫秒数（默认 10000）
     */
    public JSONObject shell_exec(String sessionId, String command, long timeoutMs) {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        if (sessionId == null) return errorJson("INVALID_SESSION_ID", "session_id cannot be null");
        if (command == null || command.trim().isEmpty()) {
            return errorJson("EMPTY_COMMAND", "command cannot be empty");
        }

        PtySession session = getSession(sessionId);
        if (session == null) return errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId);

        if (timeoutMs <= 0) timeoutMs = SHELL_EXEC_DEFAULT_TIMEOUT_MS;
        String cmd = command.trim();

        synchronized (session.lock) {
            try {
                // 发送命令（带退出码标记）
                String sentinel = cmd + " ;echo __ALINOS_EXIT__:$?";
                session.sendKey(sentinel + "\r");

                // 轮询等待标记出现
                long deadline = System.currentTimeMillis() + timeoutMs;
                boolean finished = false;

                while (System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(SHELL_EXEC_POLL_MS); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return errorJson("INTERRUPTED", "Interrupted while waiting");
                    }

                    String current = getScreenText(session);
                    Matcher m = EXIT_MARKER.matcher(current);
                    if (m.find()) {
                        finished = true;
                        break;
                    }
                }

                // 读取最终画面并解析
                String finalScreen = getScreenText(session);

                if (!finished) {
                    // 超时：返回已产生的输出
                    String partialOutput = extractOutputBeforeMarker(finalScreen, cmd);
                    JSONObject result = new JSONObject();
                    result.put("status", "timeout");
                    result.put("output", partialOutput);
                    result.put("exit_code", JSONObject.NULL);
                    result.put("cwd", getCwd(session));
                    return result;
                }

                // 解析退出码和输出
                int exitCode = -1;
                String output = "";
                String[] lines = finalScreen.split("\n", -1);
                int markerLineIdx = -1;

                for (int i = lines.length - 1; i >= 0; i--) {
                    Matcher m = EXIT_MARKER.matcher(lines[i]);
                    if (m.find()) {
                        exitCode = Integer.parseInt(m.group(1));
                        markerLineIdx = i;
                        break;
                    }
                }

                // 找到命令所在行（从 marker 行往前找，该行同时包含 command 和 __ALINOS_EXIT__）
                int cmdLineIdx = -1;
                for (int i = markerLineIdx - 1; i >= 0; i--) {
                    if (lines[i].contains(cmd) && lines[i].contains("__ALINOS_EXIT__")) {
                        cmdLineIdx = i;
                        break;
                    }
                }
                // 回退：只包含命令即可
                if (cmdLineIdx < 0) {
                    for (int i = markerLineIdx - 1; i >= 0; i--) {
                        if (lines[i].contains(cmd)) {
                            cmdLineIdx = i;
                            break;
                        }
                    }
                }

                // 提取输出 = 命令行之后、标记行之前的行
                StringBuilder outputBuf = new StringBuilder();
                int start = Math.max(0, cmdLineIdx + 1);
                for (int i = start; i < markerLineIdx; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    // 跳过可能出现的提示符行
                    if (isPromptLike(line)) continue;
                    if (outputBuf.length() > 0) outputBuf.append("\n");
                    outputBuf.append(line);
                }
                output = outputBuf.toString();

                JSONObject result = new JSONObject();
                result.put("status", "ok");
                result.put("output", output);
                result.put("exit_code", exitCode);
                result.put("cwd", getCwd(session));
                return result;

            } catch (Exception e) {
                return errorJson("EXEC_FAILED", e.getMessage());
            }
        }
    }

    /**
     * shell_exec 默认超时版本（10 秒）。
     */
    public JSONObject shell_exec(String sessionId, String command) {
        return shell_exec(sessionId, command, SHELL_EXEC_DEFAULT_TIMEOUT_MS);
    }

    // ================================================================
    //  交互输入 — Agent API
    // ================================================================

    // ── shell_write ──

    /**
     * 向会话发送文本，不追加回车。用于交互式程序应答（如密码、提示确认）。
     */
    public JSONObject shell_write(String sessionId, String text) {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        if (sessionId == null) return errorJson("INVALID_SESSION_ID", "session_id cannot be null");
        if (text == null) return errorJson("INVALID_TEXT", "text cannot be null");

        PtySession session = getSession(sessionId);
        if (session == null) return errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId);

        synchronized (session.lock) {
            session.sendKey(text);
        }
        return okJson();
    }

    // ── shell_send_key ──

    /**
     * 向会话发送一个控制键或方向键（枚举值）。
     * 支持：CTRL_C, CTRL_D, CTRL_Z, TAB, ENTER, ESCAPE,
     *       UP, DOWN, LEFT, RIGHT
     */
    public JSONObject shell_send_key(String sessionId, String key) {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        if (sessionId == null) return errorJson("INVALID_SESSION_ID", "session_id cannot be null");

        String ansi = KEY_ANSI_MAP.get(key);
        if (ansi == null) {
            return errorJson("INVALID_KEY", "Unsupported key: " + key
                + ". Supported: CTRL_C, CTRL_D, CTRL_Z, TAB, ENTER, ESCAPE, UP, DOWN, LEFT, RIGHT");
        }

        PtySession session = getSession(sessionId);
        if (session == null) return errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId);

        synchronized (session.lock) {
            session.sendKey(ansi);
        }
        return okJson();
    }

    // ================================================================
    //  输出读取 — Agent API
    // ================================================================

    // ── shell_read ──

    /**
     * 读取会话终端当前的文本内容。
     *
     * @param mode     "last_n"（默认，返回最后 N 行）、"all"（全部历史）、"new"（增量）
     * @param lines    mode=last_n 时有效，默认 20
     * @param stripAnsi true=移除 ANSI 转义码（默认），false=保留
     */
    public JSONObject shell_read(String sessionId, String mode, int lines, boolean stripAnsi) {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        if (sessionId == null) return errorJson("INVALID_SESSION_ID", "session_id cannot be null");

        PtySession session = getSession(sessionId);
        if (session == null) return errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId);

        if (mode == null) mode = "last_n";
        if (lines <= 0) lines = 20;

        try {
            String raw;
            synchronized (session.lock) {
                raw = getScreenText(session);
            }
            if (stripAnsi) raw = stripAnsiCodes(raw);

            String output;
            int lineCount;

            switch (mode) {
                case "all":
                    output = raw.trim();
                    lineCount = output.isEmpty() ? 0 : output.split("\n", -1).length;
                    break;

                case "new":
                    output = getIncrementalOutput(sessionId, raw);
                    lineCount = output.isEmpty() ? 0 : output.split("\n", -1).length;
                    break;

                default: // last_n
                    output = getLastNLines(raw, lines);
                    lineCount = output.isEmpty() ? 0 : output.split("\n", -1).length;
                    break;
            }

            JSONObject result = okJson();
            result.put("output", output);
            result.put("line_count", lineCount);
            return result;

        } catch (Exception e) {
            return errorJson("READ_FAILED", e.getMessage());
        }
    }

    /**
     * shell_read 默认参数版本（last_n, 20 行, 去除ANSI）。
     */
    public JSONObject shell_read(String sessionId) {
        return shell_read(sessionId, "last_n", 20, true);
    }

    // ================================================================
    //  调试视图 — Agent API
    // ================================================================

    // ── shell_get_debug_view ──

    /**
     * 获取当前终端画面的完整带标记文本（行号、样式名称、可选光标位置）。
     * 仅在需要分析终端样式/光标时使用。
     */
    public JSONObject shell_get_debug_view(String sessionId,
                                            boolean showLineNumbers,
                                            boolean showStyles,
                                            boolean showCursor) {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        if (sessionId == null) return errorJson("INVALID_SESSION_ID", "session_id cannot be null");

        PtySession session = getSession(sessionId);
        if (session == null) return errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId);

        try {
            String view;
            synchronized (session.lock) {
                view = dumpStyledScreen(session, showCursor);
            }

            if (!showLineNumbers) {
                view = view.replaceAll("(?m)^\\[\\d+\\] ", "");
            }
            if (!showStyles) {
                view = view.replaceAll("\\[[^\\]]+\\] ", "");
            }
            view = view.trim();

            JSONObject result = okJson();
            result.put("view", view);
            return result;
        } catch (Exception e) {
            return errorJson("READ_FAILED", e.getMessage());
        }
    }

    /** 默认显示全部标记（行号、样式、光标）。 */
    public JSONObject shell_get_debug_view(String sessionId) {
        return shell_get_debug_view(sessionId, true, true, true);
    }

    // ================================================================
    //  UI 层辅助方法（非 JSON，保持向后兼容供 Activity 轮询使用）
    // ================================================================

    /**
     * 返回所有已注册会话的 ID → 是否活跃映射。
     */
    public Map<String, Boolean> getSessionStatus() {
        Map<String, Boolean> result = new HashMap<>();
        synchronized (sessionPool) {
            for (Map.Entry<String, PtySession> e : sessionPool.entrySet()) {
                result.put(e.getKey(), e.getValue().isAlive());
            }
        }
        return result;
    }

    /**
     * 获取指定会话的终端纯文本内容（供 UI 轮询刷新）。
     */
    public String getSessionPlainScreen(String sessionId) {
        if (!initialized || !envReady || sessionId == null) return "";
        synchronized (sessionPool) {
            PtySession s = sessionPool.get(sessionId);
            if (s != null && s.isAlive()) {
                synchronized (s.lock) {
                    return getScreenText(s);
                }
            }
        }
        return "";
    }

    /**
     * 获取指定会话的结构化终端内容（带行号与样式标记，可选光标）。
     */
    public String getSessionStyledScreen(String sessionId, boolean showCursor) {
        if (!initialized || !envReady || sessionId == null) return "";
        synchronized (sessionPool) {
            PtySession s = sessionPool.get(sessionId);
            if (s != null && s.isAlive()) {
                synchronized (s.lock) {
                    return dumpStyledScreen(s, showCursor);
                }
            }
        }
        return "";
    }

    /** 获取 default 会话的终端纯文本。 */
    public String getDefaultSessionScreen() {
        return getSessionPlainScreen(DEFAULT_SESSION_ID);
    }

    // ================================================================
    //  内部与会话创建
    // ================================================================

    /** 仅查找不自动创建（与旧 getOrCreateSession 区别）。 */
    private PtySession getSession(String sessionId) {
        synchronized (sessionPool) {
            return sessionPool.get(sessionId);
        }
    }

    /** 供 create_session 内部使用的创建路径（线程安全由 caller 保证）。 */
    private PtySession createViaService(String sessionId) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String workingDir = TermuxConstants.TERMUX_HOME_DIR_PATH;

        TermuxSession termuxSession = termuxService.createTermuxSession(
                bashPath, null, null, workingDir, false, sessionId);
        if (termuxSession == null) return null;

        termuxSession.getExecutionCommand().isPermanent = true;
        termuxService.addPermanentSessionHandle(termuxSession.getTerminalSession().mHandle);

        TerminalSession terminalSession = termuxSession.getTerminalSession();
        PtySession session = new PtySession(sessionId, terminalSession);

        synchronized (sessionPool) {
            sessionPool.put(sessionId, session);
        }
        return session;
    }

    private PtySession createDirect(String sessionId) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String workingDir = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String[] env = termuxEnv != null
                ? toEnvArray(termuxEnv.getEnvironment(context, false))
                : new String[0];

        PtySession session = new PtySession(sessionId, bashPath, workingDir, env);
        synchronized (sessionPool) {
            sessionPool.put(sessionId, session);
        }
        return session;
    }

    // ================================================================
    //  内部工具：屏幕读取
    // ================================================================

    /** 从 TerminalEmulator 读取全部纯文本。 */
    private String getScreenText(PtySession session) {
        if (session == null || session.emulator == null) return "";
        TerminalBuffer screen = session.emulator.getScreen();
        if (screen == null) return "";

        int transcriptRows = screen.getActiveTranscriptRows();
        int screenRows = screen.getActiveRows() - transcriptRows;
        int cols = screen.getColumns();

        StringBuilder sb = new StringBuilder();

        for (int extRow = -transcriptRows; extRow < screenRows; extRow++) {
            TerminalRow row = screen.getRowOrNull(extRow);
            if (row == null) continue;
            if (isRowBlank(row, cols)) continue;

            StringBuilder line = new StringBuilder();
            for (int col = 0; col < cols; col++) {
                int idx = row.findStartOfColumn(col);
                if (idx >= 0 && idx < row.mText.length) {
                    char ch = row.mText[idx];
                    if (ch != '\0') line.append(ch);
                }
            }
            String lineStr = line.toString().replaceAll("\\s+$", "");
            if (lineStr.isEmpty()) continue;
            sb.append(lineStr).append('\n');
        }
        return sb.toString();
    }

    /** 检测某行是否看起来像 Shell 提示符。 */
    private static boolean isPromptLike(String line) {
        String t = line.trim();
        return t.endsWith("$") || t.endsWith("#") || ">".equals(t);
    }

    /** 从屏幕文本中提取命令输出（超时场景，在标记出现前）。 */
    private static String extractOutputBeforeMarker(String screen, String command) {
        String[] lines = screen.split("\n", -1);
        boolean foundCmd = false;
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (!foundCmd) {
                if (t.contains(command)) {
                    foundCmd = true;
                }
                continue;
            }
            if (t.isEmpty() || isPromptLike(t)) continue;
            if (t.contains("__ALINOS_EXIT__:")) break;
            if (out.length() > 0) out.append("\n");
            out.append(t);
        }
        return out.toString();
    }

    /** 增量读取：返回自上次读取后的新行。 */
    private String getIncrementalOutput(String sessionId, String currentText) {
        String[] currentLines = currentText.split("\n", -1);
        int currentCount = currentLines.length;

        Integer lastCount = lastReadLineCount.get(sessionId);
        if (lastCount == null || currentCount <= lastCount) {
            // 首次读取或滚屏导致重置 → 返回全部
            lastReadLineCount.put(sessionId, currentCount);
            return currentText.trim();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = lastCount; i < currentCount; i++) {
            String trimmed = currentLines[i].trim();
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(trimmed);
            }
        }
        lastReadLineCount.put(sessionId, currentCount);
        return sb.toString();
    }

    /** 获取会话当前工作目录（通过发送 pwd 读取）。 */
    private String getCwd(PtySession session) {
        if (session == null || !session.isAlive()) return "";
        synchronized (session.lock) {
            try {
                // 记录当前屏幕作为基线
                String before = getScreenText(session);
                int beforeLineCount = before.isEmpty() ? 0 : before.split("\n", -1).length;

                session.sendKey("pwd\r");
                Thread.sleep(200);

                String after = getScreenText(session);
                String[] lines = after.split("\n", -1);

                // 在新增行中查找 pwd 输出（以 / 开头）
                for (int i = beforeLineCount; i < lines.length && i >= 0; i++) {
                    String t = lines[i].trim();
                    if (t.startsWith("/")) {
                        // 清理可能的换行符
                        return t.replaceAll("[\\r\\n]", "").trim();
                    }
                }
                return "";
            } catch (Exception e) {
                return "";
            }
        }
    }

    /** 判断会话当前是否有命令在运行（基于屏幕最后一行是否像提示符）。 */
    private boolean hasRunningCommand(PtySession session) {
        String screen = getScreenText(session);
        if (screen.isEmpty()) return false;
        String[] lines = screen.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (!t.isEmpty()) {
                // 最后非空行不像提示符 → 可能正在运行命令
                boolean isPrompt = t.endsWith("$") || t.endsWith("#");
                if (isPrompt) return false;
                // 若很短的命令可能是提示符
                if (t.length() < 5 && (t.contains("$") || t.contains("#"))) return false;
                return true;
            }
        }
        return false;
    }

    // ================================================================
    //  dumpStyledScreen — 结构化样式输出
    // ================================================================

    private String dumpStyledScreen(PtySession session, boolean showCursor) {
        if (session == null || session.emulator == null) return "";
        TerminalBuffer screen = session.emulator.getScreen();
        if (screen == null) return "";

        int transcriptRows = screen.getActiveTranscriptRows();
        int screenRows = screen.getActiveRows() - transcriptRows;
        int cols = screen.getColumns();
        int cursorRow = session.emulator.getCursorRow();
        int cursorCol = session.emulator.getCursorCol();

        StringBuilder result = new StringBuilder();
        int displayLine = 0;

        for (int extRow = -transcriptRows; extRow < screenRows; extRow++) {
            TerminalRow row = screen.getRowOrNull(extRow);
            if (row == null) continue;

            if (isRowBlank(row, cols)) {
                if (showCursor && extRow == cursorRow) {
                    result.append("[").append(displayLine).append("] ");
                    for (int i = 0; i < cursorCol; i++) result.append(' ');
                    result.append("█\n");
                    displayLine++;
                }
                continue;
            }

            String rowText = buildRowPlainText(row, cols);
            if (rowText.trim().isEmpty()) continue;

            boolean isCursorRow = showCursor && (extRow == cursorRow);

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
                        if (bracketOpened) { segText.append("]"); bracketOpened = false; }
                        flushStyledSegment(result, segText, prevStyle);
                        segText = new StringBuilder();
                    }
                    started = true;
                    prevStyle = style;

                    if (isCursorRow && cursorInsideWord && col == cursorWordStart) {
                        segText.append("[");
                        bracketOpened = true;
                    }

                    segText.append(ch);

                    if (isCursorRow && col == cursorCol && ch == ' ') {
                        segText.setCharAt(segText.length() - 1, '█');
                    }

                    if (isCursorRow && cursorWordStart >= 0 && ch != ' ') {
                        int nextCol = col + (w > 1 ? w : 1);
                        if (nextCol == cursorCol) {
                            segText.append("(标)");
                        }
                    }

                    if (isCursorRow && bracketOpened) {
                        int nextCol = col + (w > 1 ? w : 1);
                        if (nextCol >= cursorWordEnd) {
                            segText.append("]");
                            bracketOpened = false;
                        }
                    }
                } else if (isCursorRow && col == cursorCol) {
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

    private static boolean isRowBlank(TerminalRow row, int cols) {
        for (int col = 0; col < cols; col++) {
            int idx = row.findStartOfColumn(col);
            if (idx >= 0 && idx < row.mText.length && row.mText[idx] != ' ') return false;
        }
        return true;
    }

    // ================================================================
    //  内部工具：文本处理
    // ================================================================

    private static String getLastNLines(String text, int n) {
        if (text == null || text.isEmpty() || n <= 0) return "";
        String[] lines = text.split("\n", -1);

        int end = lines.length;
        while (end > 0 && lines[end - 1].isEmpty()) end--;

        if (end <= n) {
            return text.substring(0, text.length() - countTrailingNewlines(text));
        }

        StringBuilder sb = new StringBuilder();
        for (int i = end - n; i < end; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static int countTrailingNewlines(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\n'; i--) count++;
        return count;
    }

    private static String stripAnsiCodes(String text) {
        if (text == null || text.isEmpty()) return text;
        String cleaned = text.replaceAll("\033\\[[0-9;]*[a-zA-Z]", "");
        cleaned = cleaned.replaceAll("\033\\].*?(\007|\033\\\\)", "");
        cleaned = cleaned.replaceAll("\033[a-zA-Z]", "");
        return cleaned;
    }

    private static String readStreamFully(InputStream is) {
        if (is == null) return "";
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (true) {
                int n = is.read(buf);
                if (n == -1) break;
                baos.write(buf, 0, n);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return baos.toString(StandardCharsets.UTF_8);
            } else {
                return baos.toString("UTF-8");
            }
        } catch (IOException e) {
            return "";
        }
    }

    private static String[] toEnvArray(HashMap<String, String> envMap) {
        if (envMap == null) return new String[0];
        String[] arr = new String[envMap.size()];
        int i = 0;
        for (Map.Entry<String, String> e : envMap.entrySet()) {
            arr[i++] = e.getKey() + "=" + e.getValue();
        }
        return arr;
    }
}
