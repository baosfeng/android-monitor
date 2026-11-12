#!/usr/bin/env bash
# 定位功耗报告：一次输出「省电改造」前后对比要看的四项指标。
#
# 用法：
#   scripts/location-power-report.sh              # 当前唯一设备
#   ANDROID_SERIAL=63caad7a scripts/location-power-report.sh
#
# 验收流程（手机插上后按顺序）：
#   1) ~/android-platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
#   2) 正常用一天（通勤 / 室内 / 坐车各覆盖一段，别全程插电）
#   3) 跑本脚本，把五段输出与基线对照
#   4) 基线（改前 11-12 00:00–08:43 旧包）：三 provider 各挂 7h29m–7h46m、2180 次回调、
#      186 次 GNSS 锁星、今日落库 99 条；改后同日 08:45 装包，静止 7 分钟只有 passive 1 个点，
#      network / gps 没有任何请求记录。
#      目标：回调次数与存续时长压掉一半以上，落库条数不掉（基线 99 条/日）
#
# 口径说明（别拿错数）：
#   - 「请求存续时长」是系统按 provider 记的 active duration，不等于 GNSS 芯片点亮时长；
#     真机今天这数是 7 小时级，而 GNSS 锁星次数只有几十次 —— 前者看的是「挂着等」的时长。
#   - 「落库条数」才是轨迹质量：回调几百上千次只有几十条落库是正常的（探测点只喂门禁不落库）。
#   - passive 那行存续时长会一路涨（常驻被动通道本身就是一条挂着不动的请求），它不点亮 GNSS；
#     对比省电只看 network / gps 两行。
set -uo pipefail

ADB="${ADB:-$HOME/android-platform-tools/adb}"
PKG="com.bsfeng.monitor"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# macOS 自带 bash 3.2 在 set -u 下展开空数组会报 unbound variable，
# 所以设备参数走函数而不是数组（照 scripts/debug-cli.sh 的写法）
adb_run() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    "$ADB" -s "$ANDROID_SERIAL" "$@"
  else
    "$ADB" "$@"
  fi
}

if ! adb_run get-state >/dev/null 2>&1; then
  echo "设备未连接：先确认 USB 调试已授权（见 docs/开发指南/ADB调试指南.md）" >&2
  exit 2
fi

# 免令牌，但 state / db 要解锁后才有 confirm（解锁回执自动落盘，10 分钟内复用）
(cd "$ROOT" && scripts/debug-cli.sh unlock >/dev/null 2>&1) || true

echo "==== 1. 门禁与通道（活着的采集链路） ===="
# state 只取一次：第 4 段的本地日界（dayStart）也从这里来，避免两处各算一次口径
STATE_JSON="$(cd "$ROOT" && scripts/debug-cli.sh state 2>/dev/null | grep -o 'RESULT cmd=state.*' | sed 's/.*data=//')" || STATE_JSON=""
echo "${STATE_JSON:-state 读取失败}"

echo
echo "==== 2. 三个 provider 的请求统计（回调次数 / 存续时长） ===="
adb_run shell dumpsys location 2>/dev/null | grep -E "${PKG}: min/max interval" | sed 's/^ *//'

echo
echo "==== 3. GNSS 真实锁星（TTFF） ===="
adb_run shell dumpsys location 2>/dev/null | grep -E "Number of TTFF reports|TTFF mean|Number of location reports" | sed 's/^ *//'

echo
echo "==== 4. 今天落库的定位条数 ===="
# 日界用 App 自己算的本地日起（state.dayStart）：端上 SQLite 的 'localtime' 修饰符不生效，
# 写 strftime('%s','now','start of day') 会得到 UTC 日起 —— 08:00 之前跑会把今天的点全漏掉
# （实测同一时刻：UTC 日起 6 条 vs 本地日起 99 条）。
DAY_START="$(printf '%s' "$STATE_JSON" | sed -n 's/.*"dayStart":\([0-9]*\).*/\1/p')"
if [ -z "$DAY_START" ]; then
  echo "dayStart 读取失败（第 1 段 state 没拿到）"
else
  (cd "$ROOT" && scripts/debug-cli.sh db query "select count(*) as n from records where type='LOCATION' and timestamp >= $DAY_START" 5 2>/dev/null | grep -o 'RESULT cmd=db.*' | sed 's/.*data=//') || echo "db 读取失败（先跑 scripts/debug-cli.sh unlock）"
fi

echo
echo "==== 5. 电池归因（本 App） ===="
adb_run shell dumpsys batterystats "$PKG" 2>/dev/null | grep -E "Estimated power use|GPS wake|Wake lock \*location|Proc $PKG" | head -8
