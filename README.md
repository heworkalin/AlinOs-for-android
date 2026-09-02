# AlinOs-for-Android

基于 Android 平台的云端 AI 接口专属客户端。核心架构为 **安卓应用壳 + 轻量 Termux 底层**。

> **⚠️ 项目性质（重要）**
> 本项目为 **个人评估 / 学习 / 测试用途**，代码**由 AI 辅助产出**、由项目主导者（个人）负责需求拆解与真机验证。
> - **暂不发布编译版本 / 发行版 / 上架**；仓库仅公开托管日常源码与演进记录。
> - 当前仍处**方向探索中**，技术路线（终端执行层 / proot 虚拟化 / 音频服务等）尚未完全定型。
> - 交互/架构思路上有参考 [pi.dev](https://pi.dev) 等开源项目哲学（无后台常驻、单次可观测命令执行等），但**代码均为本项目独立开发**，可明确受开源组件的许可约束（见下方版权与 LICENSE）。
> - 若后续决定走向发行，会再单独评审许可兼容性并补齐相应合规流程。

安卓应用层负责封装并对接各类云端 Open API，Termux 作为辅助层执行本地 CLI 命令、搭建轻量 Linux 运行环境。

> **当前方向：声音 / 音频服务**——已落地端侧 ASR（语音识别）、TTS（语音合成）、KWS（关键词唤醒）、声纹验证四大音频能力，并搭建统一的音频配置数据库与服务接口，后续围绕「声音 + 音频」持续深化。

---

## 项目编译要求

- Gradle 发行版：gradle-8.14
- Gradle JDK：JDK 21
- Android Studio：[Android Studio](https://developer.android.google.cn/studio)

## 项目地址

- [GitHub](https://github.com/heworkalin/AlinOs-for-android)
- [Gitee](https://gitee.com/hewrod/AlinOs-for-android)

## 当前能力

- **AI 对话**：流式 SSE 对话，支持多会话管理
- **Tool Calling**：Function Calling 工具调用循环（解析→执行→回注→递归）
- **Agent 工具集**：16 个已注册工具（含元工具 search_tools）
- **Think 块展示**：AI 思考过程可折叠卡片
- **工具调用记录**：独立数据库存储完整调用日志
- **终端仿真**：PTY Shell 会话 + 远程 SSH 连接
- **配置管理**：多 AI 服务配置（OpenAI / DeepSeek）

### 🔊 音频 / 声音模块（新）

| 模块 | 说明 |
|------|------|
| **ASR 语音识别** | sherpa-onnx 1.13.5 离线识别（paraformer/sensevoice/whisper，支持自定义模型） |
| **TTS 语音合成** | sherpa-onnx 离线 TTS（melo/aishell/xiaoya）+ Android 系统 TTS（含设置入口） |
| **KWS 关键词唤醒** | zipformer2 唤醒词检测，中英文唤醒词（内置拼音/音素转换 + 词表校验） |
| **声纹验证** | campplus 说话人识别（五轮声纹注册、余弦相似度比对） |
| **音频配置数据库** | SQLite 双表（configs 配置 + models 模型注册），扫描自动建库，模型路径数据库优先 |
| **统一音频服务接口** | `AudioService.asrRecognizePcm / ttsSynthesize / kwsDetectPcm`——自动读配置→自动加载→自动执行 |
| **自定义模型导入** | 压缩包导入 + 长按配置类型（ASR/TTS/KWS），兼容性规范提示 |

## 未来规划

- 对接更多 API：OpenAI (Responses) / Vertex AI (Express Mode) / Anthropic Messages API
- SSH 深度集成：AI 通过 SSH 执行远程命令、SCP 文件迁移
- Agent 端到端闭环：工具按需加载、多步推理
- **重写终端 / 工具服务**：低智能模型在复杂工具链路中出错率/失败率高、缓存命中偏低，计划重构工具执行与终端服务，提升容错与任务完成率
- **音频服务深化**：悬浮窗常驻监听（VAD + KWS 两级流水线）、声音样本管理、音频能力对外开放（SDK/AAR）

## 致谢

诚挚感谢所有助力项目开发的伙伴：

- 感谢ヾ^_^♪ [pi agent](pi.dev)以及对接的deepseek-v4-falsh完成近期的，项目推进和开发.
- 感谢 **[DeepSeek](https://chat.deepseek.com/)** 网页版早期代码生成，接口逻辑重构与云端 API 对接调试，攻克复杂模块开发难点。
- 感谢 **[Kimi](https://www.kimi.com/)** 早期的代码拼接，**[claude](https://claude.ai/)** 免费账号协助复杂逻辑排查，**[千问](https://www.qianwen.com/)** 资料查询，**[豆包](https://www.doubao.com/)** 早期代码生成与资料查询。

这个项目的完成，首先要归功于大语言模型带来的技术红利。它让我跳出了代码细节的束缚，转而专注于功能模块的梳理、资料的查询与整合，以及用文字清晰地表达需求。但回过头来看，真正让想法落地的，还是个人的执行力——面对层出不穷的环境配置问题，只有坚持去解决，才能走到最后。

作为项目主导者，我全程负责需求拆解、Bug 定位、真机调试、功能反馈与版本追踪，推进项目从框架搭建到核心流程落地。

---

## 版权声明

Copyright © 2026 heworkalin. All rights reserved.

本项目为**个人学习与测试（评估）用途**，当前**不发布编译版本、不发行、不上架**。进入发行前会对许可兼容性单独评审。

本项目基于 / 嵌入了多个第三方开源组件，版权归其各自所有者所有；**使用本项目即代表你同样受这些上游许可约束**。第三方协议的完整清单与逐项归属见 [`LICENSE`](LICENSE) 与 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。摘要如下：

- `com.termux.*` / `termux-shared` / 部分原生环境 — 基于 [Termux (termux-app)](https://github.com/termux/termux-app)：主体 `GPLv3`，个别文件 `MIT` / `Apache-2.0` / `GPLv2+Classpath`，详见 LICENSE。
- `com.termux.terminal`/`view` — 基于 [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)：`Apache 2.0`。
- **音频引擎（sherpa-onnx / vosk 等）** — [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、内含 [onnxruntime](https://github.com/microsoft/onnxruntime)、[alphacep/vosk-api](https://github.com/alphacep/vosk-api)：`Apache-2.0` / `MIT`。（运行时语音模型许可以各模型原始声明为准，多为通用许可证。）
- **预编译 proot / unzip / libtalloc / libbz2 工具**（内置于新版 `files.default.*`）— 来自 termux 系构建：proot 上游 `GPLv2`、unzip(Info-ZIP) `BSD-lite 类`、其余按上游 `MIT/BSD` 等，见 LICENSE。
- `alin.android.alinos` 原创代码、以及其余通过 Gradle 拉取的第三方（AndroidX、JNA、jna、okhttp、gson、media3、lottie、prism4j/markwon 等）— 各自 `Apache-2.0 / MIT / LGPL` 等，逐项见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

第三方组件的协议以其原始声明的许可证为准。
