#!/usr/bin/env bash
# ============================================================
#  AlinOs-for-android  最小编译依赖拉取脚本（二次开发/构建前必跑一次）
# ------------------------------------------------------------
#  职责：仅拉取【编译所需】的不可省略引擎包。目前只含
#        sherpa-onnx aar（build.gradle files() 引用，缺它无法编译）。
#
#  不在本脚本的范围：
#   · files.default.*（各 ABI rootfs）→ 由 scripts/rootfs/ 从 termux-packages
#     源码自行编译生成，见 scripts/rootfs/README.md。
#   · ASR/KWS/声纹/VAD/TTS 等运行时语音模型 → 由 App 内部 Java
#     (voice/ModelDownloadManager 等) 运行时下载 / 手动导入，勿在此拉。
#
#  用法：bash scripts/fetch_deps.sh
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AlinOs-fetch/1.0"
ok=0; fail=0

fetch() { # fetch <url> <dest> <expect_bytes(=0 为不校验)>
  local url="$1" dest="$2" expect="$3"
  mkdir -p "$(dirname "$dest")"
  if [ -f "$dest" ]; then
    if [ "$expect" != "0" ] && [ "$(stat -c%s "$dest" 2>/dev/null || echo 0)" = "$expect" ]; then
      echo "  [缓存] $dest 已存在且大小匹配"; ok=$((ok+1)); return 0
    fi
  fi
  echo "  [下载] $url"
  local code
  code=$(curl -fL --max-time 900 -A "$UA" -o "$dest" "$url" && echo ok || echo fail)
  if [ "$code" = "fail" ]; then
    echo "  [失败] $url"; fail=$((fail+1)); return 1
  fi
  if [ "$expect" != "0" ]; then
    local sz
    sz=$(stat -c%s "$dest" 2>/dev/null || echo 0)
    if [ "$sz" != "$expect" ]; then
      echo "  [校验失败] 期望 ${expect}B 实得 ${sz}B → $dest"; fail=$((fail+1)); return 1
    fi
  fi
  echo "  [完成] $dest (${expect}B)"; ok=$((ok+1))
}

echo "== sherpa-onnx 语音引擎 (Android AAR, 已入 gitignore) =="
# 官方源:https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.5
fetch \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar" \
  "app/libs/sherpa-onnx-1.13.5.aar" 49095090 || true

echo
echo "== 汇总 =="
echo "  成功 $ok  失败 $fail"
[ "$fail" -eq 0 ] && echo "依赖齐全，可开始编译。"
exit 0
