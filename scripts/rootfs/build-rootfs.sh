#!/usr/bin/env bash
# ============================================================
#  AlinOs-for-android — proot rootfs (files.default.*.tar.gz.so) 构建脚本
# ------------------------------------------------------------
#  用途：从 termux-packages 源码构建【仅 bash + openssh 及其依赖】的
#        Termux PREFIX 用户态，输出四个架构的：
#            app/src/<abi>/assets/files.default.<abi>.tar.gz.so
#
#  产物路径重定位：
#        com.termux       →  alin.android.alinos
#        files/usr        →  files/default
#        即最终 PREFIX = /data/data/alin.android.alinos/files/default
#
#  运行环境要求（重要）：
#    - 必须在 **x86_64 Linux + Docker** 上运行（termux-packages 官方
#      构建流程依赖 Docker 镜像 ghcr.io/termux/package-builder）。
#    - 本仓库开发机为 Android proot（无 Docker），**不能**在此运行本脚本。
#    - 首次运行会拉取 Docker 镜像与下载各包源码，耗时较长（数十 GB 磁盘）。
#
#  用法：
#      bash scripts/rootfs/build-rootfs.sh                # 全部四架构
#      bash scripts/rootfs/build-rootfs.sh aarch64        # 指定架构
#      ARCHS="aarch64 x86_64" bash scripts/rootfs/build-rootfs.sh
#
#  环境变量：
#      TERMUX_PACKAGES_REPO   自定义 termux-packages 仓库地址
#      WORKDIR                工作目录（默认 /tmp/alin-rootfs-build）
#      OUTDIR                 产物输出目录（默认 <repo>/app/src）
#      SKIP_FETCH=1           跳过克隆/更新 termux-packages
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ------------------------------------------------------------
# 配置
# ------------------------------------------------------------
APP_PACKAGE="alin.android.alinos"          # 目标 Android 包名
PREFIX_SUBDIR="default"                    # files/ 下的子目录名（默认 usr）
TERMUX_PACKAGES_REPO="${TERMUX_PACKAGES_REPO:-https://github.com/termux/termux-packages.git}"
WORKDIR="${WORKDIR:-/tmp/alin-rootfs-build}"
OUTDIR="${OUTDIR:-$REPO_ROOT/app/src}"

# termux-packages 架构名 → 本仓库 ABI 目录名
#   aarch64 → arm64      arm → arm
#   i686    → i686       x86_64 → x86_64
declare -A ABI_MAP=(
  [aarch64]="arm64"
  [arm]="arm"
  [i686]="i686"
  [x86_64]="x86_64"
)
# 目标包：仅这两个（其余依赖由 dpkg 依赖图自动带上）
TARGET_PACKAGES=("bash" "openssh")

ARCHS="${ARCHS:-aarch64 arm i686 x86_64}"
[ $# -gt 0 ] && ARCHS="$*"

log()  { echo -e "\n\033[1;34m[$(date '+%H:%M:%S')]\033[0m $*"; }
die()  { echo -e "\n\033[1;31m[错误]\033[0m $*" >&2; exit 1; }

log "构建配置"
echo "  应用包名      : $APP_PACKAGE"
echo "  PREFIX 子目录 : $PREFIX_SUBDIR"
echo "  目标包        : ${TARGET_PACKAGES[*]}"
echo "  架构          : $ARCHS"
echo "  工作目录      : $WORKDIR"
echo "  输出目录      : $OUTDIR"

# ------------------------------------------------------------
# 前置检查
# ------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "未找到 docker。本脚本必须在 x86_64 Linux + Docker 环境运行。"
docker info >/dev/null 2>&1 || die "Docker 守护进程不可用（检查是否启动 / 当前用户是否有权限）。"
[ "$(uname -m)" = "x86_64" ] || die "本脚本需在 x86_64 主机运行（当前 $(uname -m)）。termux-packages 的 Docker 镜像仅提供 x86_64 构建器。"

for cmd in git tar gzip xz file python3; do
  command -v "$cmd" >/dev/null 2>&1 || die "缺少工具：$cmd"
done

mkdir -p "$WORKDIR"

# ------------------------------------------------------------
# 步骤 1：获取并改造 termux-packages
# ------------------------------------------------------------
TP_DIR="$WORKDIR/termux-packages"

if [ "${SKIP_FETCH:-0}" != "1" ]; then
  if [ -d "$TP_DIR/.git" ]; then
    log "更新 termux-packages"
    git -C "$TP_DIR" fetch --depth 1 origin HEAD
    git -C "$TP_DIR" reset --hard FETCH_HEAD
  else
    log "克隆 termux-packages（浅克隆）"
    git clone --depth 1 "$TERMUX_PACKAGES_REPO" "$TP_DIR"
  fi
fi
[ -d "$TP_DIR/scripts" ] || die "termux-packages 目录无效：$TP_DIR"

PROPS="$TP_DIR/scripts/properties.sh"
[ -f "$PROPS" ] || die "找不到 $PROPS"

# 一次性改【三处】（这三处都是硬赋值，不能用环境变量覆盖）：
#   1) TERMUX_APP__PACKAGE_NAME  com.termux        → alin.android.alinos
#   2) TERMUX__PREFIX_SUBDIR     usr               → default
#   3) __TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT  true → false
#      ↑ 参数 (2) 必须与 (3) 同时改，否则 usr-merge 校验会告警/报错
log "改造 properties.sh（三处）"
cp -f "$PROPS" "$PROPS.orig"

# 1) 应用包名
sed -i "s|^TERMUX_APP__PACKAGE_NAME=\"com.termux\"|TERMUX_APP__PACKAGE_NAME=\"$APP_PACKAGE\"|" "$PROPS"
# 2) PREFIX 子目录 usr → default
sed -i "s|^TERMUX__PREFIX_SUBDIR=\"usr\"|TERMUX__PREFIX_SUBDIR=\"$PREFIX_SUBDIR\"|" "$PROPS"
# 3) 关闭 usr-merge 校验
sed -i 's|^__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT="true"|__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT="false"|' "$PROPS"

echo "  APP_PACKAGE_NAME   : $(grep -m1 '^TERMUX_APP__PACKAGE_NAME=' "$PROPS")"
echo "  PREFIX_SUBDIR      : $(grep -m1 '^TERMUX__PREFIX_SUBDIR=' "$PROPS")"
echo "  USR_MERGE_VALIDATE : $(grep -m1 '^__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT=' "$PROPS")"

# 校验改造是否生效
grep -q "^TERMUX_APP__PACKAGE_NAME=\"$APP_PACKAGE\""   "$PROPS" || die "APP_PACKAGE_NAME 改造失败"
grep -q "^TERMUX__PREFIX_SUBDIR=\"$PREFIX_SUBDIR\"" "$PROPS" || die "PREFIX_SUBDIR 改造失败"
grep -q '^__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT="false"' "$PROPS" || die "usr-merge 校验开关改造失败"
log "termux-packages 改造完成（包名/PREFIX/校验 三处已就位）"

# ------------------------------------------------------------
# 步骤 2：逐架构构建 → 产物直接落盘 → 打包 → 清理
# ------------------------------------------------------------
# 编译时 PREFIX 即为 /data/data/alin.android.alinos/files/default，
# 因此产物【直接生成在目标路径】，无需 deb、无需解包。
PACKER="$SCRIPT_DIR/pack-rootfs.sh"
[ -f "$PACKER" ] || die "缺少打包脚本：$PACKER"

for arch in $ARCHS; do
  abi="${ABI_MAP[$arch]:-}"
  [ -n "$abi" ] || die "未知架构：$arch（支持：${!ABI_MAP[*]}）"

  log "==================== 架构 $arch (ABI: $abi) ===================="
  STAGING="$ARCH_DIR/rootfs"          # 编译产物落盘根目录
  rm -rf "$ARCH_DIR"; mkdir -p "$STAGING"

  # --- 2.1 构建（仅 bash + openssh，依赖自动带上）---
  # 官方流程会产出 deb；我们不用 dpkg 安装机制，
  # 只在 2.3 把 deb 的内容解包到目标路径 files/default/。
  log "构建 ${TARGET_PACKAGES[*]} ($arch)"
  DEB_OUT="$ARCH_DIR/debs"
  mkdir -p "$DEB_OUT"
  if [ "${IN_DOCKER:-1}" = "1" ]; then
    (
      cd "$TP_DIR"
      docker run --rm \
        -v "$TP_DIR:/home/builder/termux-packages" \
        -v "$DEB_OUT:/home/builder/termux-packages/output" \
        ghcr.io/termux/package-builder \
        bash -lc "cd /home/builder/termux-packages && \
          for p in ${TARGET_PACKAGES[*]}; do \
            ./build-package.sh -a $arch \$p || exit 1; \
          done"
    ) || die "架构 $arch 构建失败"
  else
    ( cd "$TP_DIR" && for p in ${TARGET_PACKAGES[*]}; do \
        ./build-package.sh -a "$arch" "$p" || exit 1; done ) \
      || die "架构 $arch 构建失败"
    # 直接构建时，deb 默认落在 termux-packages/output/
    [ -d "$TP_DIR/output" ] && cp -a "$TP_DIR/output/"*.deb "$DEB_OUT/" 2>/dev/null || true
  fi

  shopt -s nullglob
  built=( "$DEB_OUT"/*.deb )
  shopt -u nullglob
  [ ${#built[@]} -gt 0 ] || die "架构 $arch 未产出任何 deb（$DEB_OUT）"
  log "编译产出 ${#built[@]} 个 deb"

  # --- 2.2 + 2.3：解包到目标路径 → 替换 → 硬链接转软链 → tar ---
  log "解包与打包：$arch"
  bash "$PACKER" \
      --arch "$arch" \
      --abi "$abi" \
      --debs "$DEB_OUT" \
      --outdir "$OUTDIR" \
      --app-package "$APP_PACKAGE" \
      --prefix-subdir "$PREFIX_SUBDIR"

  # --- 2.4 清理，避免影响下一架构 ---
  log "清理架构 $arch 的中间产物"
  rm -rf "$ARCH_DIR"
  sudo rm -rf "/data/data/$APP_PACKAGE" 2>/dev/null \
    || rm -rf "/data/data/$APP_PACKAGE" 2>/dev/null || true
done

log "全部完成，产物："
for arch in $ARCHS; do
  abi="${ABI_MAP[$arch]}"
  f="$OUTDIR/$abi/assets/files.default.$arch.tar.gz.so"
  [ -f "$f" ] && printf "  %-10s %s\n" "$abi" "$(ls -lh "$f" | awk '{print $5}')  $f"
done
