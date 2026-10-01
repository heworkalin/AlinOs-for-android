#!/usr/bin/env bash
# ============================================================
#  AlinOs-for-android — rootfs 打包脚本
# ------------------------------------------------------------
#  前提：在构建机上已完成 termux-packages 编译，产物直接落在
#        设备目标路径 <staging>/files/default/ 下（不改用 deb）。
#
#  流程（严格五步）：
#    1. 从编译产出的 deb 解包，落到 staging/files/<subdir>/
#       （官方最终也是把内容解到这个目标路径再打包）
#    2. 批量替换文件树内硬编码的旧路径（replace-paths.sh）
#    3. 硬链接 → 软链接（保证包体内只有软链接，无硬链接）
#    4. tar 打包为 files.default.<arch>.tar.gz.so
#
#  用法：
#    bash scripts/rootfs/pack-rootfs.sh \
#         --arch aarch64 --abi arm64 --debs <deb目录> --outdir <输出目录>
#
#  参数：
#    --debs <目录>    编译产出的 .deb 目录（termux-packages/output）
#    --staging <目录> 可选：已解包好的产物根（含 files/<subdir>/）；
#                     给了 --staging 则跳过 deb 解包（直接后处理）
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
[ -n "$STAGING" ] || [ -n "$DEBS_DIR" ] || die "需要 --debs（从 deb 解包）或 --staging（已解包产物）"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEW_ROOT="/data/data/$APP_PACKAGE/files"
NEW_PREFIX="$NEW_ROOT/$PREFIX_SUBDIR"

# 默认 staging：从 deb 解包到临时目录
TMPROOT=""
if [ -z "$STAGING" ]; then
  TMPROOT="$(mktemp -d "${TMPDIR:-/tmp}/alin-stage.XXXXXX")"
  [ "$KEEP" = "1" ] || trap 'rm -rf "$TMPROOT"' EXIT
  STAGING="$TMPROOT"
  [ -d "$DEBS_DIR" ] || die "deb 目录不存在：$DEBS_DIR"
fi

# ------------------------------------------------------------
# 步骤 0：从 deb 解包，内容落到 files/<subdir>/（目标路径）
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

# 定位 PREFIX 目录
if [ -d "$STAGING/files/$PREFIX_SUBDIR" ]; then
  PREFIX_DIR="$STAGING/files/$PREFIX_SUBDIR"
elif [ -d "$STAGING/$PREFIX_SUBDIR" ]; then
  log "归一化目录结构：$STAGING/$PREFIX_SUBDIR → $STAGING/files/$PREFIX_SUBDIR"
  mkdir -p "$STAGING/files"
  mv "$STAGING/$PREFIX_SUBDIR" "$STAGING/files/$PREFIX_SUBDIR"
  PREFIX_DIR="$STAGING/files/$PREFIX_SUBDIR"
else
  die "在 $STAGING 下找不到 files/$PREFIX_SUBDIR（编译产物路径不符合预期）"
fi

echo "============================================================"
echo " rootfs 打包：arch=$ARCH abi=$ABI"
echo "   产物源目录 : $STAGING"
echo "   PREFIX     : $PREFIX_DIR"
echo "   输出       : $OUTDIR/$ABI/assets/files.default.$ARCH.tar.gz.so"
echo "   目标 PREFIX: $NEW_PREFIX"
echo "============================================================"

# ------------------------------------------------------------
# 步骤 1：批量替换硬编码路径
# ------------------------------------------------------------
log "步骤 1/3：批量替换硬编码路径"
[ -f "$SCRIPT_DIR/replace-paths.sh" ] || die "缺少 replace-paths.sh"
bash "$SCRIPT_DIR/replace-paths.sh" "$STAGING" \
    --old-package com.termux \
    --new-package "$APP_PACKAGE" \
    --old-subdir  usr \
    --new-subdir  "$PREFIX_SUBDIR"

# ------------------------------------------------------------
# 步骤 2：硬链接 → 软链接
# ------------------------------------------------------------
# GNU tar 无“硬链接转软链接”原生参数（-h 是软链→实体，
# --hard-dereference 是硬链→实体副本），必须在打包前自行转换：
# 同一 inode 只保留一个实体，其余改为指向实体的【相对】软链接。
log "步骤 2/3：硬链接 → 软链接"
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
# 步骤 3：tar 打包
# ------------------------------------------------------------
# 关键：**不能**加 -h（会把软链接展开成实体副本）。
log "步骤 3/3：tar 打包"
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
