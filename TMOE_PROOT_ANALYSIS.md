# TMOE Proot 启动流程深度分析

> 分析日期：2026-09-02  
> 来源项目：https://github.com/2moe/tmoe  
> 核心文件：`install`（安装+修补）→ `startup`（生成启动脚本）→ `management`（调用启动）

---

## 一、核心流程概览

tmoe 的容器管理分**两个阶段**：

```
阶段一：标准安装流程（一次性）
  下载 rootfs → 解压 → 配置镜像源 → 配置环境 → 生成启动脚本

阶段二：启动修补流程（每次启动前）
  source 启动脚本 → 构建 proot 参数 → exec 执行
```

**对 AlinOs 的意义**：
- 我们只需在**首次安装时**跑一遍安装流程
- 每次 AI 调用 bash 时，**Java 直接拼出完整的 proot 命令**
- 不需要 shell 脚本，不需要配置文件，Java 一锅炖

---

## 二、阶段一：标准安装流程（一次性）

### 2.1 下载 rootfs

tmoe 从 BFSU（北京外国语大学）LXC 镜像站下载：

```
URL: https://mirrors.nju.edu.cn/lxc-images/images/debian/sid/${ARCH}/default/${DATE}rootfs.tar.xz
备选: https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/sid/${ARCH}/default/${DATE}rootfs.tar.xz
```

**我们的 Ubuntu 24 镜像源**：
```
URL: https://mirrors.nju.edu.cn/lxc-images/images/ubuntu/noble/${ARCH}/default/${DATE}rootfs.tar.xz
备选: https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/ubuntu/noble/${ARCH}/default/${DATE}rootfs.tar.xz
```

### 2.2 解压 rootfs

```bash
tar -pxJf rootfs.tar.xz  # -p 保留权限 -J 解压 xz
```

**关键点**：`-p` 保留所有权限和所有者，这是容器内文件权限正常的关键。

### 2.3 配置容器内环境（核心修补）

tmoe 在安装后做了以下修补：

#### 2.3.1 设置 hostname 和 hosts

```bash
# 获取 Android 设备型号作为 hostname
ANDROID_HOST_NAME=$(getprop ro.product.model | sed \
  -e 's@ @-@g' -e 's@ @@g' -e 's/[^0-9a-zA-Z.-]\+//g')

# 写入容器
echo "$ANDROID_HOST_NAME" > ${容器}/etc/hostname
echo "127.0.0.1       $ANDROID_HOST_NAME" >> ${容器}/etc/hosts
```

#### 2.3.2 创建目录结构

```bash
mkdir -pv ${容器}/media/sd
mkdir -pv ${容器}/run/shm
mkdir -pv ${容器}/etc/gitstatus
mkdir -pv ${容器}/tmp
mkdir -pv ${容器}/usr/local/etc/tmoe-linux/environment
mkdir -pv ${容器}/usr/local/etc/tmoe-linux/proot_proc
```

#### 2.3.3 创建符号链接

```bash
# 容器内 /root → /media/sd 的快捷方式
ln -sf ../media/sd ${容器}/root/

# 容器内 /sd → /media/sd 的快捷方式
ln -sf media/sd ${容器}/sd

# Android /storage/emulated 映射
mkdir -pv ${容器}/storage/emulated
ln -s ../../media/sd ${容器}/storage/emulated/0
```

#### 2.3.4 写入 container.env（环境变量文件）

tmoe 在容器内创建 `/usr/local/etc/tmoe-linux/environment/container.env`，内容包含：

```bash
export TMOE_CHROOT=false  # proot 模式下为 false
export TMOE_PROOT=true
export PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin
# ... 其他环境变量
```

**这个文件在每次启动时被 source，注入所有环境变量**。

#### 2.3.5 配置 /etc/environment

```bash
cat >> ${容器}/etc/environment <<EOF
export TMOE_CHROOT=false
export TMOE_PROOT=true
export MOZ_FAKE_NO_SANDBOX=1
EOF
```

#### 2.3.6 配置镜像源

根据发行版自动配置 apt/yum/pacman 源，使用 BFSU 或 USTC 镜像。

#### 2.3.7 创建 proot_proc 文件

tmoe 检测到宿主 `/proc` 文件权限受限，创建伪文件供容器内使用：

```bash
mkdir -pv ${容器}/usr/local/etc/tmoe-linux/proot_proc

# 创建伪 /proc 文件
cat > ${容器}/usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.stat <<EOF
cpu  13543674 2263150 11590764 15571271 210309 1343827 851885 0 0 0
ctxt 1941467212
btime 1597149124
...
EOF

# 创建伪 /proc/version
echo "Linux version $(uname -r) $(uname -v)" > ${容器}/usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.version
```

**原因**：Android 普通应用权限下，`/proc/stat`、`/proc/version` 等文件可能无法读取，需要伪造。

---

## 三、阶段二：启动流程（每次 AI 调用）

### 3.1 启动脚本结构

tmoe 的启动脚本（`tmoe-linux-container`）是一个完整的 bash 脚本，核心是 `start_tmoe_gnu_linux_container()` 函数。

**生成方式**：安装时通过 `startup` 脚本的 `cat >"${TMOE_STARTUP_SCRIPT}" <<-ENDOFPROOT ... ENDOFPROOT` 生成。

### 3.2 启动步骤详解

#### Step 1: 加载全局配置

```bash
load_global_conf() {
    # 加载 proot 全局配置
    [[ ${LOAD_PROOT_CONF} = true && -r ${PROOT_CONF_FILE} ]] && source ${PROOT_CONF_FILE}
    # 加载 SD 卡挂载配置
    [[ -z ${MOUNT_SD} && -r ${SD_CONF_FILE} ]] && source ${SD_CONF_FILE}
    # ... 其他配置文件
}
```

#### Step 2: 设置 proot 二进制和 loader

```bash
set_proot_bin_and_loader_env() {
    case ${PROOT_BIN} in
        default|system) PROOT_PROGRAM=proot ;;
        termux|prefix) PROOT_PROGRAM=${PREFIX}/bin/proot ;;
        compatibility)
            # dotNET 6 兼容模式
            PROOT_PROGRAM=${PROOT_COMPATIBLE_MODE_BIN}
            SHARE_PROOT_LOADER=true
            PROOT_LOADER=${COMPATIBLE_MODE_LOADER}
            LD_LIB_PATH=${COMPATIBLE_MODE_LD_LIB_PATH}
            ;;
        32)
            PROOT_PROGRAM=${PROOT_32_TERMUX_BIN}
            PROOT_LOADER=${PROOT_32_TERMUX_LOADER}
            LD_LIB_PATH=${PROOT_32_TERMUX_LD_LIB_PATH}
            ;;
        *) PROOT_PROGRAM=${PROOT_BIN} ;;  # 绝对路径
    esac
}
```

**对我们的意义**：Java 中只需要判断是否用兼容模式，默认用 `/system/bin/proot`。

#### Step 3: 构建 proot 命令参数（核心）

```bash
# 用户映射
if [ ${PROOT_USER} = root ]; then
    set -- "$@" --root-id
else
    UID=$(grep "^${PROOT_USER}:" /etc/passwd | awk -F: '{print $3}')
    GID=$(grep "^${PROOT_USER}:" /etc/passwd | awk -F: '{print $4}')
    set -- "$@" --change-id=${UID}:${GID}
fi

# 工作目录
set -- "$@" --pwd=${PROOT_HOME}

# 根文件系统
set -- "$@" --rootfs=${ROOTFS_DIR}

# Android 系统挂载
[[ ${MOUNT_SYSTEM} ]] && set -- "$@" --mount=/system
[[ ${MOUNT_APEX} ]] && set -- "$@" --mount=/apex

# 功能标志
[[ ${KILL_ON_EXIT} ]] && set -- "$@" --kill-on-exit
[[ ${PROOT_SYSVIPC} ]] && set -- "$@" --sysvipc
[[ ${PROOT_L} ]] && set -- "$@" -L
[[ ${PROOT_H} ]] && set -- "$@" -H
[[ ${LINK_TO_SYMLINK} ]] && set -- "$@" --link2symlink
```

#### Step 4: 设备挂载

```bash
# /proc（挂载宿主机 /proc，伪造部分条目）
# tmoe 的 /proc 修复：只伪造 stat/version/loadavg/cap_last_cap，其余继续访问宿主机
# --mount=/proc 会挂载宿主机 /proc，然后下面的 --mount 覆盖特定文件
set -- "$@" --mount=/proc

# 伪造 /proc/stat（Android 普通应用无法读取 /proc/stat）
set -- "$@" --mount=${FAKE_PROC_DIR}/stat:/proc/stat

# 伪造 /proc/version（某些程序需要内核版本）
set -- "$@" --mount=${FAKE_PROC_DIR}/version:/proc/version

# 伪造 /proc/loadavg（uptime 等程序依赖）
set -- "$@" --mount=${FAKE_PROC_DIR}/loadavg:/proc/loadavg

# 伪造 /proc/sys/kernel/cap_last_cap（空文件）
set -- "$@" --mount=/dev/null:/proc/sys/kernel/cap_last_cap

# /dev（挂载宿主机 /dev）
set -- "$@" --mount=/dev

# 设备文件描述符映射
set -- "$@" --mount=/proc/self/fd:/dev/fd
set -- "$@" --mount=/proc/self/fd/0:/dev/stdin
set -- "$@" --mount=/proc/self/fd/1:/dev/stdout
set -- "$@" --mount=/proc/self/fd/2:/dev/stderr

# /dev/urandom → /dev/random
set -- "$@" --mount=/dev/urandom:/dev/random
```

#### Step 5: 设置环境变量

```bash
# 清除所有宿主环境变量，重建
set -- "$@" /usr/bin/env -i \
    HOSTNAME=localhost \
    HOME=/root \
    USER=root \
    TERM=xterm-256color \
    SDL_IM_MODULE=fcitx \
    XMODIFIERS=@im=fcitx \
    QT_IM_MODULE=fcitx \
    GTK_IM_MODULE=fcitx \
    TMOE_CHROOT=false \
    TMOE_PROOT=true \
    TMPDIR=/tmp \
    DISPLAY=:2 \
    PULSE_SERVER=tcp:127.0.0.1:4713 \
    LANG=en_US.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin \
    ${CONTAINER_BIN_PATH} \
    SHELL=/bin/zsh -l
```

#### Step 6: 加载容器环境变量

```bash
# source container.env 中的 export 语句
if [ -s ${CONTAINER_ENV_FILE} ]; then
    for i in $(sed -E 's@export\s+@@' ${CONTAINER_ENV_FILE}); do
        set -- "$@" "$i"
    done
fi
```

#### Step 7: 最终 exec

```bash
exec proot \
    --root-id \
    --pwd=/root \
    --rootfs=/data/data/.../ubuntu_24 \
    --mount=/system \
    --mount=/apex \
    --mount=/proc \
    --mount=/dev \
    --mount=/proc/self/fd:/dev/fd \
    --mount=/dev/urandom:/dev/random \
    --sysvipc \
    -L \
    --link2symlink \
    --kill-on-exit \
    /usr/bin/env -i \
    HOSTNAME=localhost HOME=/root USER=root TERM=xterm-256color \
    LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin \
    SHELL=/bin/zsh -l
```

---

## 四、tmoe 踩过的坑（我们的避坑指南）

### 4.1 /proc 权限问题

**问题**：Android 普通应用无法读取 `/proc/stat`、`/proc/version` 等文件。

**tmoe 方案**：预创建伪文件，启动时 mount 到容器内 `/proc/` 对应位置。

**我们的方案**：首次安装时创建 `/usr/local/etc/tmoe-linux/proot_proc/` 目录，写入伪文件。

### 4.2 符号链接问题

**问题**：proot 对符号链接的处理与 chroot 不同。

**tmoe 方案**：使用 `--link2symlink` 选项，将硬链接转为符号链接。

**我们的方案**：始终加 `--link2symlink` 参数。

### 4.3 SELinux 硬链接

**问题**：SELinux 策略不允许硬链接。

**tmoe 方案**：`--link2symlink` 自动处理。

### 4.4 容器内 root 权限

**问题**：proot 模式下容器内 root 不是宿主机 root。

**tmoe 方案**：
- 使用 `--root-id` 让容器 root 获得宿主机 root 权限映射
- 给容器 root 添加 80+ 个 Android aid 组

### 4.5 cap_last_cap 问题

**问题**：某些程序需要读取 `/proc/sys/kernel/cap_last_cap`。

**tmoe 方案**：`--mount=/dev/null:/proc/sys/kernel/cap_last_cap` 伪造为零长度。

### 4.6 /dev/shm 不存在

**问题**：Android 没有 `/dev/shm`，某些程序会崩溃。

**tmoe 方案**：`--mount=/tmp:/dev/shm` 映射容器 tmp 到 /dev/shm。

### 4.7 环境变量隔离

**问题**：宿主环境变量（如 TERMUX 相关）会泄漏到容器内。

**tmoe 方案**：`/usr/bin/env -i` 清除所有环境变量，只保留需要的。

### 4.8 跨架构运行

**问题**：arm64 宿主运行 i386 容器。

**tmoe 方案**：`--qemu=qemu-user-i386` 自动调用 qemu-user 模拟器。

**我们的方案**：先不支持跨架构，后续可扩展。

### 4.9 proot loader

**问题**：某些 ARM64 proot 版本需要额外的 loader。

**tmoe 方案**：`--mount=/path/to/loader:/path/to/loader env PROOT_LOADER=/path/to/loader proot ...`

**我们的方案**：检测 proot 是否需要 loader，需要则挂载。

### 4.10 退出时进程清理

**问题**：proot 容器内后台进程可能残留。

**tmoe 方案**：`--kill-on-exit` 强制杀死所有子进程。

---

## 五、我们的极简实现方案

### 5.1 安装时（一次性）

```java
// 1. 下载 Ubuntu 24 rootfs
// 2. tar -pxJf rootfs.tar.xz
// 3. 修补：
//    - 创建 /etc/hostname, /etc/hosts
//    - 创建目录结构
//    - 创建 proot_proc 伪文件
//    - 创建 container.env
//    - 写入 /etc/environment
//    - 创建符号链接
```

### 5.2 运行时（Java 构建命令）

```java
// 拼出完整的 proot 命令
StringBuilder cmd = new StringBuilder();
cmd.append("proot ");
cmd.append("--root-id ");
cmd.append("--pwd=/root ");
cmd.append("--rootfs=${CONTAINER_DIR} ");
cmd.append("--mount=/system ");
cmd.append("--mount=/apex ");
cmd.append("--mount=/proc ");
cmd.append("--mount=/dev ");
cmd.append("--mount=/proc/self/fd:/dev/fd ");
cmd.append("--mount=/proc/self/fd/0:/dev/stdin ");
cmd.append("--mount=/proc/self/fd/1:/dev/stdout ");
cmd.append("--mount=/proc/self/fd/2:/dev/stderr ");
cmd.append("--mount=/dev/urandom:/dev/random ");
cmd.append("--mount=/dev/null:/proc/sys/kernel/cap_last_cap ");
cmd.append("--sysvipc ");
cmd.append("-L ");
cmd.append("--link2symlink ");
cmd.append("--kill-on-exit ");
cmd.append("/usr/bin/env -i ");
cmd.append("HOSTNAME=localhost ");
cmd.append("HOME=/root ");
cmd.append("USER=root ");
cmd.append("TERM=xterm-256color ");
cmd.append("LANG=en_US.UTF-8 ");
cmd.append("PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin ");
cmd.append("SHELL=/bin/bash ");
cmd.append("-c ");
cmd.append("'${USER_COMMAND}'");
```

### 5.3 核心参数说明

| 参数 | 作用 | 是否必需 |
|------|------|----------|
| `--root-id` | 容器 root 映射宿主机 root | 必需 |
| `--pwd=/root` | 初始工作目录 | 必需 |
| `--rootfs=` | 容器根目录 | 必需 |
| `--mount=/system` | 挂载 Android /system | 必需 |
| `--mount=/apex` | 挂载 Android /apex | 必需 |
| `--mount=/proc` | 挂载 /proc | 必需 |
| `--mount=/dev` | 挂载 /dev | 必需 |
| `--mount=/proc/self/fd:/dev/fd` | 设备文件描述符 | 必需 |
| `--mount=/dev/null:/proc/sys/kernel/cap_last_cap` | 伪造 cap_last_cap | 必需 |
| `--sysvipc` | 系统 V IPC 支持 | 推荐 |
| `-L` | 符号链接修复 | 推荐 |
| `--link2symlink` | 硬链接转符号链接 | 推荐 |
| `--kill-on-exit` | 退出时杀所有子进程 | 必需 |
| `/usr/bin/env -i` | 清除环境变量 | 推荐 |

---

## 六、与 tmoe 的差异总结

| 维度 | tmoe | AlinOs |
|------|------|--------|
| **容器类型** | proot + chroot + nspawn | 仅 proot |
| **发行版** | Debian/Ubuntu/Arch/Kali... | 仅 Ubuntu 24 |
| **跨架构** | qemu 支持 | 暂不支持 |
| **GUI** | VNC/X11 | 无 |
| **配置文件** | 100+ shell 变量 | Java 硬编码参数 |
| **启动脚本** | bash 脚本生成 | Java 直接拼命令 |
| **安装流程** | 复杂修补 | 简化修补 |
| **生命周期** | 持久运行 | 单次 bash |
| **多容器** | 多个容器管理 | 单容器 |

---

## 七、关键环境变量清单

tmoe 启动时注入的环境变量（供 Java 参考）：

```
HOSTNAME=localhost
HOME=/root
USER=root
TERM=xterm-256color
LANG=en_US.UTF-8
PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin
SHELL=/bin/zsh
TMOE_CHROOT=false
TMOE_PROOT=true
TMPDIR=/tmp
DISPLAY=:2
PULSE_SERVER=tcp:127.0.0.1:4713
SDL_IM_MODULE=fcitx
XMODIFIERS=@im=fcitx
QT_IM_MODULE=fcitx
GTK_IM_MODULE=fcitx
MOZ_FAKE_NO_SANDBOX=1
```

**AI 使用场景下，精简为**：
```
HOSTNAME=localhost
HOME=/root
USER=root
TERM=xterm-256color
LANG=en_US.UTF-8
PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin
SHELL=/bin/bash
```

---

## 八、AI 调用分层拼接方案

### 8.1 核心问题

proot 命令太长（50+ 参数），每次 AI 调用都拼接一遍效率低。PTY 留给用户终端（业务层），AI 只负责执行 bash 命令。

### 8.2 分层拼接设计

```
┌─────────────────────────────────────────────────────────┐
│ AI 调用                                                   │
│ "执行 ls -la /usr/bin"                                    │
└─────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│ Java 拼接                                                 │
│                                                         │
│ 固定层（硬编码，每次复用）                                │
│   proot --root-id --pwd=/root --rootfs=/... \
│         --mount=/system --mount=/apex --mount=/proc \
│         --mount=/dev --mount=/proc/self/fd:/dev/fd \
│         --mount=/dev/urandom:/dev/random \
│         --mount=/dev/null:/proc/sys/kernel/cap_last_cap \
│         --sysvipc -L --link2symlink --kill-on-exit \
│         /usr/bin/env -i \
│         HOSTNAME=localhost HOME=/root USER=root \
│         TERM=xterm-256color LANG=en_US.UTF-8 \
│         PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin \
│         SHELL=/bin/bash \
│                                                         │
│ 用户配置层（UI 配置，动态加载）                           │
│   --mount=/storage/emulated/0:/storage \
│   --mount=/data/data/com.termux:/termux \
│   ...（用户配置的路径挂载）                                │
│                                                         │
│ 执行层（AI 命令）                                         │
│   /bin/bash -c "ls -la /usr/bin"                       │
└─────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│ 执行结果                                                  │
│   stdout: 总用量 xxx                                      │
│   stderr: (空)                                           │
│   exitCode: 0                                            │
└─────────────────────────────────────────────────────────┘
```

### 8.3 container.env 文件（环境变量持久化）

每次安装时生成 `/data/data/.../ubuntu_24/usr/local/etc/tmoe-linux/environment/container.env`：

```bash
export PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin
export HOME=/root
export USER=root
export LANG=en_US.UTF-8
export TERM=xterm-256color
export SHELL=/bin/bash
```

**运行时加载方式**：
```java
// Java 读取 container.env 中的 export 语句
// 拼接到 /usr/bin/env -i 后面的环境变量中
```

### 8.4 启动脚本方案（可选）

**方案 A：Java 硬编码完整命令（推荐）**
```java
// 固定参数
StringBuilder cmd = new StringBuilder();
cmd.append("proot --root-id --pwd=/root --rootfs=${CONTAINER_DIR} ");
cmd.append("--mount=/system --mount=/apex --mount=/proc --mount=/dev ");
cmd.append("--mount=/proc/self/fd:/dev/fd --mount=/dev/urandom:/dev/random ");
cmd.append("--mount=/dev/null:/proc/sys/kernel/cap_last_cap ");
cmd.append("--sysvipc -L --link2symlink --kill-on-exit ");
cmd.append("/usr/bin/env -i HOSTNAME=localhost HOME=/root USER=root ");
cmd.append("TERM=xterm-256color LANG=en_US.UTF-8 ");
cmd.append("PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin ");
cmd.append("SHELL=/bin/bash");

// 用户配置层（从 UI 配置读取）
for (MountPath p : userConfig.getMountPaths()) {
    cmd.append(" --mount=").append(p.getHostPath()).append(":").append(p.getContainerPath());
}

// 执行层（AI 命令）
cmd.append(" /bin/bash -c ").append(quote(aiCommand));

// 执行
ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd.toString());
Process p = pb.start();
```

**方案 B：tmoe 启动脚本模式**
```bash
# 安装时生成 /data/data/.../tmoe-linux-startup.sh
cat > tmoe-linux-startup.sh << 'EOF'
#!/bin/bash
CONTAINER_DIR="/data/data/.../ubuntu_24"
USER_CMD="${1}"

exec proot \
  --root-id --pwd=/root --rootfs=${CONTAINER_DIR} \
  --mount=/system --mount=/apex --mount=/proc --mount=/dev \
  --mount=/proc/self/fd:/dev/fd --mount=/dev/urandom:/dev/random \
  --mount=/dev/null:/proc/sys/kernel/cap_last_cap \
  --sysvipc -L --link2symlink --kill-on-exit \
  /usr/bin/env -i \
  HOSTNAME=localhost HOME=/root USER=root TERM=xterm-256color \
  LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin \
  SHELL=/bin/bash \
  /bin/bash -c "${USER_CMD}"
EOF
chmod +x tmoe-linux-startup.sh

# 运行时调用
java.sh tmoe-linux-startup.sh "ls -la /usr/bin"
```

### 8.5 用户配置路径挂载

用户通过 UI 配置允许 AI 访问的路径：

```java
class MountPath {
    String hostPath;        // 宿主机路径
    String containerPath;   // 容器内挂载点
    boolean readOnly;       // 是否只读
}

// 预定义路径（用户可选开关）
List<MountPath> defaultMounts = List.of(
    new MountPath("/storage/emulated/0", "/storage", true),
    new MountPath("/data/data/com.termux/files", "/termux", false),
    new MountPath("/data/data/.../ubuntu_24", "/ubuntu", false)
);
```

### 8.6 环境变量自动加载

```java
// 安装时写入 container.env
class EnvironmentManager {
    void writeContainerEnv(Path envFile) {
        List<String> lines = List.of(
            "export PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin",
            "export HOME=/root",
            "export USER=root",
            "export LANG=en_US.UTF-8",
            "export TERM=xterm-256color",
            "export SHELL=/bin/bash"
        );
        Files.write(envFile, lines);
    }
    
    // 运行时加载并拼接到 proot 命令
    String buildEnvArgs(Path envFile) {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(envFile)) {
            if (line.startsWith("export ")) {
                // 提取变量名和值
                String var = line.replace("export ", "");
                sb.append(var).append(" ");
            }
        }
        return sb.toString();
    }
}
```

### 8.7 方案对比

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| **方案 A：Java 硬编码** | 无需管理脚本，一次构建 | 代码较长 | ⭐⭐⭐⭐⭐ |
| **方案 B：启动脚本** | 代码简洁，易于修改 | 多一层脚本管理 | ⭐⭐⭐⭐ |
| **方案 C：wrapper.sh** | 容器内直接调用 | 需要额外 proot 启动 | ⭐⭐⭐ |

**推荐方案 A + B 结合**：Java 硬编码固定参数，用户配置动态注入，AI 命令直接追加。

---

## 九、容器安装流程（一次性保姆级修复）

安装是容器初始化的核心，必须保证容器**网络通畅、证书可用、环境完整**。

### 9.1 镜像源配置（全面收集 tmoe）

tmoe 支持的镜像源全面整理（仅 Ubuntu/Debian rootfs + apt 源）：

#### A. LXC 镜像源（rootfs 下载）

| 名称 | URL 模板 | 类型 | 归属 |
|------|----------|------|------|
| **BFSU（北京外国语大学）** | `https://mirrors.nju.edu.cn/lxc-images/images/debian/sid/${ARCH}/default/${DATE}rootfs.tar.xz` | rootfs | 国内 |
| **TUNA（清华大学）** | `https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/sid/${ARCH}/default/${DATE}rootfs.tar.xz` | rootfs | 国内 |
| **LXC Images 官方** | `https://images.linuxcontainers.org/images/ubuntu/noble/${ARCH}/default/${DATE}rootfs.tar.xz` | rootfs | 境外 |

> **注**：tmoe 默认用 BFSU，TUNA 为备选。Ubuntu 镜像目前只在 BFSU/TUNA 提供，官方镜像站暂无 Ubuntu Noble。

#### B. apt 镜像源（容器内软件安装）

**Ubuntu 镜像源**：

| 名称 | URL | 类型 | 归属 |
|------|-----|------|------|
| **BFSU（北京外国语大学）** | `https://mirrors.nju.edu.cn/ubuntu/` | apt | 国内 |
| **BFSU（北京外国语大学）** | `https://mirrors.bfsu.edu.cn/ubuntu/` | apt | 国内 |
| **TUNA（清华大学）** | `https://mirrors.tuna.tsinghua.edu.cn/ubuntu/` | apt | 国内 |
| **USTC（中国科学技术大学）** | `https://mirrors.ustc.edu.cn/ubuntu/` | apt | 国内 |
| **华为云** | `https://mirrors.huaweicloud.com/ubuntu/` | apt | 国内 |
| **阿里云** | `https://mirrors.aliyun.com/ubuntu/` | apt | 国内 |
| **网易 163** | `https://mirrors.163.com/ubuntu/` | apt | 国内 |
| **腾讯** | `https://mirrors.tencent.com/ubuntu/` | apt | 国内 |
| **官方** | `https://archive.ubuntu.com/ubuntu/` | apt | 境外 |
| **LXC Images** | `https://images.linuxcontainers.org/ubuntu/` | apt | 境外 |

**Debian 镜像源**：

| 名称 | URL | 类型 | 归属 |
|------|-----|------|------|
| **BFSU（北京外国语大学）** | `https://mirrors.nju.edu.cn/debian/` | apt | 国内 |
| **BFSU（北京外国语大学）** | `https://mirrors.bfsu.edu.cn/debian/` | apt | 国内 |
| **TUNA（清华大学）** | `https://mirrors.tuna.tsinghua.edu.cn/debian/` | apt | 国内 |
| **USTC（中国科学技术大学）** | `https://mirrors.ustc.edu.cn/debian/` | apt | 国内 |
| **华为云** | `https://mirrors.huaweicloud.com/debian/` | apt | 国内 |
| **官方** | `https://deb.debian.org/debian/` | apt | 境外 |

**Kali 镜像源**：

| 名称 | URL | 类型 | 归属 |
|------|-----|------|------|
| **USTC（中国科学技术大学）** | `https://mirrors.ustc.edu.cn/kali/` | apt | 国内 |
| **华为云** | `https://mirrors.huaweicloud.com/kali/` | apt | 国内 |
| **东北大学（NEU）** | `https://mirrors.neusoft.edu.cn/kali/` | apt | 国内 |
| **官方** | `https://.kali.org/kali/` | apt | 境外 |

### 9.2 UI 化镜像源选择

安装时提供 UI 界面，让用户选择镜像源：

```java
// 镜像源分类列表
List<MirrorSource> sources = Arrays.asList(
    // 国内源
    new MirrorSource("BFSU-APT", "https://mirrors.nju.edu.cn/ubuntu/", true, "国内-推荐"),
    new MirrorSource("BFSU-APT", "https://mirrors.bfsu.edu.cn/ubuntu/", true, "国内-推荐"),
    new MirrorSource("TUNA-APT", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/", true, "国内-推荐"),
    new MirrorSource("USTC-APT", "https://mirrors.ustc.edu.cn/ubuntu/", true, "国内-推荐"),
    new MirrorSource("华为云", "https://mirrors.huaweicloud.com/ubuntu/", true, "国内"),
    new MirrorSource("阿里云", "https://mirrors.aliyun.com/ubuntu/", true, "国内"),
    new MirrorSource("网易163", "https://mirrors.163.com/ubuntu/", true, "国内"),
    new MirrorSource("腾讯", "https://mirrors.tencent.com/ubuntu/", true, "国内"),
    
    // 境外源
    new MirrorSource("LXC Images", "https://images.linuxcontainers.org/ubuntu/", false, "境外"),
    new MirrorSource("Ubuntu官方", "https://archive.ubuntu.com/ubuntu/", false, "境外")
);
```

**用户操作**：
1. 点击「选择镜像源」→ 显示列表
2. 用户点选 → 确定
3. 点击「应用」→ 写入 `/etc/apt/sources.list` → 执行 `apt update`

### 9.2 下载与解压（Download & Extract）

```bash
# 1. 下载 rootfs（流式下载，不占内存）
curl -O ${selected_mirror}/rootfs.tar.xz

# 2. 解压到容器目录（保留权限）
tar -pxJf rootfs.tar.xz -C /data/data/.../ubuntu_24/
```

### 9.3 首次启动修复（Repair Script）

这是 tmoe 的核心价值，我们必须在安装时执行一次完整的修复流程。修复脚本（`repair.sh`）执行以下操作：

#### Step 1: 基础环境修复
```bash
# 1. 设置 hostname
HOSTNAME=$(getprop ro.product.model | sed 's/[^0-9a-zA-Z-]//g')
echo "$HOSTNAME" > /etc/hostname
# 写入 /etc/hosts
echo "127.0.0.1 localhost" >> /etc/hosts

# 2. 创建必要目录
mkdir -p /storage /run/shm /tmp
mkdir -p /usr/local/etc/tmoe-linux/environment
mkdir -p /usr/local/etc/tmoe-linux/proot_proc

# 3. 创建符号链接
ln -sf ../storage /root
ln -sf storage /sd
mkdir -p /storage/emulated
ln -sf ../../storage /storage/emulated/0
```

#### Step 2: /proc 文件修复

**tmoe 的修复策略**：
- **部分 /proc 文件伪造**：stat/version/loadavg（宿主机可能无法读取）
- **其余 /proc 继续访问宿主机**：通过 `--mount=/proc` 保持映射

**修复逻辑**：
```bash
# 1. 创建伪 stat 文件
# 原因：Android 普通应用权限下，/proc/stat 可能无法读取
# 某些程序（如 uptime, top）依赖 /proc/stat
mkdir -p /usr/local/etc/tmoe-linux/proot_proc

cat > /usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.stat << EOF
cpu  1000000 0 1000000 1000000 0 0 0
cpu0 500000 0 500000 500000 0 0 0
cpu1 500000 0 500000 500000 0 0 0
ctxt 1000000
btime 1600000000
processes 100
procs_running 2
procs_blocked 0
EOF

# 2. 创建伪 version 文件
# 原因：某些程序需要读取 /proc/version 获取内核版本
echo "Linux version $(uname -r) (gcc version 10.0.0 (GCC))" > /usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.version

# 3. 创建伪 loadavg 文件
# 原因：uptime 等程序依赖 /proc/loadavg
cat > /usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.loadavg << EOF
0.00 0.00 0.00 1/50 1000
EOF

# 4. 创建伪 cap_last_cap
# 原因：某些程序需要 /proc/sys/kernel/cap_last_cap
# /dev/null 内容为空，返回 cap_last_cap=0
touch /usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.cap_last_cap
```

**proot 挂载参数**：
```bash
# 伪造的 /proc 条目
--mount=/usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.stat:/proc/stat
--mount=/usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.version:/proc/version
--mount=/usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.loadavg:/proc/loadavg

# cap_last_cap 用 /dev/null 伪造（空文件）
--mount=/dev/null:/proc/sys/kernel/cap_last_cap

# 其余 /proc 条目继续访问宿主机（通过 --mount=/proc）
--mount=/proc:/proc
```

> **关键**：tmoe 只伪造 stat/version/loadavg/cap_last_cap，其余 /proc 条目通过 `--mount=/proc` 直接访问宿主机。

#### Step 3: CA 证书安装与镜像源配置（关键步骤！）

**为什么要做这一步？**
- 容器内默认没有最新的 CA 证书
- `apt-get update` 需要 HTTPS
- 如果证书缺失，`apt` 会报错

**修复逻辑**：

```bash
# 根据用户选择的镜像源配置
# 如果用户没选，默认用 BFSU（国内优先）
MIRROR_URL="${USER_SELECTED_MIRROR:-https://mirrors.nju.edu.cn/ubuntu/}"

# 1. 写入 sources.list
MIRROR_NAME=$(echo $MIRROR_URL | cut -d/ -f3)
cat > /etc/apt/sources.list << EOF
deb ${MIRROR_URL} noble main restricted universe multiverse
deb ${MIRROR_URL} noble-updates main restricted universe multiverse
deb ${MIRROR_URL} noble-backports main restricted universe multiverse
deb ${MIRROR_URL} noble-security main restricted universe multiverse
EOF

# 2. 更新并安装 CA 证书（这是核心！）
apt-get update
apt-get install -y ca-certificates

# 3. 更新 CA 证书信任库
update-ca-certificates
```

#### Step 4: 写入 container.env
```bash
cat > /usr/local/etc/tmoe-linux/environment/container.env << 'EOF'
export PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin
export HOME=/root
export USER=root
export LANG=en_US.UTF-8
export TERM=xterm-256color
export SHELL=/bin/bash
EOF
chmod 644 /usr/local/etc/tmoe-linux/environment/container.env
```

#### Step 5: 写入 /etc/environment
```bash
cat >> /etc/environment << 'EOF'
export TMOE_CHROOT=false
export TMOE_PROOT=true
export MOZ_FAKE_NO_SANDBOX=1
EOF
chmod 644 /etc/environment
```

### 9.4 镜像源探测与自动切换（下载前）

**核心逻辑**：用户选国内/国外 → 自动选源 → 探测是否封禁（404）→ 404 切换备用源

#### 探测流程

```java
// 1. 用户选择「国内」
if (userSelectedRegion == "国内") {
    List<String> mirrors = Arrays.asList(
        "https://mirrors.nju.edu.cn/ubuntu/",
        "https://mirrors.bfsu.edu.cn/ubuntu/",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/",
        "https://mirrors.ustc.edu.cn/ubuntu/",
        "https://mirrors.huaweicloud.com/ubuntu/"
    );
    selectedMirror = detectAvailableMirror(mirrors);
}

// 2. 用户选择「国外」
else if (userSelectedRegion == "国外") {
    List<String> mirrors = Arrays.asList(
        "https://images.linuxcontainers.org/ubuntu/",
        "https://archive.ubuntu.com/ubuntu/"
    );
    selectedMirror = detectAvailableMirror(mirrors);
}

// 3. 探测可用节点
String detectAvailableMirror(List<String> mirrorList) {
    for (String mirror : mirrorList) {
        try {
            // 探测 Release 文件（404=封禁/不可用，200=可用）
            String releaseUrl = mirror + "dists/noble/Release";
            HttpURLConnection conn = (HttpURLConnection) new URL(releaseUrl).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            int code = conn.getResponseCode();
            
            if (code == 200) {
                return mirror;  // 找到可用源
            } else if (code == 404) {
                // IP 被封禁或镜像站问题，继续下一个
                Log.d("MirrorDetect", "Mirror " + mirror + " returned 404, skipping");
            }
        } catch (Exception e) {
            // 超时/网络错误，继续下一个
        }
    }
    // 所有源都不可用 → 建议手动下载并导入
    return null;
}
```

#### 探测规则

| HTTP 状态码 | 含义 | 操作 |
|-------------|------|------|
| 200 | 可用 | 使用该源 |
| 404 | IP 封禁/镜像站问题 | 切换备用源 |
| 超时/错误 | 网络不通 | 切换备用源 |
| 301/302 | 重定向 | 跟随后探测 |

> **注**：国内源被封禁概率极低，但境外源可能有 IP 封禁风险。所有源都不可用时，提示用户手动下载 rootfs 并导入。

### 9.5 环境提取机制（原生库提取，非容器内 tar）

**关键发现**：项目**不依赖容器内的 `tar` 命令**，而是使用 Android 原生库 `libtar.so` 在 APK 层提取。

#### 提取流程（见 `LocalShellTestActivity.setupEnvironmentIfNeeded`）

```java
// 1. 从 assets 拷贝架构对应的 tar.gz.so 到 cache
String tarGzName = "files.default.aarch64.tar.gz.so";
java.io.InputStream in = getAssets().open(tarGzName);
java.io.FileOutputStream out = new java.io.FileOutputStream(tmpTarGz);
// ... 写入 tmpTarGz

// 2. 使用原生库提取（不依赖容器内的 tar）
String libDir = getApplicationInfo().nativeLibraryDir;
java.io.File libTar = new java.io.File(libDir, "libtar.so");
String[] extractCmd = {libTar.getAbsolutePath(), "-xf", tmpTarGz.getAbsolutePath(), "-C", destDir};
Process extractProc = Runtime.getRuntime().exec(extractCmd);
extractProc.waitFor();

// 3. 设置权限
Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "-R", "755", binDir}).waitFor();
```

**为什么用原生库？**
- 容器内初始环境没有 `tar` 命令
- 原生 `libtar.so` 是 Termux 编译的 tar，支持 `tar -xf`
- 提取发生在 proot 启动之前，属于 App 层初始化

**assets 中的架构包**：
```
app/src/main/assets/
├── files.default.aarch64.tar.gz.so   ← arm64-v8a
├── files.default.arm.tar.gz.so       ← armeabi-v7a
├── files.default.x86_64.tar.gz.so    ← x86_64
└── files.default.i686.tar.gz.so      ← x86
```

**提取时机**：首次启动时检测 `/data/data/.../default/bin/bash` 是否存在，不存在则提取。

### 9.6 失败处理（所有源不可用）

```java
if (selectedMirror == null) {
    // 提示用户手动下载 rootfs
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle("所有镜像源不可用");
    builder.setMessage("请手动下载 Ubuntu 24 rootfs 并导入\n\n下载链接：\nhttps://mirrors.nju.edu.cn/lxc-images/images/ubuntu/noble/arm64/default/\n\n支持 .tar.xz 格式");
    builder.setPositiveButton("选择文件", (dialog, which) -> {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-xz", "application/x-tar"});
        context.startActivityForResult(intent, REQUEST_CODE_IMPORT_ROOTFS);
    });
    builder.setNegativeButton("取消", null);
    builder.show();
}
```

### 9.5 安装完成，清理修复脚本

修复完成后，**删除 `repair.sh`**，后续直接使用 Java 拼接的极简 proot 命令。修复脚本只负责

---

## 十、AI 工具路径解析机制（"虚拟根"映射）

### 10.1 核心概念

**AI 的视角**：容器内 `/` 就是根目录。
- AI 说：`cat /etc/apt/sources.list`
- AI 认为：`/etc` 是绝对路径

**App 的视角**：`/` 需要映射到真实路径。
- App 处理：`/etc/apt/sources.list` → `/data/data/.../ubuntu_24/etc/apt/sources.list`
- App 执行：`proot --rootfs=/data/data/.../ubuntu_24 -c "cat /etc/apt/sources.list"`

### 10.2 路径处理流程

```java
// AI 工具：Read/Write/Edit/Execute
// AI 输入：file="/etc/apt/sources.list" content="..."

class PathResolver {
    String containerRoot = "/data/data/.../ubuntu_24";
    
    // 核心方法：解析 AI 的路径
    String resolve(String aiPath) {
        // 如果路径以 / 开头（绝对路径），直接拼接
        if (aiPath.startsWith("/")) {
            return containerRoot + aiPath;
        }
        // 如果是相对路径（如 ./file），需要解析（可选）
        // 建议：AI 只输出 / 开头的绝对路径
        return aiPath;
    }
}

// 示例：
PathResolver resolver = new PathResolver();
String realPath = resolver.resolve("/etc/apt/sources.list");
// 结果: /data/data/.../ubuntu_24/etc/apt/sources.list

String realPath = resolver.resolve("/usr/bin/python3");
// 结果: /data/data/.../ubuntu_24/usr/bin/python3
```

### 10.3 AI 工具封装

AI 调用工具时，Java 自动处理路径：

```java
class ProotTools {
    String containerRoot = "/data/data/.../ubuntu_24";
    
    // Read 工具
    String read(String filePath) {
        String realPath = containerRoot + filePath;
        return "proot --root-id --rootfs=" + containerRoot + 
               " --mount=/system --mount=/apex --mount=/proc --mount=/dev \
               --mount=/proc/self/fd:/dev/fd --mount=/dev/urandom:/dev/random \
               --mount=/dev/null:/proc/sys/kernel/cap_last_cap \
               --sysvipc -L --link2symlink --kill-on-exit \
               /usr/bin/env -i HOSTNAME=localhost HOME=/root USER=root \
               TERM=xterm-256color LANG=en_US.UTF-8 \
               PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin \
               SHELL=/bin/bash -c \"cat '${realPath}'\""
    }
    
    // Write 工具
    String write(String filePath, String content) {
        String realPath = containerRoot + filePath;
        return "proot --root-id --rootfs=" + containerRoot + 
               " --mount=/system --mount=/apex --mount=/proc --mount=/dev \
               --mount=/proc/self/fd:/dev/fd --mount=/dev/urandom:/dev/random \
               --mount=/dev/null:/proc/sys/kernel/cap_last_cap \
               --sysvipc -L --link2symlink --kill-on-exit \
               /usr/bin/env -i HOSTNAME=localhost HOME=/root USER=root \
               TERM=xterm-256color LANG=en_US.UTF-8 \
               PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin \
               SHELL=/bin/bash -c \"echo '${content}' > '${realPath}'\""
    }
    
    // Execute 工具
    String execute(String command) {
        // AI 命令已经在容器内，路径自动解析
        return "proot --root-id --rootfs=" + containerRoot + 
               " --mount=/system --mount=/apex --mount=/proc --mount=/dev \
               --mount=/proc/self/fd:/dev/fd --mount=/dev/urandom:/dev/random \
               --mount=/dev/null:/proc/sys/kernel/cap_last_cap \
               --sysvipc -L --link2symlink --kill-on-exit \
               /usr/bin/env -i HOSTNAME=localhost HOME=/root USER=root \
               TERM=xterm-256color LANG=en_US.UTF-8 \
               PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin \
               SHELL=/bin/bash -c '${command}'"
    }
}

// AI 调用示例：
// AI: "请帮我编辑 /etc/apt/sources.list，把镜像源换成 BFSU"
// AI 输出: {"tool": "edit", "filePath": "/etc/apt/sources.list", "content": "deb https://mirrors.nju.edu.cn/ubuntu/ noble main..."}
// App: 解析 filePath -> /data/data/.../ubuntu_24/etc/apt/sources.list
// App: 执行: proot ... -c "echo 'deb https://mirrors.nju.edu.cn/ubuntu/ noble main...' > '/data/data/.../ubuntu_24/etc/apt/sources.list'"
```

### 10.4 路径规则总结

| AI 输出路径 | App 解析路径 | 说明 |
|-------------|--------------|------|
| `/etc/hostname` | `/data/data/.../ubuntu_24/etc/hostname` | 容器内绝对路径 |
| `/usr/bin/python3` | `/data/data/.../ubuntu_24/usr/bin/python3` | 容器内绝对路径 |
| `/home/ubuntu` | `/data/data/.../ubuntu_24/home/ubuntu` | 容器内绝对路径 |
| `/storage` | `/data/data/.../ubuntu_24/storage` | 用户挂载点 |

**AI 永远输出 `/` 开头的路径，App 永远在前面拼接容器根目录。**```

