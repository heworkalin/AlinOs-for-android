package alin.android.alinos.localshell;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import com.termux.app.TermuxService;

import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import org.json.JSONArray;
import org.json.JSONObject;

import android.util.Log;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * LocalShell —— Agent 用本地纯净终端工具集。
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
 * <p>前置条件：{@code LocalShellEnvironment.init(context)}、
 *    {@link # ini t(  Context, LocalShellService)} 均已完成。</p>
 */
public class LocalShellExecutor {

    private static final String TAG = "LocalShellExecutor";

    // ── 默认值 ──
    private static final int DEFAULT_RETURN_LINES = 20;
    private static final long SHELL_EXEC_DEFAULT_WAIT_MS = 200;
    private static final long SHELL_EXEC_MIN_WAIT_MS = 200;
    private static final long SHELL_EXEC_MAX_WAIT_MS = 1200;
    private static final long SCREEN_SETTLE_MS = 150;
    private static final int SESSION_ID_MAX_LENGTH = 64;
    private static final int SHELL_READ_MAX_LINES = 5000;
    private static final int MAX_RECENT_COMMANDS = 3;
    /** 等待 LocalShellService 绑定的最大时间（毫秒）。 */
    private static final long SERVICE_BIND_WAIT_MAX_MS = 3000;
    private static final String HISTORY_ARCHIVE_DIR = "localshell_history";

    private static final String[] ANSI_COLOR_NAMES = {
        "黑色", "红色", "绿色", "黄色", "蓝色", "紫色", "青色", "白色",
        "暗灰", "亮红", "亮绿", "亮黄", "亮蓝", "亮紫", "亮青", "亮白"
    };

    // ── 按键枚举 → ANSI 序列映射 ──
    private static final Map<String, String> KEY_ANSI_MAP = new HashMap<>();
    static {
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

        KEY_ANSI_MAP.put("TAB", "\t");
        KEY_ANSI_MAP.put("ENTER", "\r");
        KEY_ANSI_MAP.put("ESCAPE", "\033");
        KEY_ANSI_MAP.put("BACKSPACE", "\177");
        KEY_ANSI_MAP.put("DELETE", "\033[3~");

        KEY_ANSI_MAP.put("UP", "\033[A");
        KEY_ANSI_MAP.put("DOWN", "\033[B");
        KEY_ANSI_MAP.put("LEFT", "\033[D");
        KEY_ANSI_MAP.put("RIGHT", "\033[C");

        KEY_ANSI_MAP.put("PAGE_UP", "\033[5~");
        KEY_ANSI_MAP.put("PAGE_DOWN", "\033[6~");
        KEY_ANSI_MAP.put("HOME", "\033[H");
        KEY_ANSI_MAP.put("END", "\033[F");

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
    private static volatile LocalShellExecutor instance;

    private LocalShellExecutor() {}

    public static LocalShellExecutor getInstance() {
        if (instance == null) {
            synchronized (LocalShellExecutor.class) {
                if (instance == null) {
                    instance = new LocalShellExecutor();
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
    private LocalShellService localShellService;
    private LocalShellEnvironment shellEnv;
    private volatile boolean initialized = false;
    private volatile boolean envReady = false;

    private SharedPreferences mSessionPrefs;
    private static final String PREFS_NAME = "localshell_session_meta";
    private static final String KEY_SESSION_NAMES = "session_id_name_map";

    private static volatile Context appContext;

    /**
     * 注入应用 Context 并异步绑定 LocalShellService。
     * 应在 Application 或首个 Activity 启动时调用一次，确保后续
     * {@link #create_session} 调用时服务已就绪。
     */
    public static void provideContext(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
            Log.d(TAG, "provideContext: appContext set");
        }
    }

    private JSONObject ensureInitialized() {
        // 首次调用时初始化环境检测
        if (!initialized) {
            if (appContext == null) {
                return errorJson("NOT_INITIALIZED", "Call provideContext() first");
            }
            this.context = appContext;
            this.shellEnv = new LocalShellEnvironment();
            this.initialized = true;
            envReady = checkEnvironmentReady();
            initPersistence(appContext);
            LocalShellEnvironment.init(appContext);
            Log.d(TAG, "ensureInitialized: first init, envReady=" + envReady);
        }
        if (!envReady) return errorJson("ENV_NOT_READY", MSG_ENV_NOT_READY);
        // 每次 create_session 都确保服务已绑定
        if (localShellService == null) {
            ensureServiceBound();
        }
        if (localShellService == null) {
            return errorJson("SERVICE_NOT_AVAILABLE",
                "LocalShellService not bound after " + SERVICE_BIND_WAIT_MAX_MS + "ms");
        }
        return null;
    }

    /** 确保 LocalShellService 已启动并绑定，每次调用创建新的 CountDownLatch 等待。 */
    private void ensureServiceBound() {
        Log.d(TAG, "ensureServiceBound: binding, thread=" + Thread.currentThread().getName());
        mServiceBindLatch = new CountDownLatch(1);
        try {
            Intent intent = new Intent(context, LocalShellService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            context.bindService(intent, mServiceConnection, 0);
            boolean ok = mServiceBindLatch.await(SERVICE_BIND_WAIT_MAX_MS, TimeUnit.MILLISECONDS);
            Log.d(TAG, "ensureServiceBound: await=" + ok + ", service="
                + (localShellService != null ? "bound" : "null"));
        } catch (Exception e) {
            Log.e(TAG, "ensureServiceBound error: " + e.getMessage());
        }
    }

    private boolean mServiceBound = false;
    private volatile boolean mServiceBindingInitiated = false;
    private LocalShellService mLocalService = null;
    private CountDownLatch mServiceBindLatch;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mLocalService = (LocalShellService) ((TermuxService.LocalBinder) service).service;
            mServiceBound = true;
            if (LocalShellExecutor.this.localShellService == null) {
                LocalShellExecutor.this.localShellService = mLocalService;
            }
            Log.d(TAG, "onServiceConnected: bound OK, latch countdown");
            if (mServiceBindLatch != null) mServiceBindLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected: service died, cleaning up");
            mLocalService = null;
            mServiceBound = false;

            for (PtySession s : sessionPool.values()) {
                s.close();
            }
            sessionPool.clear();
            lastReadLineCount.clear();

            initialized = false;
            envReady = false;
            localShellService = null;
        }
    };

    private static final String MSG_ENV_NOT_READY =
            "LocalShell 环境尚未初始化，请先完成初始化。\n"
          + "预期路径: " + LocalShellConstants.PREFIX_DIR_PATH;

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
            @Override public void onSessionFinished(TerminalSession s) {
                Log.d(TAG, "PtySession.onSessionFinished: sid=" + PtySession.this.id
                    + ", exitCode=" + s.getExitStatus());
            }
            @Override public void onTitleChanged(TerminalSession s) {}
            @Override public void onCopyTextToClipboard(TerminalSession s, String t) {}
            @Override public void onPasteTextFromClipboard(TerminalSession s) {}
            @Override public void onBell(TerminalSession s) {}
            @Override public void onColorsChanged(TerminalSession s) {}
            @Override public void onTerminalCursorStateChange(boolean state) {}
            @Override public void setTerminalShellPid(TerminalSession s, int pid) {
                Log.d(TAG, "PtySession.setTerminalShellPid: sid=" + PtySession.this.id + ", pid=" + pid);
            }
            @Override public Integer getTerminalCursorStyle() { return null; }
            @Override public void logError(String t, String m) {}
            @Override public void logWarn(String t, String m) {}
            @Override public void logInfo(String t, String m) {}
            @Override public void logDebug(String t, String m) {}
            @Override public void logVerbose(String t, String m) {}
            @Override public void logStackTraceWithMessage(String t, String m, Exception e) {}
            @Override public void logStackTrace(String t, Exception e) {}
        };

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
    //  初始化（环境检测 + 持久化）
    // ================================================================

    private void initPersistence(Context ctx) {
        if (mSessionPrefs != null) return;
        mSessionPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void bindLocalShellService(Context ctx) {
        mServiceBindingInitiated = true;
        mServiceBindLatch = new CountDownLatch(1);
        try {
            Intent intent = new Intent(ctx, LocalShellService.class);
            Log.d(TAG, "bindLocalShellService: starting service " + LocalShellService.class.getSimpleName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
            boolean bound = ctx.bindService(intent, mServiceConnection, 0);
            Log.d(TAG, "bindLocalShellService: bindService returned " + bound);
        } catch (Exception e) {
            Log.e(TAG, "bindLocalShellService: exception " + e.getMessage(), e);
            mServiceBindLatch.countDown();
        }
    }

    public boolean isInitialized() { return initialized; }

    public boolean isEnvironmentReady() { return initialized && envReady; }

    private boolean checkEnvironmentReady() {
        try {
            return new File(LocalShellConstants.PREFIX_DIR_PATH).isDirectory();
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

    private String renderScreen(PtySession session, boolean colorEscape, boolean cursorMark) {
        if (!colorEscape && !cursorMark) {
            return getScreenText(session);
        }

        String output = dumpStyledScreen(session, cursorMark);

        if (!colorEscape) {
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

    private static String[] applyReturnMode(String text, String returnMode, int lines) {
        String output;
        switch (returnMode) {
            case "last_n":
                output = getLastNLines(text, lines);
                break;
            case "all":
                output = text.trim();
                break;
            default:
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

    public JSONObject create_session(String sessionId, String sessionName) {
        JSONObject err;

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

            Log.d(TAG, "create_session: sid=" + sid + ", poolSize=" + sessionPool.size());
            try {
                PtySession session = createViaService(sid);
                if (session == null) {
                    return errorJson("CREATE_FAILED", "Failed to create session: " + sid);
                }
                Log.d(TAG, "create_session: created, alive=" + session.isAlive()
                    + ", viaService=" + session.createdViaService
                    + ", running=" + session.session.isRunning()
                    + ", pid=" + (session.session.isRunning() ? "alive" : session.session.getExitStatus()));
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
            if (localShellService != null && session.createdViaService) {
                localShellService.removeTermuxSession(session.session);
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
        Log.d(TAG, "shell_exec: sessionId=" + sessionId + ", alive=" + session.isAlive()
            + ", cmd=" + command.substring(0, Math.min(command.length(), 80)));

        if (waitMs <= 0) waitMs = SHELL_EXEC_DEFAULT_WAIT_MS;
        if (waitMs < SHELL_EXEC_MIN_WAIT_MS) waitMs = SHELL_EXEC_MIN_WAIT_MS;
        if (waitMs > SHELL_EXEC_MAX_WAIT_MS) waitMs = SHELL_EXEC_MAX_WAIT_MS;
        if (returnMode == null) returnMode = "last_20";
        if ("all".equals(returnMode)) returnMode = "last_20";
        if (lines <= 0) lines = DEFAULT_RETURN_LINES;

        String cmd = command.trim();

        synchronized (session.lock) {
            if (!session.isAlive()) {
                return errorJson("SESSION_DEAD",
                    "Session is not running, cannot send command: " + sessionId);
            }
            session.recordCommand(cmd);
            session.sendKey(cmd + "\r");

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

    // ── shell_send_keys (批量按键) ──

    /** 批量发送按键，用 '|' 分隔，如 "Down|Down|Enter" */
    public JSONObject shell_send_keys(String sessionId, String keys,
                                       String returnMode, int lines,
                                       boolean colorEscape, boolean cursorMark) {
        if (keys == null || keys.isEmpty()) {
            return shell_send_key(sessionId, "Enter", returnMode, lines, colorEscape, cursorMark);
        }
        if (!keys.contains("|")) {
            return shell_send_key(sessionId, keys, returnMode, lines, colorEscape, cursorMark);
        }

        String[] keyArray = keys.split("\\|");
        JSONObject lastResult = null;
        for (int i = 0; i < keyArray.length; i++) {
            String k = keyArray[i].trim();
            if (k.isEmpty()) continue;
            boolean isLast = (i == keyArray.length - 1);
            lastResult = shell_send_key(sessionId, k,
                isLast ? returnMode : "last_20",
                isLast ? lines : 20,
                isLast ? colorEscape : true,
                isLast ? cursorMark : false);
            // 非最后按键时增加间隔
            if (!isLast) {
                try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        return lastResult != null ? lastResult : errorJson("INVALID_KEY", "No valid keys in: " + keys);
    }

    // ================================================================
    //  输出读取 — Agent API
    // ================================================================

    // ── shell_read ──

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

    // ── read_history_canvas ──

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
                rendered = renderScreen(sr.session, colorEscape, false);
            }

            String[] modeResult = applyReturnMode(rendered, returnMode, lines);

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
        String workingDir = LocalShellConstants.HOME_DIR_PATH;
        Log.d(TAG, "createViaService: sid=" + sessionId + ", cwd=" + workingDir);

        TermuxSession termuxSession = localShellService.createTermuxSession(
                null, null, null, workingDir, false, sessionId);
        Log.d(TAG, "createViaService: TermuxSession=" + (termuxSession != null ? "created" : "NULL"));
        if (termuxSession == null) return null;

        TerminalSession terminalSession = termuxSession.getTerminalSession();
        if (terminalSession == null) {
            Log.w(TAG, "createViaService: terminalSession is null");
            return null;
        }
        Log.d(TAG, "createViaService: terminalSession running=" + terminalSession.isRunning());

        PtySession session = new PtySession(sessionId, terminalSession);
        sessionPool.put(sessionId, session);
        Log.d(TAG, "createViaService: PtySession created, alive=" + session.isAlive()
            + ", createdViaService=" + session.createdViaService);
        return session;
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
    //  内部工具：屏幕读取
    // ================================================================

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

    private static String[] toEnvArray(HashMap<String, String> envMap) {
        if (envMap == null) return new String[0];
        String[] arr = new String[envMap.size()];
        int i = 0;
        for (Map.Entry<String, String> e : envMap.entrySet()) {
            arr[i++] = e.getKey() + "=" + e.getValue();
        }
        return arr;
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
}
