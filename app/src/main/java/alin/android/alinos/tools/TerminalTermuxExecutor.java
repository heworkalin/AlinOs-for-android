package alin.android.alinos.tools;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
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
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Termux Shell —— Agent 用纯净终端工具集。
 *
 * <p><b>核心理念：终端只做纯粹的 IO 通道。</b>
 * 所有命令「原样发送、原样返回」，不追加任何额外指令。
 * Agent 如需获取目录、环境变量等信息，请自行执行对应命令。</p>
 *
 * <p>全部操作收敛到永久 PTY 会话，不做 bash -c 一次性执行。
 * 所有接口返回结构化 JSON，至少包含 {@code status} 字段。</p>
 *
 * <ul>
 *   <li>纯净性 — 不在命令前后插入任何额外指令（无 pwd/echo 注入）</li>
 *   <li>结构化响应 — status + 业务数据 + 终端标识 (id/name)</li>
 *   <li>显式生命周期 — 会话必须显式创建/销毁</li>
 *   <li>枚举式按键 — 控制键/方向键使用枚举映射</li>
 * </ul>
 *
 * <p>前置条件：{@code TermuxApplication.init(context)}、
 * {@code TermuxShellEnvironment.init(context)}、
 * {@link #init(Context, TermuxService)} 均已完成。</p>
 */
public class TerminalTermuxExecutor {

    // ── 默认值 ──
    private static final int DEFAULT_RETURN_LINES = 20;
    private static final long SHELL_EXEC_DEFAULT_WAIT_MS = 200;
    private static final long SHELL_EXEC_MIN_WAIT_MS = 200;
    private static final long SHELL_EXEC_MAX_WAIT_MS = 1200;
    private static final long SCREEN_READ_POLL_MS = 80;
    private static final long SCREEN_SETTLE_MS = 150;
    private static final int SESSION_ID_MAX_LENGTH = 64;
    private static final int SHELL_READ_MAX_LINES = 5000;
    private static final int MAX_RECENT_COMMANDS = 3;
    /** 等待 TermuxService 绑定的最大时间（毫秒）。 */
    private static final long SERVICE_BIND_WAIT_MAX_MS = 3000;
    /** 等待 TermuxService 绑定的轮询间隔（毫秒）。 */
    private static final long SERVICE_BIND_POLL_MS = 100;
    private static final String HISTORY_ARCHIVE_DIR = "termux_history";

    private static final String[] ANSI_COLOR_NAMES = {
        "黑色", "红色", "绿色", "黄色", "蓝色", "紫色", "青色", "白色",
        "暗灰", "亮红", "亮绿", "亮黄", "亮蓝", "亮紫", "亮青", "亮白"
    };

    // ── 按键枚举 → ANSI 序列映射 ──
    private static final Map<String, String> KEY_ANSI_MAP = new HashMap<>();
    static {
        // Ctrl 组合
        KEY_ANSI_MAP.put("CTRL_A", "\001");
        KEY_ANSI_MAP.put("CTRL_B", "\002");
        KEY_ANSI_MAP.put("CTRL_C", "\003");
        KEY_ANSI_MAP.put("CTRL_D", "\004");
        KEY_ANSI_MAP.put("CTRL_E", "\005");
        KEY_ANSI_MAP.put("CTRL_F", "\006");
        KEY_ANSI_MAP.put("CTRL_G", "\007");
        KEY_ANSI_MAP.put("CTRL_H", "\010");
        KEY_ANSI_MAP.put("CTRL_I", "\011");
        KEY_ANSI_MAP.put("CTRL_J", "\012");
        KEY_ANSI_MAP.put("CTRL_K", "\013");
        KEY_ANSI_MAP.put("CTRL_L", "\014");
        KEY_ANSI_MAP.put("CTRL_M", "\015");
        KEY_ANSI_MAP.put("CTRL_N", "\016");
        KEY_ANSI_MAP.put("CTRL_O", "\017");
        KEY_ANSI_MAP.put("CTRL_P", "\020");
        KEY_ANSI_MAP.put("CTRL_Q", "\021");
        KEY_ANSI_MAP.put("CTRL_R", "\022");
        KEY_ANSI_MAP.put("CTRL_S", "\023");
        KEY_ANSI_MAP.put("CTRL_T", "\024");
        KEY_ANSI_MAP.put("CTRL_U", "\025");
        KEY_ANSI_MAP.put("CTRL_V", "\026");
        KEY_ANSI_MAP.put("CTRL_W", "\027");
        KEY_ANSI_MAP.put("CTRL_X", "\030");
        KEY_ANSI_MAP.put("CTRL_Y", "\031");
        KEY_ANSI_MAP.put("CTRL_Z", "\032");

        // 特殊键
        KEY_ANSI_MAP.put("TAB", "\t");
        KEY_ANSI_MAP.put("ENTER", "\r");
        KEY_ANSI_MAP.put("ESCAPE", "\033");
        KEY_ANSI_MAP.put("BACKSPACE", "\177");
        KEY_ANSI_MAP.put("DELETE", "\033[3~");

        // 方向键
        KEY_ANSI_MAP.put("UP", "\033[A");
        KEY_ANSI_MAP.put("DOWN", "\033[B");
        KEY_ANSI_MAP.put("LEFT", "\033[D");
        KEY_ANSI_MAP.put("RIGHT", "\033[C");

        // 翻页键
        KEY_ANSI_MAP.put("PAGE_UP", "\033[5~");
        KEY_ANSI_MAP.put("PAGE_DOWN", "\033[6~");
        KEY_ANSI_MAP.put("HOME", "\033[H");
        KEY_ANSI_MAP.put("END", "\033[F");

        // 功能键 F1-F12
        KEY_ANSI_MAP.put("F1", "\033OP");
        KEY_ANSI_MAP.put("F2", "\033OQ");
        KEY_ANSI_MAP.put("F3", "\033OR");
        KEY_ANSI_MAP.put("F4", "\033OS");
        KEY_ANSI_MAP.put("F5", "\033[15~");
        KEY_ANSI_MAP.put("F6", "\033[17~");
        KEY_ANSI_MAP.put("F7", "\033[18~");
        KEY_ANSI_MAP.put("F8", "\033[19~");
        KEY_ANSI_MAP.put("F9", "\033[20~");
        KEY_ANSI_MAP.put("F10", "\033[21~");
        KEY_ANSI_MAP.put("F11", "\033[23~");
        KEY_ANSI_MAP.put("F12", "\033[24~");
    }

    private static String normalizeKeyName(String key) {
        if (key == null) return null;
        String normalized = key.trim().toUpperCase(Locale.US);
        normalized = normalized.replace("CTRL+", "CTRL_");
        normalized = normalized.replace("CTRL ", "CTRL_");
        if (!normalized.contains("_") && !normalized.contains("+")) {
            normalized = normalized.replace("PAGEUP", "PAGE_UP")
                .replace("PAGEDOWN", "PAGE_DOWN")
                .replace("BACKSPACE", "BACKSPACE")
                .replace("ESCAPE", "ESCAPE")
                .replace("DELETE", "DELETE");
        }
        return normalized;
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

    private final ConcurrentHashMap<String, PtySession> sessionPool = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> lastReadLineCount = new ConcurrentHashMap<>();

    private Context context;
    private TermuxService termuxService;
    private TermuxShellEnvironment termuxEnv;
    private volatile boolean initialized = false;
    private volatile boolean envReady = false;

    /** 应用级 Context 暂存，供 AI 静默初始化使用。 */
    private static volatile Context appContext;

    /**
     * 注入应用 Context。Activities/Application 在启动时调用一次，
     * 此后 AI 通过 {@link #create_session} 即可自动完成初始化。
     */
    public static void provideContext(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    /**
     * 自动初始化（仅当未初始化且有可用 Context 时）。
     * @return null 表示就绪，非 null 为错误 JSON
     */
    private JSONObject ensureInitialized() {
        if (initialized && envReady) return null;
        if (!initialized) {
            if (appContext == null) {
                return errorJson("NOT_INITIALIZED",
                    "Executor not initialized. Call provideContext() first.");
            }
            init(appContext, null);
        }
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        return null;
    }

    private boolean mServiceBound = false;
    private volatile boolean mServiceBindingInitiated = false;
    private TermuxService mLocalService = null;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mLocalService = ((TermuxService.LocalBinder) service).service;
            mServiceBound = true;
            if (TerminalTermuxExecutor.this.termuxService == null) {
                TerminalTermuxExecutor.this.termuxService = mLocalService;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mLocalService = null;
            mServiceBound = false;

            // 关闭所有会话，清空会话池
            for (PtySession s : sessionPool.values()) {
                s.close();
            }
            sessionPool.clear();
            lastReadLineCount.clear();

            // 重置执行器状态 → 后续所有调用返回 NOT_INITIALIZED
            initialized = false;
            envReady = false;
            termuxService = null;
        }
    };

    private SharedPreferences mSessionPrefs;
    private static final String PREFS_NAME = "termux_session_meta";
    private static final String KEY_SESSION_NAMES = "session_id_name_map";

    private static final String MSG_ENV_NOT_READY =
            "Termux 环境尚未初始化，请先在「Agent Main」中完成初始化。\n"
          + "预期路径: " + TermuxConstants.TERMUX_PREFIX_DIR_PATH;

    // ================================================================
    //  PtySession
    // ================================================================

    static class PtySession {
        final String id;
        String name;
        final TerminalSession session;
        final TerminalEmulator emulator;
        final Object lock = new Object();
        final List<String> recentCommands = new ArrayList<>();
        final boolean createdViaService;
        volatile boolean serviceDead;

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
            this.name = id;
            this.createdViaService = false;
            this.session = new TerminalSession(shellPath, cwd, null, env,
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, sessionClient);
            this.session.updateSize(80, 24);
            this.emulator = session.getEmulator();
        }

        PtySession(String id, TerminalSession existingSession) {
            this.id = id;
            this.name = id;
            this.createdViaService = true;
            this.session = existingSession;
            this.session.updateSize(80, 24);
            this.emulator = session.getEmulator();
        }

        boolean sendKey(String sequence) {
            if (session.isRunning()) {
                session.write(sequence);
                return true;
            }
            return false;
        }

        boolean isAlive() {
            if (session == null) return false;
            if (serviceDead) return false;
            return session.isRunning();
        }

        synchronized void close() {
            session.finishIfRunning();
        }

        void recordCommand(String cmd) {
            synchronized (recentCommands) {
                recentCommands.add(cmd);
                while (recentCommands.size() > MAX_RECENT_COMMANDS) {
                    recentCommands.remove(0);
                }
            }
        }

        JSONArray getRecentCommandsJson() {
            JSONArray arr = new JSONArray();
            synchronized (recentCommands) {
                for (String c : recentCommands) {
                    arr.put(c);
                }
            }
            return arr;
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
        initPersistence(this.context);
        if (!mServiceBound) {
            bindTermuxService(this.context);
        }
        // 同步等待 TermuxService 绑定完成（仅在自动初始化时等待）
        if (this.termuxService == null && mServiceBindingInitiated) {
            long deadline = System.currentTimeMillis() + SERVICE_BIND_WAIT_MAX_MS;
            while (System.currentTimeMillis() < deadline) {
                if (this.termuxService != null) break;
                try {
                    Thread.sleep(SERVICE_BIND_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return envReady;
    }

    private void initPersistence(Context ctx) {
        if (mSessionPrefs != null) return;
        mSessionPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void bindTermuxService(Context ctx) {
        mServiceBindingInitiated = true;
        try {
            Intent intent = new Intent(ctx, TermuxService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
            if (!ctx.bindService(intent, mServiceConnection, 0)) {
                // bindService 返回 false → 服务不可用，回退到 createDirect
            }
        } catch (Exception e) {
            // 绑定失败 → 回退到 createDirect 创建会话
        }
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
    //  会话元数据持久化
    // ================================================================

    private void saveSessionMeta() {
        if (mSessionPrefs == null) return;
        try {
            JSONObject meta = new JSONObject();
            for (Map.Entry<String, PtySession> e : sessionPool.entrySet()) {
                JSONObject entry = new JSONObject();
                entry.put("name", e.getValue().name);
                entry.put("alive", e.getValue().isAlive());
                meta.put(e.getKey(), entry);
            }
            mSessionPrefs.edit().putString(KEY_SESSION_NAMES, meta.toString()).apply();
        } catch (Exception ignored) {}
    }

    private Map<String, String> loadSessionMeta() {
        Map<String, String> result = new HashMap<>();
        if (mSessionPrefs == null) return result;
        try {
            String json = mSessionPrefs.getString(KEY_SESSION_NAMES, "{}");
            JSONObject meta = new JSONObject(json);
            for (java.util.Iterator<String> it = meta.keys(); it.hasNext();) {
                String id = it.next();
                JSONObject entry = meta.optJSONObject(id);
                if (entry != null) {
                    result.put(id, entry.optString("name", id));
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ================================================================
    //  内部辅助：JSON 响应
    // ================================================================

    private static JSONObject successJson() {
        return successJson(null, null);
    }

    private static JSONObject successJson(String message) {
        return successJson(message, null);
    }

    private static JSONObject successJson(String message, JSONObject data) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", "success");
            if (message != null) o.put("message", message);
            if (data != null) o.put("data", data);
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

    /** 向 JSON 响应中附加终端 id 和 name。 */
    private static JSONObject attachSessionInfo(JSONObject base, PtySession session) {
        try {
            base.put("id", session.id);
            base.put("name", session.name);
        } catch (Exception ignored) {}
        return base;
    }

    // ================================================================
    //  参数校验
    // ================================================================

    private JSONObject checkInit() {
        if (!initialized) return errorJson("NOT_INITIALIZED", "Executor not initialized");
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        return null;
    }

    private JSONObject checkSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return errorJson("INVALID_SESSION_ID", "session_id cannot be null or empty");
        }
        String trimmed = sessionId.trim();
        if (trimmed.length() > SESSION_ID_MAX_LENGTH) {
            return errorJson("INVALID_SESSION_ID",
                "session_id too long (max " + SESSION_ID_MAX_LENGTH + " chars)");
        }
        if (!trimmed.matches("[a-zA-Z0-9_\\-]+")) {
            return errorJson("INVALID_SESSION_ID",
                "session_id must match [a-zA-Z0-9_\\-]+");
        }
        return null;
    }

    private static class SessionResult {
        final PtySession session;
        final JSONObject error;
        SessionResult(PtySession s, JSONObject e) { this.session = s; this.error = e; }
    }

    private SessionResult requireAliveSession(String sessionId) {
        if (sessionId == null) {
            return new SessionResult(null, errorJson("INVALID_SESSION_ID", "session_id cannot be null"));
        }
        PtySession s = sessionPool.get(sessionId);
        if (s == null) {
            return new SessionResult(null, errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId));
        }
        if (!s.isAlive()) {
            return new SessionResult(null, errorJson("SESSION_DEAD", "Session is terminated: " + sessionId));
        }
        return new SessionResult(s, null);
    }

    // ================================================================
    //  输出渲染 — 颜色转义 / 光标位置模式
    // ================================================================

    /**
     * 根据颜色转义和光标位置模式渲染终端缓冲区文本。
     * 复用 {@link #dumpStyledScreen} 作为完整渲染引擎，然后按模式后处理。
     */
    private String renderScreen(PtySession session, boolean colorEscape, boolean cursorMark) {
        // colorEscape: false = 纯文本（清除ANSI转义序列）, true = 中文样式标签
        // cursorMark:  false = 无标记, true = 可视化标记光标位置

        // 无样式 + 无光标 → 纯文本快速路径
        if (!colorEscape && !cursorMark) {
            return getScreenText(session);
        }

        String output = dumpStyledScreen(session, cursorMark);

        if (!colorEscape) {
            // 原始模式：移除 [样式名] 标签
            output = output.replaceAll("\\[[\\u4e00-\\u9fff|+]+\\] ", "");
            output = output.replaceAll(" {2,}", " ");
        }

        if (!cursorMark) {
            output = output.replaceAll("(?m)^\\[\\d+\\] ", "");
        }

        output = output.replaceAll("(?m) +$", "");
        return output;
    }

    // ================================================================
    //  内部辅助：返回模式处理 + 响应构建
    // ================================================================

    /**
     * 根据返回模式从渲染文本中提取内容。
     * @return { output, lineCount }
     */
    private static String[] applyReturnMode(String text, String returnMode, int lines) {
        String output;
        switch (returnMode) {
            case "last_n":
                output = getLastNLines(text, lines);
                break;
            case "all":
                output = text.trim();
                break;
            default: // last_20
                output = getLastNLines(text, DEFAULT_RETURN_LINES);
                break;
        }
        int lineCount = output.isEmpty() ? 0 : output.split("\n", -1).length;
        return new String[]{ output, String.valueOf(lineCount) };
    }

    // ================================================================
    //  会话管理 — Agent API
    // ================================================================

    // ── create_session ──

    /**
     * 创建一个新的永久 PTY 会话。
     *
     * @param sessionId 唯一标识，正则 [a-zA-Z0-9_\-]+，最长 64 字符
     * @param sessionName 可读名称，null 则默认等于 id
     */
    public JSONObject create_session(String sessionId, String sessionName) {
        JSONObject err;

        // AI 静默初始化：未初始化时自动完成
        err = ensureInitialized();
        if (err != null) return err;

        err = checkSessionId(sessionId);
        if (err != null) return err;

        String sid = sessionId.trim();

        synchronized (sessionPool) {
            PtySession existing = sessionPool.get(sid);
            if (existing != null) {
                if (existing.isAlive()) {
                    return errorJson("ALREADY_EXISTS",
                        "Session already exists and is alive: " + sid
                        + ". Destroy it first or use a different ID.");
                }
                existing.close();
                sessionPool.remove(sid);
            }

            try {
                PtySession session;
                // init() 已确保 termuxService 就绪（自动初始化时同步等待绑定完成）
                if (termuxService != null) {
                    session = createViaService(sid);
                } else {
                    session = createDirect(sid);
                }
                if (session == null) {
                    return errorJson("CREATE_FAILED", "Failed to create session: " + sid);
                }
                if (sessionName != null && !sessionName.trim().isEmpty()) {
                    session.name = sessionName.trim();
                }
                saveSessionMeta();
                JSONObject result = new JSONObject();
                result.put("status", "success");
                result.put("id", sid);
                result.put("name", session.name);
                return result;
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName();
                if (e.getMessage() != null) msg += ": " + e.getMessage();
                return errorJson("CREATE_FAILED", msg);
            }
        }
    }

    /** create_session 仅 id 版本（名称默认等于 id）。 */
    public JSONObject create_session(String sessionId) {
        return create_session(sessionId, null);
    }

    // ── destroy_session ──

    public JSONObject destroy_session(String sessionId) {
        JSONObject err = checkInit();
        if (err != null) return err;

        if (sessionId == null) {
            return errorJson("INVALID_SESSION_ID", "session_id cannot be null");
        }

        PtySession session = sessionPool.remove(sessionId);
        if (session == null) {
            return errorJson("NOT_FOUND", "Session not found: " + sessionId);
        }

        String sessionName = session.name;

        synchronized (session.lock) {
            if (termuxService != null) {
                termuxService.removePermanentSessionByName(session.id);
            }
            session.close();
        }
        lastReadLineCount.remove(sessionId);
        saveSessionMeta();
        JSONObject result = new JSONObject();
        try {
            result.put("status", "success");
            result.put("id", sessionId);
            result.put("name", sessionName);
            result.put("message", "终端已终止");
        } catch (Exception e) {
            return errorJson("INTERNAL_ERROR", e.getMessage());
        }
        return result;
    }

    // ── list_sessions ──

    public JSONObject list_sessions() {
        JSONObject err = checkInit();
        if (err != null) return err;

        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, PtySession> e : sessionPool.entrySet()) {
                PtySession s = e.getValue();
                JSONObject item = new JSONObject();
                item.put("session_id", e.getKey());
                item.put("name", s.name);
                item.put("alive", s.isAlive());
                item.put("recent_commands", s.getRecentCommandsJson());
                arr.put(item);
            }
            JSONObject result = successJson();
            result.put("sessions", arr);
            return result;
        } catch (Exception e) {
            return errorJson("INTERNAL_ERROR", e.getMessage());
        }
    }

    // ── search_session ──

    public JSONObject search_session(String query, String by) {
        JSONObject err = checkInit();
        if (err != null) return err;

        if (query == null || query.trim().isEmpty()) {
            return errorJson("INVALID_QUERY", "query cannot be empty");
        }
        if (by == null) by = "name";

        try {
            JSONObject result = successJson();

            if ("id".equals(by)) {
                PtySession s = sessionPool.get(query.trim());
                if (s != null) {
                    JSONObject info = new JSONObject();
                    info.put("id", s.id);
                    info.put("name", s.name);
                    info.put("recent_commands", s.getRecentCommandsJson());
                    result.put("found", true);
                    result.put("data", info);
                } else {
                    result.put("found", false);
                }
            } else {
                String lowerQuery = query.trim().toLowerCase(Locale.US);
                JSONArray matches = new JSONArray();
                for (Map.Entry<String, PtySession> e : sessionPool.entrySet()) {
                    PtySession s = e.getValue();
                    if (s.id.toLowerCase(Locale.US).contains(lowerQuery)
                            || s.name.toLowerCase(Locale.US).contains(lowerQuery)) {
                        JSONObject info = new JSONObject();
                        info.put("id", s.id);
                        info.put("name", s.name);
                        matches.put(info);
                    }
                }
                result.put("found", matches.length() > 0);
                result.put("matches", matches);
            }
            return result;
        } catch (Exception e) {
            return errorJson("INTERNAL_ERROR", e.getMessage());
        }
    }

    // ── session_status ──

    public JSONObject session_status(String sessionId) {
        JSONObject err = checkInit();
        if (err != null) return err;

        SessionResult sr = requireAliveSession(sessionId);
        if (sr.error != null) return sr.error;

        try {
            JSONObject data = new JSONObject();
            data.put("alive", sr.session.isAlive());
            data.put("session_id", sr.session.id);
            data.put("name", sr.session.name);
            return successJson(null, data);
        } catch (Exception e) {
            return errorJson("INTERNAL_ERROR", e.getMessage());
        }
    }

    // ── rename_session ──

    public JSONObject rename_session(String sessionId, String newName) {
        JSONObject err = checkInit();
        if (err != null) return err;

        if (sessionId == null) {
            return errorJson("INVALID_SESSION_ID", "session_id cannot be null");
        }
        if (newName == null || newName.trim().isEmpty()) {
            return errorJson("INVALID_NAME", "new_name cannot be null or empty");
        }

        String trimmed = newName.trim();
        if (trimmed.length() > SESSION_ID_MAX_LENGTH) {
            return errorJson("INVALID_NAME",
                "new_name too long (max " + SESSION_ID_MAX_LENGTH + " chars)");
        }
        if (!trimmed.matches("[a-zA-Z0-9_\\-\\u4e00-\\u9fff]+")) {
            return errorJson("INVALID_NAME",
                "new_name must match [a-zA-Z0-9_\\-\\u4e00-\\u9fff]+");
        }

        PtySession session = sessionPool.get(sessionId);
        if (session == null) {
            return errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId);
        }

        String oldName = session.name;
        session.name = trimmed;
        saveSessionMeta();

        try {
            JSONObject data = new JSONObject();
            data.put("session_id", sessionId);
            data.put("old_name", oldName);
            data.put("new_name", trimmed);
            return successJson("Session renamed: \"" + oldName + "\" → \"" + trimmed + "\"", data);
        } catch (Exception e) {
            return errorJson("INTERNAL_ERROR", e.getMessage());
        }
    }

    // ================================================================
    //  命令执行 — Agent API
    // ================================================================

    // ── shell_exec ──

    /**
     * 在指定会话中执行一条命令。不注入任何额外指令。
     *
     * @param sessionId    目标会话 ID
     * @param command      要执行的命令（服务器自动补 \r）
     * @param waitMs       等待毫秒数，默认 200，范围 [200, 1200]
     * @param returnMode   返回模式：last_20（默认）、last_n（需 n）
     * @param lines        last_n 时有效
     * @param colorEscape  颜色转义：false=纯文本（清除ANSI）, true=中文样式标签
     */
    public JSONObject shell_exec(String sessionId, String command, long waitMs,
                                  String returnMode, int lines, boolean colorEscape) {
        JSONObject err = checkInit();
        if (err != null) return err;

        if (sessionId == null) {
            return errorJson("INVALID_SESSION_ID", "session_id cannot be null");
        }
        if (command == null || command.trim().isEmpty()) {
            return errorJson("EMPTY_COMMAND", "command cannot be empty");
        }

        PtySession session = sessionPool.get(sessionId);
        if (session == null) {
            return errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId);
        }

        if (waitMs <= 0) waitMs = SHELL_EXEC_DEFAULT_WAIT_MS;
        if (waitMs < SHELL_EXEC_MIN_WAIT_MS) waitMs = SHELL_EXEC_MIN_WAIT_MS;
        if (waitMs > SHELL_EXEC_MAX_WAIT_MS) waitMs = SHELL_EXEC_MAX_WAIT_MS;
        if (returnMode == null) returnMode = "last_20";
        if ("all".equals(returnMode)) returnMode = "last_20";
        if (lines <= 0) lines = DEFAULT_RETURN_LINES;

        String cmd = command.trim();
        session.recordCommand(cmd);

        synchronized (session.lock) {
            if (!session.sendKey(cmd + "\r")) {
                return errorJson("SESSION_DEAD",
                    "Session is not running, cannot send command: " + sessionId);
            }

            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return errorJson("INTERRUPTED", "Interrupted while waiting");
            }

            if (!session.isAlive()) {
                String partial = renderScreen(session, colorEscape, false);
                try {
                    JSONObject result = new JSONObject();
                    result.put("status", "session_died");
                    result.put("content", partial);
                    return attachSessionInfo(result, session);
                } catch (Exception e) {
                    return errorJson("EXEC_FAILED", e.getMessage());
                }
            }

            String rendered = renderScreen(session, colorEscape, false);

            String[] modeResult = applyReturnMode(rendered, returnMode, lines);
            try {
                JSONObject result = new JSONObject();
                result.put("status", "success");
                result.put("content", modeResult[0]);
                result.put("line_count", Integer.parseInt(modeResult[1]));
                return attachSessionInfo(result, session);
            } catch (Exception e) {
                return errorJson("EXEC_FAILED", e.getMessage());
            }
        }
    }

    /** shell_exec 简化版（stripAnsi=true 去除颜色，false 保留标签）。 */
    public JSONObject shell_exec(String sessionId, String command, long waitMs, boolean stripAnsi) {
        return shell_exec(sessionId, command, waitMs,
            "last_20", DEFAULT_RETURN_LINES,
            !stripAnsi);
    }

    public JSONObject shell_exec(String sessionId, String command, long waitMs) {
        return shell_exec(sessionId, command, waitMs, "last_20", DEFAULT_RETURN_LINES, false);
    }

    public JSONObject shell_exec(String sessionId, String command) {
        return shell_exec(sessionId, command, SHELL_EXEC_DEFAULT_WAIT_MS,
            "last_20", DEFAULT_RETURN_LINES, false);
    }

    // ================================================================
    //  交互输入 — Agent API
    // ================================================================

    // ── shell_write ──

    /**
     * 向会话写入文本，不追加回车。用于交互式程序应答。
     *
     * @param returnMode   返回模式：last_20（默认）、last_n、all
     * @param lines        last_n 时有效
     * @param colorEscape  颜色转义：false=纯文本（清除ANSI）, true=中文样式标签
     * @param cursorMark   光标位置：false=无标记, true=标记
     */
    public JSONObject shell_write(String sessionId, String text,
                                   String returnMode, int lines,
                                   boolean colorEscape, boolean cursorMark) {
        JSONObject err = checkInit();
        if (err != null) return err;

        if (sessionId == null) {
            return errorJson("INVALID_SESSION_ID", "session_id cannot be null");
        }
        if (text == null) {
            return errorJson("INVALID_TEXT", "text cannot be null");
        }

        PtySession session = sessionPool.get(sessionId);
        if (session == null) {
            return errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId);
        }

        if (returnMode == null) returnMode = "last_20";
        if (lines <= 0) lines = DEFAULT_RETURN_LINES;

        synchronized (session.lock) {
            if (!session.sendKey(text)) {
                return errorJson("SESSION_DEAD", "Session is not running: " + sessionId);
            }

            try { Thread.sleep(SCREEN_SETTLE_MS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return errorJson("INTERRUPTED", "Interrupted");
            }

            String rendered = renderScreen(session, colorEscape, cursorMark);
            String[] modeResult = applyReturnMode(rendered, returnMode, lines);

            try {
                JSONObject result = successJson();
                result.put("content", modeResult[0]);
                result.put("line_count", Integer.parseInt(modeResult[1]));
                return attachSessionInfo(result, session);
            } catch (Exception e) {
                return errorJson("READ_FAILED", e.getMessage());
            }
        }
    }

    /** shell_write 简化版。 */
    public JSONObject shell_write(String sessionId, String text) {
        return shell_write(sessionId, text, "last_20", DEFAULT_RETURN_LINES, false, false);
    }

    public JSONObject shell_write(String sessionId, String text,
                                   int returnLines, boolean stripAnsi) {
        return shell_write(sessionId, text,
            returnLines > 0 ? "last_n" : "last_20",
            returnLines > 0 ? returnLines : DEFAULT_RETURN_LINES,
            !stripAnsi, false);
    }

    // ── shell_send_key ──

    /**
     * 向会话发送控制键或方向键。
     * 支持 Ctrl+A~Z, Tab, Enter, Escape, Backspace, Delete,
     * Up, Down, Left, Right, PageUp, PageDown, Home, End, F1~F12
     *
     * @param returnMode  返回模式：last_20（默认）、last_n、all
     * @param lines        last_n 时有效
     * @param colorEscape  颜色转义：false=纯文本（清除ANSI）, true=中文样式标签
     * @param cursorMark   光标位置：false=无标记, true=标记
     */
    public JSONObject shell_send_key(String sessionId, String key,
                                      String returnMode, int lines,
                                      boolean colorEscape, boolean cursorMark) {
        JSONObject err = checkInit();
        if (err != null) return err;

        if (sessionId == null) {
            return errorJson("INVALID_SESSION_ID", "session_id cannot be null");
        }

        String canonicalKey = normalizeKeyName(key);
        String ansi = KEY_ANSI_MAP.get(canonicalKey);
        if (ansi == null) {
            return errorJson("INVALID_KEY", "Unsupported key: " + key
                + ". Supported: Ctrl+A~Z, Tab, Enter, Escape, Backspace, Delete, "
                + "Up, Down, Left, Right, PageUp, PageDown, Home, End, F1~F12");
        }

        PtySession session = sessionPool.get(sessionId);
        if (session == null) {
            return errorJson("SESSION_NOT_FOUND", "Session not found: " + sessionId);
        }

        if (returnMode == null) returnMode = "last_20";
        if (lines <= 0) lines = DEFAULT_RETURN_LINES;

        synchronized (session.lock) {
            if (!session.sendKey(ansi)) {
                return errorJson("SESSION_DEAD", "Session is not running: " + sessionId);
            }

            try { Thread.sleep(SCREEN_SETTLE_MS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return errorJson("INTERRUPTED", "Interrupted");
            }

            String rendered = renderScreen(session, colorEscape, cursorMark);
            String[] modeResult = applyReturnMode(rendered, returnMode, lines);

            try {
                JSONObject result = successJson();
                result.put("content", modeResult[0]);
                result.put("line_count", Integer.parseInt(modeResult[1]));
                return attachSessionInfo(result, session);
            } catch (Exception e) {
                return errorJson("READ_FAILED", e.getMessage());
            }
        }
    }

    /** shell_send_key 简化版。 */
    public JSONObject shell_send_key(String sessionId, String key) {
        return shell_send_key(sessionId, key, "last_20", DEFAULT_RETURN_LINES, false, false);
    }

    public JSONObject shell_send_key(String sessionId, String key,
                                      int returnLines, boolean stripAnsi) {
        return shell_send_key(sessionId, key,
            returnLines > 0 ? "last_n" : "last_20",
            returnLines > 0 ? returnLines : DEFAULT_RETURN_LINES,
            !stripAnsi, false);
    }

    // ================================================================
    //  输出读取 — Agent API
    // ================================================================

    // ── shell_read（当前画布） ──

    /**
     * 读取终端当前画布（屏幕可见区域）内容。
     *
     * @param returnMode   返回模式：last_20（默认）、last_n（需 n）、all
     * @param lines        last_n 时有效
     * @param colorEscape  颜色转义：false=纯文本（清除ANSI）, true=中文样式标签
     * @param cursorMark   光标位置：false=无标记, true=标记
     */
    public JSONObject shell_read(String sessionId, String returnMode, int lines,
                                  boolean colorEscape, boolean cursorMark) {
        JSONObject err = checkInit();
        if (err != null) return err;

        SessionResult sr = requireAliveSession(sessionId);
        if (sr.error != null) return sr.error;

        if (returnMode == null) returnMode = "last_20";
        if (lines <= 0) lines = DEFAULT_RETURN_LINES;
        if (lines > SHELL_READ_MAX_LINES) lines = SHELL_READ_MAX_LINES;

        try {
            String rendered;
            synchronized (sr.session.lock) {
                rendered = renderScreen(sr.session, colorEscape, cursorMark);
            }

            String[] modeResult = applyReturnMode(rendered, returnMode, lines);

            JSONObject result = successJson();
            result.put("content", modeResult[0]);
            result.put("line_count", Integer.parseInt(modeResult[1]));
            return attachSessionInfo(result, sr.session);

        } catch (Exception e) {
            return errorJson("READ_FAILED", e.getMessage());
        }
    }

    /** shell_read 默认参数版本（last_20, false, false）。 */
    public JSONObject shell_read(String sessionId) {
        return shell_read(sessionId, "last_20", DEFAULT_RETURN_LINES, false, false);
    }

    public JSONObject shell_read(String sessionId, String mode, int lines,
                                  boolean stripAnsi, boolean showCursor,
                                  boolean preserveRaw) {
        String returnMode = "all".equals(mode) ? "all"
                          : ("new".equals(mode) ? "all" : "last_n");
        boolean cursorMark = showCursor || preserveRaw;
        return shell_read(sessionId, returnMode, lines, !stripAnsi, cursorMark);
    }

    // ── read_history_canvas（历史画布 + 归档） ──

    /**
     * 读取终端自创建至今的全部历史输出（含已滚出屏幕的内容）。
     * 每次调用会生成一个带时间戳的归档文件用于审计。
     *
     * <p>无光标参数（历史记录不维护实时光标状态）。</p>
     *
     * @param returnMode   返回模式：last_20（默认）、last_n（需 n）、all
     * @param lines        last_n 时有效
     * @param colorEscape  颜色转义：false=纯文本（清除ANSI）, true=中文样式标签
     */
    public JSONObject read_history_canvas(String sessionId, String returnMode,
                                           int lines, boolean colorEscape) {
        JSONObject err = checkInit();
        if (err != null) return err;

        SessionResult sr = requireAliveSession(sessionId);
        if (sr.error != null) return sr.error;

        if (returnMode == null) returnMode = "last_20";
        if (lines <= 0) lines = DEFAULT_RETURN_LINES;
        if (lines > SHELL_READ_MAX_LINES) lines = SHELL_READ_MAX_LINES;

        try {
            String rendered;
            synchronized (sr.session.lock) {
                // 历史画布不支持光标标记
                rendered = renderScreen(sr.session, colorEscape, false);
            }

            String[] modeResult = applyReturnMode(rendered, returnMode, lines);

            // 归档始终使用 getScreenText（原始缓冲区内容）
            String archiveRaw;
            synchronized (sr.session.lock) {
                archiveRaw = getScreenText(sr.session, true);
            }
            String archivePath = writeHistoryArchive(sessionId, archiveRaw);

            JSONObject result = successJson();
            result.put("content", modeResult[0]);
            result.put("line_count", Integer.parseInt(modeResult[1]));
            if (archivePath != null) {
                result.put("archive_file", archivePath);
            }
            return attachSessionInfo(result, sr.session);

        } catch (Exception e) {
            return errorJson("READ_FAILED", e.getMessage());
        }
    }

    /** read_history_canvas 默认参数版本（last_20, false）。 */
    public JSONObject read_history_canvas(String sessionId) {
        return read_history_canvas(sessionId, "last_20", DEFAULT_RETURN_LINES, false);
    }

    public JSONObject read_history_canvas(String sessionId, String mode,
                                           int lines, boolean stripAnsi,
                                           boolean preserveRaw) {
        return read_history_canvas(sessionId,
            "all".equals(mode) ? "all" : "last_n",
            lines, !stripAnsi);
    }

    // ================================================================
    //  调试视图 — Agent API
    // ================================================================

    public JSONObject shell_get_debug_view(String sessionId,
                                            boolean showLineNumbers,
                                            boolean showStyles,
                                            boolean showCursor) {
        JSONObject err = checkInit();
        if (err != null) return err;

        SessionResult sr = requireAliveSession(sessionId);
        if (sr.error != null) return sr.error;

        try {
            String view;
            synchronized (sr.session.lock) {
                view = dumpStyledScreen(sr.session, showCursor);
            }

            if (!showLineNumbers) {
                view = view.replaceAll("(?m)^\\[\\d+\\] ", "");
            }
            if (!showStyles) {
                view = view.replaceAll("\\[[^\\]]+\\] ", "");
            }
            view = view.trim();

            JSONObject result = successJson();
            result.put("view", view);
            return result;
        } catch (Exception e) {
            return errorJson("READ_FAILED", e.getMessage());
        }
    }

    public JSONObject shell_get_debug_view(String sessionId) {
        return shell_get_debug_view(sessionId, true, true, true);
    }

    // ================================================================
    //  UI 层辅助方法（非 JSON，保持向后兼容供 Activity 轮询使用）
    // ================================================================

    public Map<String, Boolean> getSessionStatus() {
        Map<String, Boolean> result = new HashMap<>();
        for (Map.Entry<String, PtySession> e : sessionPool.entrySet()) {
            result.put(e.getKey(), e.getValue().isAlive());
        }
        return result;
    }

    public Map<String, String> getKnownSessions() {
        return loadSessionMeta();
    }

    public String getSessionPlainScreen(String sessionId) {
        if (!initialized || !envReady || sessionId == null) return "";
        PtySession s = sessionPool.get(sessionId);
        if (s != null && s.isAlive()) {
            synchronized (s.lock) {
                return getScreenText(s);
            }
        }
        return "";
    }

    public String getSessionStyledScreen(String sessionId, boolean showCursor) {
        if (!initialized || !envReady || sessionId == null) return "";
        PtySession s = sessionPool.get(sessionId);
        if (s != null && s.isAlive()) {
            synchronized (s.lock) {
                return dumpStyledScreen(s, showCursor);
            }
        }
        return "";
    }

    public String getDefaultSessionScreen() {
        return getSessionPlainScreen("default");
    }

    public String getCwd(String sessionId) {
        if (!initialized || !envReady || sessionId == null) return "";
        PtySession s = sessionPool.get(sessionId);
        if (s == null || !s.isAlive()) return "";
        synchronized (s.lock) {
            return doGetCwd(s);
        }
    }

    // ================================================================
    //  内部与会话创建
    // ================================================================

    private PtySession createViaService(String sessionId) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String workingDir = TermuxConstants.TERMUX_HOME_DIR_PATH;

        TermuxSession termuxSession = termuxService.createTermuxSession(
                bashPath, null, null, workingDir, false, sessionId);
        if (termuxSession == null) return null;

        termuxSession.getExecutionCommand().isPermanent = true;
        termuxService.addPermanentSessionHandle(termuxSession.getTerminalSession().mHandle);

        TerminalSession terminalSession = termuxSession.getTerminalSession();
        if (terminalSession == null) return null;

        PtySession session = new PtySession(sessionId, terminalSession);
        sessionPool.put(sessionId, session);
        return session;
    }

    private PtySession createDirect(String sessionId) {
        String prefixBin = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
        String shellPath = prefixBin + "/bash";
        if (!new File(shellPath).isFile()) {
            shellPath = prefixBin + "/sh";
            if (!new File(shellPath).isFile()) {
                return null;
            }
        }
        String workingDir = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String[] env = termuxEnv != null
                ? toEnvArray(termuxEnv.getEnvironment(context, false))
                : new String[0];

        PtySession session = new PtySession(sessionId, shellPath, workingDir, env);

        if (!session.isAlive()) {
            session.close();
            return null;
        }

        sessionPool.put(sessionId, session);
        return session;
    }

    // ================================================================
    //  内部工具：屏幕读取
    // ================================================================

    /** 从 TerminalEmulator 读取全部文本。调用方须持有 session.lock。 */
    private String getScreenText(PtySession session) {
        return getScreenText(session, false);
    }

    private String getScreenText(PtySession session, boolean preserveRaw) {
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

            if (!preserveRaw) {
                if (isRowBlank(row, cols)) continue;
            }

            StringBuilder line = new StringBuilder();
            for (int col = 0; col < cols; col++) {
                int idx = row.findStartOfColumn(col);
                if (idx >= 0 && idx < row.mText.length) {
                    char ch = row.mText[idx];
                    if (ch != '\0') line.append(ch);
                }
            }

            if (preserveRaw) {
                sb.append(line).append('\n');
            } else {
                String lineStr = line.toString().replaceAll("\\s+$", "");
                if (lineStr.isEmpty()) continue;
                sb.append(lineStr).append('\n');
            }
        }
        return sb.toString();
    }

    private String getIncrementalOutput(String sessionId, String currentText) {
        String[] currentLines = currentText.split("\n", -1);
        int currentCount = currentLines.length;

        Integer lastCount = lastReadLineCount.get(sessionId);
        if (lastCount == null || currentCount <= lastCount) {
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

    // ================================================================
    //  内部工具：历史归档
    // ================================================================

    private String writeHistoryArchive(String sessionId, String rawContent) {
        if (context == null) return null;
        try {
            File dir = new File(context.getFilesDir(), HISTORY_ARCHIVE_DIR);
            if (!dir.exists() && !dir.mkdirs()) return null;

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date());
            String safeName = sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
            File file = new File(dir, safeName + "_" + timestamp + ".log");

            FileWriter fw = new FileWriter(file);
            fw.write(rawContent);
            fw.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    //  内部工具：UI 用 getCwd
    // ================================================================

    private String doGetCwd(PtySession session) {
        if (session == null || !session.isAlive()) return "";
        try {
            String before = getScreenText(session);
            int beforeLineCount = before.isEmpty() ? 0 : before.split("\n", -1).length;

            session.sendKey("pwd\r");
            Thread.sleep(200);

            String after = getScreenText(session);
            String[] lines = after.split("\n", -1);

            for (int i = beforeLineCount; i < lines.length && i >= 0; i++) {
                String t = lines[i].trim();
                if (t.startsWith("/")) {
                    return t.replaceAll("[\\r\\n]", "").trim();
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================
    //  dumpStyledScreen — 结构化样式输出
    //  ===== 保留不变，按你的要求不修改 =====
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
