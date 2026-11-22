#!/usr/bin/env bash
# 导出原型逐屏截图，作为「一模一样」的视觉真值。
#
# 为什么按元素截：device-frame 宽 417 > 手机视口 390，整页截图会横向裁切；
# 按 .screen.is-active 截取内容区（393×738/741），与实现可用宽度一一对应。
# 依赖：agent-browser。产物默认落 .open-design/parity/shots/（本地工作区，不入库）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.open-design/parity/shots"
PROTO="$ROOT/.open-design/08-prototype.html"
[ -f "$PROTO" ] || { echo "缺少产物：$PROTO" >&2; exit 1; }
mkdir -p "$OUT"
export AGENT_BROWSER_SESSION=parity-shots
agent-browser open "file://$PROTO" >/dev/null
agent-browser set viewport 460 900 >/dev/null
sleep 0.8
SCREENS=$(agent-browser eval "[...document.querySelectorAll('.screen[data-screen]')].map(e=>e.dataset.screen).join(' ')" | tr -d '"')
N=0
for s in $SCREENS; do
  agent-browser eval "App.go('$s')" >/dev/null
  sleep 0.5
  agent-browser screenshot ".screen.is-active" "$OUT/$s.png" >/dev/null
  N=$((N+1))
done
echo "已截图 $N 屏 → $OUT"
