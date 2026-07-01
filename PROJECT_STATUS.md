# AlinOs-for-Android 项目状态与推进报告

> 生成日期：2026-07-01  
> 数据来源：全量源码分析（51 原创 Java 文件）+ README.md + SYSTEM_REPORT.md

---

## 一、项目定位

**AlinOs-for-Android** 是一款基于 Android 平台的云端 AI 接口专属客户端。

核心架构：**安卓应用壳 + 轻量 Termux 底层**。安卓层封装云端 Open API，Termux 仅作为辅助层执行本地 CLI 命令、搭建轻量 Linux 运行环境。

---

## 二、模块健康度总览

### 2.1 全量文件统计

| 维度 | 数据 |
|------|------|
| 原创 Java 文件 | 51 |
| 其中存活且被引用 | 27 |
| 其中死代码（零引用） | 13（不含 `net/openai/` 预留包） |
| 预留代码（`net/openai/` 整包） | 11 |
| Termux 衍生代码 | 142 |
| XML 资源 | 119 |
| 预编译环境（rootfs） | 4 个架构 |
| 原生 SO 库 | 11 |

### 2.2 原创模块状态矩阵

| 模块 | 文件数 | 状态 | 说明 |
|------|--------|------|------|
| **Activity 层** | 6 | ✅ 全部存活 | Main/AiConfig/Chat/AgentConfig/TextToSpeech |
| **adapter/** | 5 | 🟡 部分冗余 | 重复于 ChatActivity 内部类 |
| **bean/** | 5 | 🔴 1 个死 | `ChatMessage.java` 被 ChatActivity 内部类替代 |
| **db/** | 3 | ✅ 全部存活 | ChatDBHelper/ConfigDBHelper/SshDbHelper |
| **dev/** | 3 | ✅ 全部存活 | DevTools/LocalShellTest/SshTest |
| **localshell/** | 4 | ✅ 全部存活 | 最健壮子系统 |
| **manager/** | 8 → **6**（删2后） | 🟡 大幅冗余 | 见下方详表 |
| **net/** | 1 | ✅ 存活 | `OpenAIStreamNetHelper` 唯一运行时网络入口 |
| **net/openai/** | 11 | 🔶 预留 | 接口定义已声明，待后续对接更多 API |
| **prompt/** | 1 | ✅ 存活 | PromptService |
| **service/** | 1 | ✅ 存活 | DeviceAccessibilityService |
| **tools/** | 2 | ✅ 全部存活 | ToolRegistry/ToolMeta |
| **utils/** | 2 | 🔴 1 个死 | `TtsChecker.java` 零引用 |

---

## 三、🔴 确认可删除的死代码

以下文件零引用、无预留价值，且（除你指定删除的 2 个外）可随同清理：

### 已确认删除

| 文件 | 理由 |
|------|------|
| `manager/ShizukuAdbShellExecutor.java` | 内置 Shizuku 服务未启用，无需此实现 |
| `manager/IAdbShellExecutor.java` | 上述文件的接口，一并删除 |

### 建议同时删除

| 文件 | 理由 |
|------|------|
| `bean/ChatMessage.java` | 与 `ChatActivity.ChatMessage` 内部类完全重复，常量值冲突（TYPE_USER=0 vs 1） |
| `adapter/ChatAdapter.java` | 与 `ChatActivity.ChatAdapter` 内部类完全重复 |
| `adapter/OnSessionSelectListener.java` | ChatActivity 内部 `SessionAdapter` 自建了同名接口 |
| `manager/EventBus.java` | 零引用；聊天流式已有 `ChatStreamEventBus` |
| `manager/TextToSpeechManager.java` | 零引用；`TextToSpeechActivity` 自带完整实现 |
| `manager/ConfigManager.java` | 零引用；`ChatActivity` 和 `AiConfigActivity` 直连 `ConfigDBHelper` |
| `utils/TtsChecker.java` | 零引用；`TextToSpeechActivity` 自带 TTS 检测 |
| `AudioUtils.java` | 零引用 |

**以上合计 10 个文件可安全删除，减少约 750 行无效代码。**

---

## 四、🔶 预留代码（`net/openai/` 整包）

| 文件 | 用途 | 完成度 |
|------|------|--------|
| `net/openai/OpenAIApi.java` | API 接口定义（28 方法） | 仅 `createChatCompletionStream` 有实现 |
| `net/openai/OpenAIClient.java` | 接口实现 | 骨架代码，运行时不经过此路径 |
| `model/ChatRequest.java` | 对话请求体 | 完整 |
| `model/ChatResponse.java` | 对话响应体 | 完整 |
| `model/CommonResponses.java` | 通用响应模型 | 完整 |
| `model/EmbeddingRequest.java` | 嵌入向量请求 | 完整 |
| `model/EmbeddingResponse.java` | 嵌入向量响应 | 完整 |
| `model/ImageGenerationRequest.java` | 图片生成请求 | 完整 |
| `model/AudioSpeechRequest.java` | TTS 请求 | 完整 |
| `model/AudioTranscriptionRequest.java` | STT 请求 | 完整 |

> **当前运行时路径**: `ChatActivity → PromptService → OpenAIStreamNetHelper（直连 OkHttp SSE）`  
> **预留目标**: 经 OpenAIClient 统一封装后，再接入 OpenAI Responses API、Vertex AI、Anthropic Messages API 等接口

---

## 五、🟠 冗余代码（需合并）

### 5.1 ChatMessage 两套实现

```
bean/ChatMessage.java          TYPE_USER=0, TYPE_AI=1    ← 死
ChatActivity.ChatMessage        TYPE_USER=1, TYPE_AI=2    ← 活（内部类）
```

**影响**: 常量值不同，误引用会直接导致渲染错乱。

**建议**: 删 `bean/ChatMessage.java`，或把 `ChatActivity.ChatMessage` 抽成独立 bean。

### 5.2 ChatAdapter 两套实现

```
adapter/ChatAdapter.java       ← 死（无流式 loading 控制）
ChatActivity.ChatAdapter       ← 活（完整 Animation/Clipboard/PopupMenu）
```

**建议**: 删 `adapter/ChatAdapter.java`，或将内部类抽到 adapter/ 包。

### 5.3 TTS 三份实现

| 文件 | 状态 |
|------|------|
| `TextToSpeechActivity.java` | ✅ 活——完整的 TTS Activity |
| `manager/TextToSpeechManager.java` | 💀 死（被 Activity 替代） |
| `utils/TtsChecker.java` | 💀 死（被 Activity 替代） |

**建议**: 保留 `TextToSpeechActivity`，其余删除。

### 5.4 EventBus 两套

| 文件 | 特征 | 状态 |
|------|------|------|
| `ChatStreamEventBus.java` | 强类型流式事件总线 | ✅ 活 |
| `EventBus.java` | 泛型主线程 EventBus | 💀 死（零引用） |

**建议**: 保留 `ChatStreamEventBus`，删 `EventBus`。

---

## 六、🟡 模块合并与架构优化

### 6.1 DB 层基类抽象（3 → 1）

`ChatDBHelper`、`ConfigDBHelper`、`SshDbHelper` 的 CRUD 模式 90% 重复：
- 手写 `getAll/add/update/delete`
- 手写 cursor→bean 映射
- 裸 SQLiteOpenHelper（无 Room）

**建议**: 引入 Room，或至少抽一个泛型 `BaseDbHelper<T>`。

### 6.2 adapter 统一模式（3 → 1）

`ConfigAdapter`、`SshConfigAdapter`、死掉的 `ChatAdapter` 各自写死回调。

**建议**: 统一为 `BaseRecyclerAdapter<T, VH>` + 泛型 `DataOperationListener<T>`。

### 6.3 `manager/` 包拆分

当前是 8 文件的"大杂烩"。删掉死代码后剩 `ChatStreamEventBus`、`ShizukuManager`、`ConsentDialogManager`。

**建议**: 现有 3 个存活文件保留原位即可，不单拆包（数量太少）。

### 6.4 `net/` vs `net/openai/` 分层修复

**现状**: 运行时流量 `PromptService → OpenAIStreamNetHelper（net/）`，绕过了 `net/openai/` 整层。

**建议**（后续对接多 API 时实施）:
```
PromptService → OpenAIClient（统一路由）
              ├→ OpenAIStreamNetHelper（Chat Completions）
              ├→ OpenAI Responses API
              ├→ Vertex AI Express Mode
              └→ Anthropic Messages API
```

---

## 七、✅ 已完成能力

| # | 能力 | 组件 | 成熟度 |
|---|------|------|--------|
| 1 | AI 对话（流式 SSE） | ChatActivity + PromptService + OpenAIStreamNetHelper | ⭐⭐⭐ 已投产 |
| 2 | AI 配置管理（添加/编辑/删除/默认） | AiConfigActivity + ConfigDBHelper | ⭐⭐⭐ |
| 3 | 聊天会话管理（创建/重命名/删除/切换） | ChatActivity + ChatDBHelper | ⭐⭐⭐ |
| 4 | PTY Shell 会话 | LocalShellExecutor + LocalShellService | ⭐⭐⭐ |
| 5 | 终端仿真 | LocalShellTestActivity（基于 TermuxActivity） | ⭐⭐⭐ |
| 6 | SSH 配置管理 + 自动连接 | SshTestActivity + LocalShellExecutor | ⭐⭐ |
| 7 | 工具注册系统（11 个 Agent 工具） | ToolRegistry + ToolMeta + DevToolsActivity | ⭐⭐⭐ |
| 8 | Shizuku 提权 | ShizukuManager | ⭐⭐（未深度集成） |
| 9 | TTS 语音合成 | TextToSpeechActivity | ⭐⭐⭐ |
| 10 | 无障碍服务 | DeviceAccessibilityService | ⭐（空实现） |
| 11 | 本地持久化 | 3 个 SQLite 数据库 | ⭐⭐⭐ |
| 12 | 预编译环境部署 | libtar.so 解压 rootfs | ⭐⭐⭐ |

---

## 八、🎯 待实现路线图（来自 README）

| 优先级 | 目标 | 现状 | 所需工作 |
|--------|------|------|----------|
| P0 | **OpenAI Responses API 对接** | `net/openai/` 骨架已建 | 实现 OpenAIClient 中对应方法，串联 PromptService |
| P0 | **Anthropic Messages API 对接** | 未开始 | 新增 `net/anthropic/` 包，实现流式 SSE 解析 |
| P0 | **Vertex AI Express Mode 对接** | 未开始 | 需处理 Google OAuth 认证 |
| P1 | **OpenAI Chat Completions 完整支持** | 基本可用 | 补充 Function Calling、多模态（图片输入） |
| P1 | **SSH 深度集成** | 配置管理已就绪 | Agent 通过 SSH 执行远程命令/SCP 文件传输 |
| P2 | **Agent 端到端闭环** | 工具注册完成 | 让 AI 自动调用 shell_exec/ssh 等工具完成任务 |
| P3 | **无障碍服务完善** | 空实现 | 实现截屏、模拟点击等能力 |

---

## 九、数据流架构（当前）

```
用户输入
    │
    ▼
ChatActivity
  ├→ PromptService → OpenAIStreamNetHelper → OkHttp SSE → AI API
  │
  └→ LocalShellExecutor → PTY Session → LocalShellService
                              │
                              └→ SSH 隧道 (via JSch)
```

---

## 十、风格统一进度

| Activity | 状态 |
|----------|------|
| `SshTestActivity` | ✅ 基准风格（蓝色 CardView + NoActionBar） |
| `ChatActivity` | ✅ 已同步 |
| `AgentConfigActivity` | ✅ 已同步 |
| `MainActivity` | ⏳ 待同步 |
| `AiConfigActivity` | ⏳ 待同步 |

---

## 十一、完整行动清单

### 立刻可做（测试已覆盖 / 安全删除）

| # | 操作 | 涉及文件 |
|:-:|:--|:--|
| 1 | 删除 `ShizukuAdbShellExecutor` + `IAdbShellExecutor` | 2 文件 |
| 2 | 删除 `bean/ChatMessage.java` | 1 文件 |
| 3 | 删除 `adapter/ChatAdapter.java` | 1 文件 |
| 4 | 删除 `adapter/OnSessionSelectListener.java` | 1 文件 |
| 5 | 删除 `manager/EventBus.java` | 1 文件 |
| 6 | 删除 `manager/TextToSpeechManager.java` | 1 文件 |
| 7 | 删除 `manager/ConfigManager.java` | 1 文件 |
| 8 | 删除 `utils/TtsChecker.java` | 1 文件 |
| 9 | 删除 `AudioUtils.java` | 1 文件 |

### 建议近期做

| # | 操作 | 工作量 |
|:-:|:--|:--:|
| 1 | 清理 AndroidManifest 重复权限声明 | 小 |
| 2 | 将 ChatActivity 内部类 `ChatMessage`/`ChatAdapter`/`SessionAdapter` 抽到独立文件 | 中 |
| 3 | 统一 MainActivity/AiConfigActivity 的 UI 风格到 SshTestActivity 基准 | 中 |
| 4 | 抽 DB 基类（或引入 Room） | 中-大 |

### 下一阶段开发

| # | 操作 | 优先级 |
|:-:|:--|:--:|
| 1 | 打通 OpenAIClient → API 的实际调用路径 | P0 |
| 2 | 对接 Anthropic Messages API | P0 |
| 3 | SSH 远程命令执行（Agent 端到端） | P1 |
| 4 | Function Calling / Tool Use 支持 | P1 |

---

## 附录：原创代码 vs Termux 衍生代码

| 来源 | 文件数 | 协议 |
|------|--------|------|
| AlinOs 原创（`alin.android.alinos`） | 51 | MIT |
| Termux app 层（GPLv3） | 11 | GPLv3 |
| Termux shared 库（主体 MIT） | 109 | MIT/GPLv3 |
| terminal-emulator（jackpal） | 14 | Apache 2.0 |
| terminal-view（jackpal） | 8 | Apache 2.0 |

---

*本文档基于 2026-07-01 全量源码分析生成。`net/openai/` 整包已知为预留代码，不计入死代码。*
