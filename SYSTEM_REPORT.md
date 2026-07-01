# AlinOs-for-Android 系统性报告

生成日期：2026-06-27

---

## 📊 项目概览

| 维度 | 数据 |
|------|------|
| **项目名称** | AlinOs-for-Android |
| **包名** | `alin.android.alinos` |
| **目标 SDK** | 36 (Android 15) |
| **最低 SDK** | 24 (Android 7.0) |
| **Java 文件总数** | **193**（原创 51 + Termux 衍生 142） |
| **XML 资源** | 119 |
| **原生 SO 库** | 11（4 ABI 架构） |
| **预编译环境** | 4 个架构的 Termux rootfs（约 160MB） |
| **代码行数** | ~18,000+ |
| **整体授权** | **GPLv3**（因包含 GPLv3 组件） |

---

## 一、模块架构与组件归属

### A. 🔵 原创代码 — MIT（51 文件）

**路径：** `app/src/main/java/alin/android/alinos/**`（除 `LocalShellTestActivity.java`）

| 模块 | 文件数 | 关键类 | 能力 |
|------|--------|--------|------|
| **Activity 层** | 6 | `MainActivity`, `AiConfigActivity`, `ChatActivity`, `AgentConfigActivity`, `TextToSpeechActivity` | 主入口、AI 配置 CRUD、聊天、功能仪表盘、TTS 测试 |
| **dev/ 开发工具** | 2 | `DevToolsActivity`, `SshTestActivity` | 工具注册表测试台、SSH 配置管理 |
| **localshell/ 本地 Shell** | 4 | `LocalShellExecutor`, `LocalShellService`, `LocalShellEnvironment`, `LocalShellConstants` | PTY 会话管理器、前台服务、Shell 环境配置 |
| **net/ 网络层** | 11 | `OpenAIClient`, `OpenAIStreamNetHelper`, `OpenAIApi`, 8 个 DTO | OpenAI API 实现、SSE 流式引擎、请求/响应模型 |
| **manager/ 管理器** | 8 | `ShizukuManager`, `ShizukuAdbShellExecutor`, `TextToSpeechManager`, `ChatStreamEventBus`, `EventBus`, `ConfigManager`, `ConsentDialogManager` | Shizuku 提权、TTS、事件总线、配置持久化、弹窗管理 |
| **prompt/ 提示词** | 1 | `PromptService` | 对话历史组装 + 流式请求 |
| **service/** | 1 | `DeviceAccessibilityService` | 无障碍服务桩 |
| **tools/** | 2 | `ToolRegistry`, `ToolMeta` | 11 个 Agent 工具注册、工具元数据模型 |
| **adapter/** | 5 | `ChatAdapter`, `ConfigAdapter`, `SshConfigAdapter`, 2 接口 | RecyclerView 适配器 |
| **bean/** | 5 | `ConfigBean`, `ChatSessionBean`, `ChatRecordBean`, `SshConfigBean`, `ChatMessage` | 数据模型实体 |
| **db/** | 3 | `ConfigDBHelper`, `ChatDBHelper`, `SshDbHelper` | SQLite 数据库 Helper（含 Schema 迁移） |
| **utils/** | 2 | `TokenEstimator`, `TtsChecker` | Token 估算、TTS 引擎检测 |

### B. 🔴 Termux App 层 — GPLv3 only（11 文件）

**上游：** https://github.com/termux/termux-app

| 包 | 文件数 | 关键类 | 能力 |
|-----|--------|--------|------|
| `com.termux.app/` | 2 | `TermuxService`, `EventHandler` | 核心服务、事件处理 |
| `com.termux.app.terminal/` | 5 | `TermuxTerminalViewClient`, `TermuxTerminalSessionActivityClient`, `TermuxActivityRootView`, `TermuxSessionsListViewController`, `TermuxTerminalSessionServiceClient` | 终端 Activity 客户端、会话列表控制、根视图 |
| `com.termux.app.terminal.io/` | 3 | `KeyboardShortcut`, `TerminalToolbarViewPager`, `TermuxTerminalExtraKeys` | 键盘快捷键、工具栏、额外按键 |
| `com.termux.app.event/` | 1 | `SystemEventReceiver` | 系统广播接收器（开机启动） |
| `alin...dev/LocalShellTestActivity.java` | 1 | `LocalShellTestActivity` | 基于 TermuxActivity 改造的终端 Activity |

> **注：** `LocalShellTestActivity.java` 物理路径在 alin 包中，但代码基于 TermuxActivity 深度改造，归入此类。

### C. 🟡 Termux 共享库（termux-shared）— 主体 MIT（109 文件）

**上游：** https://github.com/termux/termux-app

| 子包 | 转载协议 | 能力 |
|------|----------|------|
| `shared.termux/` | **MIT**（TermuxConstants）+ **GPLv3**（其余） | 常量、工具、Bootstrap |
| `shared.termux.extrakeys/` | GPLv3 | **ExtraKeysView**（软键盘视图）、ExtraKeyButton、SpecialButton |
| `shared.termux.shell/` | GPLv3 | TermuxShellManager、TermuxSession |
| `shared.termux.crash/` | GPLv3 | 崩溃报告 |
| `shared.termux.settings/` | GPLv3 | 偏好设置、属性系统 |
| `shared.termux.terminal/` | GPLv3 | TerminalClientBase、BellHandler |
| `shared.termux.theme/` | GPLv3 | 主题工具 |
| `shared.shell/` | MIT / **Apache 2.0**（StreamGobbler） | ShellUtils、StreamGobbler、ArgumentTokenizer |
| `shared.shell.command/` | MIT | ExecutionCommand、ResultSender、runner、environment |
| `shared.android/` | MIT | PermissionUtils、PackageUtils、AndroidUtils 等 |
| `shared.file/` | MIT | FileUtils |
| `shared.file.filesystem/` | **GPLv2 + Classpath Exception**（来自 OpenJDK） | 文件属性、权限、时间 |
| `shared.logger/` | MIT | 全项目日志系统 |
| `shared.markdown/` | MIT | Markdown 渲染 |
| `shared.activities/` | MIT | ReportActivity、TextIOActivity |
| `shared.*`（其余 12 包） | MIT | activity、data、errors、interact、models、net、notification、reflection、settings、theme、view |

### D. 🟢 终端模拟器（terminal-emulator）— Apache 2.0（14 文件）

**上游：** https://github.com/jackpal/Android-Terminal-Emulator

| 文件 | 能力 |
|------|------|
| `TerminalEmulator.java` | 终端状态机（ANSI/XTerm 转义序列解析） |
| `TerminalSession.java` | PTY 会话管理 |
| `TerminalBuffer.java` | 回滚缓冲区 |
| `TerminalRow.java` | 行数据 |
| `TextStyle.java` | 文字样式 |
| `KeyHandler.java` | 按键映射 |
| `TerminalColors.java` | 颜色方案 |
| `TerminalColorScheme.java` | 配色解析 |
| `TerminalOutput.java` | 输出编码 |
| `WcWidth.java` | 宽字符宽度计算 |
| `ByteQueue.java` | 字节队列 |
| `JNI.java` | JNI 本地接口（加载 `libtermux.so`） |
| `Logger.java` | 终端日志 |

### E. 🟢 终端视图（terminal-view）— Apache 2.0（8 文件）

**上游：** https://github.com/jackpal/Android-Terminal-Emulator

| 文件 | 能力 |
|------|------|
| `TerminalView.java` | 终端渲染 View（核心） |
| `TerminalRenderer.java` | 渲染器 |
| `GestureAndScaleRecognizer.java` | 手势缩放识别 |
| `TerminalViewClient.java` | View 回调接口 |
| `textselection/CursorController.java` | 文本选择光标控制 |
| `textselection/TextSelectionCursorController.java` | 文本选择光标控制 |
| `textselection/TextSelectionHandleView.java` | 文本选择手柄 |
| `support/PopupWindowCompatGingerbread.java` | PopupWindow 兼容 |

---

## 二、原生库

| SO 文件 | ABI | 来源 | 加载方式 |
|---------|-----|------|---------|
| `libtermux.so` | arm64-v8a, x86_64 | Termux | `JNI.java` 中 `System.loadLibrary("termux")` |
| `liblocal-socket.so` | arm64-v8a, x86_64 | Termux | `LocalSocketManager.java` 中 `System.loadLibrary()` |
| `libtar.so` | arm64-v8a, x86_64, armeabi-v7a, x86 | Termux | `LocalShellTestActivity` 中作为可执行二进制运行：`libtar -xf ...` |
| `librmt.so` | armeabi-v7a, x86 | Termux | tar 二进制运行时动态链接依赖 |

> `armeabi-v7a` 和 `x86` 为备用架构，实际 APK 打包仅包含 `arm64-v8a` + `x86_64`。

---

## 三、预编译运行环境

**文件：** `app/src/main/assets/files.default.{aarch64,arm,x86_64,i686}.tar.gz.so`

四个架构的预编译 Termux 根文件系统镜像，内含：

| 软件 | 协议 |
|------|------|
| bash | GPLv3 |
| openssh | BSD |
| openssl | Apache 2.0 |
| coreutils | GPLv3 |
| openjdk-17 | GPLv2 + Classpath Exception |
| 其他 Termux 软件包 | 以各 .deb 包 LICENSE 为准 |

由 Termux 官方软件仓库的 .deb 包经魔改构建流程裁剪合并而成，非 Termux 官方 bootstrap。本 tar.gz 仅作为聚合分发载体。

---

## 四、核心能力矩阵

| # | 能力 | 实现组件 | 来源 | 协议 |
|---|------|----------|------|------|
| 1 | **🤖 AI 对话** | ChatActivity + PromptService + OpenAIClient + OpenAIStreamNetHelper | **原创** | MIT |
| 2 | **🔄 流式输出（SSE）** | OpenAIStreamNetHelper（OkHttp）+ ChatActivity 增量渲染 | **原创** | MIT |
| 3 | **🔧 AI 配置管理** | AiConfigActivity + ConfigDBHelper + ConfigBean | **原创** | MIT |
| 4 | **📋 聊天会话管理** | ChatActivity（创建/重命名/删除/切换）+ ChatDBHelper | **原创** | MIT |
| 5 | **🖥️ 终端仿真** | TerminalEmulator + TerminalSession + TerminalView | **jackpal** | Apache 2.0 |
| 6 | **⌨️ 软键盘（额外按键）** | ExtraKeysView + ExtraKeyButton + KeyboardShortcut | **Termux** | GPLv3 |
| 7 | **🔌 PTY Shell 执行** | LocalShellExecutor + LocalShellService + TermuxService | **原创 + Termux** | MIT + GPLv3 |
| 8 | **🔑 SSH 客户端** | SshTestActivity + LocalShellExecutor + JSch 库 | **原创（JSch: BSD）** | MIT |
| 9 | **👑 Shizuku 提权** | ShizukuManager + ShizukuAdbShellExecutor + hiddenapibypass | **原创** | MIT |
| 10 | **🔊 TTS 语音合成** | TextToSpeechActivity + TextToSpeechManager + TtsChecker | **原创** | MIT |
| 11 | **🧰 工具注册系统** | ToolRegistry（11 工具）+ ToolMeta + DevToolsActivity | **原创** | MIT |
| 12 | **♿ 无障碍服务** | DeviceAccessibilityService | **原创** | MIT |
| 13 | **🗄️ 本地持久化** | 3 个 SQLite 数据库（AI配置/聊天/SSH） | **原创** | MIT |
| 14 | **📝 Markdown 渲染** | Markwon 库 + 代码高亮（Prism4j） | **开源库** | Apache 2.0 |
| 15 | **📦 预编译环境部署** | `libtar.so` 解压 `assets/*.tar.gz.so` 到数据目录 | **原创 + Termux** | GPLv3 |

---

## 五、数据流架构

```
用户输入
    │
    ▼
┌──────────────────────────────────────────────────┐
│  ChatActivity                                    │
│  ┌──────────┐   ┌──────────────┐   ┌──────────┐ │
│  │ 输入栏    │──→│ PromptService│──→│StreamBus  │ │
│  │ 会话列表   │   │ (对话历史组装) │   │ (事件总线) │ │
│  │ 消息列表   │   └──────────────┘   └─────┬────┘ │
│  └──────────┘                              │      │
└─────────────────────────────────────────────┼──────┘
                                              │
                    ┌─────────────────────────┼─────────┐
                    │                         │         │
                    ▼                         ▼         │
        ┌────────────────────┐    ┌──────────────────┐  │
        │ OpenAIStreamNetHelper│    │  OpenAI API     │  │
        │ (SSE/OkHttp)       │───→│  (HTTP)          │  │
        └────────────────────┘    └──────────────────┘  │
                                                        │
                    ┌────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────┐
│  LocalShellExecutor (单例)                            │
│  ┌──────────────────────────────────────────────────┐│
│  │ create_session → TermuxService → PTY → Shell     ││
│  │ shell_exec / shell_write / shell_send_key        ││
│  │ shell_read / destroy_session / list_sessions     ││
│  │ search_session / session_status / rename_session ││
│  └──────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────┐
│  ShizukuManager → Shizuku API → ADB shell（提权）     │
└──────────────────────────────────────────────────────┘
```

---

## 六、数据库结构

| 数据库文件 | 表 | 用途 |
|-----------|-----|------|
| **ai_config.db** | `configs` | AI 提供方配置（类型/URL/Key/模型/参数） |
| **chat_db.db** | `sessions` | 聊天会话（名称/绑定配置/创建时间） |
| **chat_db.db** | `records` | 聊天消息记录（类型/内容/Token 统计） |
| **ssh_config.db** | `ssh_configs` | SSH 连接配置（主机/端口/用户/认证方式） |

---

## 七、第三方依赖

| 库 | 版本 | 协议 | 用途 |
|----|------|------|------|
| `com.jcraft:jsch` | 0.1.55 | **BSD-style** | SSH 客户端 |
| `dev.rikka.shizuku:api` | 13.1.5 | **Apache 2.0** | ADB 权限代理 |
| `dev.rikka.shizuku:provider` | 13.1.5 | **Apache 2.0** | Shizuku Provider |
| `org.lsposed.hiddenapibypass` | 6.1 | **Apache 2.0** | 隐藏 API 绕过 |
| `com.squareup.okhttp3:okhttp` | （media3 传递依赖） | **Apache 2.0** | HTTP 客户端 |
| `com.google.code.gson:gson` | 2.10.1 | **Apache 2.0** | JSON 解析 |
| `commons-io:commons-io` | 2.15.1 | **Apache 2.0** | IO 工具 |
| `io.noties.markwon:core` | 4.6.2 | **Apache 2.0** | Markdown 渲染 |
| `io.noties:prism4j` | 2.0.0 | **Apache 2.0** | 代码高亮 |
| `com.airbnb.android:lottie` | 6.1.0 | **Apache 2.0** | 动画 |
| AndroidX 系列 | — | **Apache 2.0** | Android 支持库 |
| `androidx.media3:media3-*` | 1.2.0 | **Apache 2.0** | 媒体播放 |

---

## 八、Android 组件清单

### Activity（9 个）

| Activity | 导出 | Launch Mode | 主题 |
|----------|------|-------------|------|
| `MainActivity` | ✅ | standard | 默认 |
| `AiConfigActivity` | ❌ | standard | 默认 |
| `AgentConfigActivity` | ❌ | standard | `NoActionBar` |
| `TextToSpeechActivity` | ✅ | standard | 默认 |
| `ChatActivity` | ❌ | `singleTask` | `NoActionBar` |
| `DevToolsActivity` | ❌ | standard | 默认 |
| `LocalShellTestActivity` | ✅ | `singleTask` | Termux DayNight |
| `SshTestActivity` | ❌ | standard | `NoActionBar` |
| `ReportActivity`（Termux） | ❌ | standard | MarkdownView |

### Service（3 个）

| Service | 导出 | 类型 | 能力 |
|---------|------|------|------|
| `TermuxService`（Termux） | ✅ | 前台服务（specialUse） | 终端会话托管 |
| `LocalShellService` | ❌ | 前台服务（specialUse） | PTY 会话管理 |
| `DeviceAccessibilityService` | ❌ | 无障碍服务 | 辅助功能 |

### Receiver（2 个）

| Receiver | 监听 |
|----------|------|
| `SystemEventReceiver`（Termux） | `BOOT_COMPLETED` |
| `ReportActivityBroadcastReceiver`（Termux） | 崩溃报告 |

### Provider（1 个）

| Provider | 用途 |
|----------|------|
| `rikka.shizuku.ShizukuProvider` | Shizuku 跨进程通信 |

---

## 九、权限清单

| 权限分类 | 权限 | 用途 |
|----------|------|------|
| **网络** | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | AI API 通信 |
| **存储** | `READ/WRITE/MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*` | 文件访问 |
| **通知** | `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK` | 前台服务保活 |
| **系统** | `SYSTEM_ALERT_WINDOW`, `WRITE_SECURE_SETTINGS`, `PACKAGE_USAGE_STATS`, `DUMP`, `READ_LOGS` | 提权操作 |
| **安装** | `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES` | 包管理 |
| **辅助** | `BIND_ACCESSIBILITY_SERVICE`, `READ_FRAME_BUFFER` | 无障碍服务 |
| **硬件** | `CAMERA`, `FLASHLIGHT`, `RECORD_AUDIO`, `VIBRATE` | 多媒体 |
| **其他** | `RECEIVE_BOOT_COMPLETED`, `READ_PHONE_STATE`, `READ_SMS`, `USE_FINGERPRINT`, `USE_BIOMETRIC`, `SET_ALARM` | 杂项 |

---

## 十、工具注册表（Agent 可调用工具）

| 工具名 | 参数 | 能力 |
|--------|------|------|
| `create_session` | sessionId, displayName | 创建 PTY 会话 |
| `destroy_session` | sessionId | 销毁 PTY 会话 |
| `list_sessions` | — | 列出所有活跃会话 |
| `search_session` | keyword, field | 按字段搜索会话 |
| `session_status` | sessionId | 查询会话状态 |
| `rename_session` | sessionId, newName | 重命名会话 |
| `shell_exec` | sessionId, command, timeoutMs | 执行命令并等待 |
| `shell_write` | sessionId, text | 写入文本到会话 |
| `shell_send_key` | sessionId, keyName | 发送控制键（CTRL_C 等）|
| `shell_read` | sessionId, mode, maxLines | 读取会话输出 |
| `read_history_canvas` | sessionId | 读取历史画布 |
| `shell_get_debug_view` | sessionId | 获取调试视图 |

---

## 十一、已删除的冗余代码

| 删除项 | 原因 |
|--------|------|
| `TermuxAPIAppSharedPreferences.java` | Termux:API 插件配置，未使用 |
| `TermuxBootAppSharedPreferences.java` | Termux:Boot 插件配置，未使用 |
| `TermuxFloatAppSharedPreferences.java` | Termux:Float 悬浮窗配置，未使用 |
| `TermuxStylingAppSharedPreferences.java` | Termux:Styling 主题配置，未使用 |
| `TermuxTaskerAppSharedPreferences.java` | Termux:Tasker 配置，未使用 |
| `TermuxWidgetAppSharedPreferences.java` | Termux:Widget 小部件配置，未使用 |
| `TermuxAPIShellEnvironment.java` | API 专用 Shell 环境，未引用 |
| `TermuxDocumentsProvider.java` + `filepicker/` | 文件选择器，零引用 |
| `FileUtilsTests.java` | 混入 src/main 的测试文件 |

---

## 十二、风格统一进度

| Activity | 状态 | 主题 |
|----------|------|------|
| `SshTestActivity` | ✅ 基准风格 | 蓝色 CardView + 浅灰底 + `NoActionBar` |
| `ChatActivity` | ✅ 已同步 | 蓝色 CardView 标题栏 + 圆角气泡 + `NoActionBar` |
| `AgentConfigActivity` | ✅ 已同步 | 蓝色 CardView 标题栏 + `NoActionBar` |
| `MainActivity` | ⏳ 待同步 | — |
| `AiConfigActivity` | ⏳ 待同步 | — |

---

*本文档由 CodeGraph 静态分析生成，反映截至 2026-06-27 的项目状态。*
