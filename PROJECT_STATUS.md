# AlinOs-for-Android 项目状态与推进报告

> 更新日期：2026-07-06
> 数据来源：全量源码分析 + 工具调用系统重构记录

---

## 一、项目定位

**AlinOs-for-Android** 是一款基于 Android 平台的云端 AI 接口专属客户端。

核心架构：**安卓应用壳 + 轻量 Termux 底层**。安卓层封装云端 Open API（DeepSeek / OpenAI 兼容），Termux 作为辅助层执行本地 CLI 命令、搭建轻量 Linux 运行环境。

---

## 二、模块健康度总览

### 2.1 全量文件统计

| 维度 | 数据 |
|------|------|
| 原创 Java 文件 | 51+ |
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
| **bean/** | 6 | ✅ 新增 ToolCallLogBean | UUID 重构 |
| **db/** | 4 | ✅ 新增 ToolCallDbHelper v2 | UUID 主键 + getByUuid() |
| **dev/** | 3 | ✅ 全部存活 | DevTools/LocalShellTest/SshTest |
| **localshell/** | 4 | ✅ 全部存活 | 最健壮子系统，新增 shell_send_keys 批量按键 |
| **manager/** | 6 | 🟡 清理后 | ChatStreamEventBus 存活，其余删 |
| **net/** | 1 | ✅ 存活 | OpenAIStreamNetHelper 唯一运行时网络入口 |
| **net/openai/** | 11 | 🔶 预留 | 接口定义已声明 |
| **prompt/** | 1 | ✅ 存活 | PromptService |
| **tools/** | 5 | ✅ 新增 3 个 | ToolCallCoordinator / ToolCallCardCallback / ToolConverter |

---

## 三、🔴 确认可删除的死代码

| 文件 | 理由 |
|------|------|
| `manager/ShizukuAdbShellExecutor.java` | 未启用 |
| `manager/IAdbShellExecutor.java` | 上述的接口 |
| `bean/ChatMessage.java` | 与 ChatActivity 内部类完全重复 |
| `adapter/ChatAdapter.java` | 与 ChatActivity 内部类完全重复 |
| `adapter/OnSessionSelectListener.java` | ChatActivity 内部自建了同名接口 |
| `manager/EventBus.java` | 零引用；聊天流式已有 ChatStreamEventBus |
| `manager/TextToSpeechManager.java` | 零引用 |
| `manager/ConfigManager.java` | 零引用 |
| `utils/TtsChecker.java` | 零引用 |
| `AudioUtils.java` | 零引用 |

---

## 四、✅ 已完成功能

### 4.1 核心对话

| # | 能力 | 组件 | 成熟度 |
|---|------|------|--------|
| 1 | AI 对话（流式 SSE） | ChatActivity + PromptService + OpenAIStreamNetHelper | ⭐⭐⭐ |
| 2 | AI 配置管理（添加/编辑/删除/默认） | AiConfigActivity + ConfigDBHelper | ⭐⭐⭐ |
| 3 | 聊天会话管理（创建/重命名/删除/切换） | ChatActivity + ChatDBHelper | ⭐⭐⭐ |
| 4 | 聊天历史持久化 + 恢复 | loadChatRecords + UUID 匹配 | ⭐⭐⭐ |
| 5 | TTS 语音合成 | TextToSpeechActivity | ⭐⭐⭐ |

### 4.2 Tool Calling 引擎

| # | 能力 | 组件 | 成熟度 |
|---|------|------|--------|
| 1 | 工具注册表（16 个工具） | ToolRegistry + ToolMeta | ⭐⭐⭐ |
| 2 | 工具定义 → OpenAI JSON Schema | ToolConverter | ⭐⭐⭐ |
| 3 | 工具调用循环引擎 | ToolCallCoordinator（支持递归循环） | ⭐⭐⭐ |
| 4 | 流式解析 tool_calls | OpenAIStreamNetHelper（SSE 分片累积） | ⭐⭐⭐ |
| 5 | 工具卡片 UI | ChatAdapter + item_chat_tool_call.xml | ⭐⭐⭐ |
| 6 | 工具调用日志（UUID 关联） | ToolCallLogBean + ToolCallDbHelper v2 | ⭐⭐⭐ |
| 7 | 递归工具调用占位消息 | ToolCallCardCallback.onNewPlaceholder() | ⭐⭐⭐ |
| 8 | chat_record 中 UUID 标记 | `[tool_call:UUID]toolName` 格式 | ⭐⭐⭐ |
| 9 | ANSI 转义码剥离 | buildToolResultMessage 正则清理 | ⭐⭐⭐ |
| 10 | 工具描述优化（菜单导航指南） | shell_read/shell_send_key 描述 | ⭐⭐ |
| 11 | 批量按键发送 | shell_send_keys（`Down\|Down\|Enter`） | ⭐⭐ |

### 4.3 终端能力

| # | 能力 | 组件 | 成熟度 |
|---|------|------|--------|
| 1 | PTY Shell 会话管理 | LocalShellExecutor + create/destroy/list | ⭐⭐⭐ |
| 2 | 命令执行 | shell_exec / shell_write / shell_send_key | ⭐⭐⭐ |
| 3 | 终端画面读取 | shell_read / read_history_canvas | ⭐⭐⭐ |
| 4 | SSH 隧道 + 远程操作 | LocalShellExecutor + JSch | ⭐⭐ |
| 5 | 终端仿真 UI | LocalShellTestActivity | ⭐⭐⭐ |

---

## 五、🔧 近期修复记录

### 5.1 Tool Calling 稳定性修复

| 日期 | 问题 | 修复 |
|------|------|------|
| 07-06 | thinkFinish 竞态 → 递归循环只执行一次 | ChatStreamEventBus 加 thinkFinish 标记，reCallLlm 回调区分 think/final 事件 |
| 07-06 | 递归工具卡片索引错位 | mCurrentIndices[] 替代 mNextIndex+i，用 onNewPlaceholder 返回值 |
| 07-06 | MAX_LOOP 时 reasoning_text 丢失 | 错误处理器保存 mStreamContentBuffer 内容 |
| 07-06 | 递归轮 reasoning 挤在同一对话框 | reCallLlm 检测 tool_calls 时发 buildFinish 关闭当前 AI 消息 |
| 07-06 | 工具卡片不在可见区域 | onNewPlaceholder + onToolCallResult 加 scrollToPosition |
| 07-06 | 历史消息污染 LLM 上下文 | buildCurrentMessages 跳过 [流式异常] 和 [tool_call] 标记 |
| 07-06 | ANSI 码浪费 token | buildToolResultMessage 正则剥离 |
| 07-06 | loadChatRecords UUID 匹配不可用 | 逐条解析 UUID 从 toolCallMap 精确匹配 |
| 07-06 | PromptService msgType=2 警告刷屏 | mapToOpenAIRole 对 msgType==2 静默返回 null |
| 07-06 | 数据库空回复/异常消息显示 | loadChatRecords 跳过 `[空回复]` / `[流式异常]` 开头的记录 |

### 5.2 数据库 Schema 变更

| 版本 | 变更 |
|------|------|
| v2 | ToolCallLogBean.id (int 自增) → uuid (String UUID)，ToolCallDbHelper 主键改为 TEXT PRIMARY KEY |

---

## 六、🎯 待实现

### 6.1 P0 — 中断停止

**现状**：发送后无法停止正在执行的工具链。只有 `PromptService.cancelStream()` 但未与 UI 连接。

**方案**：
- 发送按钮在 AI 回复期间文字变为"停止"（红色），点击触发停止
- `ToolCallCoordinator` 加 `volatile boolean mStopped`
- `runLoop()` 每轮工具前检查标志
- `reCallLlm()` 的 `latch.await(5, MINUTES)` 改为 500ms 轮询 + 检查停止标志
- 停止时调用 `cancelStream()` + `coordinator.stop()`

**涉及文件**：`ChatActivity.java`, `ToolCallCoordinator.java`

### 6.2 P1 — 权限审批

**现状**：所有工具无条件执行，无审批机制。

**三种模式**：
| 模式 | 行为 |
|------|------|
| `allow_all` | 全部自动批准（当前行为） |
| `summary_approve` | 每个工具显示摘要，用户点击确认后执行 |
| `per_tool_ask` | 动态授权（暂不做） |

**方案**：
- `ConfigBean` 加 `permissionMode` 字段
- `ToolMeta` 加 `isReadOnly` / `isDestructive` 标志
- `ToolCallCoordinator.runLoop()` 执行前检查模式
- summary 模式：通过 `ToolCallCardCallback` 通知 UI，CountDownLatch 等待用户确认

**涉及文件**：`ConfigBean.java`, `ToolMeta.java`, `ToolCallCoordinator.java`, `ToolCallCardCallback.java`, `ChatActivity.java`

### 6.3 P2 — System Prompt 动态构建

**现状**：`PromptService.buildOpenAIMessages()` 中 system prompt 硬编码为简单字符串。

**方案**：
- 静态段：从 `ToolRegistry` 各工具的 `description` 字段动态拼接工具使用策略，首次构建后内存缓存
- 动态段：用户输入 + 历史消息每轮重新构建
- 不需要磁盘缓存

**涉及文件**：`PromptService.java`

### 6.4 P3 — 工具延迟加载

**现状**：16 个工具全部注入每次 LLM 请求。

**方案**（工具超 20 个时启用）：
- 添加 `search_tools` 工具（描述硬编码）
- 首轮只注入核心 5 工具 + `search_tools`
- LLM 需要时调用 `search_tools("关键词")` 获取完整定义
- 下轮带完整工具列表

**涉及文件**：`ToolRegistry.java`, `PromptService.java`

### 6.5 P3 — 对话上下文压缩

**现状**：无自动压缩，超长对话 token 溢出。

**方案**：参照 `autoCompact` 模式，消息历史接近 token 限制时自动摘要旧消息。

**涉及文件**：`PromptService.java`, 新增 `CompactService.java`

---

## 七、数据流架构（当前）

```
用户输入
  │
  ▼
ChatActivity
  ├→ PromptService → OpenAIStreamNetHelper → OkHttp SSE → DeepSeek API
  │                   │
  │                   └→ parseResponse → tool_calls 检测
  │                                       │
  │                                       ▼
  │                              ToolCallCoordinator
  │                                ├→ ToolRegistry.find()
  │                                ├→ tool.executor.execute()
  │                                ├→ ToolCallDbHelper.insert(uuid)
  │                                ├→ mCardCallback → UI 更新
  │                                └→ reCallLlm() → 循环
  │
  └→ LocalShellExecutor → PTY Session → LocalShellService
                              │
                              └→ SSH 隧道 (JSch)
```

---

## 八、Tool Calling 执行流程

```
用户消息
  │
  ▼
PromptService.sendStreamMessage()
  │ system prompt + 历史消息(messages) + tools(JSON Schema)
  ▼
OpenAIStreamNetHelper → DeepSeek API (SSE)
  │
  ▼
parseStreamResponse()
  │ text chunks → handleStreamEvent → UI 逐字显示
  │ finish_reason=tool_calls → buildToolCalls 事件
  ▼
ChatActivity.handleStreamEvent()
  │ 生成 UUID → 创建 TYPE_TOOL_CALL 占位消息 → 写入 chat_record 标记
  ▼
ToolCallCoordinator.execute(toolCalls, uuids)
  │
  ▼
runLoop() ───────────────────────┐
  │ 1. 执行工具（for 循环）      │
  │ 2. 记录到 tool_call_log      │
  │ 3. 构建 assistant + tool 消息 │
  │ 4. reCallLlm() → LLM API     │
  │ 5. 检测 tool_calls → 递归    │
  └──────────────────────────────┘
  (循环直到 LLM 返回纯文本)
  │
  ▼
最终文本 → handleStreamEvent → writeStreamRecordToDb
```

---

## 九、完整行动清单

### 立刻可做

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

### 近期（本次迭代）

| # | 操作 | 优先级 |
|:-:|:--|:--:|
| 1 | 停止按钮（发送按钮变红色"停止"） | P0 |
| 2 | ToolCallCoordinator 中断机制 | P0 |
| 3 | 权限模式配置 + 摘要审批 | P1 |
| 4 | 将 ChatActivity 内部类抽到独立文件 | 中 |
| 5 | 统一 MainActivity/AiConfigActivity UI 风格 | 中 |

### 下一阶段

| # | 操作 | 优先级 |
|:-:|:--|:--:|
| 1 | System prompt 动态构建（ToolRegistry 驱动） | P2 |
| 2 | ToolSearch 延迟加载（工具超 20 个时） | P3 |
| 3 | 对话上下文自动压缩 | P3 |
| 4 | 打通 OpenAIClient 多 API 路由 | P1 |

---

## 附录：原创代码 vs Termux 衍生代码

| 来源 | 文件数 | 协议 |
|------|--------|------|
| AlinOs 原创（`alin.android.alinos`） | 51+ | MIT |
| Termux app 层（GPLv3） | 11 | GPLv3 |
| Termux shared 库（主体 MIT） | 109 | MIT/GPLv3 |
| terminal-emulator（jackpal） | 14 | Apache 2.0 |
| terminal-view（jackpal） | 8 | Apache 2.0 |

---
