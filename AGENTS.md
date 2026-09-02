# AlinOs-for-Android — Agent 项目上下文

> 最后更新：2026-09-02

---

## 📌 项目定位

**AlinOs-for-Android** 是一款基于 Android 平台的云端 AI 接口专属客户端。

**核心架构：安卓应用壳 + 轻量 Termux 底层**
- 安卓层：封装云端 Open API（DeepSeek / OpenAI 兼容）
- Termux 层：执行本地 CLI 命令、搭建轻量 Linux 运行环境

---

## 🎯 当前方向：声音 / 音频服务

项目当前聚焦方向：**🔊 声音 / 音频相关服务**

已落地端侧 ASR（语音识别）、TTS（语音合成）、KWS（关键词唤醒）、声纹验证四大音频能力，并搭建统一的音频配置数据库与服务接口。

---

## 📊 项目当前进度总览

### 已完成模块（✅）

| 模块 | 状态 | 说明 |
|------|:----:|------|
| **核心对话** | ✅ | 流式 SSE 对话 + 多会话管理 + 聊天历史持久化 |
| **AI 配置管理** | ✅ | 多 AI 服务配置（DeepSeek / OpenAI 兼容） |
| **Tool Calling 引擎** | ✅ | Function Calling 循环（解析→执行→回注→递归，上限 10 轮） |
| **Agent 工具集** | ✅ | 16 个已注册工具（含元工具 search_tools） |
| **Think 块展示** | ✅ | AI 思考过程可折叠卡片 |
| **工具调用日志** | ✅ | 独立数据库存储完整调用日志（UUID 主键） |
| **终端仿真** | ✅ | PTY Shell 会话 + 远程 SSH 连接（JSch） |
| **上下文缓存** | ✅ | ContextCache 替代简单截断，token 超限分层丢弃 |
| **停止按钮** | ✅ | volatile mStopped 中断机制 + 红色停止按钮 |
| **音频服务** | ✅ | ASR/TTS/KWS/声纹 + 统一 AudioService 接口 |
| **MCP 工具服务** | ✅ | MCP Server 实现（HttpServer + ProtocolHandler） |

### 规划中模块（⏳）

| 模块 | 优先级 | 说明 |
|------|:------:|------|
| **悬浮窗常驻监听** | P0 | VAD 静音检测 + KWS 唤醒词两级流水线 |
| **工具执行层重构** | P1 | 参数校验、错误恢复、重试机制、结果回注容错 |
| **终端服务重构** | P1 | shell 会话稳定性、命令超时、输出截断处理 |
| **权限审批** | P1 | 多模式审批（allow_all / summary_approve / per_tool_ask） |
| **System Prompt 动态构建** | P2 | 从 ToolRegistry 动态拼接工具描述 |
| **工具延迟加载** | P3 | search_tools 按需注入，避免全部塞入 tools |
| **对话上下文压缩** | P3 | autoCompact 自动摘要旧消息 |
| **音频能力 SDK/AAR** | P1 | voice/core + 引擎接口对外打包 |
| **声音样本管理** | P2 | 声纹/声音样本采集、更新、删除 |
| **OpenAI Responses / Vertex AI / Anthropic** | P1 | 多 API 路由对接 |

---

## 🔧 构建要求

| 项目 | 要求 |
|------|------|
| Gradle 发行版 | gradle-8.14 |
| Gradle JDK | JDK 21 |
| Android Studio | 最新版 |

### ⚠️ 重要构建规则

- **不得删除 `/home/he/.gradle/*`** — 这是公共路径，清空后需重新下载大量依赖
- **优先修改配置**，而非清空 `.gradle` 内容
- 编译配置 `android.aapt2FromMavenOverride=/home/he/Android/Sdk/build-tools/36.1.0/aapt2` 加入 gradle.properties 即可正常编译

---

## 🏗️ 项目架构

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
  ├→ LocalShellExecutor → PTY Session → LocalShellService
  │                              │
  │                              └→ SSH 隧道 (JSch)
  │
  ├→ AudioService → SherpaAsrEngine / SherpaTtsEngine / SherpaKwsEngine
  │
  └→ MCP Server → McpHttpServer → MCP Protocol Handler
```

### 代码结构概览（原创文件 51+，存活 27）

| 包 | 说明 |
|----|------|
| `Activity` 层 | ChatActivity / AiConfigActivity / AgentConfigActivity / MainActivity 等 |
| `adapter/` | ChatAdapter / ConfigAdapter / SessionAdapter 等 |
| `bean/` | ChatRecordBean / ChatSessionBean / ConfigBean / ToolCallLogBean 等 |
| `db/` | ChatDBHelper / ConfigDBHelper / ToolCallDbHelper / SshDbHelper |
| `localshell/` | LocalShellExecutor / LocalShellService / LocalShellEnvironment |
| `tools/` | ToolCallCoordinator / ToolConverter / ToolCallCardCallback / 工具集 |
| `net/` | OpenAIStreamNetHelper / OpenAIClient（预留） |
| `prompt/` | PromptService / ContextCache |
| `manager/` | ChatStreamEventBus / ConsentDialogManager / ShizukuManager |
| `voice/` | ASR/TTS/KWS/声纹引擎 + 音频工具 + 配置数据库 |
| `mcp/` | McpHttpServer / McpProtocolHandler / McpServerManager |

---

## 📝 已注册工具（16 个）

### 真实工具（LocalShellExecutor）
`localshell_create_session` · `localshell_destroy_session` · `localshell_list_sessions` · `localshell_search_session` · `localshell_shell_exec` · `localshell_shell_write` · `localshell_shell_send_key` · `localshell_shell_read` · `localshell_read_history_canvas` · `localshell_shell_get_debug_view`

### 环境工具
`shell_exec` · `shell_read` · `shell_write` · `shell_send_key` · `shell_send_keys`（批量按键）

### 元工具
`search_tools` — 查询已注册工具列表

### 测试工具
`get_weather` · `get_time` · `search_web` · `calculate`

---

## 🎵 音频模块（当前方向）

| 模块 | 组件 | 状态 |
|------|------|:----:|
| ASR 离线识别 | SherpaAsrEngine + paraformer/sensevoice/whisper | ✅ |
| TTS 语音合成 | SherpaTtsEngine + SystemTtsEngine | ✅ |
| KWS 关键词唤醒 | SherpaKwsEngine + zipformer2 | ✅ |
| 声纹验证 | VoiceprintStore + campplus | ✅ |
| 音频配置数据库 | AppConfigStore（configs + models 双表） | ✅ |
| 统一音频接口 | AudioService.asrRecognizePcm / ttsSynthesize / kwsDetectPcm | ✅ |
| 自定义模型导入 | AudioModelManagerActivity | 🟡 |
| 悬浮窗常驻监听 | （规划中 VAD+KWS 流水线） | ⏳ |

---

## 🔴 近期修复记录（摘要）

- **Tool Calling 稳定性**：thinkFinish 竞态、递归卡片索引错位、MAX_LOOP reasoning 丢失
- **上下文缓存**：ContextCache 替代最后 10 条截断，token 超限分层丢弃
- **shell_read waitMs**：下载/安装任务设 2000~3000ms 延迟读取
- **ANSI 剥离**：buildToolResultMessage 正则清理
- **停止机制**：ToolCallCoordinator volatile mStopped + 红色停止按钮
- **sherpa-onnx 升级**：1.13.5 修复 qnnConfig JNI 崩溃
- **MCP 工具服务**：新增 MCP Server 实现
- **LocalShell 初始化**：修复并发/前台服务崩溃

---

## 📌 数据流关键变更

- **ToolCallLogBean UUID 重构**：id (int 自增) → uuid (String UUID)，ToolCallDbHelper 主键改为 TEXT PRIMARY KEY
- **chat_record UUID 标记**：`[tool_call:UUID]toolName` 格式精确匹配
- **ContextCache 替代简单截断**：用户/AI 消息全文保留，工具标记跳过

---

## 📂 项目资源

- **GitHub**: https://github.com/heworkalin/AlinOs-for-android
- **Gitee**: https://gitee.com/hewrod/AlinOs-for-android
- **详细状态**: PROJECT_STATUS.md
- **能力规划**: AGENT_CAPABILITY_ROADMAP.md
- **MCP 规范**: mcp.md