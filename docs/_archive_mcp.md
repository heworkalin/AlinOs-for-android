# Model Context Protocol（MCP）**官方标准规范文档（核心原文摘录，不含调用教程，仅字段/结构/约束）**
协议基准：**JSON-RPC 2.0**；协议基准版本：**2025-06-18（稳定版）**；两大传输：**Stdio Transport、Streamable HTTP Transport**；完整官方地址：
- 英文原版：https://modelcontextprotocol.io/specification/2025-06-18/basic
- 中文译本：https://modelcontextprotocol.info/zh-cn/specification/2025-06-18/basic

---

## 一、基础消息类型（三类，强制JSON结构）
### 1. 请求报文（Client ↔ Server，必须应答）
**必填字段**
| 字段 | 类型 | 约束 |
|---|---|---|
| `jsonrpc` | 字符串 | 固定值 `"2.0"`，不可修改 |
| `id` | string / number | **禁止null**；同会话内不可重复；用于匹配响应 |
| `method` | string | MCP内置方法名 |
| `params` | object | 可选；承载该方法入参；可包含保留子字段 `_meta` |

JSON Schema 结构：
```json
{
  "jsonrpc": "2.0",
  "id": "req-1",
  "method": "initialize",
  "params": {}
}
```

### 2. 响应报文（仅应答请求，二选一：result / error）
#### （1）成功响应
**必填**：`jsonrpc`、`id`、`result`；`result` 内**必须包含 `resultType`**（固定值 `complete`）
```json
{
  "jsonrpc": "2.0",
  "id": "req-1",
  "result": {
    "resultType": "complete"
  }
}
```

#### （2）错误响应
**必填**：`jsonrpc`、`id`、`error`；`error` 必须包含 `code`（int）、`message`（string）；`data` 可选
```json
{
  "jsonrpc": "2.0",
  "id": "req-1",
  "error": {
    "code": -32601,
    "message": "Method not found",
    "data": {}
  }
}
```
> 约束：**一个响应不能同时存在 `result` 和 `error`**。

### 3. 通知报文（单向推送，无应答，无id）
**必填**：`jsonrpc`、`method`；`params` 可选；**禁止携带id**
```json
{
  "jsonrpc": "2.0",
  "method": "notifications/initialized",
  "params": {}
}
```

---

## 二、两大传输层标准（连接规范）
### 1. Stdio Transport（本地进程标准，最通用）
1. 通信载体：**stdin（发JSON）、stdout（收JSON）、stderr（仅日志，严禁输出JSON）**
2. 分包规则：**单行为一条完整JSON对象，换行分隔；JSON内部禁止换行**
3. 启动方式：客户端fork子进程，通过命令行拉起服务；配置为命令+参数数组
4. 限制：单客户端独占；无原生鉴权，依赖系统权限控制

### 2. Streamable HTTP Transport（网络多客户端标准）
1. 下行（Client→Server）：`POST` 请求承载JSON-RPC消息
2. 上行（Server→Client）：**SSE（Server-Sent Events）长连接推送响应/通知**
3. 鉴权：标准 `Authorization: Bearer <token>` 请求头
4. 会话：单HTTP会话绑定一组请求-响应流；支持多客户端并发接入

---

## 三、生命周期标准方法（固定调用顺序，字段全约束）
### 1. `initialize`（握手初始化，会话第一条消息）
#### Client 请求 params 必填字段
- `protocolVersion`：字符串，必须与服务端支持版本一致（如 `"2025-06-18"`）
- `clientInfo`：`{name:string, version:string}`，客户端标识
- `capabilities`：对象，声明客户端支持能力（`tools`/`sampling`/`roots` 等）

#### Server 响应 result 必写字段
- `protocolVersion`
- `serverInfo`：`{name:string, version:string}`
- `capabilities`：服务端暴露能力（`tools`/`resources`/`prompts`）

#### 握手收尾
客户端收到成功响应后，**必须发送通知**：`notifications/initialized`。

### 2. `tools/list`（枚举工具清单）
- 请求：`params` 可携带分页 `cursor`，无分页则空对象
- 响应 `result.tools[]` 单工具结构（**全部字段约束**）
| 字段 | 是否必填 | 类型 | 说明 |
|---|---|---|---|
| `name` | 必选 | string | 全局唯一工具标识，调用时严格匹配 |
| `description` | 必选 | string | 工具功能自然语言描述 |
| `inputSchema` | 必选 | object | JSON Schema 2020-12，定义入参结构、必填项、类型 |
| `title` | 可选 | string | 展示用别名 |
| `outputSchema` | 可选 | object | 输出结构约束 |
| `annotations` | 可选 | object | 行为标记（是否可长时间运行、是否只读等） |

### 3. `tools/call`（工具执行核心方法）
#### 请求 params 必写字段
- `name`：字符串，与 `tools/list` 返回的工具名完全一致
- `arguments`：对象，严格匹配该工具 `inputSchema`

#### 响应 result 必写字段
- `resultType`：固定字符串 `complete`
- `isError`：布尔值；`true`=业务异常，`false`=正常执行
- `content[]`：数组，承载返回内容，仅支持三类元素：
  1. 文本：`{"type":"text","text":"字符串内容"}`
  2. 图片：`{"type":"image","data":"base64","mimeType":"image/png"}`
  3. 音频：`{"type":"audio","data":"base64","mimeType":"audio/wav"}`

---

## 四、配套可选标准能力（resources / prompts）
1. **Resources（资源）**：`resources/list` / `resources/read`；用于读取文件、日志、配置；通过URI寻址；返回结构同 `tools/call.content`。
2. **Prompts（模板）**：`prompts/list` / `prompts/get`；预置对话模板；支持入参填充；返回结构化消息数组。

---

## 五、通用子结构标准
### 1. 内容结构体（content 通用格式）
```json
{"type":"text","text":"xxx"}
{"type":"image","data":"base64","mimeType":"xxx"}
{"type":"audio","data":"base64","mimeType":"xxx"}
```

### 2. `_meta` 保留元数据（嵌入params顶层）
- `progressToken`：用于进度推送通知
- 仅用于协议控制，**不可作为业务参数**

### 3. 错误码体系（JSON-RPC标准 + MCP扩展）
- `-32700`：解析错误（JSON非法）
- `-32600`：请求格式非法
- `-32601`：方法不存在
- `-32602`：参数校验失败（不匹配inputSchema）
- `-32603`：服务内部异常

---

## 六、协议强制约束（MUST / SHOULD / MAY）
1. 所有JSON Schema默认采用 **JSON Schema 2020-12**；
2. 会话为**有状态**；能力协商仅在initialize阶段生效；
3. 工具参数**必须经过inputSchema校验**，服务端不得跳过校验；
4. Stdio模式下，服务进程**禁止向stdout输出非JSON内容**；
5. 工具名称禁止空格，推荐小写+下划线命名；
6. 多模态内容必须Base64编码，禁止原始二进制传输。

---

## 七、可直接引用的JSON Schema总入口
- 官方TS Schema：https://github.com/modelcontextprotocol/modelcontextprotocol/tree/main/schema
- 自动生成JSON Schema：https://modelcontextprotocol.io/specification/2025-06-18/schema
