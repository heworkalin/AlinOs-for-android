# THIRD-PARTY NOTICES（第三方组件清单）

> 本文件列出 AlinOs-for-android 引入 / 嵌入的第三方开源资源及各自版权与许可证。
> 完整的三层归属与使用者义务见 [`LICENSE`](LICENSE)；此处为代号/模块级的**明细索引**。
> 协议一律以其**上游原始声明**为准；下列为常用短名，个别库为多协议发行（按项目引用的形式计入）。

---

## 一、源码层（直接入代码树的第三方包）

| 来源项目 | 涉及路径 / 用途 | 许可证 |
|---------|----------------|:------:|
| [Termux (termux-app)](https://github.com/termux/termux-app) | `com.termux.app/**`、`com.termux.app.terminal/**`、`LocalShellTestActivity` | GPLv3 only |
| **Termux packages** | **`files.default.*.tar.gz.so` 内全部工具链的构建脚本与 Android 适配补丁（见第三节）** | GPLv3 / 各包上游 |
| Termux shared | `com.termux.shared/**`（少数文件例外，见 LICENSE） | 主体 MIT，部分 GPLv3 / GPLv2+CE / Apache-2.0 |
| Termux 原生库 | `jniLibs/*/libtermux.so`、`libtar.so`、`liblocal-socket.so`、`librmt.so` | GPLv3（Termux 构建产物） |
| [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator) | `com.termux.terminal/**`、`com.termux.view/**` | Apache-2.0 |
| （本项目原创） | `alin.android.alinos/**`（LocalShellTestActivity 除外） | 见 LICENSE / 本项目保留 |

## 二、核心上游：termux-packages（工具链与 Android 适配的源头）

> **本项目的本地运行时环境（`files.default.*.tar.gz.so`）、以及绝大多数“安卓原生工具”，
> 都源自 [termux/termux-packages](https://github.com/termux/termux-packages)。**

该项目不仅仅是“下载源”，它提供了：

| 提供内容 | 说明 |
|---------|------|
| **构建脚本** | 每个包的 `build.sh`，定义如何交叉编译到 Android |
| **Android 适配补丁** | `packages/<pkg>/*.patch`，让上游软件能在 Bionic libc / Android 上工作 |
| **依赖与元数据** | 包间依赖、版本、打包规则 |
| **技术 Wiki** | 包括 [Building packages](https://github.com/termux/termux-packages/wiki/Building-packages)、[Termux and Android 10](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10) 等 |

**简而言之：没有 `termux-packages`，就没有在 Android 上可用的 bash / ssh / coreutils / proot 等工具。**
本项目只是它们的**下游使用者与打包者**，不重新实现这些工具。

参考来源文件：
- `com.termux.shared.termux.TermuxConstants`（内含 `TERMUX_PACKAGES_GITHUB_REPO_URL` 等常量）
- `com.termux.terminal.WcWidth`、`com.termux.view.TerminalView` 注释中引用的 `termux-packages` 链接
- `SshTestActivity.CODE_MIRROR` 中引用的 `termux-packages-24` 软件源镜像

---

## 三、预编译运行环境 `files.default.*.tar.gz.so`（随仓库）

新版四架构内置，并**额外打入 proot / unzip / libtalloc / libbz2**。

> ⚠ 全部工具的源码、构建脚本与 Android 适配补丁均来自
> [termux/termux-packages](https://github.com/termux/termux-packages)；
> 本节仅列出常用组件，许可以各自**上游原始声明**为准。

| 组件 | 用途 | 许可证 |
|------|------|:------:|
| [termux/proot](https://github.com/termux/proot)（上游 [PRoot](https://github.com/proot-me/PRoot)） | 用户态系统调用模拟（无需 root 的 Linux 容器） | GPLv2（上游）/ 按 termux 构建 |
| [bash](https://www.gnu.org/software/bash/) | 交互式 shell | GPLv3+ |
| [openssh](https://www.openssh.com/)（`ssh` / `sshd`） | SSH 客户端与守护进程 | BSD-style（OpenSSH） |
| [openssl](https://www.openssl.org/) | TLS/加密库 | Apache-2.0（OpenSSL 3.x） |
| [coreutils](https://www.gnu.org/software/coreutils/) / busybox | 基础工具集 | GPLv3+ / GPLv2 |
| unzip (Info-ZIP) | 解压工具 | Info-ZIP（BSD 风格） |
| libtalloc | 内存分配器（proot 依赖） | LGPL-3.0（Samba） |
| libbz2 (bzip2) | 压缩支持（proot/unzip 依赖） | BSD-like（bzip2/libpng 许可） |
| 其他未列出的包 | 环境工具链 | 见 `termux-packages/packages/<名>/` 下各自的 LICENSE |

净说明：本环境为**非官方魔改精简构建**（原 Termux 官方 .deb 裁剪合并），
用于个人本地 CLI/SSH 用途；聚合 tar 仅作分发载体，**单软件许可以各上游为准**。

---

## 四、Android 三方依赖（Gradle 引入）

| 坐标 | 用途 | 许可证 |
|------|------|:------:|
| `com.alphacephei:vosk-android:0.3.47` | 离线语音识别引擎 | Apache-2.0（[vosk-api](https://github.com/alphacep/vosk-api)） |
| `files('libs/sherpa-onnx-1.13.5.aar')` | ASR/TTS/KWS/声纹 引擎 | Apache-2.0（[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)） |
| sherpa aar 内置 `onnxruntime` | 推理运行时 | MIT（[microsoft/onnxruntime](https://github.com/microsoft/onnxruntime)） |
| `com.jcraft:jsch:0.1.55` | SSH 客户端 | BSD-style |
| `dev.rikka.shizuku:*` | ADB 权限代理 | Apache-2.0 |
| `org.lsposed.hiddenapibypass:6.1` | 隐藏 API 绕过 | Apache-2.0 |
| `com.squareup.okhttp3:okhttp` | HTTP 客户端 | Apache-2.0 |
| `com.google.code.gson:gson` | JSON | Apache-2.0 |
| `commons-io:commons-io` | IO 工具 | Apache-2.0 |
| AndroidX 系列（core/recyclerview/cardview/preference/constraintlayout…） | Android 支持库 | Apache-2.0 |
| `androidx.media3:*`（media3-exoplayer/extractor/datasource-okhttp） | 音视频/数据源 | Apache-2.0 |
| `net.java.dev.jna:jna` | Java 本地访问 | LGPL-2.1（JNA） |
| `com.airbnb.android:lottie` | 动画 | Apache-2.0 |
| `io.noties.markwon:*` / `io.noties:prism4j` | Markdown 渲染/高亮 | Apache-2.0 |
| `com.github.L-JINBIN:MTDataFilesProvider` | 数据文件共享 | Apache-2.0（见该仓库 LICENSE） |

## 五、运行时按需下载的语音模型（不随 APK/Git 打包）

以下由 App 内 Java（`voice/*`）运行中下载或用户手动导入，许可以各模型原始来源为准：

- sherpa-onnx 模型（ASR `<paraformer|sense-voice|whisper>`、KWS zipformer、声纹 campplus、TTS、VAD）——来自 k2-fsa/sherpa-onnx Releases。
- Vosk 模型（如 vosk-model-small-cn / en-us）——来自 alphacephei.com/vosk/models；多为含许可声明的公开模型。

## 六、备注 / 义务摘要

- **工具链归属**：`files.default.*.tar.gz.so` 内的所有可执行文件（bash/ssh/proot/coreutils 等）
  均由 [termux/termux-packages](https://github.com/termux/termux-packages) 构建，
  本项目仅作裁剪打包与运行时集成。
- GPLv3 传染（来自 termux-app）：**分发本项目必须提供完整源码且以 GPLv3 授权**；
  个人本地评估/不发行 stage 不触发对外分发义务，但源码开放存在于 Gitee/GitHub。
- 保留上游版权与协议文本；协议仅作技术参考，不构成法律建议。
