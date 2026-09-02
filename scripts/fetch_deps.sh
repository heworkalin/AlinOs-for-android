#!/usr/bin/env bash
# ============================================================
#  AlinOs-for-android  依赖拉取脚本（二次开发/构建前必跑一次）
# ------------------------------------------------------------
#  用途：本仓库出于体积与远端限制，把可在线取得的大二进制（引擎 aar、
#        原生 .so、语音模型等）从 git 移出。clone 后须先执行本脚本，
#        将依赖下载到工作区对应目录，否则 app 无法编译。
#  说明：files.default.*（各 ABI 精简版 proot 环境）因无外部发布源、
#        只能存仓库，不在此下载。
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
