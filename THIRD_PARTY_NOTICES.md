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

### 3.1 构建工具链（本项目自建，非 Termux 官方发布）

> **声明口径**：无论产物是“直接下载”还是“源码编译生成”，
> **只要它出现在 APK / 仓库产物里，就属于引入了该上游资源**，
> 归属与许可证义务**不因构建方式而改变**。本节说明的是**构建方式**，
> 而非归属声明。（产物内各组件的归属见第三节表格与第七节总表。）

> 本仓库的 `files.default.*.tar.gz.so` **不是**直接下载 Termux 官方 bootstrap，
> 而是由本项目用下述脚本从 `termux/termux-packages` 源码自行编译、
> 裁剪（仅 `bash` + `openssh` 及其依赖）、重定位打包而成。

| 工具 | 用途 | 说明 |
|------|------|------|
| `scripts/rootfs/build-rootfs.sh` | 总调度 | 改造 properties.sh → Docker 编译 → 打包 → 清理 |
| `scripts/rootfs/pack-rootfs.sh` | 解包 + 后处理 + 打包 | deb → `files/default/` → 路径替换 → 硬链接转软链 → tar |
| `scripts/rootfs/replace-paths.sh` | 路径批量替换 | `com.termux` → `alin.android.alinos` 等 |
| `scripts/rootfs/README.md` | 构建说明 | 环境要求、分步手册、自检与排错 |
| `scripts/fetch_deps.sh` | 编译依赖拉取 | `sherpa-onnx-1.13.5.aar`（不入 git） |

**路径重定位**（与官方 Termux 布局不同）：

| 项目 | Termux 官方 | 本项目 |
|------|------------|--------|
| 应用包名 | `com.termux` | `alin.android.alinos` |
| PREFIX 子目录 | `usr` | `default` |
| PREFIX | `/data/data/com.termux/files/usr` | `/data/data/alin.android.alinos/files/default` |

打包形态：包体内**只有软链接、无硬链接**（打包前硬链接统一降级为相对软链接）；
`tar` 打包时**未使用** `-h`（否则软链接会被展开为实体副本）。

**依赖连锁（为何“只编译两个包”却得到 400+ 命令）**：

脚本只显式构建 `bash` 与 `openssh`，但官方依赖图会连锁引入：

```
bash / openssh
  └→ termux-tools
       └→ bzip2, coreutils, curl, dash, diffutils, findutils, gawk,
          grep, gzip, less, procps, psmisc, sed, tar, termux-am,
          termux-am-socket, termux-core, termux-exec, util-linux,
          xz-utils, dialog
```

因此产物自然包含 coreutils / curl / tar / gzip / sed / grep 等全部基础命令
（实测 arm64 包 `bin/` 下约 416 个命令）。这些同样是**引入的第三方资源**，
归属见第三节与第七节——**不因“自动依赖”而免除记录义务**。

**待办（尚未完成）**：`libtar.so` 的静态编译（需修改多个 termux-packages 包体
才能完成静态链接），当前仍沿用既有产物。详见 `THIRD_PARTY_NOTICES.md` 备注。

---

## 四、Android 三方依赖（Gradle 引入）

> 以下为 `app/build.gradle` 实际启用的依赖（与 Gradle 版本目录 `gradle/libs.versions.toml`）。

| 坐标 | 用途 | 许可证 |
|------|------|:------:|
| `com.alphacephei:vosk-android:0.3.47` | 离线语音识别引擎 | Apache-2.0（[vosk-api](https://github.com/alphacep/vosk-api)） |
| `files('libs/sherpa-onnx-1.13.5.aar')` | ASR/TTS/KWS/声纹 引擎 | Apache-2.0（[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)） |
| sherpa aar 内置 `onnxruntime` | 推理运行时 | MIT（[microsoft/onnxruntime](https://github.com/microsoft/onnxruntime)） |
| `com.jcraft:jsch:0.1.55` | SSH 客户端 | BSD-style |
| `dev.rikka.shizuku:api` / `:provider` | ADB 权限代理 | Apache-2.0 |
| `org.lsposed.hiddenapibypass:hiddenapibypass:6.1` | 隐藏 API 绕过 | Apache-2.0 |
| `com.google.code.gson:gson:2.10.1` | JSON | Apache-2.0 |
| `commons-io:commons-io:2.15.1` | IO 工具 | Apache-2.0 |
| `com.github.L-JINBIN:MTDataFilesProvider:v1.0.0` | 数据文件共享 | 见该仓库 LICENSE |
| `com.airbnb.android:lottie:6.1.0` | 动画 | Apache-2.0 |
| `io.noties.markwon:*:4.6.2`（core / inline-parser / linkify / recycler / ext-latex / ext-strikethrough / ext-tables / ext-tasklist / syntax-highlight） | Markdown 渲染与高亮 | Apache-2.0 |
| `androidx.appcompat:appcompat:1.7.1` | 兼容库 | Apache-2.0 |
| `com.google.android.material:material:1.13.0` | Material 组件 | Apache-2.0 |
| `com.google.firebase:firebase-crashlytics-buildtools:3.0.7` | Crashlytics 构建工具 | Apache-2.0 |
| `androidx.core:core:1.17.0` | 核心扩展 | Apache-2.0 |
| `androidx.cardview:cardview:1.0.0` | 卡片视图 | Apache-2.0 |
| `androidx.recyclerview:recyclerview:1.3.2` | 列表视图 | Apache-2.0 |
| `androidx.constraintlayout:constraintlayout:2.1.4` | 布局 | Apache-2.0 |
| `androidx.preference:preference:1.2.0` | 设置页 | Apache-2.0 |
| `androidx.window:window:1.2.0` | 窗口适配 | Apache-2.0 |
| `androidx.media3:*`（exoplayer / extractor / datasource-okhttp 1.2.0） | 音视频/数据源 | Apache-2.0 |
| `com.squareup.okhttp3:okhttp`（媒体传递依赖） | HTTP 客户端 | Apache-2.0 |
| `junit` / `androidx.test.ext:junit` / `espresso-core`（测试专用） | 单元/仪器测试 | EPL-1.0 / Apache-2.0 |

**已声明但未启用**（代码中已注释，不作为当前依赖）：`io.noties:prism4j`、`net.java.dev.jna:jna`。
若后续重新启用，需回写本表。

## 五、运行时按需下载的语音模型（不随 APK/Git 打包）

以下由 App 内 Java（`voice/*`）运行中下载或用户手动导入，许可以各模型原始来源为准：

- sherpa-onnx 模型（ASR `<paraformer|sense-voice|whisper>`、KWS zipformer、声纹 campplus、TTS、VAD）——来自 k2-fsa/sherpa-onnx Releases。
- Vosk 模型（如 vosk-model-small-cn / en-us）——来自 alphacephei.com/vosk/models；多为含许可声明的公开模型。

## 六、备注 / 义务摘要

- **工具链归属**：`files.default.*.tar.gz.so` 内的所有可执行文件（bash/ssh/coreutils 等）
  均由 [termux/termux-packages](https://github.com/termux/termux-packages) 构建，
  本项目仅作裁剪打包与运行时集成。
- **引入口径**：无论产物是“直接下载”还是“源码编译生成”，只要出现在仓库/APK 产物里，
  即视为引入该上游资源——归属与许可证义务不因构建方式而改变。
- **依赖连锁**：脚本只显式构建 `bash` + `openssh`，但依赖图会连锁引入 `termux-tools`
  及其全部依赖（coreutils/curl/tar/gzip/sed/grep 等），实测 arm64 包约 416 个基础命令。
- **构建方式**：本项目自行编译；构建脚本见 `scripts/rootfs/`，产物经路径重定位
  （`com.termux`→`alin.android.alinos`、`usr`→`default`）与硬链接→软链接转换后打包。
- **待办**：`libtar.so` 静态编译流程尚未实现（需修改多个 termux-packages 包体），
  暂沿用既有产物；完成后需同步更新本节与 `LICENSE`。
- GPLv3 传染（来自 termux-app）：**分发本项目必须提供完整源码且以 GPLv3 授权**；
  个人本地评估/不发行 stage 不触发对外分发义务，但源码开放存在于 Gitee/GitHub。
- 保留上游版权与协议文本；协议仅作技术参考，不构成法律建议。

---

## 七、上游仓库总表（审计索引）

> 本项目实际引入/参考的全部上游仓库汇总，便于审计核对。

| 上游仓库 | 引入方式 | 对应产物 / 路径 | 许可证 |
|---------|---------|----------------|:------:|
| [termux/termux-app](https://github.com/termux/termux-app) | 源码 | `com.termux.app/**`、`com.termux.shared/**`、`com.termux.terminal/**`、`com.termux.view/**`、`jniLibs/liblocal-socket.so` `libtermux.so` `librmt.so` | GPLv3 / 部分 MIT·Apache-2.0 |
| [termux/termux-packages](https://github.com/termux/termux-packages) | 源码编译 | `files.default.*.tar.gz.so`（bash/openssh/openssl/coreutils 等） | GPLv3 / 各包上游 |
| [termux/proot](https://github.com/termux/proot)（上游 [proot-me/PRoot](https://github.com/proot-me/PRoot)） | 源码编译 | `jniLibs/libproot.so`、`libproot-loader.so`、`libproot-loader32.so` | GPLv2 |
| [jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator) | 源码 | `com.termux.terminal/**`、`com.termux.view/**` | Apache-2.0 |
| [wuxianggujun/Android-Proot-Builder](https://github.com/wuxianggujun/Android-Proot-Builder) | 流程参考 | proot 交叉编译流程 | 以该仓库为准 |
| [2moe/tmoe](https://github.com/2moe/tmoe) | 流程参考 | proot 容器安装/启动流程 | 以该仓库为准 |
| [proot_proc](https://gitee.com/ak2/proot_proc) | 数据包 | `assets/proot_proc.tar.xz` | 以该仓库为准 |
| [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | AAR / 模型 | `libs/sherpa-onnx-1.13.5.aar`、语音模型 | Apache-2.0 |
| [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | 传递依赖 | sherpa aar 内置 `libonnxruntime` | MIT |
| [alphacep/vosk-api](https://github.com/alphacep/vosk-api) | Maven | `com.alphacephei:vosk-android`、vosk 模型 | Apache-2.0 |
| [snakers4/silero-vad](https://github.com/snakers4/silero-vad) | 模型 | `assets/silero_vad.onnx` | MIT |
| [termux/termux-am](https://github.com/termux/termux-am) | 构建产物 | `assets/TermuxAm-debug.apk` | Apache-2.0 |
| [Chainfire/libsuperuser](https://github.com/Chainfire/libsuperuser) | 代码片段 | `com.termux.shared.shell.StreamGobbler` | Apache-2.0 |
| [pi](https://pi.dev) | 数据参考 | `assets/models.json`（provider/定价元数据） | 以该上游声明为准 |
| [L-JINBIN/MTDataFilesProvider](https://github.com/L-JINBIN/MTDataFilesProvider) | Maven | 数据文件共享 | 以该仓库 LICENSE 为准 |
| junit / AndroidX Test | Maven（测试） | 单元/仪器测试 | EPL-1.0 / Apache-2.0 |
| AndroidX / Material / Lottie / Markwon / Gson / JSch / Shizuku / HiddenApiBypass / commons-io / media3 | Maven | 见第四节明细 | Apache-2.0 / BSD-style |

> 若新增或删减依赖，请同步更新本表、第四节明细与 `LICENSE`。
