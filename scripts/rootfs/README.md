# rootfs 构建链（files.default.*.tar.gz.so）

本目录的三个脚本负责把 **Termux 官方 `termux-packages` 构建流程**改造成产出
本项目 Android App 可直接解压使用的 PREFIX 用户态包：

| 脚本 | 职责 |
|------|------|
| `build-rootfs.sh` | 宿主侧总调度：改造 `properties.sh`、起常驻 Docker 容器、逐架构编译、容器内打包、`docker cp` 取最终产物 |
| `pack-rootfs.sh` | **容器内**执行的打包脚本：替换硬编码路径 → 硬链接转软链接 → tar 打包 |
| `replace-paths.sh`  | 独立可用的路径批量替换工具（被 `pack-rootfs.sh` 调用，也可单独对任意文件树使用） |

最终产物：

```
app/src/<abi>/assets/files.default.<arch>.tar.gz.so
```

---

## 一、前置条件

| 项目 | 要求 |
|------|------|
| 宿主 | x86_64 Linux + Docker（官方 builder 镜像仅提供 x86_64 构建器） |
| 镜像 | `ghcr.io/termux/package-builder` |
| 宿主工具 | `git tar gzip xz file docker` |
| 磁盘 | 数十 GB（首次拉镜像 + 各架构源码与工具链） |

> **设备（Android proot）侧能否构建**：可以。**跑不了的是 Docker 容器运行时**
> （设备上 `docker` daemon 起不来），但构建本身不绑定 Docker：可以**以当前 proot 环境作为
> 构建基础、不走容器直接编译**。
>
> 但要注意：**当前脚本实现仍强依赖 Docker**（开头硬检查 `docker` 命令，失败即退出），
> 要脱离 Docker 直接构建需先改造脚本（补一个类似 `IN_DOCKER=0` 的直编分支）。
>
> 即使改造完成，也**不推荐在手机跑**：构建性能消耗严重，属典型的电脑任务，且手机还要日常使用。
> 故**当前暂不在设备上执行构建**（并非跑不了）。

---

## 二、核心思路

**关键认知：不需要从 `.deb` 里再解一次包。**

`termux-packages` 官方构建链本身就会把文件"释放"到目标路径，`.deb` 只是
同一流程末尾顺带产出的封装：

```
termux_step_make_install
        ↓  按 properties.sh 推导出的 $PREFIX 安装
   $PREFIX = /data/data/alin.android.alinos/files/default      ← 文件就在这里
        ↓
termux_step_copy_into_massagedir
        ↓  $PREFIX 内容整体复制到 massage 目录
   $MASSAGEDIR/$PREFIX_CLASSICAL
        ↓
termux_step_massage
        ↓  strip / shebang 重写为新 PREFIX / 硬链接检测 / 子包拆分
termux_step_create_debian_package
        ↓  du -sk . 算 Installed-Size，tar 打 data.tar.xz，再套 DEBIAN/ 合成
   output/<pkg>_<ver>_<arch>.deb                              ← 顺带产出
```

也就是说：**改了 `properties.sh` 里的包名与 PREFIX 子目录之后，官方流程会
自动把文件释放到 `/data/data/alin.android.alinos/files/default`，`.deb` 只是副产品。**
我们要打包的对象，就是这个已经被官方流程释放好的 `files/default/`。

因此本项目**不使用** dpkg 安装机制，也**不需要** `dpkg-deb -x` 二次解包。

---

## 三、完整流程

```
① 改造 termux-packages/scripts/properties.sh（三处）
        ↓
② 编译 bash + openssh
   （官方流程自动产出并释放 files/default/，同时顺带产出 .deb）
        ↓
③ 批量替换文件树内硬编码的旧路径
        ↓
④ 硬链接 → 软链接（保证包体内只有软链接）
        ↓
⑤ tar 打包 → files.default.<arch>.tar.gz.so
        ↓
⑥ 删除容器与 /data/data/alin.android.alinos/，进入下一架构
```

步骤 ③④⑤ **全部在 Docker 容器内执行**（容器内有 `python3`/`tar`/`find`/`dpkg-deb`，
权限干净，无需 `chown`），宿主只通过 `docker cp` 取走最终 `.so`。

### ① 改造 properties.sh（三处）

三处都是硬赋值，无法用环境变量覆盖，必须改源文件：

| # | 变量 | 原值 | 目标值 | 作用 |
|---|------|------|--------|------|
| 1 | `TERMUX_APP__PACKAGE_NAME` | `com.termux` | `alin.android.alinos` | 决定 `TERMUX_APP__DATA_DIR` |
| 2 | `TERMUX__PREFIX_SUBDIR` | `usr` | `default` | 决定 PREFIX 子目录 |
| 3 | `__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT` | `true` | `false` | 放行非 `usr` 的子目录 |

第 2 处与第 3 处**必须同时改**：官方在 usr-merge 校验开启时，要求
`TERMUX__PREFIX` 必须等于 `$TERMUX__ROOTFS/usr`，改成 `default` 会被校验拒绝。

变量推导链条：

```
TERMUX_APP__PACKAGE_NAME = alin.android.alinos
  → TERMUX_APP__DATA_DIR = /data/data/alin.android.alinos
  → TERMUX__ROOTFS       = /data/data/alin.android.alinos/files
  → TERMUX__PREFIX       = /data/data/alin.android.alinos/files/default
  → TERMUX_PREFIX_CLASSICAL = 同上
```

**路径映射：**

| 项目 | Termux 原始值 | 本项目目标值 |
|------|--------------|-------------|
| 应用包名 | `com.termux` | `alin.android.alinos` |
| ROOTFS 子目录 | `files` | `files`（不变） |
| PREFIX 子目录 | `usr` | `default` |
| 最终 PREFIX | `/data/data/com.termux/files/usr` | `/data/data/alin.android.alinos/files/default` |

> `files/default` 是本项目自定义布局（**非** Termux 的 `usr` merge 格式），
> 所以必须关闭 usr-merge 校验。

### ② 编译（官方流程）

```bash
# 宿主：起常驻容器（不要 --rm，官方 run-docker.sh 也是常驻再 exec）
docker run -d --name alin-rootfs-builder \
    -v "$TP_DIR:/home/builder/termux-packages" \
    ghcr.io/termux/package-builder

# 容器内：编译（依赖由 dpkg 依赖图自动带上）
docker exec alin-rootfs-builder bash -lc '
    cd /home/builder/termux-packages
    ./build-package.sh -a <arch> bash
    ./build-package.sh -a <arch> openssh
'
```

编译完成后，容器内同时存在两份东西：

| 路径（容器内） | 内容 |
|----------------|------|
| `~/.termux-build/<pkg>/massage/data/data/alin.android.alinos/files/default` | 官方释放出的文件树（massage 后的最终形态） |
| `~/termux-packages/output/*.deb` | 官方顺带产出的 deb 封装 |

我们取**前者**。

### ③④⑤ 容器内后处理 + 打包

`pack-rootfs.sh` 被拷进容器后执行：

```
③ replace-paths.sh  →  替换文件树内硬编码的旧路径
④ python3 块         →  同 inode 只留一个实体，其余改为相对软链接
⑤ tar --format=gnu   →  files.default.<arch>.tar.gz.so
```

### ⑥ 取产物 + 清理

```bash
docker cp <ctr>:/home/builder/<产物> ./     # 取出最终 .so
docker rm -f <ctr>                          # 删容器
sudo rm -rf /data/data/alin.android.alinos/ # 清设备侧残留，进入下一架构
```

---

## 四、脚本用法

### `build-rootfs.sh` — 宿主侧总调度

```bash
bash scripts/rootfs/build-rootfs.sh                # 全部四架构
bash scripts/rootfs/build-rootfs.sh aarch64        # 指定架构
ARCHS="aarch64 x86_64" bash scripts/rootfs/build-rootfs.sh
```

环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `TERMUX_PACKAGES_REPO` | 官方 GitHub 地址 | termux-packages 仓库 |
| `WORKDIR` | `/tmp/alin-rootfs-build` | 工作目录 |
| `OUTDIR` | `<repo>/app/src` | 产物输出目录 |
| `TARGET_PACKAGES` | `bash openssh` | 目标包 |
| `KEEP_CONTAINER=1` | 未设置 | 保留容器便于排查 |
| `SKIP_FETCH=1` | 未设置 | 跳过克隆/更新 termux-packages |

> `IN_DOCKER=0`（直接在宿主编译、不走容器）**目前尚未实现**，属预留方向；
> 见「前置条件」中关于设备侧直接构建的说明。

架构映射：

| termux 架构名 | 本仓库 ABI 目录 |
|--------------|----------------|
| `aarch64` | `arm64` |
| `arm` | `arm` |
| `i686` | `i686` |
| `x86_64` | `x86_64` |

### `pack-rootfs.sh` — 容器内打包

```bash
bash pack-rootfs.sh --arch aarch64 --abi arm64 \
     --staging <已释放的 files 根目录> \
     --outdir  <输出目录>
```

| 参数 | 说明 |
|------|------|
| `--arch` | termux 架构名，决定产物文件名 |
| `--abi` | ABI 目录名，决定产物子目录 |
| `--staging` | 含 `files/<subdir>/` 的根目录（**首选**，官方释放产物） |
| `--debs` | `.deb` 目录（**兜底**，仅当没有 staging 时才用） |
| `--outdir` | 产物输出目录 |
| `--app-package` | 目标包名（默认 `alin.android.alinos`） |
| `--prefix-subdir` | PREFIX 子目录（默认 `default`） |
| `--keep` | 保留临时工作目录 |

### `replace-paths.sh` — 独立路径替换

```bash
bash replace-paths.sh <目标目录> [--old-package X] [--new-package Y]
                                  [--old-subdir X] [--new-subdir Y]
                                  [--dry-run] [--binary] [--backup]
```

替换规则（长路径优先）：

| 旧 | 新 |
|----|----|
| `/data/data/com.termux/files/usr` | `/data/data/alin.android.alinos/files/default` |
| `/data/data/com.termux/files/home` | `/data/data/alin.android.alinos/files/home` |
| `/data/data/com.termux/files` | `/data/data/alin.android.alinos/files` |
| `/data/data/com.termux/cache` | `/data/data/alin.android.alinos/cache` |
| `/data/data/com.termux` | `/data/data/alin.android.alinos` |
| `@TERMUX_PREFIX@` | `/data/data/alin.android.alinos/files/default` |
| `@TERMUX_HOME@` | `/data/data/alin.android.alinos/files/home` |
| `com.termux` | `alin.android.alinos` |

- 默认只改**文本文件**（`#!` 开头、无 NUL 字节、大小 ≤ 64MB）。
- 替换**幂等**：已是新路径的文件不会被再次改写。
- 二进制内的路径需重新编译，文本替换会破坏 ELF，脚本会跳过并报告。

---

## 五、产物结构与自检

`pack-rootfs.sh` 结束时输出：

```
- 普通文件  d 目录  l 软链接  h 硬链接
[通过] 包体内无硬链接
[通过] 无 com.termux 残留
顶层结构：
      files/
      files/default/
```

包内顶层为 `files/<subdir>/`，与 App 侧解压目标一致：

```
/data/data/alin.android.alinos/files/default/
```

---

## 六、常见问题

| 现象 | 原因与处理 |
|------|-----------|
| `properties.sh` 校验报错 | 三处未同时改（尤其 usr-merge 校验未关） |
| 容器内找不到 `~/.termux-build/<pkg>/massage` | 编译失败或包名不符；用 `docker exec <ctr> ls ~/.termux-build/` 核对 |
| 文件树里仍有 `com.termux` | 用 `--binary` 复核，或检查是否落在 replace 规则未覆盖的路径 |
| 解包后软链接悬空 | 绝对软链仍指向旧路径；确认 replace 步骤在转软链之前执行 |
| 宿主 `python3` 缺失 | 打包在容器内执行，宿主无需 `python3` |
| `docker: command not found` | 当前脚本强依赖 Docker（未实现直编分支）。可改用 Docker 环境跑，或改造脚本为不走容器、以本地环境直接构建 |

---

## 七、相关文件

- `app/src/main/java/.../localshell/` — 设备侧解压与 PREFIX 使用
- `PROJECT_STATUS.md` — 项目整体进度
