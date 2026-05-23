# ### 项目核心定位
**AlinOs-for-android** 是一款基于 Android 平台的**云端 AI 接口专属客户端**。项目核心架构为 **“安卓应用壳 + 轻量 Termux 底层”**：安卓应用层负责封装并对接各类云端 Open API，Termux 仅作为辅助层执行本地 CLI 命令、搭建轻量 Linux 运行环境，以及实现部分基础系统级功能。
## 关于本项目编译基本要求
- Gradle 发行版：gradle-8.14  
- Gradle JDK：JDK 21
- Android Studio：谷歌安卓[Android Studio](https://developer.android.google.cn/studio)
# 项目地址
- [github](https://github.com/heworkalin/AlinOs-for-android) 
- [gitee](https://gitee.com/hewrod/AlinOs-for-android)
### 致谢
诚挚感谢所有助力项目开发的伙伴：
- 感谢 **[DeepSeek](https://chat.deepseek.com/)**网页版早期代码生成，及通过 API 对接的 **Claude工具** [DeepSeek 提供兼容 Anthropic（Claude）格式的 API 服务](https://api-docs.deepseek.com/zh-cn/guides/anthropic_api)，从2026年03月份开始的后续推送和更新**Claude Code**终端执行工具，主导核心代码生成、接口逻辑重构与云端 API 对接调试，攻克复杂模块开发难点；
- 感谢 **[Kimi](https://www.kimi.com/)**早期的代码拼接，感谢 **[claude](https://claude.ai/)**免费一些复杂逻辑的排查,[千问](https://www.qianwen.com/)资料查询，[豆包](https://www.doubao.com/)早期代码的生成，现在资料查询。
- 这个项目的完成，首先要归功于大语言模型带来的技术红利。它让我跳出了代码细节的束缚，转而专注于功能模块的梳理、资料的查询与整合，以及用文字清晰地表达需求。但回过头来看，真正让想法落地的，还是个人的执行力——面对层出不穷的环境配置问题，只有坚持去解决，才能走到最后。

作为项目主导者，我全程负责需求拆解、Bug 定位、真机调试、功能反馈与版本追踪，推进项目从框架搭建到核心流程落地。

### 项目现状与规划
- **当前状态**：已完成安卓应用基础框架搭建，已实现云端 Open API 基础调用流程，ollama部分兼容由于舍弃了一部分后续可能会拆剪，后续将对接以下4种AP接口OpenAI (Responses)  OpenAI (Chat Completions)   Vertex AI (Express Mode)  Anthropic Messages API ，同时增加tools提示此对接能力
- **Termux 环境**已集成，基于项目[aide termux](https://github.com/heworkalin/AIDE-Termux)这个项目通过prooT伪造环境，已达到正常类似于[termux](https://github.com/termux/termux-app)所有包环境能力，当然，基于我的测试还可以再嵌套一层proot，但是这个终端执行能力存在一个最令人难说的能力，proot它的最低执行SDK是支持到安卓设备的安卓9。所以，规划并准备使用独立静态编译或动态编译的一个新的执行能力。
- **未来规划**：proot termux本地执行能成功优先使用这个，其次，对接SSH相关的其他服务端或客户端,保证所有的能力都能正常执行,就是让AI能本地执行执行终端命令通过SSH连接其他客户端进行深度对接，或者SCP完成文件工作文件的迁移