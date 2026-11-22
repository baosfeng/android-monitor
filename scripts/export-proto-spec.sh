#!/usr/bin/env bash
# 导出原型逐屏锚点几何 / 样式，作为「原型 ↔ 实现」对照的真值。
#
# 依赖：agent-browser（本地 Chrome for Testing）+ 已组装的 .open-design/08-prototype.html。
# 为什么逐屏：原型是单页 DOM 切换，非活动屏的盒模型为 0，必须在页面里逐屏 App.go 后再量。
#
# 用法：bash scripts/export-proto-spec.sh [输出路径]
#       默认输出 .open-design/parity/proto-screens.json
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.open-design/parity/proto-screens.json"
if [ "$#" -ge 1 ]; then OUT="$1"; fi
PROTO="$ROOT/.open-design/08-prototype.html"
MEASURE="$ROOT/scripts/proto-measure.js"

[ -f "$PROTO" ] || { echo "缺少产物：$PROTO（先跑 node scripts/assemble-prototype.mjs）" >&2; exit 1; }
[ -f "$MEASURE" ] || { echo "缺少测量脚本：$MEASURE" >&2; exit 1; }
command -v agent-browser >/dev/null || { echo "缺少 agent-browser" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
export AGENT_BROWSER_SESSION=parity-export
agent-browser open "file://$PROTO" >/dev/null
cat "$MEASURE" | agent-browser eval --stdin > "$OUT.raw"
node -e '
const fs=require("fs");
const p=process.argv[1];
let v=JSON.parse(fs.readFileSync(p+".raw","utf8"));
if(typeof v==="string") v=JSON.parse(v);
fs.writeFileSync(p, JSON.stringify(v,null,1));
fs.unlinkSync(p+".raw");
' "$OUT"
COUNT=$(node -e 'const d=require(process.argv[1]);console.log(Object.keys(d).length)' "$OUT")
echo "已导出 $COUNT 屏 → $OUT"
