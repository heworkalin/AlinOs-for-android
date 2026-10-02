#!/usr/bin/env bash
# ============================================================
#  AlinOs-for-android — rootfs 打包脚本
# ------------------------------------------------------------
#  设计定位：本脚本在 **Docker 容器内** 执行（容器内有 python3/tar/find，
#            权限干净，无需 chown）。宿主只负责 docker cp 取走最终产物。
#
#  前提：termux-packages 官方构建流程已跑完。官方流程本身就会把文件
#        "释放"到 $PREFIX，即：
#
#          termux_step_make_install        → $PREFIX
#          termux_step_copy_into_massagedir→ $MASSAGEDIR/$PREFIX_CLASSICAL
#          termux_step_massage             → strip/shebang/硬链检查/子包
#          termux_step_create_debian_package → output/*.deb（顺带产出）
#
#        因为 properties.sh 已改成 alin.android.alinos + default，$PREFIX 即
#        /data/data/alin.android.alinos/files/default，所以官方释放出的
#        **massage 目录**就是我们真正要打包的对象，**不需要**从 deb 二次解包。
#
#  流程（③④⑤，与 README 一致）：
#    ③ 批量替换文件树内硬编码的旧路径（replace-paths.sh）
#    ④ 硬链接 → 软链接（保证包体内只有软链接，无硬链接）
#    ⑤ tar 打包为 files.default.<arch>.tar.gz.so
#
#  用法（容器内）：
#    bash pack-rootfs.sh --arch aarch64 --abi arm64 \
#         --staging <massagedir的files根> --outdir <输出目录>
#
#  参数：
#    --staging <目录> 【首选】官方流程释放好的产物根（含 files/<subdir>/）
#                     典型值：~/.termux-build/<pkg>/massage
#    --debs <目录>    【兜底】仅当没有 staging 时，从 .deb 解出文件树
#
#  可选：
#    --app-package    目标包名（默认 alin.android.alinos）
#    --prefix-subdir  PREFIX 子目录（默认 default）
#    --keep           保留临时工作目录
# ============================================================
set -euo pipefail

ARCH=""; ABI=""; STAGING=""; OUTDIR=""; DEBS_DIR=""
APP_PACKAGE="alin.android.alinos"
PREFIX_SUBDIR="default"
KEEP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --arch)          ARCH="$2"; shift 2;;
    --abi)           ABI="$2"; shift 2;;
    --debs)          DEBS_DIR="$2"; shift 2;;
    --staging)       STAGING="$2"; shift 2;;
    --outdir)        OUTDIR="$2"; shift 2;;
    --app-package)   APP_PACKAGE="$2"; shift 2;;
    --prefix-subdir) PREFIX_SUBDIR="$2"; shift 2;;
    --keep)          KEEP=1; shift;;
    -h|--help)       sed -n '2,26p' "$0"; exit 0;;
    *) echo "未知参数：$1" >&2; exit 1;;
  esac
done

log() { echo -e "\033[1;36m  →\033[0m $*"; }
die() { echo -e "\033[1;31m[错误]\033[0m $*" >&2; exit 1; }

[ -n "$ARCH" ]   || die "缺少 --arch"
[ -n "$ABI" ]    || die "缺少 --abi"
[ -n "$OUTDIR" ] || die "缺少 --outdir"
[ -n "$STAGING" ] || [ -n "$DEBS_DIR" ] || die "需要 --staging（官方释放产物，首选）或 --debs（兜底）"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEW_ROOT="/data/data/$APP_PACKAGE/files"
NEW_PREFIX="$NEW_ROOT/$PREFIX_SUBDIR"

# 兜底路径：没有 --staging 时才从 deb 解包到临时目录
TMPROOT=""
if [ -z "$STAGING" ]; then
  log "未提供 --staging，回退到 deb 兜底路径"
  TMPROOT="$(mktemp -d "${TMPDIR:-/tmp}/alin-stage.XXXXXX")"
  [ "$KEEP" = "1" ] || trap 'rm -rf "$TMPROOT"' EXIT
  STAGING="$TMPROOT"
  [ -d "$DEBS_DIR" ] || die "deb 目录不存在：$DEBS_DIR"
fi

# ------------------------------------------------------------
# 【兜底】从 deb 解包，内容落到 files/<subdir>/
#   仅在未提供 --staging（官方释放产物）时使用。
#   注意：这不是主路径，主路径是官方流程已经释放好的 files/<subdir>/。
# ------------------------------------------------------------
if [ -n "$DEBS_DIR" ]; then
  shopt -s nullglob
  debs=("$DEBS_DIR"/*.deb)
  shopt -u nullglob
  [ ${#debs[@]} -gt 0 ] || die "$DEBS_DIR 下没有 .deb"

  declare -A seen
  for deb in "${debs[@]}"; do
    pkgname="$(basename "$deb" | sed -E 's/^([a-zA-Z0-9+._-]+)_.*/\1/')"
    seen["$pkgname"]="$deb"
  done

  EXTRACT_ROOT="$STAGING/files/$PREFIX_SUBDIR"
  mkdir -p "$EXTRACT_ROOT"
  n=0
  for pkgname in "${!seen[@]}"; do
    deb="${seen[$pkgname]}"
    # deb 内路径相对 PREFIX（./bin/...），直接解到 PREFIX 根
    dpkg-deb -x "$deb" "$EXTRACT_ROOT" 2>/dev/null \
      || dpkg-deb --extract "$deb" "$EXTRACT_ROOT" \
      || die "解包失败：$deb"
    n=$((n+1))
  done
  log "已从 deb 解包 $n 个包 → $EXTRACT_ROOT"

  # 清理可能混入的 deb 控制目录/夹（正常不会出现，保险）
  rm -rf "$EXTRACT_ROOT/DEBIAN" "$EXTRACT_ROOT/debian-binary" 2>/dev/null || true
fi

# ------------------------------------------------------------
# 归一化：把 STAGING 变成一棵只含 files/<subdir>/ 的干净树
#   后续 ③④⑤ 三步统一对 $STAGING 整树操作，因此先收敛目录形状。
#   典型输入（--staging 指向官方 massage 目录）：
#     <massagedir>/data/data/<pkg>/files/<subdir>/
#   归一化后：
#     <staging>/files/<subdir>/
# ------------------------------------------------------------
if [ -d "$STAGING/files/$PREFIX_SUBDIR" ]; then
  : # 已是目标形状
elif [ -d "$STAGING/$PREFIX_SUBDIR" ]; then
  log "归一化：$STAGING/$PREFIX_SUBDIR → $STAGING/files/$PREFIX_SUBDIR"
  mkdir -p "$STAGING/files"
  mv "$STAGING/$PREFIX_SUBDIR" "$STAGING/files/$PREFIX_SUBDIR"
elif [ -d "$STAGING/$NEW_PREFIX" ]; then
  # massage 目录：<massagedir>/data/data/<pkg>/files/<subdir>
  log "识别为 massage 目录，提升 files/$PREFIX_SUBDIR"
  mkdir -p "$STAGING/_lift"
  mv "$STAGING/$NEW_PREFIX" "$STAGING/_lift/$PREFIX_SUBDIR"
  rm -rf "$STAGING/files"
  mv "$STAGING/_lift" "$STAGING/files"
else
  die "在 $STAGING 下找不到 files/$PREFIX_SUBDIR 或 $NEW_PREFIX（官方释放产物路径不符合预期）"
fi
PREFIX_DIR="$STAGING/files/$PREFIX_SUBDIR"

echo "============================================================"
echo " rootfs 打包：arch=$ARCH abi=$ABI"
echo "   产物源目录 : $STAGING"
echo "   归一化后   : $PREFIX_DIR"
echo "   输出       : $OUTDIR/$ABI/assets/files.default.$ARCH.tar.gz.so"
echo "   目标 PREFIX: $NEW_PREFIX"
echo "============================================================"

# ------------------------------------------------------------
# 步骤 ③：批量替换硬编码路径
# ------------------------------------------------------------
log "步骤 ③/⑤：批量替换硬编码路径"
[ -f "$SCRIPT_DIR/replace-paths.sh" ] || die "缺少 replace-paths.sh"
bash "$SCRIPT_DIR/replace-paths.sh" "$STAGING" \
    --old-package com.termux \
    --new-package "$APP_PACKAGE" \
    --old-subdir  usr \
    --new-subdir  "$PREFIX_SUBDIR"

# ------------------------------------------------------------
# 步骤 ④：硬链接 → 软链接
# ------------------------------------------------------------
# GNU tar 无“硬链接转软链接”原生参数（-h 是软链→实体，
# --hard-dereference 是硬链→实体副本），必须在打包前自行转换：
# 同一 inode 只保留一个实体，其余改为指向实体的【相对】软链接。
log "步骤 ④/⑤：硬链接 → 软链接"
python3 - "$STAGING" <<'PYEOF'
import os, sys, collections

staging = sys.argv[1]
inodes = collections.defaultdict(list)
for dirpath, _dirnames, filenames in os.walk(staging):
    for name in filenames:
        p = os.path.join(dirpath, name)
        if os.path.islink(p):
            continue
        try:
            st = os.lstat(p)
        except OSError:
            continue
        if os.path.isfile(p):
            inodes[(st.st_dev, st.st_ino)].append(p)

converted = 0
for _key, paths in inodes.items():
    if len(paths) <= 1:
        continue
    paths.sort()
    canonical = paths[0]
    for dup in paths[1:]:
        rel = os.path.relpath(canonical, os.path.dirname(dup))
        os.remove(dup)
        os.symlink(rel, dup)
        converted += 1
print(f"    转换 {converted} 个硬链接为软链接")
PYEOF

LEFT=$(find "$STAGING" -type f -links +1 2>/dev/null | wc -l)
[ "$LEFT" = "0" ] && echo "    [通过] 已无硬链接" || echo "    [警告] 仍有 $LEFT 个文件 link count > 1"

# ------------------------------------------------------------
# 步骤 ⑤：tar 打包
# ------------------------------------------------------------
# 关键：**不能**加 -h（会把软链接展开成实体副本）。
log "步骤 ⑤/⑤：tar 打包"
# outdir 可能为相对路径，而 tar 会在子 shell 中 cd 到 staging，
# 因此先转为绝对路径，避免路径失效。
mkdir -p "$OUTDIR"
OUTDIR_ABS="$(cd "$OUTDIR" && pwd)"
DEST_DIR="$OUTDIR_ABS/$ABI/assets"
DEST="$DEST_DIR/files.default.$ARCH.tar.gz.so"
mkdir -p "$DEST_DIR"

( cd "$STAGING" && tar \
    --format=gnu \
    --owner=0 --group=0 --numeric-owner \
    --sort=name \
    -czf "$DEST" files )

log "完成：$(ls -lh "$DEST" | awk '{print $5}')"

# ------------------------------------------------------------
# 自检
# ------------------------------------------------------------
log "产物自检"
tar -tvzf "$DEST" 2>/dev/null | awk '{print substr($1,1,1)}' | sort | uniq -c | \
  awk '{printf "      %s×%s\n", $2, $1}' 
HARD=$(tar -tvzf "$DEST" 2>/dev/null | awk '$1 ~ /^h/' | wc -l)
[ "$HARD" = "0" ] && echo "    [通过] 包体内无硬链接" || echo "    [警告] 包体内仍有 $HARD 个硬链接"
LEFTOVER=$(tar -tvzf "$DEST" 2>/dev/null | grep -c "com\.termux" || true)
[ "$LEFTOVER" = "0" ] && echo "    [通过] 无 com.termux 残留" || echo "    [警告] 仍有 $LEFTOVER 条 com.termux"
echo "    顶层结构："
tar -tzf "$DEST" 2>/dev/null | head -3 | sed 's/^/      /'
echo "  (- 普通文件  d 目录  l 软链接  h 硬链接)"

if [ "$KEEP" != "1" ]; then
  :
fi
