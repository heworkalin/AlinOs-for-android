#!/usr/bin/env bash
# ============================================================
#  AlinOs-for-android — rootfs 路径批量替换工具（独立可用）
# ------------------------------------------------------------
#  作用：扫描已解包（或已挂载）的 Termux PREFIX 文件树，把其中
#        硬编码的旧 Termux 路径强制替换为 AlinOs 的路径。
#
#  替换规则（长路径优先）：
#      /data/data/com.termux/files/usr   →  /data/data/alin.android.alinos/files/default
#      /data/data/com.termux/files/home  →  /data/data/alin.android.alinos/files/home
#      /data/data/com.termux/files       →  /data/data/alin.android.alinos/files
#      /data/data/com.termux/cache       →  /data/data/alin.android.alinos/cache
#      /data/data/com.termux             →  /data/data/alin.android.alinos
#      @TERMUX_PREFIX@                   →  /data/data/alin.android.alinos/files/default
#      @TERMUX_HOME@                     →  /data/data/alin.android.alinos/files/home
#      com.termux                        →  alin.android.alinos
#
#  用法：
#     bash scripts/rootfs/replace-paths.sh <目标目录> [选项]
#
#  选项：
#     --old-package <名>    旧包名（默认 com.termux）
#     --new-package <名>    新包名（默认 alin.android.alinos）
#     --old-subdir  <名>    旧 PREFIX 子目录（默认 usr）
#     --new-subdir  <名>    新 PREFIX 子目录（默认 default）
#     --dry-run             只报告，不写入
#     --binary              同时处理二进制文件（默认仅文本，需自行确认安全）
#     --backup              写入前对每个文件生成 .bak 备份
#
#  说明：
#     · 默认只处理"文本文件"（无 NUL 字节且大小 ≤ 64MB），避免破坏
#       二进制。脚本类硬编码（bin/pkg、termux-*、profile.d/*）都在文本范围。
#     · 替换是幂等的：已是新路径的文件不会被再次改写。
# ============================================================
set -euo pipefail

TARGET_DIR=""
OLD_PKG="com.termux"
NEW_PKG="alin.android.alinos"
OLD_SUBDIR="usr"
NEW_SUBDIR="default"
DRY_RUN=0
DO_BINARY=0
DO_BACKUP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --old-package) OLD_PKG="$2"; shift 2;;
    --new-package) NEW_PKG="$2"; shift 2;;
    --old-subdir)  OLD_SUBDIR="$2"; shift 2;;
    --new-subdir)  NEW_SUBDIR="$2"; shift 2;;
    --dry-run)     DRY_RUN=1; shift;;
    --binary)      DO_BINARY=1; shift;;
    --backup)      DO_BACKUP=1; shift;;
    -h|--help)     sed -n '2,40p' "$0"; exit 0;;
    -*)            echo "未知选项：$1" >&2; exit 1;;
    *)             TARGET_DIR="$1"; shift;;
  esac
done

[ -n "$TARGET_DIR" ] || { echo "用法：$0 <目标目录> [选项]（--help 查看帮助）" >&2; exit 1; }
[ -d "$TARGET_DIR" ] || { echo "目录不存在：$TARGET_DIR" >&2; exit 1; }

OLD_ROOT="/data/data/$OLD_PKG/files"
NEW_ROOT="/data/data/$NEW_PKG/files"
OLD_PREFIX="$OLD_ROOT/$OLD_SUBDIR"
NEW_PREFIX="$NEW_ROOT/$NEW_SUBDIR"

echo "============================================================"
echo " rootfs 路径替换"
echo "   目标目录 : $TARGET_DIR"
echo "   旧 PREFIX: $OLD_PREFIX"
echo "   新 PREFIX: $NEW_PREFIX"
echo "   模式     : $([ "$DRY_RUN" = 1 ] && echo '仅报告' || echo '实际写入')$([ "$DO_BINARY" = 1 ] && echo ' + 二进制' || echo ' 仅文本')"
echo "============================================================"

# 替换规则表（顺序敏感：先长后短）
python3 - "$TARGET_DIR" "$OLD_PKG" "$NEW_PKG" "$OLD_SUBDIR" "$NEW_SUBDIR" \
         "$DRY_RUN" "$DO_BINARY" "$DO_BACKUP" <<'PYEOF'
import os, sys

(target_dir, old_pkg, new_pkg, old_subdir, new_subdir,
 dry_run, do_binary, do_backup) = sys.argv[1:9]

dry_run   = dry_run == "1"
do_binary = do_binary == "1"
do_backup = do_backup == "1"

old_root = f"/data/data/{old_pkg}/files"
new_root = f"/data/data/{new_pkg}/files"
old_prefix = f"{old_root}/{old_subdir}"
new_prefix = f"{new_root}/{new_subdir}"

# 顺序重要：必须先替换最具体的路径
REPLACEMENTS = [
    (f"{old_prefix}".encode(),                 new_prefix.encode()),
    (f"{old_root}/home".encode(),              f"{new_root}/home".encode()),
    (f"{old_root}/cache".encode(),             f"{new_root}/cache".encode()),
    (f"{old_root}/.built-packages".encode(),   f"{new_root}/.built-packages".encode()),
    (old_root.encode(),                        new_root.encode()),
    (f"/data/data/{old_pkg}/cache".encode(),   f"/data/data/{new_pkg}/cache".encode()),
    (f"/data/data/{old_pkg}".encode(),         f"/data/data/{new_pkg}".encode()),
    (b"@TERMUX_PREFIX@",                       new_prefix.encode()),
    (b"@TERMUX_HOME@",                         f"{new_root}/home".encode()),
    (b"@TERMUX__PREFIX@",                      new_prefix.encode()),
    (old_pkg.encode(),                         new_pkg.encode()),
]

TEXT_EXTS = {
    ".sh", ".bash", ".zsh", ".csh", ".h", ".c", ".cc", ".cpp", ".pc",
    ".conf", ".cfg", ".ini", ".properties", ".profile", ".man", ".md",
    ".py", ".pl", ".awk", ".sed", ".txt", ".list", ".env", ".desktop",
}
TEXT_BASENAMES = {
    "bash.bashrc", "profile", "bash_profile", "bash_login", "bash_logout",
    "PKGBUILD", "Makefile", "configure", "pkg", "termux-reset", "termux-restore",
    "termux-setup-storage", "termux-backup", "termux-info", "termux-open",
    "termux-reload-settings", "termux-wake-lock", "termux-wake-unlock",
    "termux-change-repo", "am", "apt", "dpkg", "ssh", "sshd", "sv",
}
MAX_SIZE = 64 * 1024 * 1024

def looks_text(data: bytes) -> bool:
    return b"\x00" not in data[:65536]

def is_script(path: str, data: bytes) -> bool:
    if data.startswith(b"#!"):
        return True
    base = os.path.basename(path)
    if base in TEXT_BASENAMES:
        return True
    ext = os.path.splitext(base)[1].lower()
    return ext in TEXT_EXTS

changed = 0
hits_total = 0
skipped_binary = []

for dirpath, dirnames, filenames in os.walk(target_dir):
    for name in filenames:
        p = os.path.join(dirpath, name)
        if os.path.islink(p):
            continue
        try:
            st = os.lstat(p)
        except OSError:
            continue
        if not os.path.isfile(p) or st.st_size == 0 or st.st_size > MAX_SIZE:
            continue
        try:
            with open(p, "rb") as f:
                data = f.read()
        except OSError:
            continue

        text_mode = looks_text(data)
        if not text_mode and not do_binary:
            if old_pkg.encode() in data:
                skipped_binary.append(p)
            continue
        if not text_mode and do_binary and not is_script(p, data):
            # 二进制且非脚本：默认不动（改二进制长度会破坏 ELF）
            if old_pkg.encode() in data:
                skipped_binary.append(p)
            continue

        orig = data
        hits = 0
        for old, new in REPLACEMENTS:
            c = data.count(old)
            if c:
                data = data.replace(old, new)
                hits += c
        if data == orig:
            continue

        changed += 1
        hits_total += hits
        rel = os.path.relpath(p, target_dir)
        if dry_run:
            print(f"  [将改] {rel}  ({hits} 处)")
            continue
        if do_backup:
            try:
                with open(p + ".bak", "wb") as b:
                    b.write(orig)
            except OSError:
                pass
        mode = st.st_mode
        with open(p, "wb") as f:
            f.write(data)
        os.chmod(p, mode)
        print(f"  [已改] {rel}  ({hits} 处)")

print()
print(f"  汇总：改写 {changed} 个文件，共 {hits_total} 处替换")
if skipped_binary:
    print(f"  跳过二进制 {len(skipped_binary)} 个（含旧包名，未处理）：")
    for p in skipped_binary[:20]:
        print(f"      {os.path.relpath(p, target_dir)}")
    if len(skipped_binary) > 20:
        print(f"      ... 其余 {len(skipped_binary) - 20} 个")
    print("  提示：二进制内的路径若需修改，需重新编译，不能用文本替换。")
PYEOF

echo
echo "完成。"
