# AlinOs AI Agent 能力规划与现状分析

> 生成日期：2026-07-01（更新于 2026-07-01）  
> 目的：明确 AI Agent 应具备的核心能力，对照 AlinOs 当前完成度，确定下一步开发方向

---

## 一、AI Agent 能力全景图

一个完整的 AI Agent 需要以下 **7 大核心能力模块**：

```
┌────────────────────────────────────────────────────────────────────┐
│                         AI Agent 能力体系                            │
├──────────┬──────────┬──────────┬──────────┬──────────┬─────────────┤
│  感知层   │  规划层   │  工具层   │  记忆层   │  反馈层   │  安全层     │
│ (输入)    │ (思考)    │ (执行)    │ (上下文)   │ (学习)    │ (边界)      │
├──────────┼──────────┼──────────┼──────────┼──────────┼─────────────┤
│ ·多模态   │ ·任务分解  │ ·Function │ ·短期记忆 │ ·结果评估  │ ·权限控制   │
│  输入     │ ·链式推理  │  Calling  │  (上下文)  │ ·错误恢复  │ ·内容审核   │
│ ·自然语言  │ ·路径规划  │ ·API调用   │ ·长期记忆  │ ·人类反馈  │ ·沙箱隔离   │
│ ·结构化   │ ·工具选择  │ ·代码执行   │  (向量DB)  │ ·自我修正  │ ·速率限制   │
│  输入     │ ·反思重试  │ ·SSH/Shell │ ·外部存储  │            │            │
│           │ ·按需加载  │ ·Web搜索   │ ·工具调用   │            │            │
│           │  工具集    │            │  记录库    │            │            │
└──────────┴──────────┴──────────┴──────────┴──────────┴─────────────┘
```

### 1.1 感知层（Perception）
| 能力 | 说明 | 优先级 |
|------|------|:------:|
| 自然语言输入 | 接收用户对话文本 | P0 |
| 多模态输入 | 图片/音频/文件 | P1 |
| 结构化输入 | JSON/Config 等 | P0 |
| 系统事件感知 | 通知/状态变化 | P2 |

### 1.2 规划层（Planning）
| 能力 | 说明 | 优先级 |
|------|------|:------:|
| 任务分解 | 复杂目标拆解为子任务 | P1 |
| 工具选择 | 根据意图选择合适的工具调用 | P0 |
| 链式推理 | 多步推理（Chain-of-Thought） | P1 |
| 错误重试 | 失败后自动重试或回退 | P0 |
| **工具按需加载** | 不全部注入工具集，按意图只加载本轮需要的工具子集 | **P0** |
| 反思修正 | 根据执行结果调整计划 | P2 |

### 1.3 工具层（Tool Use / Function Calling）⭐ 核心
| 能力 | 说明 | 优先级 |
|------|------|:------:|
| **Function Calling 标准** | 符合 OpenAI/DeepSeek/Anthropic 的工具定义格式 | **P0** |
| 工具注册与发现 | 动态注册、描述、参数模式声明 | P0 |
| 工具执行引擎 | 解析 `tool_calls` → 执行 → 返回 `tool_result` | **P0** |
| 流式工具调用 | SSE 流中处理 tool_calls 增量分片 | **P0** |
| 多工具并行 | 一次响应中并发执行多个工具，统一回注 | **P0** |
| 搜索工具注册 | 必须注册 Web Search 类工具 | **P0** |
| 测试工具先行 | Tool Calling 循环未跑通前，先用固定假工具测试 | **P0** |
| 工具调用日志 | 每次调用的完整内容记入独立数据库 | **P0** |
| 工具链编排 | 前一个工具的输出作为后一个的输入 | P1 |
| Prompt Caching | 利用 API 缓存减少重复工具定义消耗 | **P0** |

### 1.4 记忆层（Memory）
| 能力 | 说明 | 优先级 |
|------|------|:------:|
| 短期记忆 | 对话上下文窗口管理 | P0 |
| Token 窗口管理 | 控制上下文不超限 | P0 |
| 会话持久化 | 跨轮次保存聊天记录 | ✅ 已完成 |
| **工具调用记录** | 独立 DB 表存储每次工具调用的完整入参/出参/耗时/状态 | **P0** |
| 长期记忆 | 向量数据库/知识库 | P2 |

### 1.5 反馈层（Feedback）
| 能力 | 说明 | 优先级 |
|------|------|:------:|
| 结果评估 | 判断执行结果是否满足意图 | P1 |
| 错误处理 | 超时/异常/拒绝时的降级策略 | P0 |
| 人类干预 | 关键操作需用户确认 | P1 |

### 1.6 安全层（Safety）
| 能力 | 说明 | 优先级 |
|------|------|:------:|
| 权限控制 | 敏感操作需授权 | P1 |
| 沙箱隔离 | 工具执行在受限环境中 | P2 |
| 审计日志 | 所有工具调用可追溯（同工具调用记录库） | P0 |

---

## 二、工具调用标准详解（Agent 核心）

### 2.1 核心概念：Tool Calling 工作流

工具调用的本质是一个 **循环（Loop）**，直到模型不再要求调用工具为止：

```
初始化对话历史
    │
    ▼
按意图加载本轮需要的工具子集（非全量）
    │
    ▼
调用 AI 聊天接口（传入工具定义 + tool_choice 策略）
    │
    ▼
解析 AI 返回：
  ├─ 不需要工具 → 结束循环，返回最终回答
  └─ 需要工具 → 取出所有 tool_calls（可能多个）
                     │
                     ▼
              【并发执行每个工具】
                ├─ 取 function.name / arguments
                ├─ arguments string → JSON 解析
                ├─ 执行对应工具函数
                ├─ 记录完整日志到 tool_call DB
                └─ 生成标准 tool_result
                     │
                     ▼
              把工具调用结果追加到历史记录
              更新 UI 显示工具卡片（摘要态）
                     │
                     ▼
              回到第 2 步，继续循环
```

### 2.2 OpenAI Function Calling 标准（业界事实标准）

所有主流 LLM（OpenAI、DeepSeek、Anthropic、Google Gemini）均兼容此格式。

#### 工具定义格式（tools 参数）

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "获取指定城市的当前天气",
        "parameters": {
          "type": "object",
          "properties": {
            "location": {
              "type": "string",
              "description": "城市名称，如北京、上海"
            },
            "unit": {
              "type": "string",
              "enum": ["celsius", "fahrenheit"],
              "default": "celsius"
            }
          },
          "required": ["location"]
        }
      }
    }
  ],
  "tool_choice": "auto"
  // tool_choice 可选值：
  //   "auto"       — 模型自主决定是否调用（默认）
  //   "required"   — 强制调用工具
  //   "none"       — 禁止调用工具
  //   {"type":"function","function":{"name":"xxx"}} — 指定调用某个工具
}
```

**最佳实践**: 工具名要直观，描述要清楚（"什么时候用"），参数要少而准。

#### 调用返回值格式

模型返回 `finish_reason: "tool_calls"` 时，`message` 中包含 `tool_calls` 数组：

```json
{
  "choices": [{
    "finish_reason": "tool_calls",
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_xxx",
        "type": "function",
        "function": {
          "name": "get_weather",
          "arguments": "{\"location\":\"北京\"}"
        }
      }]
    }
  }]
}
```

#### 工具结果回注格式

```json
{
  "role": "tool",
  "tool_call_id": "call_xxx",
  "content": "{\"temperature\": 28, \"condition\": \"晴\"}"
}
```

#### 流式场景下的 tool_calls

SSE 流中，`tool_calls` 也以增量方式返回：

```
分片 1: delta.tool_calls[0].id = "call_xxx"
分片 2: delta.tool_calls[0].function.name = "get_weather"
分片 3: delta.tool_calls[0].function.arguments = "{\"location\":"
分片 4: delta.tool_calls[0].function.arguments = "\"北京\"}"
```

**关键**: 流式下需要按 `index` 字段归并同一个 tool_call 的增量参数。

### 2.3 GPT-5 的新特性（演进方向）

| 特性 | 说明 | 对 AlinOs 的意义 |
|------|------|:----------------:|
| **自定义工具（Custom Tools）** | 自由格式文本输出，不强制 JSON Schema | 适合直接生成 Shell 脚本、SQL 等原始文本 |
| **语法约束（Lark/CFG）** | 用上下文无关文法约束输出格式 | 确保生成格式绝对正确 |
| **工具白名单（allowed_tools）** | 限制本次对话可用的工具子集 | 安全控制 |
| **Preamble（调用前释义）** | 模型在调用前解释调用原因 | 提升透明度，方便调试 |

```
GPT-5 的工具分类：
  函数工具 (Function Tools) — 基于 JSON Schema，结构化输入输出，精确可靠
  自定义工具 (Custom Tools) — 自由格式文本，适合 SQL/Shell脚本/配置文件
```

### 2.4 DeepSeek 的 Tool Calls 实现

DeepSeek 完全兼容 OpenAI 的 Function Calling 格式，额外提供：

| 特性 | 说明 | 等级 |
|------|------|:----:|
| **思考模式下的工具调用** | 从 DeepSeek-V3.2 开始支持，思考后再决定工具选择 | ✅ 已可用 |
| **Strict 模式（Beta）** | 强制模型输出严格符合 JSON Schema 定义 | 🔶 Beta |
| **$ref/$def 模块化 Schema** | 支持 JSON Schema 的引用和复用，减少重复定义 | ✅ 已支持 |

#### Strict 模式的 JSON Schema 要求

```json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "strict": true,
    "parameters": {
      "type": "object",
      "properties": {
        "location": { "type": "string" }
      },
      "required": ["location"],
      "additionalProperties": false    ← strict 模式必须
    }
  }
}
```

**支持的 JSON Schema 类型**：

| 类型 | 支持 | 关键约束 |
|------|:----:|:---------|
| `object` | ✅ | 所有属性必须为 required，`additionalProperties: false` |
| `string` | ✅ | 支持 `pattern`、`format`（email/hostname/ipv4/ipv6/uuid）|
| `number`/`integer` | ✅ | 支持 `minimum`/`maximum`/`exclusiveMin`/`exclusiveMax`/`multipleOf` |
| `array` | ✅ | 支持 `items` 定义元素类型 |
| `boolean` | ✅ | 无特殊约束 |
| `enum` | ✅ | 限定可选值列表 |
| `anyOf` | ✅ | 多类型联合（如 邮箱 或 手机号） |
| `$ref`/`$def` | ✅ | 模块化复用 |

### 2.5 Anthropic Tool Use（Claude）— 后续适配

| 维度 | OpenAI | Anthropic |
|------|--------|-----------|
| 参数名 | `tools` | `tools` |
| 工具定义 | `type: "function"` + `function: {...}` | 直接定义 `{name, description, input_schema}` |
| 调用返回值 | `tool_calls` 数组 | `content` 中 `type: "tool_use"` 块 |
| 工具结果 | `role: "tool"` | `role: "user"` 中 `type: "tool_result"` 块 |
| 参数 Schema | `parameters` (JSON Schema) | `input_schema` (JSON Schema) |
| 流式 | `delta.tool_calls` 增量 | `content_block_delta` 增量 |

### 2.6 AlinOs 标准化层设计

```
┌──────────────────────────────────────────────────────────────┐
│                    ToolIntentRouter                            │
│          （按意图路由：本轮需要哪些工具？）                       │
├──────────────────────────────────────────────────────────────┤
│  get_weather  │  search_web  │  calculate  │  更多工具...     │
│  ← 天气相关     │  ← 搜索相关   │  ← 计算相关   │                │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────┐
│               ToolRegistry / ToolMeta                          │
│              (统一工具定义 + 多格式输出)                          │
├──────────────────────────────────────────────────────────────┤
│  ToolMeta → OpenAI format (tools)       ← 先实现              │
│           → DeepSeek strict format            ← 同时          │
│           → Anthropic format (input_schema)   ← 后续          │
└──────────────────┬───────────────────────────────────────────┘
                   │
                   ▼
          ToolCallCoordinator (执行引擎 + 回注循环)
                   │
          ┌────────┴────────┐
          ▼                 ▼
   LocalShellExecutor   测试工具集
   (11个真实工具)        (3-4个固定假工具)
```

---

## 三、UI 渲染规范（新增）

### 3.1 消息类型扩展

当前：2 种 ViewType → 扩展为 **4 种 ViewType**

| ViewType | 渲染内容 | 状态 |
|:---------|:---------|:----:|
| `TYPE_USER` | 用户消息气泡（已有） | ✅ |
| `TYPE_AI` | AI 文本回复（已有） | ✅ |
| `TYPE_THINK` | Think 思考块卡片（新增） | 🆕 |
| `TYPE_TOOL_CALL` | 工具调用卡片（新增） | 🆕 |

### 3.2 Think 思考块卡片

```
┌─────────────────────────────────────────────────────┐
│ 🤔  AI 思考过程  ▼                ← 可点击折叠/展开  │
├─────────────────────────────────────────────────────┤
│ 模型内部推理内容...                                    │
│ 多行文本...                                           │
│ 思考链...                                             │
└─────────────────────────────────────────────────────┘
```

**行为逻辑**:
- 流式解析时检测到 `<think>...</think>` → 单独剥离为 Think 块
- 流式进行中：保持展开，实时显示思考内容
- 流式结束后：自动折叠成标题栏
- 用户可点击标题栏手动展开/折叠

**解析方式**（在 OpenAIStreamNetHelper 中）:

```
当前: delta.content = "完整的回复文本<think>...</think>混合内容"

改造后:
  delta.content 中若含 <think>...</think> → 拆分两路事件:
    → "stream_think" 事件: 思考块内容
    → "stream_content" 事件: 纯回复内容（不含思考块）
```

### 3.3 工具调用卡片

```
┌─────────────────────────────────────────────────────┐
│ 🔧  get_weather()                    [1.2s]  ✅ 成功 │  ← 摘要行
├─────────────────────────────────────────────────────┤
│ 📥 {"location":"北京"}                               │  ← 参数摘要
│                                                     │
│ ── 点击展开完整请求/响应 ──                           │  ← 虚线提示
└─────────────────────────────────────────────────────┘
                          ↓ 点击展开
┌─────────────────────────────────────────────────────┐
│ 🔧  get_weather()                    [1.2s]  ✅ 成功 │
├─────────────────────────────────────────────────────┤
│ [请求]                                               │
│ tool_call_id: call_abc123                            │
│ arguments: {"location":"北京"}                        │
│                                                     │
│ [响应]                                               │
│ status: success                                      │
│ result: {"temperature":28,"condition":"晴"}          │
│ raw: {...完整JSON...}                                 │
│                                                     │
│ [日志]                                               │
│ 执行时间: 2026-07-01 12:00:00.123                      │
│ 耗时: 1.2s                                           │
│ 数据库ID: #42                                        │
└─────────────────────────────────────────────────────┘
```

**行为逻辑**:
- 默认渲染 **摘要模式**：工具名、耗时、状态、参数摘要
- 点击卡片**展开/折叠**完整内容
- 完整内容从内存缓存或 `tool_call_log` 数据库读取
- 工具正在执行时显示 `⏳ 执行中...` 状态
- 多工具并行时，每个工具独立一张卡片，按顺序排列

### 3.4 消息列表渲染流程（完整）

```
流式数据流 → OpenAIStreamNetHandler
  │
  ├── delta.content 含有 <think> → TYPE_THINK 卡片 + 剥离内容
  │
  ├── delta.content 纯文本       → TYPE_AI 文本气泡
  │
  └── finish_reason: tool_calls  → ToolCallCoordinator
        │
        ├── 并行执行所有工具
        ├── 每个工具 → TYPE_TOOL_CALL 卡片（摘要模式）
        ├── 执行中 → UI 显示 ⏳
        └── 完成后 → UI 更新状态 ✅/❌ + 耗时
              │
              ▼
        回注结果 → 重新调用 LLM → 回到流式接收
```

---

## 四、工具按需加载策略

### 4.1 问题

未来工具数量可能很多（10+、20+），如果全部塞进 API 调用的 `tools` 参数：

- **Token 消耗大**：每个工具定义含 name + description + parameters JSON Schema，全量注入可能上千 token
- **模型选择负担**：工具越多，模型误选/不选的概率越高
- **响应变慢**：首 token 时间受 tools 长度影响

### 4.2 方案：按意图路由

```
用户输入
    │
    ▼
ToolIntentRouter.analyze(message)
    │
    ├── 包含"搜索""查找""查一下"等 → 注入 search_web 工具
    ├── 包含"天气"                → 注入 get_weather 工具
    ├── 包含"计算"                → 注入 calculate 工具
    ├── 包含"终端""命令""shell"    → 注入 shell_exec 等工具
    └── 默认                      → 注入基础工具集
```

**实现**:
- 新增 `ToolIntentRouter.java`，基于关键词/语义匹配
- 每个工具注册时声明触发词（trigger keywords）
- 支持多个工具交集（如"搜索北京的天气" → search_web + get_weather）

### 4.3 Fallback 策略

- 按意图加载后，如果模型认为工具不够用，可以追加
- 追加方式：用户输入"调用xxx工具" → 补充注入 + 重新请求

---

## 五、Prompt Caching 策略

### 5.1 问题

每次对话请求都携带完整的 `tools` 定义，即使工具集在连续多轮中没有变化，也重复消耗 token。

### 5.2 方案

利用 DeepSeek/OpenAI 的 **Prompt Caching** 特性：

```
第 1 轮: system prompt + tools(完整) → 写入缓存 ✅
第 2 轮: system prompt + tools(完整) → 命中缓存，只算少量缓存读取费 ✅
第 3 轮: 工具集变化 → 新缓存写入
```

**条件**（以 DeepSeek 为例）：
- 连续 N 个 token 前缀相同 → 自动命中缓存
- `tools` 定义在对话历史中的位置靠前 → 更容易命中缓存

**AlinOs 实现**：
- 将 `tools` 定义放在 `messages` 数组的最前面（紧接 system）
- 多轮对话中工具集不变时不重复发送 tools（第一次发送后缓存在服务端）
- 工具集变化时才重新发送

---

## 六、工具调用日志数据库

### 6.1 新增表结构

```sql
CREATE TABLE tool_call_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    
    -- 关联信息
    session_id      INTEGER NOT NULL,       -- 关联 chat_session
    record_id       INTEGER,                -- 关联 chat_record（可选）
    
    -- 调用信息
    tool_name       TEXT NOT NULL,           -- 工具名
    tool_call_id    TEXT,                    -- LLM 返回的 tool_call_id
    arguments       TEXT,                    -- 入参 JSON
    result          TEXT,                    -- 出参 JSON
    
    -- 执行状态
    status          TEXT DEFAULT 'success',  -- success / error / timeout
    error_message   TEXT,                    -- 错误信息
    duration_ms     INTEGER,                -- 执行耗时（毫秒）
    
    -- 时间戳
    created_at      INTEGER NOT NULL        -- 调用时间
);
```

### 6.2 作用

| 用途 | 说明 |
|:----|:------|
| **调试** | 开发过程中查看每次工具调用的完整入参/出参 |
| **UI 回溯** | 工具卡片展开时，从 DB 读取完整内容展示 |
| **审计** | 安全存档，追溯 AI 曾调用了哪些工具做了什么操作 |
| **历史回顾** | 用户查看某条聊天记录时，可以看到当时调用了哪些工具 |

### 6.3 生命周期

- 每次工具调用执行完毕后写入
- 工具卡片 UI 默认显示摘要，展开时从 DB 或内存缓存读取完整内容
- 保留策略同聊天记录（删除会话时级联删除）

---

## 七、第一阶段测试工具集

Tool Calling 循环没有跑通之前，**不暴露真实工具（LocalShellExecutor 的 11 个工具）给 AI**。先注册 4 个固定假工具，专门用于调试循环流程：

| 工具名 | 描述 | 参数 | 返回 |
|:-------|:-----|:-----|:-----|
| `get_weather` | 获取城市天气 | `location: string` | `{"temp":28,"condition":"晴"}` |
| `get_time` | 获取当前时间 | 无 | `{"time":"12:00","timezone":"UTC+8"}` |
| `search_web` | 搜索互联网 | `query: string` | `{"results":["结果1","结果2"]}` |
| `calculate` | 执行数学计算 | `expression: string` | `{"result": 42}` |

**调试日志输出格式**（Logcat + UI 调试面板）：

```
╔═══════════════════════════════════════════════╗
║            Tool Call #1                      ║
╠═══════════════════════════════════════════════╣
║ Tool:     get_weather                        ║
║ Args:     {"location":"北京"}                  ║
║ ────────────────────────────────────────────  ║
║ Raw API Response:                            ║
║ {                                            ║
║   "id": "chatcmpl-xxx",                      ║
║   "choices": [{                              ║
║     "finish_reason": "tool_calls",            ║
║     "message": {                             ║
║       "tool_calls": [{                       ║
║         "id": "call_abc",                    ║
║         "function": {                        ║
║           "name": "get_weather",             ║
║           "arguments": "{\"location\":\"北京\"}" ║
║         }                                    ║
║       }]                                     ║
║     }                                        ║
║   }]                                         ║
║ }                                            ║
║ ────────────────────────────────────────────  ║
║ Execution: success, 1.2s                      ║
║ Result:   {"temp":28,"condition":"晴"}        ║
║ DB ID:   #42                                 ║
╚═══════════════════════════════════════════════╝
```

**循环状态日志**（UI 顶部状态栏）：

```
Tool Loop: ⏳ 第 2 轮 | tools: get_weather | 总耗时: 3.5s
Tool Loop: ✅ 完成 | 共调用 2 个工具 | 总耗时: 4.2s
```

---

## 八、AlinOs 当前完成度评估

### 8.1 模块对照表

| 能力模块 | 组件 | 完成度 | 状态 |
|----------|------|:------:|:----:|
| **感知层** | ChatActivity 输入/流式渲染 | ✅ 90% | 已投产 |
| **感知层** | 多模态输入（图片/文件） | ⬜ 0% | 未开始 |
| **规划层** | 工具选择 | 🔶 20% | ToolRegistry 已注册但未接入对话流 |
| **规划层** | 按需加载 | ⬜ 0% | 未开始 |
| **规划层** | 错误重试（503 重试） | ✅ 80% | 已实现 |
| **工具层** | 工具定义模型（ToolMeta） | ✅ 90% | 已实现 |
| **工具层** | 工具注册表（ToolRegistry） | ✅ 90% | 已注册 11 个工具 |
| **工具层** | **Function Calling 标准对接** | 🔶 **10%** | **核心缺口** |
| **工具层** | 流式 tool_calls 解析 | ⬜ 0% | 未开始 |
| **工具层** | 多工具并行执行 | ⬜ 0% | 未开始 |
| **工具层** | 搜索工具注册 | ⬜ 0% | 待注册 |
| **UI 层** | Think 卡片 | ⬜ 0% | 未开始 |
| **UI 层** | 工具调用卡片 | ⬜ 0% | 未开始 |
| **记忆层** | 短期记忆（上下文窗口） | ✅ 80% | PromptService 已实现 |
| **记忆层** | 会话持久化（SQLite） | ✅ 90% | ChatDBHelper |
| **记忆层** | Token 估算与窗口控制 | ✅ 80% | TokenEstimator |
| **记忆层** | 工具调用日志库 | ⬜ 0% | 未开始 |
| **反馈层** | 错误处理 | ✅ 60% | 超时重试、流式异常处理 |
| **反馈层** | 人工确认 | ✅ 50% | Shizuku 授权流程 |
| **安全层** | Shizuku 提权控制 | ✅ 70% | ShizukuManager |

### 8.2 当前已走通 vs 待走通

```
✅ 已通:
用户输入 → 流式文本回复

💀 未通:
用户输入 → 流式文本 → Think 块 → Tool Calling → 工具执行 → 结果回注 → 最终回复
```

---

## 九、路线图：按优先级排序

### 🔴 P0 — 必须优先实现（核心缺口）

| # | 任务 | 涉及 | 工作量 |
|:-:|:----|:-----|:------:|
| 1 | **测试工具集注册** | 新增 4 个固定假工具（get_weather/get_time/search_web/calculate） | 1 文件，~60 行 |
| 2 | **ToolMeta → OpenAI JSON Schema 转换** | 将 ToolMeta 参数定义自动转为 `tools` 数组 | 1 文件，~100 行 |
| 3 | **PromptService 注入 tools 参数** | 请求时携带 tools + tool_choice | 修改 2 文件 |
| 4 | **流式 tool_calls 解析** | parseStreamResponse 识别 finish_reason + 按 index 归并增量 | 修改 1 文件，~150 行 |
| 5 | **Think 块剥离 + 事件回调** | 流式解析中识别 `<think>` 标签，拆分为独立事件 | 修改 1 文件，~50 行 |
| 6 | **Tool Calling 循环引擎** | 收到 tool_calls → 并发执行 → 回注 → 重新调用 → 循环直到结束 | 新增 ToolCallCoordinator.java，~250 行 |
| 7 | **UI: Think 卡片** | 可折叠 Think 气泡（流式展开→结束后自动折叠） | 修改 ChatActivity + ChatAdapter |
| 8 | **UI: 工具调用卡片** | 摘要模式 + 点击展开完整内容 | 修改 ChatActivity + ChatAdapter |
| 9 | **工具调用日志 DB** | 新建 tool_call_log 表记录完整入参/出参/耗时/状态 | 新增 ToolCallDbHelper.java |
| 10 | **调试日志** | 每次循环打印完整 API 响应 + 执行过程到 Logcat | 少 |
| 11 | **搜索工具注册** | 注册 search_web 到 ToolRegistry | 少 |

### 🟠 P1 — 重要的增强

| # | 任务 | 说明 |
|:-:|:----|:-----|
| 12 | 工具按需加载（ToolIntentRouter） | 基于意图只注入本轮需要的工具子集 |
| 13 | Prompt Caching | 工具定义前置 + 多轮复用缓存 |
| 14 | Anthropic Tool Use 适配 | 兼容 Claude 格式 |
| 15 | tool_choice 控制 | 允许用户/配置指定强制/禁用 |
| 16 | 真实工具接入 | 测试通过后，逐步挂上 LocalShellExecutor 的 11 个工具 |
| 17 | 工具调用历史 UI 回溯 | 展开卡片时从 DB 读取完整记录 |

### 🟡 P2 — 长期演进

| # | 任务 | 说明 |
|:-:|:----|:-----|
| 18 | 多模态输入（图片） | 支持 Vision API |
| 19 | 向量记忆（长期记忆） | 引入向量数据库 |
| 20 | Agent 多步推理循环 | 自动任务分解 + 迭代执行 |
| 21 | SSH SCP 文件传输 | Agent 调用 SCP 完成文件迁移 |

---

## 十、架构集成点详述

### 10.1 流式数据流改造

```
OpenAIStreamNetHelper.parseStreamResponse()
    │
    ├── 普通文本分片 → "stream_content" 事件 → TYPE_AI 气泡
    │
    ├── <think>...</think> 分片 → "stream_think" 事件 → TYPE_THINK 卡片
    │
    └── finish_reason = "tool_calls"
         → 累积 delta.tool_calls → 完整 tool_calls →
           "stream_tool_calls" 事件 → ToolCallCoordinator
```

### 10.2 ToolCallCoordinator 循环

```
ToolCallCoordinator.run(sessionId, tools, messages)
    │
    ├─ 1. 调用 LLM（传入 messages + tools）
    │
    ├─ 2. 解析响应
    │    ├─ 有 tool_calls → 进入步骤 3
    │    └─ 无 tool_calls → 返回最终文本 → 结束
    │
    ├─ 3. 并发执行工具
    │    ├─ UI 更新：每个工具显示 ⏳ 执行中卡片
    │    ├─ 执行完毕 → 写入 tool_call_log DB
    │    ├─ UI 更新：卡片切换为 ✅/❌ + 耗时
    │    └─ 构造 role: "tool" 消息
    │
    ├─ 4. 回注 + 递归
    │    ├─ 追加 tool 消息到 messages
    │    ├─ 更新 UI
    │    └─ 回到步骤 1（递归调用，最多 N 轮防死循环）
    │
    └─ 5. 最终文本发送到 UI
```

### 10.3 调试日志（每次循环打印）

```
=== Tool Call #[轮次] ===
Tool:     ${tool_name}
Args:     ${arguments_json}
────────────────────────
Raw API Response:
${完整API响应JSON}
────────────────────────
Execution: ${status}, ${duration}ms
Result:    ${result_json}
DB ID:     #${db_id}
Tool Loop: 第 N 轮 | 总耗时 ${total}ms
========================
```

---

## 十一、当前项目已注册的工具表

### 11.1 真实工具（LocalShellExecutor — 测试通过后接入）

| 工具名 | 能力 |
|:-------|:-----|
| `localshell_create_session` | 创建 PTY 终端会话 |
| `localshell_destroy_session` | 销毁会话 |
| `localshell_list_sessions` | 查看活跃会话 |
| `localshell_search_session` | 搜索会话 |
| `localshell_shell_exec` | **核心**——执行 Shell 命令 |
| `localshell_shell_write` | 写入文本 |
| `localshell_shell_send_key` | 发送控制键 |
| `localshell_shell_read` | 读取输出 |
| `localshell_read_history_canvas` | 读取历史画布 |
| `localshell_shell_get_debug_view` | 调试视图 |

### 11.2 测试工具（第一阶段用）

| 工具名 | 能力 |
|:-------|:-----|
| `get_weather` | 返回固定天气数据 |
| `get_time` | 返回当前时间 |
| `search_web` | 返回固定搜索结果 |
| `calculate` | 执行简单数学运算 |

---

## 十二、总结：行动指令

### 当前状态

```
真实工具注册 ✅    测试工具待注册 ❌    Tool Calling 循环 ❌    Think/工具UI ❌
ToolRegistry        get_weather/...      链路全部断开            无对应 UI
```

### 第 1 阶段目标（P0）

**把 Tool Calling 循环从 0 到 1 跑通，同时 UI 支持 Think 和工具调用的展示**

```
测试工具 → PromptService(含tools) → LLM 返回 tool_calls
  → ToolCallCoordinator → 并发执行 → 结果回注
  → LLM 最终回复 → UI 展示(Think折叠 + 工具卡片 + 文本)
```

### 第 2 阶段目标（P1）

```
按需加载 → Prompt Caching → 真实工具接入 → Anthropic 适配
```

### 不做的事（当前阶段）

- ❌ 不搭 MCP Server
- ❌ 不实现 Anthropic/Vertex AI 适配（先跑通 DeepSeek）
- ❌ 不实现多模态
- ❌ 不实现长期记忆
- ❌ 不重构 UI 风格

---

*本文档基于 AlinOs 源码分析 + 四份参考资料整理：*

1. **GPT-5 Function Calling 完全指南** — blog.eimoon.com
2. **DeepSeek Tool Calls 官方文档** — api-docs.deepseek.com
3. **Function Calling / Tool Calling 实践** — cnblogs.com/Jkingj
4. **OpenAI Function Calling 官方文档** — help.openai.com
