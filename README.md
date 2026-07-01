# AlinOs-for-Android

基于 Android 平台的云端 AI 接口专属客户端。核心架构为 **安卓应用壳 + 轻量 Termux 底层**。

安卓应用层负责封装并对接各类云端 Open API，Termux 作为辅助层执行本地 CLI 命令、搭建轻量 Linux 运行环境。

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
- **TTS 语音合成**：文本转语音测试
- **配置管理**：多 AI 服务配置（OpenAI / DeepSeek）

## 未来规划

- 对接更多 API：OpenAI (Responses) / Vertex AI (Express Mode) / Anthropic Messages API
- SSH 深度集成：AI 通过 SSH 执行远程命令、SCP 文件迁移
- Agent 端到端闭环：工具按需加载、多步推理

## 致谢

诚挚感谢所有助力项目开发的伙伴：

- 感谢 **[DeepSeek](https://chat.deepseek.com/)** 网页版早期代码生成，及通过 API 对接的 Claude 工具。DeepSeek 提供兼容 Anthropic (Claude) 格式的 API 服务，从 2026 年 3 月开始的后续推送和更新中，**Claude Code** 终端执行工具主导核心代码生成、接口逻辑重构与云端 API 对接调试，攻克复杂模块开发难点。
- 感谢 **[Kimi](https://www.kimi.com/)** 早期的代码拼接，**[claude](https://claude.ai/)** 免费账号协助复杂逻辑排查，**[千问](https://www.qianwen.com/)** 资料查询，**[豆包](https://www.doubao.com/)** 早期代码生成与资料查询。

这个项目的完成，首先要归功于大语言模型带来的技术红利。它让我跳出了代码细节的束缚，转而专注于功能模块的梳理、资料的查询与整合，以及用文字清晰地表达需求。但回过头来看，真正让想法落地的，还是个人的执行力——面对层出不穷的环境配置问题，只有坚持去解决，才能走到最后。

作为项目主导者，我全程负责需求拆解、Bug 定位、真机调试、功能反馈与版本追踪，推进项目从框架搭建到核心流程落地。

---

## 版权声明

Copyright © 2026 heworkalin. All rights reserved.

本项目为**个人学习与测试用途**，当前不发布编译版本或发行版。

本项目包含来自第三方的代码组件，其版权归各自所有者所有：
- `com.termux.*` 包 — 基于 [Termux](https://github.com/termux/termux-app) 项目，遵循 GPLv3 协议
- `com.termux.terminal` 包 — 基于 [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)，遵循 Apache 2.0 协议
- `alin.android.alinos` 包 — 原创代码，保留所有权利

第三方组件的协议以其原始声明的许可证为准。
