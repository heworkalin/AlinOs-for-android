# Android 私有目录可执行文件运行机制

> 记录时间：2026-09-10
> 背景：实现 `localshell_exec_capture` 工具时踩坑，特此归档避免重蹈。
> **结论已由真机验证**（非模拟环境）。

---

## 一、根本原因：W^X + SELinux 策略（非传统 UGO 权限）

这不是内核天生限制，而是 **Android 随版本 + `targetSdkVersion` 引入的 SELinux 策略变更**。

### 📚 权威来源：Termux 官方 Wiki

来自 [termux-packages Wiki — Termux and Android 10](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10)（Henrik Grimler，2024-05-27 修订）：

> Google requires the target SDK level to be set to at least **29** …
> But due to new operating system behavior changes we **cannot** do so and have to use **SDK level 28**.
>
> > [Execution of files from the writable app home directory is a **W^X violation**.](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)
> > Apps should load only the binary code that's embedded within an app's APK file.
>
> We do not want to introduce the breaking changes to comply with Google's requirements.
> But as result **Termux does not support Android 10 officially**.

关键信息：

| 要点 | 说明 |
|------|------|
| **targetSdk 分水岭** | API 29（Android 10）是官方明确的分界线 |
| **Termux 的选择** | 为保持功能，**拒绝升到 targetSdk 29，长期停留 28** |
| **分发渠道** | 因 Google Play 强制 targetSdk ≥ 29，**Termux 从 Play Store 下架，改走 F-Droid** |
| **后果** | “Termux does not support Android 10 officially”（低 targetSdk 应用在新系统上可能异常） |

**这就解释了：为什么 Termux 官方不需要复杂的执行包装 —— 它根本停留在 targetSdk 28，直接 exec 就行。**

### 关键分水岭：API 29（Android 10）

| targetSdkVersion | 行为 |
|------------------|------|
| **≤ 28**（Android 9 及更早） | `untrusted_app` SELinux 域**允许**直接 `execve` `/data/data/<pkg>/files`（标签 `app_data_file`）内的 ELF。只要 `chmod +x`，`Runtime.exec` 直接跑私有二进制**完全没问题**（早期 Termux 就这么干） |
| **≥ 29**（Android 10+） | SELinux 策略增加 **neverallow**：不可在可写的 `app_data_file` 类型目录里直接执行 ELF。哪怕文件 755、uid 归属正确，内核/SELinux 直接拦截 `execve`，返回 `EACCES` → `error=13, Permission denied` |

### ⚠ 重要：生效条件是 `targetSdkVersion`，不是系统版本

手机是 Android 14，但 app 打包 `targetSdk=28` → **限制不开启**，仍可直接跑私有目录 bin。

### 安全目的（W^X：写异或执行）

阻止 app 下载任意二进制到**可写的私有目录**后直接执行，规避恶意代码注入风险。
`/data/data/<pkg>/files` 是 app 可写，因此默认禁止执行。

### 本项目历史（已验证）

```
2025-12-29  e7923a0  targetSdk 34
2026-03-14  3a771f2  targetSdk 34 → 36
2026-09-02  a21918b  targetSdk 36
```

**始终 ≥ 29**，所以 `Runtime.exec` 直接执行私有 bash 从来不可能成功，
必须用下面的绕过方案。（如果当初用 targetSdk 28，直接 exec 就能跑）

> 注：Android 11/12/13 持续收紧 sepolicy，但核心阻断逻辑在 **API 29 就已落地**。

---

## 二、问题现象

```java
Runtime.getRuntime().exec(new String[]{
    "/data/data/alin.android.alinos/files/default/bin/bash", "-c", "echo hi"
});
```

失败：

```
Cannot run program "/data/data/.../files/default/bin/bash"
(in directory "/data/data/.../files/home"): error=13, Permission denied
```

**文件存在、权限 755，但就是 Permission denied。**
**本质是 SELinux MAC 强制访问控制的版本策略变更，不是 rwx 权限问题。**

---

## 三、✅ 正确方案：`/system/bin/sh` 套 `exec`

```java
String bashPath = "/data/data/<pkg>/files/default/bin/bash";
String inner = "exec " + shqSingle(bashPath) + " -c " + shqSingle(command);
String[] cmdArray = new String[]{"/system/bin/sh", "-c", inner};

Runtime.getRuntime().exec(cmdArray, envArray, workDir);
```

执行的命令形如：

```bash
/system/bin/sh -c 'exec '\''/data/data/<pkg>/files/default/bin/bash'\'' -c '\''<command>'\'''
```

### 为什么有效

| 方式 | `execve` 目标 | SELinux 判定 | 结果 |
|------|--------------|-------------|:----:|
| Java 直接 exec 私有 ELF | `app_data_file`（可写） | 命中 neverallow | ❌ |
| **`sh -c 'exec <私有>/bash'`** | **`/system/bin/sh`（系统分区）** | 允许执行；fork 出的 sh 进程内建 exec 替换镜像，主体是已 fork 的 sh 进程 | ✅ |

**关键**：`exec` 是 shell **内建命令**。Java 层 `execve` 的目标是
`/system/bin/sh`（系统分区文件，标签非 `app_data_file`，允许执行）；
之后 sh 在**子进程上下文**内建 `exec` 替换镜像，此时 SELinux 主体是已 fork 的 sh 进程，
**不再触发 `app_data_file` 执行阻断**。

### 真机验证结果

```json
{
  "status": "success",
  "data": {
    "stdout": "/data/data/alin.android.alinos/files/default/bin/bash\n",
    "stderr": "",
    "exit_code": 0,
    "shell": "bash(local)",
    "bash_installed": true
  }
}
```

`echo $0` 输出私有目录 bash 完整路径，证明运行的确实是本地 bash 而非系统 sh。

---

## 四、多种并列的可行方案

下面几种在不同加载场景各有适用，都是长期实践验证、可稳定使用的方法，
**而非"首选/备选"或"特例"的关系**。

### 方案 A：系统执行器 + `exec`（本项目采用）

```java
String bashPath = "/data/data/<pkg>/files/default/bin/bash";
String inner = "exec " + shqSingle(bashPath) + " -c " + shqSingle(command);
String[] cmdArray = new String[]{"/system/bin/sh", "-c", inner};
```

用系统分区自带的执行器做实际执行，再由它的 `exec` 内建替换为私有 bash 进程。

### 方案 B：linker 前缀

```java
String[] cmdArray = {"/system/bin/linker64", bashPath, "-c", command};
```

`execve` 目标是系统分区的 linker（允许执行），由 linker 加载私有 ELF。
⚠ 需确保 `LD_LIBRARY_PATH` 指向 `$PREFIX/lib`，否则 linker 找不到依赖。

### 方案 C：JNI `fork+exec`（`TerminalSession` / proot 运行时走的路径）

`TerminalSession` 通过 JNI native 层 `fork+exec` 启动，
**这正是 proot 运行时（本开发环境）本身的工作方式**。

native 层 fork 之后，子进程上下文与 Java `Runtime.exec` 的进程主体在 SELinux 判定上
走的是不同路径，因此不受该阻断影响。很多终端 APP 选择 JNI 而非纯 Java，就是出于这个原因。

### 方案 D：以 `.so` 形式放入 `jniLibs`（`nativeLibraryDir`）

| 目录 | SELinux 标签 | 可执行 | 可写 |
|------|-------------|:------:|:----:|
| `nativeLibraryDir`（`/data/app/.../lib/arm64/`） | `app_lib_file` | ✅ 允许 | ❌ 不可写（符合 W^X） |
| `files/`（app 私有数据） | `app_data_file` | ❌ 禁止 | ✅ 可写 |

#### 为什么这不算"伪装"

现代 Android 的 ELF 都是 **`ET_DYN`（共享对象 / PIE）**，
**可执行文件与共享库在 ELF 层面本就是同一类文件**。

实测本项目 jniLibs：
```
libtermux.so → ELF Header Type = 3 (ET_DYN)
             → Class = 2 (64-bit)
             → ELF 64-bit LSB shared object, ARM aarch64, dynamically linked
```

所以把一个 PIE 可执行文件命名为 `libXXX.so` 放入 `jniLibs`，
它**完全符合 `.so` 的标准头**，Android 按合法原生库处理，不违反任何规则。

> 通常有两种用法：
> 1. **标准做法**：编译成共享库，由 **JNI `System.loadLibrary()`** 加载调用
> 2. **本文场景**：当成可执行文件，直接 `Runtime.exec` 执行（逻辑上类似，只是入口不同）
>
> 两者都是合理的，看需求选择。

#### 本项目实例：`libtar.so`

`LocalShellTestActivity.setupEnvironmentIfNeeded()` 中：

```java
// 1. 从 assets 拷架构对应的 tar.gz.so 到 cache
String tarGzName = "files.default.aarch64.tar.gz.so";
InputStream in = getAssets().open(tarGzName);
// ... 写入 cacheDir/tmpTarGz ...

// 2. 用原生库解压（不依赖容器内的 tar）—— 直接 Runtime.exec，无需 sh/linker 包装
File libDir = new File(getApplicationInfo().nativeLibraryDir);
File libTar = new File(libDir, "libtar.so");          // ← 实质是 tar 可执行文件
String[] extractCmd = { libTar.getAbsolutePath(), "-xf", tmpTarGz.getAbsolutePath(), "-C", destDir };
Runtime.getRuntime().exec(extractCmd).waitFor();

// 3. 修正解压出的 bin 权限
Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "-R", "755", binDir}).waitFor();
```

**为什么用这个方案？**
- 容器内初始环境没有 `tar` 命令（鸡生蛋问题）
- `libtar.so` 是 Termux 编译的 tar，支持 `tar -xf`
- 提取发生在 proot 启动之前，属于 App 层初始化
- 从 `nativeLibraryDir` 执行零障碍，不依赖任何运行时绕过

**局限**：`nativeLibraryDir` 不可写，所以**只能放打包时已确定的二进制**，
无法用这个方案动态释放新文件。因此解压出的 `files/default/` 环境仍需方案 A/B/C 执行。

#### 本质上是一个“鸡生蛋”解法

先把一个最小可执行工具（tar）用方案 D 塞进 `nativeLibraryDir`，
用它解开完整运行时环境，之后环境内的命令再用方案 A/B/C 执行。

### 小结

| 方案 | 适合场景 |
|------|---------|
| A. sh + exec | Java 层启动私有目录 shell / 单次命令 |
| B. linker | 需要直接指定 ELF 时 |
| C. JNI fork+exec | 需要完整 PTY、长期会话（终端类 APP、proot） |
| D. 以 `.so` 形式放 jniLibs | 打包时确定的小工具（如 `libtar.so`），用于解开运行时环境 |

**这些路径在 Android 中都可用，取决于加载方式的差异，不存在绝对的"正解"。**

### ⚠ 为何不能直接照搬 Termux 官方经验

| | Termux 官方 | 本项目 |
|---|---|---|
| **targetSdk** | **28**（有意保持，拒绝升到 29） | **36**（跟随现代 Android 要求） |
| 直接 exec 私有 ELF | ✅ 可用 | ❌ 被 SELinux 拒绝 |
| 需要的绕过 | 较少（主要靠 termux-exec） | 必须（sh+exec / linker） |
| 分发渠道 | F-Droid（自建源，不受 Play 强制） | 需符合现代渠道要求 |

Termux 方案看上去“稳定且简单”，**本质是因为它的舒适区建立在低 targetSdk 上**。
它在多版本、多 ROM 上长期实验积累的兼容层确实成熟，
但那不能自动平移给 targetSdk 36 的项目 —— 我们只能走绕过路径。

---

## 五、❌ 无效方案：`libtermux-exec.so` + `Runtime.exec`

```java
env.put("LD_PRELOAD", ".../libtermux-exec.so");   // 对 Runtime.exec 无效
```

`libtermux-exec.so` 通过 `LD_PRELOAD` hook `execve()` 自动改写路径，
**但只对已运行中的 shell 内后续 fork/exec 有效**。

`Runtime.exec` 自身就是那次被拒的 `execve`，在钩子生效前已被 SELinux 拒绝。

---

## 六、常见误区

### 误区 1：以为是文件权限（rwx）问题

本质是 SELinux MAC 强制访问控制的**版本策略变更**，不是传统 UGO 权限。
`chmod 755` 无论如何都救不了。

### 误区 2：在 proot 环境里验证

proot（如 proot-distro 的 Ubuntu）**不是真实 Android 沙箱**，
在其内部测试"直接 exec 私有 ELF"会**全部成功**，无法复现真实限制。

**验证必须在真机 app 内进行。**

### 误区 3：以为只有一种正确路径

JNI `fork+exec`、`sh + exec`、linker 前缀都是并列可行的，
区别只在**加载方式**，不存在绝对的"正解"。
`TerminalSession` 能跑不代表 `Runtime.exec` 能跑（反之亦然），
但原因不是"一个是特例"，而是**两条路径的 SELinux 判定主体不同**。

### 误区 4：以为系统版本决定一切

**决定因素是 app 的 `targetSdkVersion`。**
老代码（targetSdk ≤ 28）+ 新手机 → 仍可直接 exec，移植后突然踩 `error=13`，
很多人误以为是文件权限问题。

### 误区 5：`\` 之类的 shell 方言差异

见下节。

---

## 七、另一个坑：Android toybox grep 不支持 BRE 的 `\|`

```bash
/system/bin/grep -q 'A\|B' file     # ❌ toybox 把 \| 当字面字符，永远匹配不到
/system/bin/grep -qE 'A|B' file     # ✅ 用 -E（ERE）
/system/bin/grep -q 'A' file        # ✅ 或单一模式
/usr/bin/grep   -q 'A\|B' file      # ✅ GNU grep 认（proot/Ubuntu 里）
```

app 终端 `/system/bin/sh` 的 PATH 里是 **toybox grep**，
而 proot 里是 **GNU grep** —— 环境差异导致"本地测试通过、真机失败"。

**教训：跨环境代码优先用最保守的语法（单一模式）。**

---

## 八、核心结论

**能执行的关键不是文件权限，而是：这个文件在哪里、由谁发起加载。**

| 场景 | 能否执行私有目录 ELF |
|------|:-------------------:|
| `Runtime.exec("<私有>/bash", ...)`，targetSdk ≥ 29 | ❌ Permission denied |
| `Runtime.exec("<私有>/bash", ...)`，targetSdk ≤ 28 | ✅（历史行为） |
| `Runtime.exec("/system/bin/sh", "-c", "exec <私有>/bash -c ...")` | ✅ |
| `Runtime.exec("/system/bin/linker64", "<私有>/bash", ...)` | ✅ |
| 运行中的 shell 内 `bash -c '...'` | ✅ |
| `TerminalSession`（JNI fork+exec） | ✅ |
| `nativeLibraryDir` 内的 `.so`（`ET_DYN`，直接 exec 或 JNI 加载） | ✅（`app_lib_file`，且不可写） |

**Android 并未"封锁唯一通道"，而是根据**文件位置 + 发起方**划分了允许与不允许的路径。
上面几种（sh+exec / linker / JNI / nativeLibraryDir）都是长期实验得出的稳定手段，
按需选择即可 —— **只要它能把你的自定义可执行文件成功跑起来，就是合适的方案。**

这套 `sh + exec` 方案，是当前很多 Android 终端项目（含新版 Termux、AlinOs）
在 targetSdk 29+ 环境下用 Java `Runtime.exec` 启动私有 bash 的常用手段之一。

---

## 九、相关代码位置

| 位置 | 说明 |
|------|------|
| `LocalShellExecutor.exec_capture()` | 采用 `sh + exec` 嵌套方案 |
| `LocalShellExecutor.shqSingle()` | 嵌套命令的单引号转义 |
| `LocalShellExecutor.findLinker()` | linker 路径探测（备选方案） |
| `LocalShellExecutor.buildEnvArray()` | 保证 `LD_LIBRARY_PATH` / `PATH` 就绪 |
| `LocalShellEnvironment.setupShellCommandEnvironment()` | `LD_PRELOAD` 注入 termux-exec |
| `TermuxSession.execute()` | native 层的 bash 选择逻辑 |
