# rootfs（files.default.*.tar.gz.so）构建说明

> 从 [termux/termux-packages](https://github.com/termux/termux-packages) 构建
> **仅 `bash` + `openssh`（及其依赖）** 的 Termux PREFIX 用户态，
> 输出四个架构的 rootfs 包，供 App 的 proot 执行层使用。

---

## 一、最终产物

| 文件 | 架构 | 对应 Android ABI |
|------|------|-----------------|
| `app/src/arm64/assets/files.default.aarch64.tar.gz.so` | aarch64 | arm64-v8a |
| `app/src/arm/assets/files.default.arm.tar.gz.so` | arm | armeabi-v7a |
| `app/src/i686/assets/files.default.i686.tar.gz.so` | i686 | x86 |
| `app/src/x86_64/assets/files.default.x86_64.tar.gz.so` | x86_64 | x86_64 |

包内顶层目录：`files/default/`，即设备上的
`/data/data/alin.android.alinos/files/default/`。

---

## 二、核心思路

编译采用官方 `build-package.sh`（默认会产出 `.deb`，我们**不用**它的
apt/dpkg 安装机制，但需要 deb 里的文件树）。真正打包的对象是
**解包后落到目标路径的文件树**。

```
① 改造 termux-packages/properties.sh（三处）
        ↓
② 编译 bash + openssh（官方流程产出 .deb）
        ↓
③ 把所有 deb 的内容解包到 files/default/（目标路径）
        ↓
④ 批量替换文件树内硬编码的旧路径
        ↓
⑤ 硬链接 → 软链接（保证包体内只有软链接）
        ↓
⑥ tar 打包 → files.default.<arch>.tar.gz.so
        ↓
⑦ 删除 /data/data/alin.android.alinos/，进入下一架构
```

> deb 内的路径是**相对 PREFIX** 的（`./bin/...`），所以用
> `dpkg-deb -x <deb> <staging>/files/default/` 解包，
> 得到的就是 `files/default/bin/...`。

**路径映射：**

| 项目 | Termux 原始值 | 本项目目标值 |
|------|--------------|-------------|
| 应用包名 | `com.termux` | `alin.android.alinos` |
| PREFIX 子目录 | `usr` | `default` |
| 最终 PREFIX | `/data/data/com.termux/files/usr` | `/data/data/alin.android.alinos/files/default` |

> `files/default` 是本项目自定义布局（**非** Termux 的 `usr` merge 格式），
> 因此必须同时关闭 termux-packages 的 usr-merge 校验，否则构建会告警/报错。

---

## 三、运行环境

| 项目 | 要求 |
|------|------|
| 主机 | x86_64 Linux（构建任务是**在电脑上**完成的） |
| Docker | 可选，镜像 `ghcr.io/termux/package-builder`（推荐，依赖开箱即用） |
| 磁盘 | ≥ 50 GB 可用 |
| 网络 | 能访问 GitHub / ghcr.io（首次拉镜像与源码） |
| 工具 | `git tar gzip xz ar python3 file dpkg-deb` |

> ⚠️ 本仓库开发机为 Android proot（无 Docker），**不能**在本机运行构建脚本。

---

## 四、一键构建

```bash
# 全部四架构
bash scripts/rootfs/build-rootfs.sh

# 单个架构（调试用）
bash scripts/rootfs/build-rootfs.sh aarch64

# 指定多个架构
ARCHS="aarch64 x86_64" bash scripts/rootfs/build-rootfs.sh
```

常用环境变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `WORKDIR` | `/tmp/alin-rootfs-build` | 工作目录（克隆的 termux-packages 等） |
| `OUTDIR` | `<repo>/app/src` | 产物输出目录 |
| `SKIP_FETCH` | （空） | 设为 `1` 跳过克隆/更新 termux-packages |
| `IN_DOCKER` | `1` | 设为 `0` 在构建机直接编译（不用 Docker） |

---

## 五、脚本分工

### `build-rootfs.sh` — 总调度

> **依赖连锁说明**：脚本只显式构建 `bash` 与 `openssh`，但官方依赖图会自动引入
> `termux-tools` 及其全部依赖，因此产物仍包含完整基础命令（coreutils / curl /
> tar / gzip / sed / grep 等，实测 arm64 包 `bin/` 下约 416 个）。
> 这是**预期行为**，无需额外指定包名。

1. 环境检查（Docker / x86_64 / 必要工具）。
2. 克隆 termux-packages（浅克隆）到 `$WORKDIR/termux-packages`。
3. **改造 `scripts/properties.sh`（三处，均为硬赋值，无法用环境变量覆盖）**：

   | 变量 | 原值 | 改为 |
   |------|------|------|
   | `TERMUX_APP__PACKAGE_NAME` | `"com.termux"` | `"alin.android.alinos"` |
   | `TERMUX__PREFIX_SUBDIR` | `"usr"` | `"default"` |
   | `__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT` | `"true"` | `"false"` |

   > 后两项必须**同时**改：只改 `PREFIX_SUBDIR` 会触发 usr-merge 校验告警。

4. **逐架构编译** `bash` 与 `openssh`（依赖由 dpkg 依赖图自动带上）。
5. 调用 `pack-rootfs.sh` 做后处理并打包。
6. 清理该架构产物与 `/data/data/alin.android.alinos/`，再进入下一架构。

### `pack-rootfs.sh` — 解包 + 后处理 + 打包

| 步骤 | 内容 |
|------|------|
| 0 | 把 `--debs` 目录下所有 `.deb` 解包到 `files/<subdir>/`（`dpkg-deb -x`，按包名去重） |
| 1 | 批量替换硬编码路径（调用 `replace-paths.sh`） |
| 2 | 硬链接 → 软链接（同一 inode 只留一个实体，其余改为相对软链） |
| 3 | `tar --format=gnu -czf` 打包 |

> 也支持 `--staging <已解包目录>` 跳过步骤 0（直接后处理）。
>
> **tar 参数要点**：**不能**加 `-h`。`-h` 会把软链接展开成实体文件副本，
> 使包体暴涨；而 `--hard-dereference` 会把硬链接变成实体副本，方向也不对。
> GNU tar 没有“硬链接→软链接”的原生参数，所以第 2 步必须自己转换。

### `replace-paths.sh` — 路径批量替换

对完整文件树统一替换（长路径优先，幂等）：

| 旧路径 | 新路径 |
|--------|--------|
| `/data/data/com.termux/files/usr` | `/data/data/alin.android.alinos/files/default` |
| `/data/data/com.termux/files/home` | `/data/data/alin.android.alinos/files/home` |
| `/data/data/com.termux/files` | `/data/data/alin.android.alinos/files` |
| `/data/data/com.termux/cache` | `/data/data/alin.android.alinos/cache` |
| `/data/data/com.termux` | `/data/data/alin.android.alinos` |
| `@TERMUX_PREFIX@` | `/data/data/alin.android.alinos/files/default` |
| `@TERMUX_HOME@` | `/data/data/alin.android.alinos/files/home` |
| `com.termux` | `alin.android.alinos` |

默认只处理**文本文件**（无 NUL 字节，≤ 64 MB）与脚本类文件。
二进制文件内若含旧路径需重新编译，**不能**用文本替换。

单独使用：

```bash
bash scripts/rootfs/replace-paths.sh /path/to/rootfs --dry-run   # 预览
bash scripts/rootfs/replace-paths.sh /path/to/rootfs             # 执行
```

---

## 六、手动分步执行

```bash
# 1. 准备 termux-packages
git clone --depth 1 https://github.com/termux/termux-packages.git
cd termux-packages

# 2. 改造 properties.sh（三处）
sed -i 's|^TERMUX_APP__PACKAGE_NAME="com.termux"|TERMUX_APP__PACKAGE_NAME="alin.android.alinos"|' scripts/properties.sh
sed -i 's|^TERMUX__PREFIX_SUBDIR="usr"|TERMUX__PREFIX_SUBDIR="default"|' scripts/properties.sh
sed -i 's|^__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT="true"|__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT="false"|' scripts/properties.sh
# 若还没有目标目录，先建好（Docker 需挂载）：
sudo mkdir -p /data/data/alin.android.alinos

# 3. 编译（每架构各一次），deb 落在 ./output/
docker run --rm -v "$PWD:/home/builder/termux-packages" \
    ghcr.io/termux/package-builder \
    bash -lc "cd /home/builder/termux-packages && \
              ./build-package.sh -a aarch64 bash && \
              ./build-package.sh -a aarch64 openssh"

# 4. 解包 + 后处理 + 打包（deb → files/default/ → 替换 → 转软链 → tar）
bash scripts/rootfs/pack-rootfs.sh \
     --arch aarch64 --abi arm64 \
     --debs ./output \
     --outdir app/src

# 5. 清理，进入下一架构
rm -rf ./output
```

---

## 七、产物自检

```bash
f=app/src/arm64/assets/files.default.aarch64.tar.gz.so

# 条目类型：期望 只有 '-'（文件） 'd'（目录） 'l'（软链接），【无 h（硬链接）】
tar -tvzf "$f" | awk '{print substr($1,1,1)}' | sort | uniq -c

tar -tvzf "$f" | grep -c "com\.termux"   # 期望 0
tar -tzf  "$f" | head | sed 's/^/  /'    # 期望 files/default/...
```

---

## 八、常见问题

| 现象 | 原因 / 处理 |
|------|------------|
| 构建告警 usr-merge 格式 | `VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT` 未改为 `false` |
| 产物落在 `files/usr/` | `TERMUX__PREFIX_SUBDIR` 未改为 `default` |
| 包内仍有 `com.termux` | `replace-paths.sh` 未执行，或残留在二进制中（需重编） |
| 包内出现 `h` 硬链接 | 硬链接转换步骤未生效，检查 `pack-rootfs.sh` 步骤 2 |
| 包体异常大（数百 MB） | 打包时误加了 `-h`，软链接被展开成实体副本 |
| 解包后软链接悬空 | 绝对软链仍指向 `/data/data/com.termux/...`，替换规则未覆盖 |
| `dpkg-deb` 不存在 | 安装 dpkg（Debian/Ubuntu：`apt install dpkg`）；或用 `ar x` + `tar xf data.tar.*` 代替 |
| Docker 权限不足 | 将用户加入 `docker` 组，或用 `sudo` 运行 |
