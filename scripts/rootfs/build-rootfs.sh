#!/usr/bin/env bash
# ============================================================
#  AlinOs-for-android — proot rootfs (files.default.*.tar.gz.so) 构建脚本
# ------------------------------------------------------------
#  用途：从 termux-packages 源码构建【仅 bash + openssh 及其依赖】的
#        Termux PREFIX 用户态，输出四个架构的：
#            app/src/<abi>/assets/files.default.<abi>.tar.gz.so
#
#  产物路径重定位（改 properties.sh 后由官方流程自动生效）：
#        com.termux       →  alin.android.alinos
#        files/usr        →  files/default
#        即最终 PREFIX = /data/data/alin.android.alinos/files/default
#
#  关键认知（重要）：
#    termux-packages 的官方构建流程本身就会把文件"释放"到 $PREFIX：
#        make_install → PREFIX → massagedir → massage → deb
#    改了 properties.sh 之后，$PREFIX 即
#        /data/data/alin.android.alinos/files/default
#    所以官方释放出的 massagedir 就是我们要打包的对象，
#    **不需要**从 .deb 二次解包（deb 只是同一流程的顺带产物）。
#
#  执行模型：
#    所有重活（编译 + 替换 + 转软链 + tar）都在 Docker 容器内完成，
#    宿主只负责：改 properties.sh、起容器、docker cp 取最终 .so。
#    容器用 -d --name 常驻（对齐官方 run-docker.sh 的做法），
#    跑完不删可由 --keep-container 保留，便于 docker exec 进去排查。
#
#  运行环境要求：
#    - 必须在 **x86_64 Linux + Docker** 上运行（官方 builder 镜像仅 x86_64）。
#    - 本仓库开发机为 Android proot（无 Docker），**不能**在此运行本脚本。
#    - 首次运行会拉取 Docker 镜像与下载各包源码（数十 GB 磁盘）。
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
#      TARGET_PACKAGES        目标包（默认 "bash openssh"）
#      TERMUX_BUILDER_IMAGE   Docker 镜像（默认 ghcr.io/termux/package-builder）
#      KEEP_CONTAINER=1       保留容器（便于排查）
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
TERMUX_BUILDER_IMAGE="${TERMUX_BUILDER_IMAGE:-ghcr.io/termux/package-builder}"
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
read -r -a TARGET_PACKAGES <<< "${TARGET_PACKAGES:-bash openssh}"

ARCHS="${ARCHS:-aarch64 arm i686 x86_64}"
[ $# -gt 0 ] && ARCHS="$*"

log()  { echo -e "\n\033[1;34m[$(date '+%H:%M:%S')]\033[0m $*"; }
die()  { echo -e "\n\033[1;31m[错误]\033[0m $*" >&2; exit 1; }

log "构建配置"
echo "  应用包名      : $APP_PACKAGE"
echo "  PREFIX 子目录 : $PREFIX_SUBDIR"
echo "  目标包        : ${TARGET_PACKAGES[*]}"
echo "  架构          : $ARCHS"
echo "  Docker 镜像   : $TERMUX_BUILDER_IMAGE"
echo "  工作目录      : $WORKDIR"
echo "  输出目录      : $OUTDIR"

# ------------------------------------------------------------
# 前置检查
# ------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "未找到 docker。本脚本必须在 x86_64 Linux + Docker 环境运行。"
docker info >/dev/null 2>&1 || die "Docker 守护进程不可用（检查是否启动 / 当前用户是否有权限）。"
[ "$(uname -m)" = "x86_64" ] || die "本脚本需在 x86_64 主机运行（当前 $(uname -m)）。termux-packages 的 Docker 镜像仅提供 x86_64 构建器。"

for cmd in git tar gzip xz file; do
  command -v "$cmd" >/dev/null 2>&1 || die "缺少工具：$cmd"
done

mkdir -p "$WORKDIR"

# ------------------------------------------------------------
# 步骤 ①：获取并改造 termux-packages
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
#      → 连带 TERMUX_APP__DATA_DIR / TERMUX__ROOTFS / TERMUX__PREFIX
#   2) TERMUX__PREFIX_SUBDIR     usr               → default
#   3) __TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT  true → false
#      ↑ 参数 (2) 必须与 (3) 同时改，否则 usr-merge 校验会告警/报错
#
# 改完后官方流程会自动把文件释放到：
#   /data/data/alin.android.alinos/files/default
log "步骤 ①：改造 properties.sh（三处）"
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
log "properties.sh 改造完成（包名 / PREFIX / 校验 三处已就位）"

# ------------------------------------------------------------
# 步骤 ②~⑤：逐架构构建 → 容器内打包 → docker cp 取产物 → 清理
# ------------------------------------------------------------
PACKER="$SCRIPT_DIR/pack-rootfs.sh"
[ -f "$PACKER" ] || die "缺少打包脚本：$PACKER"

CTR="alin-rootfs-builder"

cleanup_container() {
  if [ "${KEEP_CONTAINER:-0}" = "1" ]; then
    log "保留容器 $CTR（KEEP_CONTAINER=1）"
    return
  fi
  docker rm -f "$CTR" >/dev/null 2>&1 || true
}
trap cleanup_container EXIT

for arch in $ARCHS; do
  abi="${ABI_MAP[$arch]:-}"
  [ -n "$abi" ] || die "未知架构：$arch（支持：${!ABI_MAP[*]}）"

  log "==================== 架构 $arch (ABI: $abi) ===================="
  ARCH_DIR="$WORKDIR/$arch"
  rm -rf "$ARCH_DIR"
  mkdir -p "$ARCH_DIR"

  # ----------------------------------------------------------
  # ② 起常驻容器（不用 --rm，对齐官方 run-docker.sh）
  # ----------------------------------------------------------
  log "步骤 ②-a：启动容器 $CTR"
  docker rm -f "$CTR" >/dev/null 2>&1 || true
  docker run -d \
    --init \
    --name "$CTR" \
    -v "$TP_DIR:/home/builder/termux-packages" \
    -v "$SCRIPT_DIR:/home/builder/alin-scripts:ro" \
    "$TERMUX_BUILDER_IMAGE" \
    bash -lc "sleep infinity" >/dev/null || die "容器启动失败"

  # ----------------------------------------------------------
  # ② 容器内编译：官方流程自动释放 files/default/ 并产出 deb
  # ----------------------------------------------------------
  log "步骤 ②-b：编译 ${TARGET_PACKAGES[*]}（$arch）"
  PKGS_CMD=""
  for p in "${TARGET_PACKAGES[@]}"; do
    PKGS_CMD+="./build-package.sh -a $arch $p || exit 1; "
  done
  docker exec "$CTR" bash -lc "
      set -e
      cd /home/builder/termux-packages
      $PKGS_CMD
    " || die "架构 $arch 编译失败（可 docker exec -it $CTR bash 进容器排查）"

  # 定位官方释放出的 massage 目录（每个包一个）
  log "步骤 ②-c：定位官方释放产物 massagedir"
  MASSAGE_LIST="$(docker exec "$CTR" bash -lc "ls -d /home/builder/.termux-build/*/massage 2>/dev/null || true")"
  [ -n "$MASSAGE_LIST" ] || die "容器内未找到 massagedir，编译可能未产出文件树"
  echo "$MASSAGE_LIST" | sed 's/^/      /'

  # ----------------------------------------------------------
  # ③④⑤ 容器内打包：替换路径 → 转软链 → tar
  #   把 massagedir 的 data/data/<pkg>/files/<subdir> 提升为干净树，
  #   然后调用 pack-rootfs.sh 完成 ③④⑤。
  # ----------------------------------------------------------
  log "步骤 ③④⑤：容器内打包"
  docker exec "$CTR" bash -lc "
      set -e
      STAGE=/home/builder/_alin_stage
      rm -rf \"\$STAGE\"; mkdir -p \"\$STAGE\"
      n=0
      for m in $MASSAGE_LIST; do
        src=\"\$m/data/data/$APP_PACKAGE/files/$PREFIX_SUBDIR\"
        [ -d \"\$src\" ] || { echo \"[跳过] 无 files/$PREFIX_SUBDIR: \$m\" >&2; continue; }
        mkdir -p \"\$STAGE/files/$PREFIX_SUBDIR\"
        # 多个包（bash/openssh/各依赖）合并进同一裸树
        ( cd \"\$src\" && tar -cf - . ) | ( cd \"\$STAGE/files/$PREFIX_SUBDIR\" && tar -xf - )
        n=\$((n+1))
      done
      echo \"    合并 \$n 个 massagedir → \$STAGE/files/$PREFIX_SUBDIR\"
      [ \$n -gt 0 ] || exit 1
    " || die "架构 $arch 合并 massagedir 失败"

  docker exec "$CTR" bash -lc "
      set -e
      bash /home/builder/alin-scripts/pack-rootfs.sh \
          --arch $arch \
          --abi $abi \
          --staging /home/builder/_alin_stage \
          --outdir /home/builder/_alin_out \
          --app-package $APP_PACKAGE \
          --prefix-subdir $PREFIX_SUBDIR
    " || die "架构 $arch 容器内打包失败"

  # ----------------------------------------------------------
  # ⑥ 取出最终产物 → 清容器/设备残留 → 下一架构
  # ----------------------------------------------------------
  log "步骤 ⑥：取产物并清理（$arch）"
  DEST_DIR="$OUTDIR/$abi/assets"
  mkdir -p "$DEST_DIR"
  docker cp "$CTR:/home/builder/_alin_out/$abi/assets/files.default.$arch.tar.gz.so" \
            "$DEST_DIR/files.default.$arch.tar.gz.so" \
    || die "docker cp 取产物失败"

  # 容器内临时目录清理（容器随后整体删除，这里只是保持整洁）
  docker exec "$CTR" bash -lc "rm -rf /home/builder/_alin_stage /home/builder/_alin_out" || true

  # 设备侧残留清理（仅当本脚本真的跑在设备上时生效；
  #   Docker 环境下该路径不存在，属预期）
  sudo rm -rf "/data/data/$APP_PACKAGE" 2>/dev/null \
    || rm -rf "/data/data/$APP_PACKAGE" 2>/dev/null || true

  # 宿主中间目录清理
  rm -rf "$ARCH_DIR"

  # 删容器，进入下一架构
  if [ "${KEEP_CONTAINER:-0}" = "1" ]; then
    log "保留容器 $CTR（KEEP_CONTAINER=1），下一架构将重建"
  fi
  docker rm -f "$CTR" >/dev/null 2>&1 || true
done

log "全部完成，产物："
for arch in $ARCHS; do
  abi="${ABI_MAP[$arch]}"
  f="$OUTDIR/$abi/assets/files.default.$arch.tar.gz.so"
  [ -f "$f" ] && printf "  %-10s %s\n" "$abi" "$(ls -lh "$f" | awk '{print $5}')  $f"
done
