# Proot 单次执行重构方案

> 设计日期：2026-09-02  
> 核心思路：抛弃持久 PTY 会话模型，改用 **proot 容器单次启动→执行→返回→关闭**

---

## 一、为什么重构？

### 当前模型（PTY 持久会话）的痛点

| 问题 | 说明 |
|------|------|
| **生命周期复杂** | 需显式创建/销毁会话，管理 TermuxSession 状态 |
| **状态依赖** | 环境变量、工作目录、变量在会话间需要手动维护 |
| **前台服务依赖** | LocalShellService 必须常驻，5 秒内 startForeground，否则系统杀进程 |
| **AI 调用不自然** | AI 执行一个命令要"打开会话→发命令→读输出→关会话"，多步交互 |
| **并发脆弱** | 多工具并行时 PTY 状态可能互相干扰 |

### pi 的思路（单次 bash）

> **"No background bash. Use tmux. Full observability, direct interaction."**

pi 的 `bash` 工具是**无状态的**——发一条命令，等结果返回，完事。对 AI 来说更自然：
```
AI 说: "帮我安装 vim"
→ bash: "apt install -y vim"
→ 返回: 安装输出 + exitCode
→ 完事
```

### proot 的优势

`/home/he/proot/` 已有四个架构的 proot 二进制（arm/arm64/x86/x86_64）+ libbz2 + libtalloc + unzip：

| 组件 | 用途 |
|------|------|
| `proot` | 用户态模拟 Linux 内核调用，无需 root |
| `libbz2.so.1.0` | bz2 压缩支持 |
| `libtalloc.so.2` | 内存分配器 |
| `unzip` | 解压工具 |

proot 可以在普通第三方应用权限下，模拟一个完整的 Linux 用户空间环境。

---

## 二、新架构设计

### 核心组件

```
┌─────────────────────────────────────────────┐
│          ProotContainerManager              │
│  (单例，管理 proot 容器生命周期)              │
├─────────────────────────────────────────────┤
│  detectArch() → 选择对应架构的 proot 二进制   │
│  startContainer() → proot -r -b ... /system  │
│  exec(command) → bash -c "command"          │
│  stopContainer() → 清理                     │
└─────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────┐
│       ProotBashTool (pi 风格单次执行)         │
│  bash -c "apt install -y vim"               │
│  → 返回: stdout + stderr + exitCode         │
│  → 支持 timeout                             │
│  → 支持工作目录切换                          │
└─────────────────────────────────────────────┘
```

### 关键设计决策

| 决策 | 方案 | 理由 |
|------|------|------|
| **容器模式** | 每次 exec 启动新容器 | 状态隔离，AI 调用无副作用 |
| **根目录** | `/data/data/<pkg>/files/default` | 与应用数据目录绑定 |
| **bind 挂载** | proot -b /system -b /data | 模拟完整 Linux 路径 |
| **超时** | 默认 30s，可配置 | 防止卡死 |
| **结果格式** | JSON: `{stdout, stderr, exitCode, durationMs}` | AI 友好 |

### 与现有工具的映射

| 旧工具 | 新实现 | 变化 |
|--------|--------|------|
| `shell_exec` | `proot_exec` | 无状态，每次独立启动 |
| `shell_read` | 合并到 `proot_exec` 的 wait 逻辑 | 不需要单独读取 |
| `shell_send_key` | 可选参数 `stdin: "text"` | 通过 stdin 注入 |
| 会话管理 | 去掉 create/destroy/list | 不再需要 |

---

## 三、proot 启动命令

```bash
# 检测架构
ARCH=$(getprop ro.product.cpu.abi)

# 启动 proot 容器
proot \
  -r /data/data/alin.android.alinos/files/default \
  -b /system \
  -b /dev \
  -b /proc \
  -b /sdcard \
  -w /data/data/alin.android.alinos/files/default/home \
  /system/bin/sh -c "your_command"
```

### bind 挂载说明

| 挂载点 | 用途 |
|--------|------|
| `/system` | Android 系统库和二进制 |
| `/data` | 应用私有数据 |
| `/dev` | 设备节点（音频、传感器等） |
| `/proc` | 进程信息 |
| `/sdcard` | 共享存储 |

---

## 四、实施步骤

### Phase 1: ProotContainerManager（基础层）

- [ ] `ProotArchDetector` — 检测设备架构，选择对应 proot 二进制
- [ ] `ProotContainer` — proot 容器封装（启动/执行/停止）
- [ ] `ProotExecutor` — 单次命令执行入口
- [ ] 资源复制：将 `/home/he/proot/$ARCH/` 复制到应用私有目录

### Phase 2: ProotBashTool（AI 工具）

- [ ] 注册新工具 `proot_exec`（替代现有 shell 工具）
- [ ] JSON 结果格式标准化
- [ ] timeout + 重试机制

### Phase 3: 过渡期（双模式并存）

- [ ] 保留 LocalShellExecutor 作为备用
- [ ] 配置开关切换模式
- [ ] 逐步迁移工具定义

### Phase 4: 清理

- [ ] 移除 LocalShellService 前台服务依赖
- [ ] 移除会话管理工具
- [ ] 简化 LocalShellExecutor

---

## 五、MCP 传输方案评估

### 当前 MCP 服务

MCP 通过 `McpHttpServer` + `McpProtocolHandler` 暴露 HTTP 接口，AI 通过 MCP 协议调用工具。

### 新的传输方案

| 方案 | 说明 | 状态 |
|------|------|------|
| **单次 bash（推荐）** | `proot_exec` 工具直接执行，无连接管理 | ✅ 设计完成 |
| **HTTP + JSON-RPC** | 轻量 HTTP API，非完整 MCP | ⏳ 评估中 |
| **Stdio Transport** | MCP Stdio 模式，每次启动新 proot 进程 | ⏳ 评估中 |

### 传输方案对比

| 维度 | 持久 MCP | 单次 bash | 轻量 HTTP |
|------|----------|-----------|-----------|
| 实现复杂度 | 高 | 低 | 中 |
| AI 调用自然度 | 中 | 高 | 中 |
| 资源开销 | 高（常驻） | 低（按需） | 中 |
| 状态管理 | 需管理 | 无状态 | 可选 |
| 适合场景 | 复杂交互 | 工具执行 | API 调用 |

**结论**：工具执行用单次 bash，API 调用可保留 HTTP。MCP 不必全部重写，按需取舍。

---

## 六、风险评估

| 风险 | 影响 | 缓解 |
|------|------|------|
| proot 性能 | 容器启动开销 | 冷启动 ~200ms，可接受 |
| 权限不足 | 部分系统调用失败 | bind /proc 和 /dev |
| 架构不匹配 | ARM64 设备用 x86 proot | 检测 ro.product.cpu.abi |
| 存储限制 | 容器占用空间 | 复用 files/default 目录 |

---

## 七、参考

- [pi.dev 哲学](https://pi.dev): "No background bash. Use tmux."
- [proot README](https://github.com/termux/proot): 用户态系统调用模拟
- `/home/he/proot/` — 已有四架构 proot 二进制
