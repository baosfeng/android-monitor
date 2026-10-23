#!/usr/bin/env bash
# 调试 CLI：把「点坐标验证」换成一条广播（仅 debug 包；release 包里没有这个 receiver）。
#
# 用法：
#   scripts/debug-cli.sh unlock                 # 一次性解锁调试层（CI / 新装机第一步）
#   scripts/debug-cli.sh state                  # 功能开关 + 定位权限 + 各类型当日计数
#   scripts/debug-cli.sh locate                 # 立刻采一次位置（绕过节流）
#   scripts/debug-cli.sh seed [day|stay|move|gap]
#   scripts/debug-cli.sh dump [limit] [TYPE]
#   scripts/debug-cli.sh reset [TYPE]           # 删「当日」某类型（默认 PAGE）
#   scripts/debug-cli.sh reset all              # 清空全表（所有类型、所有日期）
#   scripts/debug-cli.sh open <trip|settings|live|overview>
#   scripts/debug-cli.sh heartbeat              # 转发一次真实心跳
#
# 设备：ANDROID_SERIAL=xxx 指定（多设备时必须给）；adb 路径 ADB=... 可覆盖。
# 输出：原样打印本次调用的 logcat 行（tag=DebugCmd）；退出码 = 命令的 ok
#       （0 成功 / 1 ok=false 或超时 / 2 用法错误）。
# 注意：`am broadcast` 自己打印的 `Broadcast completed: result=0` 是「广播投递成功」，
#       不代表命令执行成功 —— 判成败只看 RESULT 行的 ok= 与本脚本的退出码。
set -uo pipefail

ADB="${ADB:-$HOME/android-platform-tools/adb}"
RECEIVER="com.bsfeng.monitor/.debug.DebugCommandReceiver"
TIMEOUT="${TIMEOUT:-12}"
TOKEN="dbg-$(date +%s)-$RANDOM"

adb_run() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    command "$ADB" -s "$ANDROID_SERIAL" "$@"
  else
    command "$ADB" "$@"
  fi
}

usage() {
  sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

send() {
  adb_run shell am broadcast -n "$RECEIVER" --es tag "$TOKEN" "$@" >/dev/null 2>&1
}

# 轮询 logcat 直到收到本次 token 的结果；dump 会分片，等 part 数补齐
wait_result() {
  local deadline=$(( $(date +%s) + TIMEOUT ))
  local lines="" want="" got=0
  while :; do
    lines=$(adb_run logcat -d -v brief -s DebugCmd:I 2>/dev/null | grep -F "$TOKEN" || true)
    if [ -n "$lines" ]; then
      want=$(printf '%s\n' "$lines" | sed -n 's/.*"parts":\([0-9][0-9]*\).*/\1/p' | head -1)
      got=$(printf '%s\n' "$lines" | grep -c . )
      if [ -z "$want" ] || [ "$got" -ge "$want" ]; then
        printf '%s\n' "$lines"
        return 0
      fi
    fi
    [ "$(date +%s)" -ge "$deadline" ] && { printf '%s\n' "$lines"; return 1; }
    sleep 0.3
  done
}

# 打印结果行并按 OK 行决定退出码（这里是唯一的 exit 出口，保证退出码不会传丢）
report() {
  local lines="$1" status="$2" last=""
  if [ -n "$lines" ]; then
    printf '%s\n' "$lines" | grep -F "$TOKEN"
    last=$(printf '%s\n' "$lines" | grep -F "$TOKEN" | tail -1)
  fi
  if [ "$status" -ne 0 ]; then
    echo "debug-cli: 等待超时（${TIMEOUT}s），结果可能不完整；确认包已装、进程在跑、已 unlock" >&2
    exit 1
  fi
  if [ -z "$last" ]; then
    echo "debug-cli: 没有拿到 RESULT 行（包名/进程/解锁状态？先跑 unlock）" >&2
    exit 1
  fi
  case "$last" in
    *" ok=true"*) exit 0 ;;
    *) exit 1 ;;
  esac
}

cmd="${1:-help}"
[ $# -gt 0 ] && shift

case "$cmd" in
  help|-h|--help)
    usage
    exit 0
    ;;
  unlock)
    send --es cmd unlock
    ;;
  state)
    send --es cmd state
    ;;
  locate|sample_location)
    send --es cmd sample_location --ez bypass_throttle true
    ;;
  heartbeat)
    send --es cmd heartbeat
    ;;
  seed)
    send --es cmd seed --es scenario "${1:-day}"
    ;;
  dump)
    limit="${1:-20}"
    type="${2:-}"
    if [ -n "$type" ]; then
      send --es cmd dump --ei limit "$limit" --es type "$type"
    else
      send --es cmd dump --ei limit "$limit"
    fi
    ;;
  reset|clear)
    case "${1:-}" in
      "") send --es cmd clear --es type PAGE ;;
      all|--all|-a) send --es cmd clear --ez all true ;;
      *) send --es cmd clear --es type "$1" ;;
    esac
    ;;
  open)
    if [ -z "${1:-}" ]; then
      echo "debug-cli: open 需要目标屏（trip|settings|live|overview）" >&2
      exit 2
    fi
    send --es cmd open --es screen "$1"
    ;;
  *)
    echo "debug-cli: 未知子命令 $cmd" >&2
    usage >&2
    exit 2
    ;;
esac

lines=$(wait_result)
status=$?
report "$lines" "$status"
