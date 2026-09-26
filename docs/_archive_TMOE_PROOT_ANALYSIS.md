# tmoe-linux Proot 方案分析（校准版）

> 归档文档 · 最后校准：2026-09-26
> 目的：为 AlinOs 在 Android/Termux 上自建 proot 容器提供**经实测校准**的参考。
>
> **本文所有结论均来自两处一手证据：**
> 1. tmoe 仓库源码：`/data/data/com.termux/files/home/.local/share/tmoe-linux/git`
> 2. 本机已安装的真实容器：`~/.local/share/tmoe-linux/containers/proot/ubuntu-noble_arm64`
>
> 凡未在这两处得到证实的内容，一律标注「未证实」或删除。上一版文档中大量「看着合理」的描述已列入
> [§7 幻觉纠正表](#七旧文档幻觉纠正表)。

---

## 〇、术语与路径约定

| 变量 | 实际值（Android/Termux） | 说明 |
|------|--------------------------|------|
| `$HOME` | `/data/data/com.termux/files/home` | Termux 家目录 |
| `$PREFIX` | `/data/data/com.termux/files/usr` | Termux 前缀 |
| `TMOE_LINUX_DIR` | `$HOME/.local/share/tmoe-linux` | tmoe 数据根 |
| `TMOE_GIT_DIR` | `${TMOE_LINUX_DIR}/git` | 仓库 |
| `TMOE_CONTAINER_DIR` | `${TMOE_LINUX_DIR}/containers` | 容器根 |
| `CONFIG_FOLDER` | `$HOME/.config/tmoe-linux` | 配置 |
| `ROOTFS_DIR`（下载缓存） | `/sdcard/Download/backup/rootfs`（优先 external-1） | 镜像 tar 缓存 |
| `DEBIAN_FOLDER` | `${LINUX_CONTAINER_DISTRO}_${ARCH_TYPE}` | 如 `ubuntu-noble_arm64` |
| `DEBIAN_CHROOT` | `${TMOE_CONTAINER_DIR}/proot/${DEBIAN_FOLDER}` | **rootfs 真实落盘位置** |
| `TMOE_STARTUP_SCRIPT` | `${DEBIAN_CHROOT}/usr/local/etc/tmoe-linux/container/tmoe-linux-container` | 生成的启动脚本 |

> ⚠️ 旧文档把它写成 proot-distro 的
> `.../usr/var/lib/proot-distro/containers/ubuntu/rootfs`，**完全错误**。tmoe 从不使用 proot-distro 的目录。

---

## 一、仓库现状（本次核对发现的最大变化）

### 1.1 仓库已归档

`git log` 只有 1 个提交（浅克隆 `--depth=1`）：

```
ebc811a 2024-10-11 chore(ubuntu): fix codename
```

根目录 `Readme.md` 全文：

> The old edition has been archived, the next one will be the new edition.
> See you next time.

也就是说：**我们分析的是一套已冻结、不再维护的旧版实现**。`origin/master` 与本地一致，无新提交。

### 1.2 新版入口 `debian.sh` 已改为「下载 awk 运行」

根 `debian.sh` 是 POSIX sh 引导器，逻辑为：

```sh
main() {
    run_old_file || {          # 若存在旧版 manager 则直接运行旧版
        _tmp_awk_file=$(get_temp_file)
        _tmp_awk_uri=$(get_awk_uri gh)   # 下载 2/2.awk
        get_awk_file
        check_file_size
        run_awk_program
    }
}
```

- 新版权威源：`https://raw.githubusercontent.com/2moe/tmoe/2/2.awk`（备选 `gi.tmoe.me`、`gitee.com/mo2/linux`）。
- 旧版全部保留在 `share/old-version/`，并由 `run_old_file()` 直接调用
  `share/old-version/share/app/manager`。
- 本机容器正是通过旧版路径安装出来的。

### 1.3 本地未提交改动（4 处 shebang）

`git status` 显示 4 个文件被本地修改，均只是把 shebang 改为 Termux 绝对路径：

```
share/old-version/share/app/manager               #!/data/data/com.termux/files/usr/bin/env bash
share/old-version/share/app/tmoe                  同上
share/old-version/share/container/debian/debian   同上
share/old-version/share/container/debian/lnk-menu 同上
```

属于运行环境适配，非上游变更。

---

## 二、实测容器：`ubuntu-noble_arm64`

### 2.1 基本信息

| 项 | 值 |
|----|----|
| 发行版配置 | `$HOME/.config/tmoe-linux/linux_container_distro.txt` = `ubuntu-noble` |
| 容器目录 | `~/.local/share/tmoe-linux/containers/proot/ubuntu-noble_arm64` |
| 架构 | `across_architecture_container.txt` = `arm64`（与宿主一致，**未启用 QEMU**） |
| locale | `$HOME/.config/tmoe-linux/locale.txt` = `zh_CN.UTF-8` |
| 镜像缓存 | `/sdcard/Download/backup/rootfs/ubuntu-noble_arm64-rootfs.tar.xz` |
| 启动脚本 | `$ROOTFS/usr/local/etc/tmoe-linux/container/tmoe-linux-container`（约 35 KB，可执行） |

### 2.2 容器内关键配置（实测）

```ini
# etc/hostname
PJE110                              # 取自 getprop ro.product.model

# etc/environment
PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin"
export TMOE_PROOT=true
export MOZ_FAKE_NO_SANDBOX=1
export QT_QPA_PLATFORMTHEME=qt5ct

# etc/apt/sources.list（LXC 官方原样，未被 tmoe 改写）
deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
...（另有已注释的 bfsu 源）

# etc/apt/sources.list.d/mozillateam-ubuntu-ppa-noble.sources（第三方 PPA）

# etc/resolv.conf
nameserver 114.114.114.114
...
```

容器内已存在用户 `he`（uid/gid 1001），与宿主用户名一致；启动脚本因此使用
`--change-id=1001:1001` 而**不是** `--root-id`（详见 §4）。

### 2.3 容器内的 tmoe 文件

```
$ROOTFS/usr/local/etc/tmoe-linux/
├── container/tmoe-linux-container        # 生成的启动脚本（唯一真正被执行的）
├── environment/container.env            # 环境变量文件（本机为空）
├── environment/entrypoint               # cd ~
├── environment/login                    # 执行 /etc/profile.d/permanent/* 等
├── proot_proc/                          # ~36 个 /proc 伪文件（已解压）
│   ├── .tmoe-container.stat
│   ├── .tmoe-container.version
│   ├── uptime / loadavg / vmstat / ...
│   └── bus/input, bus/pci ...
├── git/                                 # 容器内又克隆了一份完整 tmoe 仓库
├── locale.txt
├── icons/  novnc  ...
└── tmp/resolv.conf
```

> 注意：`container.env` 为空、`git/` 是完整仓库副本、`/usr/local/etc/tmoe-linux` 同时存在于
> 宿主与容器两侧 —— 这些都是旧版设计的冗余，AlinOs 不必复刻。

---

## 三、安装流程（源码实测）

tmoe 的 `share/old-version/share/container/install` **本身是一份 debian 模板脚本**，
其它发行版通过 `sed` 打补丁后 `bash -c` 执行：

```bash
# share/container/list
linux_distro_common_model_01() {
    bash -c "$(sed -n p .../container/install |
        sed -E -e "s/debian container/${DISTRO_NAME} container/g" \
               -e "s:debian-sid:${DISTRO_NAME}-${DISTRO_CODE}:g" \
               -e "s:debian/sid:${DISTRO_NAME}/${DISTRO_CODE}:g" \
               -e "s:Debian GNU/Linux:${DISTRO_NAME} GNU/Linux:g")"
}
```

所以「debian/sid」在 Ubuntu 分支会被替换为「ubuntu/noble」。真实流程如下。

### 3.1 架构探测

```bash
dpkg --print-architecture      # aarch64 → ARCH_TYPE=arm64
# 或 uname -m 兜底
# 跨架构时读 $CONFIG_FOLDER/across_architecture_container.txt（第1行容器架构，第2行QEMU架构）
```

### 3.2 下载 LXC 官方裸镜像

```bash
# 模板里的变量（sed 后对 ubuntu noble arm64 生效）
TUNA_LXC_IMAGE_MIRROR_REPO="https://mirrors.nju.edu.cn/lxc-images/images/ubuntu/noble/arm64/default"
TUNA_LXC_IMAGE_MIRROR_REPO_02="https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/ubuntu/noble/arm64/default"
TMOE_ROOTFS_TAR_XZ="ubuntu-noble_arm64-rootfs.tar.xz"

# 目录日期：抓取镜像站目录，取 date 链接的最后一行
TTIME=$(curl --connect-timeout 10 -L "${REPO}/" | grep date | ... | tail -n 1)

# 下载
aria2c --console-log-level=warn --connect-timeout=10 --no-conf \
       -x 5 -k 1M --split 5 -o ${TMOE_ROOTFS_TAR_XZ} \
       "${REPO}/${TTIME}rootfs.tar.xz"
# 失败则用清华备镜像重试
```

下载缓存目录 `ROOTFS_DIR`：优先 `~/storage/external-1/Download/backup/rootfs`，
否则 `/sdcard/Download/backup/rootfs`。本机实际为 `/sdcard/Download/backup/rootfs`。

### 3.3 解压 rootfs

Android 分支的关键命令（`install: uncompress_tar_xz_file`）：

```bash
cd ${DEBIAN_CHROOT}
pv ${CURRENT_TMOE_DIR}/${TMOE_ROOTFS_TAR_XZ} \
  | proot --link2symlink ${GNU_TAR_BIN} -pJx
```

- `-p` 保留权限；`-J` = xz；`--link2symlink` 把硬链接退化为符号链接，
  规避 Android 文件系统/权限限制 —— **这是 Android 下解压 LXC 镜像的必要手段**。
- 解压后若 `usr/bin/env` 与 `bin/busybox` 都不存在，会再解压一次并 `chown -R 0:0`。
- 本机另存有 `debian-bookworm`、`debian-trixie`、`archlinux-latest` 等镜像，说明该目录可复用。

### 3.4 创建 proot_proc 伪文件目录

```bash
cd ${CONFIG_FOLDER}
git clone --depth=1 https://gitee.com/ak2/proot_proc proot_proc \
  || git clone --depth=1 https://github.com/cu233/proot_proc proot_proc
# 失败则 aria2c 直下 proc.tar.xz

cd ${DEBIAN_CHROOT}
tar -Jxf ${CONFIG_FOLDER}/proot_proc/proc.tar.xz
# → 生成 $ROOTFS/usr/local/etc/tmoe-linux/proot_proc/*
```

### 3.5 修补容器内环境

```bash
# /etc/environment
export TMOE_PROOT=true
export MOZ_FAKE_NO_SANDBOX=1

# hostname（Android 优先取设备型号）
getprop ro.product.model | sed 's@ @-@g; s/[^0-9a-zA-Z.-]\+//g'
# → 写入 $CONFIG_FOLDER/hostname，再 cp 到 $ROOTFS/etc/hostname

# 把宿主 getprop 注入容器 /tmp/getprop（容器内可用）
printf '#!/usr/bin/env sh\nPATH=$PATH:/system/bin exec /system/bin/getprop "$@"\n' > $TMPDIR/getprop
mv $TMPDIR/getprop $ROOTFS/tmp/

# /etc/hosts（容器运行时补齐，见 install 尾部）
127.0.0.1 localhost
::1 localhost ip6-localhost ip6-loopback
```

### 3.6 生成启动脚本

```bash
TMOE_PROC_PATH="${DEBIAN_CHROOT}/usr/local/etc/tmoe-linux/proot_proc"
source ${TMOE_SHARE_DIR}/container/proot/startup   # 用 heredoc 写出完整启动脚本
chmod a+rx ${TMOE_STARTUP_DIR}
```

`startup` 会把所有可调参数（`PROOT_USER`、`MOUNT_*`、`PROOT_BIN` …）连同 `main()` 一起
写入 `tmoe-linux-container`，随后用 `termux-fix-shebang` 修正 shebang。

### 3.7 proot_proc 权限自适应（重要）

安装收尾调用 `check_tmoe_proot_container_proc` →
`share/environment/manager_environment: check_proot_proc_permissions`：

```bash
for i in buddyinfo cgroups consoles crypto devices diskstats execdomains fb \
         filesystems interrupts iomem ioports kallsyms keys key-users kpageflags \
         loadavg locks misc modules pagetypeinfo partitions sched_debug softirqs \
         timer_list uptime vmallocinfo vmstat zoneinfo; do
    TMOE_PROC_FILE=$(sed -n p /proc/${i} 2>/dev/null)
    case "${TMOE_PROC_FILE}" in
    "")  # 宿主无权读取 → 取消注释，启用伪文件 mount
        sed -i "s@#.*set -- \"--mount=${TMOE_PROC_PATH}/${i}@set -- ...@" "${CONTAINER_STARTUP_FILE}" ;;
    *)   # 宿主可读 → 注释掉伪文件 mount
        sed -i "s@set.*tmoe-linux/proot_proc/${i}@#&@g" "${CONTAINER_STARTUP_FILE}" ;;
    esac
done
```

也就是说：**伪文件 mount 是按宿主实际权限逐项开关的**，`stat`/`version`/`bus` 另有专门分支。
本机 `/proc` 多不可读，因此生成脚本中这些 `set --` 行大多处于**启用**状态。

### 3.8 生成启动器命令

```bash
ln -sf ${TMOE_SHARE_DIR}/app/tmoe  ${PREFIX}/bin/tmoe
ln -sf ${TMOE_SHARE_DIR}/app/manager ${PREFIX}/bin/debian-i
ln -sf ${TMOE_SHARE_DIR}/container/debian/debian ${PREFIX}/bin/debian
ln -svf tmoe tome
# 还会生成 startvnc / stopvnc / startx11vnc / startxsdl / novnc 等一堆 GUI 包装器
```

`debian` 命令的实质：

```bash
# share/container/debian/debian
start_tmoe_gnu_linux_default_container() {
    if [ -e "${CONFIG_FOLDER}/chroot_container" ]; then
        bash ${PREFIX}/bin/tmoe ch
    else
        bash ${PREFIX}/bin/tmoe pr     # pr = proot
    fi
}
```

即 `debian` → `tmoe pr` → 找到唯一/最近容器 → `source tmoe-linux-container` → `exec`。

---

## 四、启动流程：启动脚本真正拼出的 proot 命令

启动脚本不是「每次重新计算」，而是**把安装时确定的值固化成变量 + 一段 `main()`**，
运行时 `source` 后直接拼参数、`exec`。

### 4.1 本机实测的关键变量值

| 变量 | 实测值 | 影响 |
|------|--------|------|
| `PROOT_USER` | `he` | 下文按 uid 1001 处理 |
| `PROOT_BIN` | `default` | → `PROOT_PROGRAM=proot`（走 PATH） |
| `SHARE_PROOT_LOADER` | `false` | **不注入 loader** |
| `PROOT_LOADER` | 空 | 仅兼容模式才非空 |
| `LD_LIB_PATH` | `default` | 不设 `LD_LIBRARY_PATH` |
| `ROOTFS_DIR` | `.../containers/proot/ubuntu-noble_arm64` | `--rootfs` |
| `KILL_ON_EXIT` | `true` | `--kill-on-exit` |
| `PROOT_SYSVIPC` | `true` | `--sysvipc` |
| `PROOT_L` | `true` | `-L` |
| `PROOT_H` / `PROOT_P` | `false` | 不加 `-H`/`-p` |
| `LINK_TO_SYMLINK` | `true` | `--link2symlink` |
| `FAKE_KERNEL` | `false` | 不加 `--kernel-release` |
| `MOUNT_PROC` | `true` | `--mount=/proc` |
| `FAKE_PROOT_PROC` | `true` | 启用 §3.7 的伪文件 mount |
| `MOUNT_DEV` | `true` | `--mount=/dev` + 子挂载 |
| `MOUNT_SYSTEM` / `MOUNT_APEX` | `true` | `--mount=/system`、`--mount=/apex` |
| `TMOE_SHELL` | `/bin/bash` | 容器内无 zsh/fish，落到 bash |
| `HOST_NAME_FILE` | `$ROOTFS/etc/hostname` | → `PJE110` |

挂载相关 conf（本机实测）：

```ini
# ~/.config/tmoe-linux/rootless/mount_termux.conf
MOUNT_TERMUX=true
TERMUX_DIR="/data/data/com.termux/files/home"   # 注意：是 home，不是整个 files

# ~/.config/tmoe-linux/rootless/mount_sd.conf
MOUNT_SD=false
```

### 4.2 实际拼出的命令（按脚本逐行还原）

```bash
proot \
  --change-id=1001:1001 \
  --pwd=/home/he \
  --rootfs=/data/data/com.termux/files/home/.local/share/tmoe-linux/containers/proot/ubuntu-noble_arm64 \
  --mount=/system \
  --mount=/apex \
  --kill-on-exit \
  --mount=/storage \
  --mount=/data/data/com.termux/files/home:/media/termux \
  --sysvipc \
  -L \
  --link2symlink \
  --mount=/proc \
  --mount=/dev \
  --mount=${ROOTFS}/tmp:/dev/shm \
  --mount=/dev/urandom:/dev/random \
  --mount=/proc/self/fd:/dev/fd \
  --mount=/proc/self/fd/0:/dev/stdin \
  --mount=/proc/self/fd/1:/dev/stdout \
  --mount=/proc/self/fd/2:/dev/stderr \
  --mount=/dev/null:/dev/tty0 \
  --mount=${CONFIG_FOLDER}/gitstatus:/root/.cache/gitstatus \
  --mount=/dev/null:/proc/sys/kernel/cap_last_cap \
  `# ↓ 以下来自 FAKE_PROOT_PROC，本机大量启用` \
  --mount=${ROOTFS}/usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.stat:/proc/stat \
  --mount=${ROOTFS}/usr/local/etc/tmoe-linux/proot_proc/.tmoe-container.version:/proc/version \
  --mount=.../proot_proc/bus:/proc/bus \
  --mount=.../proot_proc/uptime:/proc/uptime \
  `# ... 约 30+ 项` \
  /usr/bin/env -i \
    HOSTNAME=PJE110 \
    HOME=/home/he \
    USER=he \
    TERM=xterm-256color \
    SDL_IM_MODULE=fcitx XMODIFIERS=@im=fcitx QT_IM_MODULE=fcitx GTK_IM_MODULE=fcitx \
    TMOE_CHROOT=false TMOE_PROOT=true \
    TMPDIR=/tmp DISPLAY=:2 PULSE_SERVER=tcp:127.0.0.1:4713 \
    LANG=zh_CN.UTF-8 \
    SHELL=/bin/bash \
    PATH=/usr/local/bin:/bin:/usr/bin:/usr/games:/usr/local/games \
    /bin/bash -l
```

要点：

- **不是 `--root-id`**：因为 `PROOT_USER=he`，走 `--change-id`。
- `--mount=/proc` 与 proot_proc 伪文件**同时存在**：整体 `/proc` 先挂，伪文件覆盖单个条目。
- `/dev/shm` 指向 rootfs 自己的 `tmp` 目录（`MOUNT_SHM_TO_TMP=true`）。
- 兼容 loader **默认完全不出现**（`PROOT_LOADER` 为空 → 最终没有 `env PROOT_LOADER=...`）。

### 4.3 收尾 exec 的三分支

```bash
set -- "${PROOT_PROGRAM}" "${@}"
if   [[ -n ${PROOT_LOADER} && -z ${TMOE_LD_LIB_PATH} ]]; then
     set -- "env" "PROOT_LOADER=${PROOT_LOADER}" "${@}"
elif [[ -z ${PROOT_LOADER} && -n ${TMOE_LD_LIB_PATH} ]]; then
     set -- "env" "LD_LIBRARY_PATH=${TMOE_LD_LIB_PATH}" "${@}"
elif [[ -n ${PROOT_LOADER} && -n ${TMOE_LD_LIB_PATH} ]]; then
     set -- "env" "LD_LIBRARY_PATH=${TMOE_LD_LIB_PATH}" \
         "PROOT_LOADER=${PROOT_LOADER}" "${@}"
fi
exec "${@}"
```

→ 只有**兼容模式**才会给 proot 进程设置 `PROOT_LOADER` / `LD_LIBRARY_PATH`。

---

## 五、兼容模式与 proot loader 的真相

旧文档把这套机制说成「tmoe 的核心优势」，这是**严重误导**。源码事实：

```bash
DOT_NET_6_COMPATIBLE_MODE=false      # 默认关闭
PROOT_BIN="default"                  # 默认走普通 proot
SHARE_PROOT_LOADER=false             # 默认不共享 loader
```

只有当目标发行版是 `dotnet*` 时，`install: enable_dotnet_comp_mode()` 才会：

```bash
sed -E -e "s@^(DOT_NET_6_COMPATIBLE_MODE=).*@\1true@g" \
       -e "s@^(PROOT_BIN=).*@\1\"compatibility\"@g" \
       -e "s@^(SHARE_PROOT_LOADER=).*@\1true@g" -i "${TMOE_STARTUP_SCRIPT}"
```

兼容模式的路径（**注意与旧文档不同**）：

```bash
PROOT_COMPATIBLE_MODE_BIN="${TMOE_LINUX_DIR}/lib/data/data/com.termux/files/usr/bin/proot"
COMPATIBLE_MODE_LOADER="${TMOE_LINUX_DIR}/lib/data/data/com.termux/files/usr/libexec/proot/loader"
COMPATIBLE_MODE_LD_LIB_PATH="${TMOE_LINUX_DIR}/lib/data/data/com.termux/files/usr/lib"
```

- 这些文件**默认不存在**（本机 `~/.local/share/tmoe-linux/lib` 目录都没有）。
- 需要时由 `get_proot_comp_mode_deb()` 现下载现解包：

```bash
cd "${TMOE_LINUX_DIR}/lib/"
curl -Lo proot.deb l.tmoe.me/proot-aarch64      # 一个 Termux 结构的 proot deb 包
apt-get download libtalloc
dpkg-deb -X ./proot*.deb ./ ; dpkg-deb -X ./libtalloc*.deb ./
```

- loader 的作用仅是修复 **.NET 6** 在 proot 下 `csc.dll exited with code 139` / loader not found；
  .NET 7 无此问题，源码注释明确写着 "In general, you should not enable this mode!!!"。

> **结论**：普通 Ubuntu 容器根本不用 loader。AlinOs 不需要复刻它，除非将来要跑 .NET 6。
> proot 本体就是 Termux 的 `$PREFIX/bin/proot`（本机 `command -v proot` 指向它）。

---

## 六、AlinOs 可借鉴的最小方案

### 6.1 必须保留的（真正让 LXC 裸镜像跑起来的部分）

1. **LXC 官方裸镜像**：`mirrors.nju.edu.cn/lxc-images/images/<distro>/<codename>/<arch>/default/<DATE>rootfs.tar.xz`。
2. **Android 专用解压**：`pv rootfs.tar.xz | proot --link2symlink tar -pJx`（处理硬链接/权限）。
3. **proot_proc 伪文件**：解决 `/proc/stat`、`/proc/version` 等宿主不可读问题；
   本项目应直接在打包资源里内置 `proc.tar.xz`，**不要**在用户机联网 `git clone`。
4. **固定挂载集**：`/system`、`/apex`、`/proc`、`/dev` 及 `/dev/{fd,random,shm,stdin,stdout,stderr}`。
5. **启动参数**：`--rootfs`、`--pwd`、`--sysvipc`、`-L`、`--link2symlink`、`--kill-on-exit`。
6. **`env -i` 白名单环境变量**：`HOME/USER/TERM/LANG/SHELL/PATH/HOSTNAME/TMOE_PROOT`。
7. **hostname 取设备型号**、**容器内补齐 `/etc/hosts`**。

### 6.2 应当丢弃的（对 AlinOs 无价值的 tmoe 特性）

| tmoe 特性 | 处理 | 原因 |
|-----------|------|------|
| TUI/whiptail 菜单、`tmoe`/`debian-i` | 丢弃 | AlinOs 用 Java UI |
| 多发行版 / QEMU 跨架构 | 丢弃（只留 arm64 同架构） | 降低复杂度 |
| VNC / XSDL / x11 / novnc 包装器 | 丢弃 | AI 终端无需图形 |
| zsh / neofetch / 字体 / PPA | 丢弃 | 与能力无关 |
| 兼容模式 + loader | 不实现 | 仅 .NET 6 需要 |
| 容器内再克隆一份 tmoe `git/` | 丢弃 | 冗余 |
| 宿主与容器双侧 `/usr/local/etc/tmoe-linux` | 只留容器侧或只留宿主侧 | 冗余 |
| `mount_sd` / `mount_tf` / `gitstatus` | 按需 | AlinOs 不一定需要 |

### 6.3 ⚠️ 本机环境的硬约束：禁止嵌套 proot

当前 AlinOs 的调试 shell 本身就运行在第三方 App 的 proot 里。在 proot 内再 `proot` 启动
tmoe 容器会形成**嵌套 proot**，路径转换层会叠加，极易出现 `faccessat`/`statx` 解析异常、
`--link2symlink` 失效、进程卡死等问题。

因此：

- **开发期**：只做静态分析 + 读取已安装容器，不要在本 shell 里执行 `proot`/`debian`/`tmoe pr`。
- **产品期**：proot 必须由 **Android 原生层（真实 Termux host 或 App 直接 fork/exec）** 拉
  起，不能从已有的 proot 会话里再套一层。
- 若必须做验证，应在真实 Termux（非 proot）中进行。

### 6.4 建议的 AlinOs 落地形态

```
安装（一次性，Java/Kotlin 控制）
  1. 选架构/发行版 → 拼 LXC 镜像 URL
  2. 下载 rootfs.tar.xz（OkHttp/aria2）
  3. 解压到 filesDir/containers/proot/<distro>_<arch>/
     （APK 内嵌 libtar.so 或调用 Termux tar + --link2symlink 辅助）
  4. 解压内置 proot_proc/proc.tar.xz
  5. 写 /etc/environment、/etc/hostname、/etc/hosts、/etc/resolv.conf
  6. 按宿主 /proc 可读性生成伪文件挂载列表（移植 §3.7 的检测逻辑）

启动（每次 AI 调用）
  Java 直接拼 proot 参数数组 → ProcessBuilder 执行
  （不复刻 tmoe 的 shell 启动脚本，避免二次解释）
```

> 旧文档提的「Java 拼 proot 命令」方向是对的；错误在于它抄了 proot-distro 的路径与
> `--root-id`，且把 loader 当默认项。

---

## 七、旧文档幻觉纠正表

| 旧文档说法 | 事实 | 证据 |
|-----------|------|------|
| rootfs 在 `.../usr/var/lib/proot-distro/containers/ubuntu/rootfs` | 在 `~/.local/share/tmoe-linux/containers/proot/<distro>_<arch>` | `install: tmoe_manager_env` |
| `--root-id` | 本机是 `--change-id=1001:1001`（`PROOT_USER=he`） | 生成的启动脚本第 8 行 |
| 「tmoe 核心优势 = proot loader 注入」 | loader 仅 .NET 6 兼容模式使用，默认关闭且文件不存在 | `startup` 默认值 + `enable_dotnet_comp_mode` |
| loader 路径 `${TMOE_LINUX_DIR}/libexec/proot/loader` | `${TMOE_LINUX_DIR}/lib/data/data/com.termux/files/usr/bin/proot`（及对应 loader） | `startup` 变量定义 |
| 「首次安装写入 `mirrors.nju.edu.cn/ubuntu` 作为 apt 源」 | 本机 `sources.list` 是 LXC 官方 `ports.ubuntu.com`，未被改写 | `$ROOTFS/etc/apt/sources.list` |
| 「proot_proc 从 gitee 克隆，每次启动注入」 | 仅**安装时**克隆并解压；启动时按宿主权限决定是否 mount | `install` + `check_proot_proc_permissions` |
| 「AlinOs 不在容器内生成 shell 启动脚本」 | tmoe 恰恰必须生成 `tmoe-linux-container` 才能启动 | `create_proot_startup_script` |
| `/dev/shm` 来自 `containers/.../shm` | 来自 `$ROOTFS/tmp`（`MOUNT_SHM_TO_TMP=true`） | `startup` |
| `--mount=/data/data/com.termux/files:/media/termux`（旧写出 `/media/termux` 映射整个 files） | 本机 `mount_termux.conf` 把 `TERMUX_DIR` 覆写为 `.../files/home` | `mount_termux.conf` |
| 「容器内 `/data/data/alin.android.alinos/` 空」等 | 未在源码或实测中出现，属推测 | — |
| 章节「六、修复脚本注入：getprop / Android group / .NET」 | getprop 注入存在；Android group 注入**仅 chroot 模式**执行（`install:1170` 有 `TMOE_CHROOT=true` 前置条件），proot 模式跳过；.NET 仅兼容模式 | `install:1170-1200` 全文 grep |

---

## 八、关键文件索引（按重要性）

| 文件 | 作用 |
|------|------|
| `debian.sh` | 新版入口：下载 `2moe/tmoe` 的 `2/2.awk`；否则回退旧版 |
| `share/old-version/share/app/manager` | 旧版主程序（TUI） |
| `share/old-version/share/container/install` | **debian 安装模板**（各发行版 sed 打补丁后执行） |
| `share/old-version/share/container/list` | 发行版菜单、镜像 URL 拼装、调用 install |
| `share/old-version/share/container/proot/startup` | **生成容器启动脚本的模板（核心）** |
| `share/old-version/share/container/proot/management` | 启动/管理已装容器 |
| `share/old-version/share/container/debian/debian` | `debian` 命令本体 |
| `share/old-version/share/environment/manager_environment` | `check_proot_proc_permissions` 等 |
| `~/.local/share/tmoe-linux/git/...` | 本机仓库实例 |
| `~/.local/share/tmoe-linux/containers/proot/ubuntu-noble_arm64/...` | 本机实测容器 |
| `~/.config/tmoe-linux/{linux_container_distro,across_architecture_container,locale}.txt` | 全局配置 |
| `~/.config/tmoe-linux/rootless/mount_{sd,termux}.conf` | 挂载开关 |
| `/sdcard/Download/backup/rootfs/*.tar.xz` | 镜像缓存 |

---

## 九、待确认事项（避免再次臆测）

1. 新版 `2moe/tmoe` 的 `2/2.awk` 是否仍采用同一套 proot 启动模型 —— **未验证**（需联网取该文件）。
2. 本机容器由哪条命令/菜单路径安装（`debian-i` 还是 `tmoe pr`）——**未从 shell history 确认**。
3. `PROOT_USER=he` 是安装时自动取宿主用户名，还是用户手动填写 —— 需查 `management` 的用户配置入口。
4. LXC 镜像站 `mirrors.nju.edu.cn` 当前是否仍在线、目录结构与 `TTIME` 抓取是否可用 —— 需联网实测。
5. `/proc` 伪文件在当前 Android 版本下的实际可读性矩阵 —— 需在真实 Termux（非 proot）中复测。

> 以上未确认项在动手实现前应逐条落实，不要再基于推测写死参数。
