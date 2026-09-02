# AlinOs-for-Android

基于 Android 平台的云端 AI 接口专属客户端。核心架构为 **安卓应用壳 ＋ 内置 proot 的用户态 Linux 执行层（files.default rootfs，四架构）**。

> 本环境**不标榜“轻量/精简”**：它随 proot 虚拟化运行，体积、依赖都偏厚重；之所以选择 proot，是为了在无 root 的第三方应用权限下拿到一个**真正可执行、可被脚本 / CLI / AI 工具驱动的近乎完整 Linux 用户态**，而不是为了“小而美”。执行侧早期以 Termux 精简辅助层为主，现已把重心放到 proot-root 之上。

**EN（English）**: [English README](README.en.md) — 中文为权威主档。

---

## 项目性质与当前状态（请先读）

- **个人评估 / 学习 / 测试用途**；代码 **由 AI 辅助产出**，由项目主导者（个人）负责需求拆解、Bug 定位与真机验证。
- **暂不发布编译版本 / 发行版 / 不上架**；仓库仅公开托管源码与演进记录。
- 仍处 **方向探索中，且短期可能遥遥无期 / 搁置**：技术路线（执行层 / proot / 音频 / MCP / Agent 形态）尚未定型，主导者短期忙于其他事务，想清楚了再推进。
- 交互与架构思路上**参考了 [pi.dev](https://pi.dev) 等开源哲学**（无后台常驻、可观测的单次命令执行、提示词/上下文压缩思路等）；若确有代码级复用，相应许可会随之生效，见 LICENSE / THIRD_PARTY_NOTICES。
- 若未来转为发行，会先单独评审许可兼容性并补齐合规流程。

---

## 项目编译要求

- Gradle 发行版：gradle-8.14；Gradle JDK：JDK 21
- Android Studio：[Android Studio](https://developer.android.google.cn/studio)

## 项目地址

- [GitHub](https://github.com/heworkalin/AlinOs-for-android)
- [Gitee](https://gitee.com/hewrod/AlinOs-for-android)

## 一线执行的现状（如实）

- **运行/依赖前置**：clone 后先执行 `bash scripts/fetch_deps.sh` 拉取被移出 git 的最小编译引擎（`sherpa-onnx-1.13.5.aar` → `app/libs/`）；四架构 `files.default.*`（内含 proot / unzip / libtalloc / libbz2）随仓库保留。

### 已实现的模块（曾完成，按当前真实状态标注）

| 模块 | 说明 | 状态 |
|------|------|:----:|
| AI 对流对话 | 流式 SSE，多会话 + 历史 | ✅ 在用 |
| Tool Calling | FT 解析→执行→回注→递归循环 | ✅ 在用（工具将随执行层精简） |
| Agent 工具集 | 若干已注册工具（含元工具 search_tools） | ✅ 在用，将在重构中收敛 |
| Think / 工具日志 | 思考块展示 / 调用记录入库 | ✅ 在用 |
| 配置管理 | 多 AI 服务（OpenAI / DeepSeek） | ✅ 在用 |
| 终端执行/远端 SSH | 本地 Shell(PTY) + JSch 远端 | 🟡 **遗留** — 因 PTY 长会话难维护，正改向 proot 单次 CLI |
| 音频（ASR/TTS/KWS/声纹） | sherpa-onnx / vosk 离线端侧八大能力 | ⏸️ **短期搁置** |
| MCP 服务端 | 早前实验 HttpServer 实现 | 🟡 **可能撤/收回内部** — 改用 MCP 客户端（mcp-cli）接入远端 |

> 说明：上表是当前“能跑/曾跑”的真实盘点。短期不会新增功能；后续若推进，重点是**精简工具与执行层到 proot 单次 CLI 三大件（读写/修改/执行）**。

### 后端预置环境

`app/src/{arm,arm64,x86_64,i686}/assets/files.default.*.tar.gz.so` —— 四架构非官方精简 rootfs（内含 openssh/bash/coreutils 等），并内置 proot/unzip 等，供执行层容器化调用；无外部重建源，故随仓库保留。

---

## 后续方向（收敛后的候选，非承诺）

- **执行层**：PTY 会话 → proot 内的 **CLI / bash 单次执行**（无状态、可观测），把工具收敛为读写 / 修改 / 执行等基础原语。
- **MCP**：优先以**客户端**形态，或直接调用 `mcp-cli` 接入远端，而非自维护服务端。
- **Agent**：提示词组装 / 相对路径 / 上下文压缩等，参考 pi.dev 思路做轻量实现；区分“给 AI 的能力”与“工具内部用”的能力，避免外放一堆旁支。
- **API 多路由（可选）**：OpenAI Responses / Vertex / Anthropic 等，按需再议。
- **音频（搁置）**：短期不深度更新；如推进再评估 VAD/KWS 悬浮监听、SDK/AAR 等低频候选。

## 文档归档（docs/）

为**避免多份文档彼此迷失、看不清该看哪份**，历史长报告已收拢到 `docs/`（保留随仓库，仅作索引）：

| 归档文件 | 内容 |
|------|------|
| `docs/_archive_PROJECT_STATUS.md` | 阶段推进 / 状态报告 |
| `docs/_archive_AGENT_CAPABILITY_ROADMAP.md` | Agent 能力规划分析 |
| `docs/_archive_PROOT_REFACTOR_PLAN.md` | proot 单次执行重构方案（曾规划） |
| `docs/_archive_TMOE_PROOT_ANALYSIS.md` | proot 启动流程深度分析 |
| `docs/_archive_SYSTEM_REPORT.md` | 早期系统报告 |
| `docs/_archive_mcp.md` | MCP 官方规范摘录 |

**文档约定**：先看本 README；找不到再翻 `docs/_archive_*`。

## 致谢

开发中参考 / 借鉴了 [Termux](https://github.com/termux/termux-app)、[Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)、[TMOE](https://github.com/2moe/tmoe) 及 k2-fsa/sherpa 等开源项目的用法 / 思路；可能引入的部分已归并说明，**具体以各上游自身许可为准**，代码复用请核对其 LICENSE。

同时感谢一路参与开发的伙伴/工具：
- [pi](https://pi.dev) agent（近期的方向梳理与代码协作）、[DeepSeek](https://chat.deepseek.com/)（早期接口对接/重构）等各 AI 与检索平台。

> 坦白记录：这个项目历经很久、做了很多探索，但在“轻量精简”上反复折腾、结果往往越叠越重、重复造轮子。现已按现实收敛写入本 README，后续是否还能持续由主导者评估决定。

---

## 版权声明

Copyright © 2026 heworkalin. All rights reserved.

本项目为**个人学习与测试（评估）用途**，**不发布编译版本 / 不发行 / 不上架**；发行前会另行评审许可兼容性。

本项目基于 / 嵌入了多个第三方开源组件，版权归各所有者；**使用本项目即代表接受这些上游许可约束**。完整清单见 [`LICENSE`](LICENSE) 与 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。摘要：

- `com.termux.*` / `termux-shared` / 原生环境 — [Termux (termux-app)](https://github.com/termux/termux-app)：主体 GPLv3，个别 MIT / Apache-2.0 / GPLv2+Classpath。
- `com.termux.terminal` / `view` — [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)：Apache-2.0。
- 音频引擎 — [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（含 [onnxruntime](https://github.com/microsoft/onnxruntime)）、[alphacep/vosk-api](https://github.com/alphacep/vosk-api)：Apache-2.0 / MIT（模型许以各原始声明为准）。
- 预编译 proot / unzip / libtalloc / libbz2（内置于 `files.default.*`）— proot 上游 GPLv2、unzip(Info-ZIP) BSD 类、libtalloc LGPL-2.1+、libbz2 BSD-like。
- `alin.android.alinos` 原创代码及 Gradle 三方（AndroidX / JNA / okhttp / gson / media3 / lottie / markwon-prism 等）— Apache-2.0 / MIT / LGPL，细节见 NOTICE。

第三方组件协议以其原始声明为准。
