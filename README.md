# AlinOs-for-Android

基于 Android 平台的云端 AI 接口专属客户端。核心架构为 **安卓应用壳 ＋ 自编译 proot 的用户态 Linux 执行层（Ubuntu 24.04 rootfs，四架构）**。

> 本环境**不标榜“轻量/精简”**：它随 proot 虚拟化运行，体积、依赖都偏厚重；之所以选择 proot，是为了在无 root 的第三方应用权限下拿到一个**真正可执行、可被脚本 / CLI / AI 工具驱动的近乎完整 Linux 用户态**，而不是为了“小而美”。

**EN（English）**: [English README](README.en.md) — 中文为权威主档。

---

## 项目性质与当前状态（请先读）

- **个人评估 / 学习 / 测试用途**；代码 **由 AI 辅助产出**，由项目主导者（个人）负责需求拆解、Bug 定位与真机验证。
- **暂不发布编译版本 / 发行版 / 不上架**；仓库仅公开托管源码与演进记录。
- 仍处 **方向探索中**：执行层已确定走 proot 容器路线（不再折腾 PTY 长会话），多协议 AI 接入已落地；音频 / MCP 形态尚未定型。
- 交互与架构思路上**参考了 [pi.dev](https://pi.dev) 等开源哲学**（无后台常驻、可观测的单次命令执行、提示词/上下文压缩、多协议 provider 抽象等）；若确有代码级复用，相应许可会随之生效，见 LICENSE / THIRD_PARTY_NOTICES。
- 若未来转为发行，会先单独评审许可兼容性并补齐合规流程。

---

## 项目编译要求

- Gradle 发行版：gradle-8.14；Gradle JDK：JDK 21
- Android Studio：[Android Studio](https://developer.android.google.cn/studio)
- proot / loader 为**预编译产物**，随仓库提交；如需自行重建见「预编译来源」一节。

## 项目地址

- [GitHub](https://github.com/heworkalin/AlinOs-for-android)
- [Gitee](https://gitee.com/hewrod/AlinOs-for-android)

## 一线执行的现状（如实）

- **运行/依赖前置**：clone 后先执行 `bash scripts/fetch_deps.sh` 拉取被移出 git 的最小编译引擎（`sherpa-onnx-1.13.5.aar` → `app/libs/`）。
- **proot 工具链**已转为**自编译静态版本**（四架构），不再依赖 `files.default.*` 内的旧 proot 与 `libtalloc.so.2`。
- **rootfs 可自行构建**：`files.default.*.tar.gz.so`（四架构）由 `scripts/rootfs/` 从源码编译生成；详见 `scripts/rootfs/README.md`。

### 执行层（当前主力，已跑通）

| 能力 | 说明 | 状态 |
|------|------|:----:|
| proot 容器部署页 | 12 镜像源可切换、断点续传、SHA256 固化校验、伪 200 校验 × 解压 | ✅ 在用 |
| 容器解压 | `proot --link2symlink <libtar.so> -xJf`，硬链接自动降级为符号链接 | ✅ 真机验证 |
| 容器启动 | Ubuntu 24.04.5 LTS / `uid=0(root)` / aarch64，`--root-id` + `env -i` | ✅ 真机验证 |
| 伪造 `/proc` | 内置 `proot_proc.tar.xz` + 运行时抓取宿主数据（默认启用，兼容 LXC 用户态） | ✅ 在用 |
| AI 工具 | **bash / read / write / edit / ls / grep / find**（原生命名，描述不暴露底层） | ✅ 在用 |
| 路径沙箱 | AI 只见容器内路径；补全 → symlink 写穿 → rootfs 拼接；宿主路径不可达 | ✅ 在用 |
| 符号链接语义 | 读写**链接指向的真实文件**，链接本身保留，并回传 `link_warning` | ✅ 在用 |

### AI 接入（多协议 / 多 provider）

| 能力 | 说明 | 状态 |
|------|------|:----:|
| 协议方言 | `openai-completions` / `openai-responses` / `anthropic-messages`（覆盖约 82% 模型） | ✅ 在用 |
| 模型注册表 | **39 家 provider / 1312 个模型**，含定价、上下文窗口、最大输出 | ✅ 在用 |
| 动态模型发现 | 轮询 OpenAI 兼容 / Anthropic / Google 的模型端点，与静态基线合并并持久化 | ✅ 在用 |
| 费用计算 | 与 pi 同源公式：输入/输出/缓存读写 + 阶梯定价 + Anthropic 1h 缓存 2 倍 | ✅ 在用 |
| 配置编辑页 | 独立 Activity：基础 + 高级参数（默认收起）+ **动态自定义参数**，默认 128K 上下文 | ✅ 在用 |
| 用量落库 | `usage_json` / `cost_total` / 缓存 token 写入 `chat_record`，消息下方显示 token 与费用 | ✅ 在用 |

### 其他模块（沿用 / 搁置）

| 模块 | 说明 | 状态 |
|------|------|:----:|
| AI 对流对话 | 流式 SSE，多会话 + 历史 | ✅ 在用 |
| Tool Calling | 解析 → 执行 → 回注 → 递归循环 | ✅ 在用 |
| Think / 工具日志 | 思考块展示 / 调用记录入库 | ✅ 在用 |
| 终端执行/远端 SSH | 本地 Shell(PTY) + JSch 远端 | 🟡 遗留（AI 侧已不再暴露 PTY 工具） |
| 音频（ASR/TTS/KWS/声纹） | sherpa-onnx / vosk 离线端侧能力 | ⏸️ 短期搁置 |
| MCP 服务端 | HttpServer 实现 | 🟡 可能收回内部，或改用 MCP 客户端 |

### 后端预置环境

- **`files.default.*.tar.gz.so`（四架构）** —— 打包内容为 Termux 的 `$PREFIX`（`files/default/` 即 Termux 的 `usr/`），也就是 **Termux bootstrap rootfs**：内含 `bash` / coreutils / `curl` / `ssh` / `apt` / `dpkg` / `tar` / `proot` / `unzip` 等，来源为 **[termux/termux-packages](https://github.com/termux/termux-packages)** 的构建产物与下载源；如需自建，可按其构建流程（Docker）编译对应包后重新打包。
- **`libproot.so` / `libproot-loader.so`（jniLibs，四架构）** —— 自编译静态 proot 与 loader，见下方「预编译来源」。
- **`libtar.so`（jniLibs，四架构）** —— 与 rootfs 同源的 **GNU tar 静态编译版**：只为一个零依赖的解压工具（rootfs 内的 `bin/tar` 依赖 Termux 动态库环境），叠加进 `jniLibs` 供解压流程直接调用；与执行权限无关。
- `app/src/main/assets/proot_proc.tar.xz` —— 伪造 `/proc` 数据包（来自 `proot_proc` 项目）。
- `app/src/main/assets/models.json` —— 模型与定价注册表（由 pi 的 provider 数据转译）。

---

## 后续方向（候选，非承诺）

- **执行层收尾**：容器内工具的错误恢复、超时策略、输出截断、只读模式等细化。
- **AI 接入扩展**：补齐其余协议方言（Google Generative AI / Vertex、Bedrock、Mistral）与对应 provider。
- **费用与统计**：会话/全局累计费用视图；历史消息重进会话显示价格。
- **配置能力**：模型名搜索、Provider 自定义端点模板、配置导入导出。
- **MCP**：优先以客户端形态接入远端，或直接调用 `mcp-cli`。
- **音频（搁置）**：短期不深度更新；如推进再评估 VAD/KWS 悬浮监听、SDK/AAR 等候选。

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

---

## 现在对接了哪些 AI

客户端内置 **39 家 provider / 1312 个模型**的元数据与定价，主流可直接选用的包括：OpenAI、Anthropic、DeepSeek、Google Gemini、xAI Grok、Qwen 通义千问、Moonshot Kimi、Z.ai GLM、MiniMax、Mistral、Groq、Cerebras、Together、Fireworks、OpenRouter、Vercel AI Gateway、Cloudflare Workers AI、Hugging Face、NVIDIA、Baseten、Amazon Bedrock、Azure OpenAI、GitHub Copilot、Cline/OpenCode 等；同时兼容任何 OpenAI 风格的**自建 / 本地端点**（Ollama、llama.cpp、one-api / new-api 网关等）。协议上已实现 Chat Completions、Responses、Anthropic Messages 三种方言，其余方言（Google / Bedrock / Mistral 等）的数据已就位，待接入。

---

## 致谢

### 开源项目

开发中参考 / 借鉴 / 直接使用了许多开源项目：**[Termux](https://github.com/termux/termux-app)**（Android 终端仿真与 `termux-shared` 思路）、**[termux/termux-packages](https://github.com/termux/termux-packages)**（Android 用户态工具链与补丁的上游源头）、**[termux/proot](https://github.com/termux/proot)**（proot 及 loader 源码）、**[Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)**（终端视图与仿真）、**[TMOE](https://github.com/2moe/tmoe)**（proot 容器安装/启动流程的重要参考）、**[Android-Proot-Builder](https://github.com/wuxianggujun/Android-Proot-Builder)**（proot 交叉编译流程参考，本项目在其基础上扩展了 32 位架构支持）、**[proot_proc](https://gitee.com/ak2/proot_proc)**（伪造 `/proc` 数据包）、**[talloc](https://talloc.samba.org/)**（proot 依赖的内存库，现已静态链接）、**[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** 与 **[onnxruntime](https://github.com/microsoft/onnxruntime)**（离线语音）、**[alphacep/vosk-api](https://github.com/alphacep/vosk-api)**（语音识别）、**[pi.dev](https://pi.dev)**（Agent 工具设计与多协议 provider 抽象的参考）、以及 AndroidX / OkHttp / Gson / JSch / Media3 / Lottie / Markwon 等 Gradle 依赖。**各项目许可与版权归其各自所有者**，具体以各上游 LICENSE 为准。

### 预编译来源

`app/src/main/jniLibs/` 下的四架构 `libproot.so` 与 `libproot-loader.so`，是用 **Android NDK r28c**（`aarch64-linux-android28` / `armv7a-linux-androideabi28` / `i686-linux-android28` / `x86_64-linux-android28` 交叉工具链）从 **[termux/proot](https://github.com/termux/proot)** 源码编译的，`talloc` 以**静态链接**并入，最终产物仅依赖 `libc.so` 与 `libdl.so`；编译流程参考 **[Android-Proot-Builder](https://github.com/wuxianggujun/Android-Proot-Builder)** 并扩展了 32 位目标。

四架构 `files.default.*.tar.gz.so`（rootfs）来自 **[termux/termux-packages](https://github.com/termux/termux-packages)**：由本项目用 `scripts/rootfs/` 下的脚本**自行编译**（仅 `bash` + `openssh` 及其依赖），
经**路径重定位**（`com.termux`→`alin.android.alinos`、`files/usr`→`files/default`）与**硬链接→软链接**转换后打包。

### rootfs 构建（scripts/rootfs/）

| 脚本 | 作用 |
|------|------|
| `build-rootfs.sh` | 总调度：改造 properties.sh → Docker 编译 bash+openssh → 打包 → 清理（四架构循环） |
| `pack-rootfs.sh` | 解包 deb → 路径批量替换 → 硬链接转软链 → tar 打包 |
| `replace-paths.sh` | 硬编码路径批量替换（可独立使用，支持 `--dry-run`） |
| `README.md` | 环境要求、分步手册、产物自检与排错 |

关键点：官方 `build-package.sh` 默认产出 `.deb`，本项目**不用**其 dpkg 安装机制，而是将 deb 内容解包到目标路径再打包；
包体内**只有软链接、无硬链接**；`tar` 打包时**不加 `-h`**（否则软链会被展开为实体副本）。

> 待办：`libtar.so` 的静态编译流程尚未实现（需修改多个 termux-packages 包体才能完成静态链接），暂沿用既有产物。

### AI 工具

感谢一路参与开发、调试、资料与代码协作的 AI 工具（绝大多数为网页端）：**pi**（https://pi.dev ，近期方向梳理、大规模代码协作与仓库整理）、**DeepSeek**（https://chat.deepseek.com ，早期代码生成、接口对接与云端 API 调试）、**Kimi**（https://www.kimi.com ，早期代码拼接与长文阅读）、**Claude**（https://claude.ai ，复杂逻辑排查与代码审查）、**通义千问**（https://www.tongyi.com ，资料查询与汇总）、**豆包**（https://www.doubao.com ，早期代码生成与资料查询），以及其它在排查、翻译、文档整理过程中提供过帮助的模型。此处只作开发历程致谢，不构成任何推广或担保，仍以实际使用方式为准。

> 坦白记录：这个项目历经很久、做了很多探索；早期在“轻量精简”上反复折腾、结果往往越叠越重。现已按现实收敛写入本 README。

---

## 版权声明

Copyright © 2026 heworkalin. All rights reserved.

本项目为**个人学习与测试（评估）用途**，**不发布编译版本 / 不发行 / 不上架**；发行前会另行评审许可兼容性。

本项目基于 / 嵌入了多个第三方开源组件，版权归各所有者；**使用本项目即代表接受这些上游许可约束**。完整清单见 [`LICENSE`](LICENSE) 与 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。摘要：

- `com.termux.*` / `termux-shared` / 原生环境 — [Termux (termux-app)](https://github.com/termux/termux-app)：主体 GPLv3，个别 MIT / Apache-2.0 / GPLv2+Classpath。
- **本地运行环境工具链（bash / ssh / coreutils 等）— [termux/termux-packages](https://github.com/termux/termux-packages)**：构建脚本 + Android 适配补丁的上游源头；各包许以其中各自声明为准。**rootfs 由本项目自行编译**（`scripts/rootfs/`），归属与许可证义务不因构建方式而改变。
- **proot / proot-loader（自编译）— [termux/proot](https://github.com/termux/proot)：GPLv2**；`loader` 同源。以静态链接方式并入的 `talloc` — LGPL-2.1+。
- `libtar.so` — GNU tar，GPLv3。
- 伪造 `/proc` 数据包 — [proot_proc](https://gitee.com/ak2/proot_proc)，许以该仓库声明为准。
- 模型与定价元数据（`assets/models.json`）— 转译自 [pi](https://pi.dev) 的 provider 数据，许以该上游声明为准。
- `com.termux.terminal` / `view` — [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)：Apache-2.0。
- 音频引擎 — [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（含 [onnxruntime](https://github.com/microsoft/onnxruntime)）、[alphacep/vosk-api](https://github.com/alphacep/vosk-api)：Apache-2.0 / MIT（模型许以各原始声明为准）。
- `alin.android.alinos` 原创代码及 Gradle 三方（AndroidX / JNA / okhttp / gson / media3 / lottie / markwon-prism 等）— Apache-2.0 / MIT / LGPL，细节见 NOTICE。

第三方组件协议以其原始声明为准。
