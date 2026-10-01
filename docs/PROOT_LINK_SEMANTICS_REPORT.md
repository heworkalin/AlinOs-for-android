# proot 链接语义 — 修复与残余限制报告

> 最后更新：2026-10-01
> 适用：AlinOs-for-Android（内嵌 Proot Ubuntu noble）

## 一、结论摘要

| 维度 | 状态 |
|------|:----:|
| `apt` / `dpkg` 安装含硬链接的包（perl 等） | ✅ 正常 |
| 普通文件硬链接（`ln`、`cp -l`、`tar`） | ✅ 正常 |
| 普通符号链接（`ln -s`、读写、rename、unlink） | ✅ 正常 |
| 伪硬链接的 `chown` / `open` / 读写 | ✅ 正常 |
| 对符号链接做硬链接（`ln <symlink> new`） | ⚠️ 不再损坏数据，但部分语义与原生 Linux 有差异（见第四节） |

**总体判断：绝大多数软件（包管理、构建工具、脚本运行时）不受影响。** 残余差异集中在
「硬链接 × 符号链接」交叉这一边缘场景。

---

## 二、本次修复的问题

### 1. 伪硬链接 `chown`/`open`/`read` 报 `ENOENT`（主问题）

**根因**：`PROOT_L2S_DIR` 放在 rootfs **外面**（`files/proot_l2s`）。proot 的
`canonicalize()`（`src/path/canon.c`）解析伪硬链接时，会把 backing 的宿主路径
当 guest 路径重新规范化；不在 rootfs 内时 `detranslate_path()` 剥不掉前缀，
`substitute_binding_stat()` 直接返回 `-ENOENT`。更糟的是它在 `link2symlink`
之前发生，导致"这是硬链接"的替换逻辑根本没机会执行。

**修复**：`PROOT_L2S_DIR` 放入 rootfs 内部 —— `<rootfs>/.l2s`
（与 proot 官方 `tests/test-8d3c07f5.sh` 一致）。解压 / 运行时 exec / 运行时 PTY
三路统一走 `ProotContainerManager.l2sDir()`。

**影响**：这是 `apt install perl` 报
`error setting ownership of '...perl5.38.2.dpkg-new': No such file or directory`
的根因。

### 2. `ln` 普通符号链接时 backing 名错乱（`xxx0001`）

**根因**：`move_and_symlink_path()` 的 `S_ISLNK(original)` 分支把
`readlink(original)` 的结果当作 intermediate。对普通用户符号链接，
链接目标 `y` 不以 `.l2s.` 开头，`first_link` 保持 1，随后
`sprintf(new_intermediate, "%s%04d", intermediate, 1)` 拼出 `y0001`，
并 `l2s_rename()` 改写了原链接本身。

**修复**：非伪造硬链接（目标不以 `.l2s.` 开头）的符号链接，必须像普通文件一样
**用 original 的 basename** 构造 intermediate。

### 3. 配套修复

| 问题 | 修复 |
|------|------|
| Android `/data` 上 f2fs workaround 误报，`readdir` 判定误杀刚创建的文件 | 设置 `PROOT_F2FS_WORKAROUND=0` |
| rootfs 缺 `/tmp` 导致 `canonicalize("/tmp/...")` 直接 `-ENOENT` | 启动时保证 `<rootfs>/tmp` 存在 |
| 解压 proot 的多个环境变量被 `Runtime.exec` 的 `envp` 整体替换掉 | 改用 `ProcessBuilder.environment()` 叠加 |
| `link2symlink` 的 `PROOT_L2S_DIR` 未解析软链接 | 源码补丁：`get_l2s_directory()` 增加 `realpath()` |

---

## 三、验证结果（原生 Linux 语义对照）

测试环境：容器内 `bash`，GNU coreutils。

### 通过 ✅

| 用例 | 结果 |
|------|------|
| `touch original; ln original hard1; ln original hard2` | 三者同 inode，nlink=3 |
| `ln -s original sym` | 独立 inode，`-> original` |
| `ln -L sym new`（单层符号链接 → 普通文件） | new 为普通文件，与 original 同 inode，sym 未变，original nlink+1 |
| `ln <硬链接> <新名>` | 同 inode，nlink 递增 |
| `rm` 硬链接 / 符号链接 | 目标保留，nlink 正确递减 |
| `mv` 硬链接 / 符号链接 | inode、target 字符串不变 |
| 跨目录硬链接 `ln original subdir/x` | 同 inode，无 ENOENT / 无越界 |
| `ln -L` 悬空、循环链接 | 正确失败（ENOENT / ELOOP），不创建文件 |
| `apt install -y perl` / 重装 | 退出码 0 |

### 残余差异 ⚠️

| 用例 | 原生 Linux | AlinOs(proot) | 影响 |
|------|-----------|---------------|------|
| `ln -L sym2 new`（`sym2→sym1→hard1→original`） | new 穿透到 original，同 inode | 只解一层，未穿透 | 极低 |
| `ln -P sym sym_hard`（对符号链接建硬链接） | `sym_hard` 是符号链接（`[[ -L ]]` 为真） | inode/readlink 正确，但 `lstat` 报普通文件 | 极低 |
| `ln -P` 悬空/循环符号链接 | 新名是符号链接 | 同上：模式判断不准 | 极低 |

**根因**：proot 并非真的建硬链接，而是「把目标搬进 `.l2s` + 用符号链接伪装 +
改写 `stat` 结果」。当目标本身是符号链接时，需要一个名字同时伪装「硬链接计数」
和「链接模式」，这一步 upstream 没做完整。

---

## 四、影响评估

**不受影响（覆盖绝大多数真实软件）**：

- `apt` / `dpkg` / `dpkg-deb`：包内硬链接都是**普通文件**之间的；
- `tar` / `cp` / `install` / `rsync` / `git` / `make` / shell 脚本；
- 常规符号链接操作（`ln -s`、读写、`mv`、`rm`）；
- 对**普通文件**做硬链接。

**可能受影响（罕见）**：

- 显式对符号链接做硬链接并依赖 `lstat` 报 `S_IFLNK`（如某些备份/去重工具）；
- 依赖 `ln -L` 穿透多层符号链接链。

这两类在 Ubuntu 包安装与日常开发中基本不会出现。

---

## 五、涉及文件与提交

### 提交（`main`）

```
2d6f95e fix(proot): ln 普通符号链接不再用链接目标名构造 backing
9a041f5 fix(prootfs): 区分用户符号链接与 proot 伪硬链接
5ec879c feat(tools): read/write/edit 全面对齐 pi
a210985 feat(prootfs): read 目录返回警告、content 改为原始内容
33240f2 fix(prootfs): 伪硬链接对外隐藏内部 backing 路径
ed954a4 feat(prootfs): read 空内容返回英文警告
375ca3b fix(prootfs): .l2s.* 内部文件不再返回 link_warning
263e49b fix(prootfs): 解析宿主绝对路径符号链接目标
6fb0ab7 fix(proot): 修复伪硬链接 chown/open 报 ENOENT
```

### proot 源码补丁（仓库外，构建卷 `/build/src/proot`）

| 文件 | 补丁 |
|------|------|
| `src/extension/link2symlink/link2symlink.c` | ① `get_l2s_directory()` 增加 `realpath()`；② `move_and_symlink_path()` 非伪造硬链接用原路径名构造 backing |
| `src/extension/fake_id0/chown.c` | meta 不存在时不再放行真 chown（`#ifdef USERLAND`，当前构建未启用，保留备用） |

重编四架构见 `docs/PROOT_HARDLINK_FIX.md`。

---

## 六、复现 / 回归测试建议

安装新 APK 后**强停 App**、进容器执行：

```sh
# 基础链路
mkdir -p /tmp/lt && cd /tmp/lt
echo data > original
ln original hard1                       # 普通文件硬链接
ln -s original sym                      # 符号链接
cat sym; chown 0:0 hard1; echo "chown=$?"   # 读写 + chown 应成功

# 关键回归：对符号链接做 ln，不得出现 xxx0001、不得改写 sym
ln sym sym_hard
ls -li                                   # sym 与 sym_hard 同 inode，target 不变
readlink sym sym_hard                    # 都是 original

# 包管理回归
apt install --reinstall -y perl; echo "apt=$?"
```

预期：无 `0001` 后缀，`sym` 未被改写，`chown`/`apt` 退出码 0。

---

## 七、后续可选（未做）

- **A（小，可选）**：修 `handle_sysexit_end` 的 stat 伪造，让"硬链接到符号链接"
  时正确回填 `S_IFLNK`（解决第三节的 `[[ -L ]]` 判断）。
- **B（大，不建议）**：让 `ln -L` 递归穿透 + 完整 POSIX 语义，等于重写
  link2symlink 的符号链接处理，回归风险高、收益低。
