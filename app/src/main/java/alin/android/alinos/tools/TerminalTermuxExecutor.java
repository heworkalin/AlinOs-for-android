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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Termux Shell 标准化接口执行器。
 *
 * <p>对外提供且仅提供四个标准函数 + 一个快捷辅助函数：
 * <ol>
 *   <li>{@link #execPersistentShell(String, String, boolean, String, long, Object, boolean, boolean)}</li>
 *   <li>{@link #createPersistentSession(String)}</li>
 *   <li>{@link #closePersistentSession(String)}</li>
 *   <li>{@link #execTemporaryShell(String, boolean)}</li>
 * </ol>
 * 快捷辅助：{@link #execPersistentCommand(String)}
 *
 * <p>调用前必须确保：
 * <ul>
 *   <li>{@code TermuxApplication.init(context)} 已调用</li>
 *   <li>{@code TermuxShellEnvironment.init(context)} 已调用</li>
 *   <li>本执行器的 {@link #init(Context, TermuxService)} 已完成</li>
 * </ul>
 *
 * <p>线程安全：所有 PTY 读写、会话池操作均同步处理。
 *
 * <p>注意：当使用 TermuxService 创建会话时，{@link #execPersistentShell} 需在 UI 线程调用
 * （TermuxService 内部 ListView 适配器不支持并发修改）。
 */
/**
 感谢您的纠正，我理解了：**行号和ANSI样式标记** 由 `stripFormat` 控制（默认 `false` 即保留），**光标标记**（`[ ]`、`█`、`(标)`）由另一个独立参数控制，默认 **`false` 不显示**。

因此，`execPersistentShell` 的参数列表必须增加第 8 个参数 `showCursor`，类型 `boolean`，默认值 `false`。  
下面是根据您的指示修正后的最终规范，其他所有逻辑不变。

---

# AlinOs Termux Shell 接口函数规范文档（修正版）

## 文档目的
本接口规范定义 **永久会话模式** 与 **临时会话模式** 两套执行体系，共 **4 个标准函数** + 1 个快捷辅助函数，**参数默认值、业务逻辑、边界行为、返回规则全部固化**；所有细节、默认行为、隐式逻辑全部写明，无任何隐含规则，确保任意 AI 可直接理解并生成代码。

---

## 一、永久会话体系（长驻 PTY 交互会话，自动生命周期管理）

### 1. 核心主函数：`execPersistentShell`

```java
public String execPersistentShell(
    String sessionId,
    String inputText,
    boolean appendEnter,
    String specialKeySeq,
    long waitMs,
    Object fetchRule,
    boolean stripFormat,
    boolean showCursor
)
```

**功能总述**  
永久模式统一入口，99% 业务场景仅需调用此函数：  
- 自动检测目标会话是否存在，**不存在则自动创建**  
- 支持发送命令 / 纯文本 / 特殊按键  
- 发送后阻塞等待终端渲染  
- 按规则截取终端输出并格式化返回  
- 输出自动附加 **行号前缀** 与 **具名 ANSI 样式标记**（通过 `stripFormat` 控制）  
- 可选择显示 **光标占位标记**（通过 `showCursor` 控制）  
- 所有参数支持默认值，可无参直接调用  

**参数明细（顺序不可变，默认值严格固定）**  

| 序号 | 参数名 | 类型 | 默认值 | 完整业务描述 |
|------|--------|------|--------|--------------|
| 1 | sessionId | String | `"default"` | 目标永久会话唯一标识；<br>不传则使用默认会话；<br>函数内部自动检测：不存在则自动新建，已存在则直接复用 |
| 2 | inputText | String | `""` | 要发送的 Shell 命令、纯文本内容；<br>空字符串 = 不发送任何内容，仅刷新当前终端画面 |
| 3 | appendEnter | boolean | `false` | 是否在输入内容末尾**自动追加回车符 `\r`**；<br>默认不追加，仅发送文本不执行；<br>设为 `true` 用于执行命令 |
| 4 | specialKeySeq | String | `null` | 特殊按键 ANSI 序列（方向键、Tab、ESC、Ctrl 组合键等）；<br>`null` = 不发送任何特殊按键；<br>与 `inputText` 互斥，二选一执行 |
| 5 | waitMs | long | `300` | 发送操作完成后，**阻塞等待终端渲染完成的毫秒时长**；<br>默认 300ms，适配绝大多数命令；<br>长耗时交互命令可调大 |
| 6 | fetchRule | Object | `20` | 输出截取规则，**永远从终端最后一行向上截取**：<br>1. 数字 `Integer N`：返回最后 N 行；<br>2. 字符串 `"all"`：返回终端全部历史内容；<br>3. `null`：不截取、不返回任何内容，仅执行操作 |
| 7 | stripFormat | boolean | `false` | **行号与样式标记** 控制：<br>`false` = **保留行号、ANSI 样式标记**，返回结构化终端画布；<br>`true` = 移除所有行号、样式标记及 ANSI 控制符，仅输出纯文本内容 |
| 8 | showCursor | boolean | `false` | **光标标记** 控制：<br>`false` = **不显示**光标占位符（`[ ]`、`█`、`(标)`）；<br>`true` = 在当前光标位置渲染对应的占位标记，便于调试或精准定位 |

**隐式内置逻辑（必须明确）**  

1. **自动会话兜底**：目标 `sessionId` 不存在 → 自动调用 `createPersistentSession` 创建  
2. **行号与样式标记**（由 `stripFormat` 控制）：每行输出以 `[行号]` 开头；ANSI 颜色 / 效果自动转换为具名标记（如 `[红色]`、`[粗体|亮青]` 等），内嵌于行内  
3. **光标标记**（仅在 `showCursor = true` 时出现）：  
   - 光标所在单词两侧加 `[` `]`  
   - 光标位于空格上时渲染为 `█`  
   - 光标位于字符右侧时追加 `(标)`  
4. **线程安全**：所有 PTY 读写、会话状态操作均同步处理  
5. **异常兜底**：会话死亡时自动重建默认会话  

**重载版本（完全继承全参默认逻辑）**  

```java
// 1. 无参调用（仅刷新画面，返回最后 20 行，带行号与样式，无光标）
public String execPersistentShell()

// 2. 仅传输入文本（不追加回车，返回最后 20 行，带行号与样式，无光标）
public String execPersistentShell(String inputText)

// 3. 快捷执行命令（自动追加回车，返回最后 20 行，带行号与样式，无光标）
public String execPersistentCommand(String command)
```

`execPersistentCommand` 等价于 `execPersistentShell("default", command, true, null, 300, 20, false, false)`，专为“输入命令并立刻执行”场景提供。

**返回值规则**  
- 成功：返回按 `fetchRule` + `stripFormat` + `showCursor` 处理后的终端文本  
- `fetchRule = null`：返回空字符串 `""`  
- 失败：返回错误信息纯文本  

---

### 2. 创建函数：`createPersistentSession`

```java
public void createPersistentSession(String sessionId)
```

**功能总述**  
手动创建指定 ID 的永久 PTY 会话。  
**注意**：日常业务无需调用，`execPersistentShell` 已自动封装。

| 参数名 | 类型 | 默认值 | 描述 |
|--------|------|--------|------|
| sessionId | String | 无（必填） | 会话唯一 ID，不能为空 / 空串；重复创建会直接跳过，不报错 |

**返回值**：void，失败仅打印日志。

---

### 3. 销毁函数：`closePersistentSession`

```java
public void closePersistentSession(String sessionId)
```

**功能总述**  
手动销毁指定 ID 的永久会话，释放 PTY 资源并从全局会话池移除。

| 参数名 | 类型 | 默认值 | 描述 |
|--------|------|--------|------|
| sessionId | String | 无（必填） | 要关闭的目标会话 ID；不存在则直接跳过，不报错 |

**返回值**：void，无返回。

---

## 二、临时会话体系（一次性 `bash -c` 调用，无状态、3s 超时）

### 唯一主函数：`execTemporaryShell`

```java
public String execTemporaryShell(String command, boolean stripFormat)
```

**功能总述**  
一次性执行 Shell 命令，**无常驻会话、无状态保留**；底层通过 `bash -c` 执行，强制 3 秒超时，超时自动杀进程。

| 序号 | 参数名 | 类型 | 默认值 | 完整业务描述 |
|------|--------|------|--------|--------------|
| 1 | command | String | 无（必填） | 要执行的完整 Shell 命令，**必须传入，不允许空** |
| 2 | stripFormat | boolean | `false` | 输出格式控制：<br>`false` = 保留 ANSI 颜色样式；<br>`true` = 仅返回 stdout/stderr 纯文本，剔除所有格式符 |

**固定隐式逻辑**  
1. 独立进程执行，3 秒超时强制销毁  
2. 不维护任何会话，执行后进程完全退出  
3. 返回包含 stdout、stderr、exit code 的结构化文本  
4. **无行号、无光标标记、无终端画布维护**  

**返回值规则**  
返回结构化执行结果文本（stdout、stderr、exit code、超时提示）。

---

## 三、输出格式详解与样例

### 永久模式（默认：行号与样式保留，无光标，`stripFormat = false`, `showCursor = false`）
每行格式：`[行号] [样式] 文本内容`
```
[0] [绿色] ~ [亮白] $ [默认]  ls 
[1] [默认] a.txt   crash.log
[2] [粗体|青色] 容器选择菜单.sh 
[50] [绿色] ~ [亮白] $ [默认]  help
```

### 永久模式（显示光标，`showCursor = true`）
在上一输出的基础上，当前光标行会出现标记：
```
[50] [绿色] ~ [亮白] $ [默认]  [mmmmmnnnnnnmmmmmmmmmmmmmsl](标)█
```
- 单词被 `[ ]` 包围  
- 光标位置标注 `(标)`  
- 光标所在空格渲染为 `█`  

### 永久模式纯文本（`stripFormat = true`，无论 `showCursor` 为何值，移除所有标记）
```
~ $ ls
a.txt   crash.log
容器选择菜单.sh
~ $ █
```

---

## 四、全局统一约定

1. **空值等价**：`null` 与空串 `""` 完全等价  
2. **时间单位**：所有超时 / 等待均为 **毫秒（ms）**  
3. **会话 ID**：大小写敏感，唯一不可重复  
4. **默认值固定**：所有默认值不随环境或版本变化  
5. **无黑盒行为**：所有隐式逻辑均已显式说明  

---

至此，规范完全对齐您的需求：行号和样式标记由 `stripFormat` 控制（默认保留），光标标记由 `showCursor` 独立控制（默认关闭）。`TerminalTermuxExecutor` 的实现应严格遵循本规范。
*/
public class TerminalTermuxExecutor {

    private static final String DEFAULT_SESSION_ID = "default";

    private static final String[] ANSI_COLOR_NAMES = {
        "黑色", "红色", "绿色", "黄色", "蓝色", "紫色", "青色", "白色",
        "暗灰", "亮红", "亮绿", "亮黄", "亮蓝", "亮紫", "亮青", "亮白"
    };

    // ── 单例 ──
    private static volatile TerminalTermuxExecutor instance;

    private TerminalTermuxExecutor() {}

    /**
     * 获取全局单例实例。进程内唯一，sessionPool 随实例持久保留。
     * Activity 重建后应调用 {@link #init(Context, TermuxService)} 刷新 TermuxService 引用。
     */
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
    //  会话池（实例级别，单例内全局唯一）
    // ────────────────────────────────────────────────────────────────
    private final HashMap<String, PtySession> sessionPool = new HashMap<>();

    private Context context;
    private TermuxService termuxService;
    private TermuxShellEnvironment termuxEnv;
    private volatile boolean initialized = false;
    private volatile boolean envReady = false;

    private static final String MSG_ENV_NOT_READY =
            "Termux 环境尚未初始化，请先在「Agent Main」中完成初始化。\n"
          + "预期路径: " + TermuxConstants.TERMUX_PREFIX_DIR_PATH;

    // ================================================================
    //  PtySession — 基于 TerminalSession 的 PTY 长驻会话
    //  TerminalSession 通过 JNI 创建伪终端，自动读取输出并解析 ANSI，
    //  无需手动管理 stdin/stdout 管道。
    // ================================================================
    static class PtySession {
        final String id;
        final TerminalSession session;
        final TerminalEmulator emulator;
        /** per-session 锁：序列化同一会话的写入与读取操作 */
        final Object lock = new Object();

        /** 最小化 TerminalSessionClient —— 用于直接创建的 TerminalSession */
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

        /** 直接创建新 PTY 会话（不依赖 TermuxService） */
        PtySession(String id, String shellPath, String cwd, String[] env) {
            this.id = id;
            this.session = new TerminalSession(shellPath, cwd, null, env,
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, sessionClient);
            this.session.updateSize(80, 24);
            this.emulator = session.getEmulator();
        }

        /** 包装 TermuxService 创建的已有 TerminalSession */
        PtySession(String id, TerminalSession existingSession) {
            this.id = id;
            this.session = existingSession;
            this.session.updateSize(80, 24);
            this.emulator = session.getEmulator();
        }

        /** 发送按键/文本序列到 PTY（非阻塞） */
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

    /**
     * 初始化执行器。
     * <p>初始化时会自动检测 Termux 环境是否就绪（检查 {@code $PREFIX} 目录是否存在）。
     * 环境未就绪时初始化失败，后续所有调用均返回错误提示。
     *
     * @param context Activity 或 Application Context
     * @param service TermuxService 实例（建议传入以支持 Termux 抽屉/通知管理；可为 null）
     * @return true=初始化成功且环境就绪；false=环境未就绪，无法使用
     */
    public boolean init(Context context, TermuxService service) {
        this.context = context.getApplicationContext();
        this.termuxService = service;
        this.termuxEnv = new TermuxShellEnvironment();
        this.initialized = true;

        // 检测 Termux 真实环境是否可用
        envReady = checkEnvironmentReady();
        return envReady;
    }

    /** 执行器是否已初始化 */
    public boolean isInitialized() {
        return initialized;
    }

    /** Termux 真实运行环境是否就绪（$PREFIX 目录存在） */
    public boolean isEnvironmentReady() {
        return initialized && envReady;
    }

    /**
     * 检测 Termux 前缀目录是否存在，判断环境是否已完成首次初始化。
     * 对应 Activity 中 {@code FileUtils.directoryFileExists(TermuxConstants.TERMUX_PREFIX_DIR_PATH, true)}。
     */
    private boolean checkEnvironmentReady() {
        try {
            return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH).isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    /** 环境未就绪时的统一错误提示 */
    private String envNotReadyError() {
        return "ERROR: " + MSG_ENV_NOT_READY;
    }

    // ================================================================
    //  1. execPersistentShell — 永久模式统一入口
    // ================================================================

    /**
     * 永久模式统一入口。
     *
     * <p>自动检测目标会话是否存在，不存在则自动创建；支持发送命令/文本/特殊按键；
     * 发送后阻塞等待终端渲染；按规则截取终端输出并返回。
     *
     * @param sessionId      目标会话 ID（null 或空串使用 {@code "default"}）
     * @param inputText      要发送的 Shell 命令或纯文本（空字符串仅刷新画面）
     * @param appendEnter    是否在末尾自动追加回车符 {@code \r}
     * @param specialKeySeq  特殊按键 ANSI 序列（与 inputText 互斥，非 null 时优先）
     * @param waitMs         发送完成后等待终端渲染的毫秒数；≤0 使用默认 300ms
     * @param fetchRule      输出截取规则：
     *                       <ul>
     *                         <li>{@code Integer N} → 返回最后 N 行</li>
     *                         <li>{@code String "all"} → 返回全部历史内容</li>
     *                         <li>{@code null} → 不返回任何内容，仅执行操作</li>
     *                       </ul>
     * @param stripFormat    {@code false}=保留行号与ANSI样式标记；{@code true}=仅纯文本
     * @param showCursor     {@code true}=渲染光标占位标记（[ ]/█/(标)）；{@code false}=不显示
     * @return 按 fetchRule + stripFormat + showCursor 处理后的终端文本；失败返回错误信息
     */
    public String execPersistentShell(
            String sessionId,
            String inputText,
            boolean appendEnter,
            String specialKeySeq,
            long waitMs,
            Object fetchRule,
            boolean stripFormat,
            boolean showCursor) {

        if (!initialized) return "ERROR: TerminalTermuxExecutor not initialized";
        if (!envReady) return envNotReadyError();

        // ── 参数归一化 ──
        if (sessionId == null || sessionId.isEmpty()) sessionId = DEFAULT_SESSION_ID;
        if (inputText == null) inputText = "";
        if (waitMs <= 0) waitMs = 300;

        // ── 获取或自动创建会话 ──
        PtySession session = getOrCreateSession(sessionId);
        if (session == null) {
            return "ERROR: Failed to create/get session: " + sessionId;
        }

        // ── session 级互斥：发送→等待→读取 原子化 ──
        String screenText;
        synchronized (session.lock) {
            // ── 发送操作：specialKeySeq 优先级高于 inputText ──
            if (specialKeySeq != null && !specialKeySeq.isEmpty()) {
                session.sendKey(specialKeySeq);
            } else if (!inputText.isEmpty()) {
                session.sendKey(appendEnter ? inputText + "\r" : inputText);
            }

            // ── 阻塞等待终端渲染 ──
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "ERROR: interrupted";
                }
            }

            // ── 按 fetchRule 截取 ──
            if (fetchRule == null) return "";

            if (stripFormat) {
                screenText = getScreenText(session);
            } else {
                screenText = dumpStyledScreen(session, showCursor);
            }
        }
        if (screenText.isEmpty()) return "";

        String result;
        if (fetchRule instanceof Integer) {
            result = getLastNLines(screenText, (Integer) fetchRule);
        } else if ("all".equals(fetchRule)) {
            result = screenText;
        } else {
            // 非预期类型，安全返回全部文本
            result = screenText;
        }

        if (stripFormat) {
            result = stripAnsiCodes(result);
        }

        return result;
    }

    /**
     * 全默认调用：使用 default 会话，不发送任何内容，仅刷新终端并返回最后 20 行（带格式，无光标）。
     * 等价于 {@code execPersistentShell("default", "", false, null, 300, 20, false, false)}。
     */
    public String execPersistentShell() {
        return execPersistentShell(DEFAULT_SESSION_ID, "", false, null, 300, 20, false, false);
    }

    /** 仅传输入文本（最常用重载）：使用 default 会话，不追加回车，返回最后 20 行（带格式，无光标）。
     * 等价于 {@code execPersistentShell("default", inputText, false, null, 300, 20, false, false)}。
     */
    public String execPersistentShell(String inputText) {
        return execPersistentShell(DEFAULT_SESSION_ID, inputText, false, null, 300, 20, false, false);
    }

    /**
     * 快捷执行命令：使用 default 会话，自动追加回车，返回最后 20 行（带格式，无光标）。
     * 等价于 {@code execPersistentShell("default", command, true, null, 300, 20, false, false)}。
     */
    public String execPersistentCommand(String command) {
        return execPersistentShell(DEFAULT_SESSION_ID, command, true, null, 300, 20, false, false);
    }

    /**
     * 6 参数便捷版本：省略 {@code stripFormat} 与 {@code showCursor}（均默认 {@code false}）。
     * 等价于 {@code execPersistentShell(sessionId, inputText, appendEnter, specialKeySeq, waitMs, fetchRule, false, false)}。
     */
    public String execPersistentShell(
            String sessionId,
            String inputText,
            boolean appendEnter,
            String specialKeySeq,
            long waitMs,
            Object fetchRule) {
        return execPersistentShell(sessionId, inputText, appendEnter, specialKeySeq, waitMs, fetchRule, false, false);
    }
    // ================================================================
    //  2. createPersistentSession
    // ================================================================

    /**
     * 手动创建指定 ID 的永久 PTY 会话。
     * 会话已存在则直接跳过（不报错）。
     *
     * @param sessionId 会话唯一 ID；null 或空串直接返回
     */
    public void createPersistentSession(String sessionId) {
        if (!initialized || sessionId == null || sessionId.isEmpty()) return;
        if (!envReady) return;
        getOrCreateSession(sessionId);
    }

    // ================================================================
    //  3. closePersistentSession
    // ================================================================

    /**
     * 手动销毁指定 ID 的永久会话，释放 PTY 资源并从全局会话池移除。
     * 会话不存在则直接跳过（不报错）。
     *
     * @param sessionId 目标会话 ID；null 或空串直接返回
     */
    public void closePersistentSession(String sessionId) {
        if (!initialized || sessionId == null || sessionId.isEmpty()) return;
        if (!envReady) return;
        PtySession session;
        synchronized (sessionPool) {
            session = sessionPool.remove(sessionId);
        }
        if (session != null) {
            synchronized (session.lock) {
                if (termuxService != null) {
                    termuxService.removePermanentSessionByName(session.id);
                }
                session.close();
            }
        }
    }

    // ================================================================
    //  4. execTemporaryShell — 临时一次性命令
    // ================================================================

    /**
     * 一次性执行 Shell 命令。
     *
     * <p>底层通过 {@code bash -c} 执行，强制 3 秒超时，超时自动杀进程。
     * 无穷驻会话、无状态保留，返回结构化执行结果文本。
     *
     * @param command     要执行的完整 Shell 命令（不可为空）
     * @param stripFormat {@code true}=仅返回纯文本（剔除 ANSI），{@code false}=保留格式
     * @return 包含 stdout、stderr、exit code、超时提示的结构化文本
     */
    public String execTemporaryShell(String command, boolean stripFormat) {
        if (!initialized) return "ERROR: TerminalTermuxExecutor not initialized";
        if (!envReady) return envNotReadyError();
        if (command == null || command.trim().isEmpty()) return "ERROR: empty command";

        StringBuilder result = new StringBuilder();
        Process process = null;

        try {
            String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
            String[] cmdArray = termuxEnv.setupShellCommandArguments(
                    bashPath, new String[]{"-c", command});
            HashMap<String, String> envMap = termuxEnv.getEnvironment(context, false);
            String[] envArray = toEnvArray(envMap);
            File workDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);

            process = Runtime.getRuntime().exec(cmdArray, envArray, workDir);

            boolean finished;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                finished = process.waitFor(3, TimeUnit.SECONDS);
            } else {
                // API < 26: Process.waitFor(long, TimeUnit) 不可用，用线程 + join 模拟超时
                Process p = process;
                Thread waiter = new Thread(() -> { try { p.waitFor(); } catch (InterruptedException ignored) { } });
                waiter.start();
                try {
                    waiter.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                finished = !waiter.isAlive();
                if (!finished) waiter.interrupt();
            }

            if (!finished) {
                process.destroy();
                // 等进程真正退出
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            String stdoutText = readStreamFully(process.getInputStream());
            String stderrText = readStreamFully(process.getErrorStream());
            int exitCode = process.exitValue();

            result.append("$ ").append(command).append("  [临时模式]\n");

            if (!finished) {
                result.append("── 超时 (3s) ──\n");
                result.append("命令未在 3 秒内完成，已强制终止。\n");
                result.append("exit code: ").append(exitCode).append("\n");
                result.append("⚠ 该命令可能需要持续交互，请切换到「永久模式」执行。");
            } else {
                if (!TextUtils.isEmpty(stdoutText)) {
                    String out = stripFormat ? stripAnsiCodes(stdoutText) : stdoutText;
                    result.append("── stdout ──\n").append(out).append("\n");
                }
                if (!TextUtils.isEmpty(stderrText)) {
                    String err = stripFormat ? stripAnsiCodes(stderrText) : stderrText;
                    result.append("── stderr ──\n").append(err).append("\n");
                }
                result.append("── 返回 ──\n");
                result.append("exit code: ").append(exitCode);
            }

        } catch (Exception e) {
            result.append("$ ").append(command).append("  [临时模式]\n");
            result.append("── 异常 ──\n").append(e.getMessage());
        } finally {
            if (process != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (process.isAlive()) process.destroyForcibly();
                    }
                } catch (Exception ignored) {}
            }
        }

        return result.toString();
    }

    // ================================================================
    //  内部工具方法
    // ================================================================

    /**
     * 获取或创建会话（线程安全）。
     * 路径：已存在且活跃 → 直接返回；不存在 → createViaService / createDirect。
     */
    private PtySession getOrCreateSession(String sessionId) {
        synchronized (sessionPool) {
            PtySession existing = sessionPool.get(sessionId);
            if (existing != null && existing.isAlive()) return existing;
        }

        try {
            PtySession session;
            if (termuxService != null) {
                session = createViaService(sessionId);
            } else {
                session = createDirect(sessionId);
            }
            return session;
        } catch (Exception e) {
            return null;
        }
    }

    /** 通过 TermuxService 创建会话（会话将出现在 Termux 抽屉和通知中） */
    private PtySession createViaService(String sessionId) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String workingDir = TermuxConstants.TERMUX_HOME_DIR_PATH;

        TermuxSession termuxSession = termuxService.createTermuxSession(
                bashPath, null, null, workingDir, false, sessionId);

        if (termuxSession == null) return null;

        // 标记为永久会话（不可被通知 Exit 关闭）
        termuxSession.getExecutionCommand().isPermanent = true;
        termuxService.addPermanentSessionHandle(termuxSession.getTerminalSession().mHandle);

        TerminalSession terminalSession = termuxSession.getTerminalSession();
        PtySession session = new PtySession(sessionId, terminalSession);

        synchronized (sessionPool) {
            sessionPool.put(sessionId, session);
        }
        return session;
    }

    /** 直接创建 PTY 会话（不依赖 TermuxService） */
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

    /** 从 TerminalEmulator 屏幕缓冲区读取全部纯文本（已过滤光标占位符） */
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

            // 跳过完全空行
            if (isRowBlank(row, cols)) continue;

            StringBuilder line = new StringBuilder();
            for (int col = 0; col < cols; col++) {
                int idx = row.findStartOfColumn(col);
                if (idx >= 0 && idx < row.mText.length) {
                    char ch = row.mText[idx];
                    if (ch != '\0') line.append(ch);
                }
            }

            // 剔除行尾空白（保留有意义的内容）
            String lineStr = line.toString().replaceAll("\\s+$", "");
            if (lineStr.isEmpty()) continue;

            sb.append(lineStr).append('\n');
        }

        return sb.toString();
    }

    // ================================================================
    //  dumpStyledScreen — 结构化样式输出（带行号 + ANSI 具名标记 + 可选光标标记）
    // ================================================================

    /**
     * 从 TerminalEmulator 读取终端画布并渲染为结构化文本。
     * <p>
     * 输出格式：每行 {@code [行号] [样式名] 文本内容}，样式名在字符间变化时自动切换。
     * 当 {@code showCursor = true} 时，在当前光标位置渲染 {@code [word](标)} 或 {@code █}。
     *
     * @param session    PTY 会话
     * @param showCursor 是否渲染光标占位标记
     * @return 结构化终端文本
     */
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

            // 空行但光标在此行 → 单独处理
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

            // 光标所在单词的起止列
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
                    // 光标在 \0 上 → 刷出前一段后渲染 █
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

    /** 将样式一致的文本段输出为 {@code [样式名] 文本 } */
    private static void flushStyledSegment(StringBuilder result, StringBuilder text, long style) {
        String t = text.toString().replaceAll("\\s+$", "");
        if (t.isEmpty()) return;
        result.append("[").append(styleToString(style)).append("] ").append(t).append(" ");
    }

    /** 将 TextStyle 位编码转换为中文具名样式字符串 */
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

    /** 判断 TerminalRow 是否全为空格 */
    private static boolean isRowBlank(TerminalRow row, int cols) {
        for (int col = 0; col < cols; col++) {
            int idx = row.findStartOfColumn(col);
            if (idx >= 0 && idx < row.mText.length && row.mText[idx] != ' ') return false;
        }
        return true;
    }

    /**
     * 从文本末尾取最后 N 行。
     * 自动忽略末尾由尾部换行符产生的空行。
     */
    private static String getLastNLines(String text, int n) {
        if (text == null || text.isEmpty() || n <= 0) return "";
        String[] lines = text.split("\n", -1);

        // 从末尾跳过空行
        int end = lines.length;
        while (end > 0 && lines[end - 1].isEmpty()) end--;

        if (end <= n) {
            // 少于等于 N 行 → 返回全部（trim 末尾多余换行）
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

    /** 剔除 ANSI 转义控制符 */
    private static String stripAnsiCodes(String text) {
        if (text == null || text.isEmpty()) return text;
        // CSI 序列: \033[<params><letter>
        String cleaned = text.replaceAll("\033\\[[0-9;]*[a-zA-Z]", "");
        // OSC 序列: \033]<params>...(\007|\033\\)
        cleaned = cleaned.replaceAll("\033\\].*?(\007|\033\\\\)", "");
        // 两字符 ESC 序列: \033<letter>
        cleaned = cleaned.replaceAll("\033[a-zA-Z]", "");
        return cleaned;
    }

    /** 全量读取 InputStream 为 UTF-8 字符串 */
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

    /** {@link HashMap}{@code <String, String>} 转 {@code String[]} 环境变量数组 */
    private static String[] toEnvArray(HashMap<String, String> envMap) {
        if (envMap == null) return new String[0];
        String[] arr = new String[envMap.size()];
        int i = 0;
        for (Map.Entry<String, String> e : envMap.entrySet()) {
            arr[i++] = e.getKey() + "=" + e.getValue();
        }
        return arr;
    }

    // ================================================================
    //  会话状态查询（外部 UI 使用）
    // ================================================================

    /**
     * 返回所有已注册会话的 ID → 是否活跃 映射。
     * 用于 Activity 中显示会话列表、刷新 UI。
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
     * 获取 default 会话的终端文本（用于外部 UI 轮询刷新）。
     * 临时获取当前终端画布的快照，不阻塞。
     */
    public String getDefaultSessionScreen() {
        if (!initialized) return "";
        if (!envReady) return "";
        synchronized (sessionPool) {
            PtySession s = sessionPool.get(DEFAULT_SESSION_ID);
            if (s != null && s.isAlive()) {
                synchronized (s.lock) {
                    return getScreenText(s);
                }
            }
        }
        return "";
    }

    /**
     * 获取指定会话的终端纯文本内容（用于 UI 轮询刷新）。
     *
     * @param sessionId 目标会话 ID
     * @return 纯文本终端内容，会话不存在或不可用时返回空串
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
     * 获取指定会话的结构化终端内容（带行号与样式标记，默认无光标）。
     * Activity 轮询用 — 不暴露光标参数给 UI 层。
     *
     * @param sessionId 目标会话 ID
     * @return 结构化终端文本，会话不存在或不可用时返回空串
     */
    public String getSessionStyledScreen(String sessionId) {
        return getSessionStyledScreen(sessionId, false);
    }

    /**
     * 获取指定会话的结构化终端内容（带行号与样式标记，可选光标标记）。
     *
     * @param sessionId  目标会话 ID
     * @param showCursor 是否渲染光标占位标记
     * @return 结构化终端文本，会话不存在或不可用时返回空串
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
}
