#!/usr/bin/env bash
# 调试 CLI：把「点坐标验证」换成一条广播（仅 debug 包；release 包里没有这个 receiver）。
#
# 用法：
#   scripts/debug-cli.sh unlock                 # 一次性解锁调试层（CI / 新装机第一步）
#   scripts/debug-cli.sh state                  # 功能开关 + 定位权限 + 各类型当日计数
#   scripts/debug-cli.sh locate                 # 立刻采一次位置（绕过节流）
#   scripts/debug-cli.sh seed [day|stay|move|noise|gap]
#   scripts/debug-cli.sh dump [limit] [TYPE]
#   scripts/debug-cli.sh reset [TYPE]           # 删「当日」某类型（默认 SCREEN；别名 PAGE 已随类型表下线）
#   scripts/debug-cli.sh reset all              # 清空全表（所有类型、所有日期）
#   scripts/debug-cli.sh open <trip|settings|live|overview>
#   scripts/debug-cli.sh db tables              # 表名 + 行数 + user_version（只读）
#   scripts/debug-cli.sh db schema <表名>       # 列定义（只读）
#   scripts/debug-cli.sh db query "<sql>" [limit]   # 单条 select/with，默认 50 行、上限 200
#   scripts/debug-cli.sh heartbeat              # 转发一次真实心跳
#   scripts/debug-cli.sh ui dump [tag]          # 无障碍控件树 → files/dsh-ui/<tag>.json
#   scripts/debug-cli.sh ui audit [tag]         # 无障碍审计：无可读名 / 触达 <48dp → files/dsh-ui/a11y-<tag>.json（有缺口=退出码 1）
#   scripts/debug-cli.sh ui click --es text 功能开关   # 按文案点（亦可 --es desc / --es id / --es bounds）
#   scripts/debug-cli.sh ui longclick --es id row_trip
#   scripts/debug-cli.sh ui scroll --es dir down
#   scripts/debug-cli.sh ui swipe --ei x1 540 --ei y1 1800 --ei x2 540 --ei y2 600 --ei ms 300
#   scripts/debug-cli.sh ui back|home|recents   # 全局动作（不注入 input 事件）
#   scripts/debug-cli.sh ui wait --ei ms 800    # 等界面稳定（上限 5000）
#   scripts/debug-cli.sh shot [name]            # adb exec-out screencap -p 存本地（默认 /tmp；ui shot 同义）
#
# ui 系列是 agent 操作手机界面的**唯一**通道：不注入 input/keyevent，在 App 进程内读树 +
# performAction。无障碍没开时回 ok=false reason=accessibility_off。
#
# db 系列替代 `adb pull` 整个库：USB 易断、WAL 库拷出来常是坏页。只读闸门在端上做
# （单语句 + select/with 开头 + 拒绝写关键字），被拒时 ok=false 且 reason=bad_args。
#
# 鉴权（安全审计 S-01）：receiver 是 exported 的，端上要求「读过本次 unlock 回执」——
#   - unlock / state / heartbeat / open / locate：免令牌；
#   - dump / db / ui（含 click·swipe·back 等真点击）：需 unlock 下发的 confirm，10 分钟内可复用；
#   - clear / seed：同一 confirm 消费即废，成功后回执换发新值。
# 令牌由本脚本自动取用（unlock 后落盘、后续命令自动附加），**用法与以前完全一致**；
# 手动排查时可从 unlock 回执的 data.confirm 取，直接 am broadcast 时加 --es confirm <值>。
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

# 端上对「读数据 / 驱动界面 / 写库」三类命令都要求 confirm 令牌（unlock 回执里下发，
# 有效期 10 分钟；clear / seed 消费即废，成功后回执换发新值）。令牌没法跨进程传递，
# 所以 unlock 后落到本机状态文件；之后每条相关命令自动附加，用法完全不变。
# 文件名带设备序列号：多设备并行时不会串。
CONFIRM_FILE="${TMPDIR:-/tmp}/android-monitor-confirm-${ANDROID_SERIAL:-default}"

# 需要令牌的子命令（与 DebugCommandGates 的三档一一对应；unlock/state/heartbeat/open/样本定位不需要）
NEEDS_CONFIRM=" dump db seed clear ui "

read_confirm() {
  [ -s "$CONFIRM_FILE" ] && cat "$CONFIRM_FILE" 2>/dev/null
}

# 从回执 JSON 里取 confirm 并覆盖本地值：unlock 与消费型命令（clear / seed）成功后都会换发新令牌
save_confirm() {
  local token
  token=$(printf '%s' "$1" | sed -n 's/.*"confirm":"\([^"]*\)".*/\1/p' | head -1)
  [ -n "$token" ] && printf '%s' "$token" >"$CONFIRM_FILE"
}

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

# 远端 sh 转义：adb shell 会把参数拼成一条命令交给手机上的 sh 再解析一次，
# SQL 里的括号 / 星号 / 引号不转义会直接变成 "syntax error: unexpected ("（实测 count(*)）。
# 每个参数单引号包裹、内部的单引号写成 '\''。
shell_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

send() {
  local args=()
  local arg
  for arg in "$@"; do
    args+=("$(shell_quote "$arg")")
  done
  # 需要令牌的子命令自动带上（调用方不用改任何一行用法）：取 unlock 时落盘的 confirm，
  # 端上按 TTL 复用；clear / seed 消费后回执换发新值，由文件末尾的 save_confirm 覆盖。
  local cmdval="" i already=0
  for ((i = 0; i < ${#args[@]}; i++)); do
    if [ "${args[i]}" = "'cmd'" ] && [ $((i + 1)) -lt ${#args[@]} ]; then
      cmdval=$(printf '%s' "${args[i + 1]}" | tr -d "'")
    fi
    [ "${args[i]}" = "'confirm'" ] && already=1
  done
  if [ "$already" -eq 0 ] && [ -n "$cmdval" ] && [[ "$NEEDS_CONFIRM" == *" $cmdval "* ]]; then
    local saved
    saved=$(read_confirm)
    [ -n "$saved" ] && args+=("$(shell_quote "--es")" "$(shell_quote "confirm")" "$(shell_quote "$saved")")
  fi
  adb_run shell am broadcast -n "$RECEIVER" --es tag "$TOKEN" "${args[@]}" >/dev/null 2>&1
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

# 截图：只读通道（screencap 不注入输入事件，也不改无障碍配置 canTakeScreenshot=false）。
# 输出本地绝对路径；失败不留半个文件，直接返回 1。
take_shot() {
  local name="${1:-shot}"
  local dir="${SHOT_DIR:-/tmp}"
  if ! mkdir -p "$dir"; then
    echo "debug-cli: 截图目录不可用：$dir" >&2
    return 1
  fi
  local file="$dir/$name-$(date +%Y%m%d_%H%M%S).png"
  if adb_run exec-out screencap -p >"$file" 2>/dev/null && [ -s "$file" ]; then
    echo "$file"
    return 0
  fi
  rm -f "$file"
  echo "debug-cli: screencap 失败（设备未连接 / USB 调试被拦）" >&2
  return 1
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
    # 令牌由 send() 统一附加；seed 同为写库命令，消费后回执换发新值
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
    # 令牌由 send() 统一附加；clear 是消费型命令，成功后回执换发新值（见文件末尾 save_confirm）
    args=(--es cmd clear)
    case "${1:-}" in
      # 默认值取 SCREEN：历史上写的 PAGE 已不在 RecordType.CANONICAL_NAMES 里，
      # 无参 reset 会一直 bad_args（"type 取值非法"）—— 与「用法不变」的目标正好相反。
      "") args+=(--es type SCREEN) ;;
      all|--all|-a) args+=(--ez all true) ;;
      *) args+=(--es type "$1") ;;
    esac
    send "${args[@]}"
    ;;
  open)
    if [ -z "${1:-}" ]; then
      echo "debug-cli: open 需要目标屏（trip|settings|live|overview）" >&2
      exit 2
    fi
    send --es cmd open --es screen "$1"
    ;;
  db)
    op="${1:-}"
    case "$op" in
      tables)
        send --es cmd db --es op tables
        ;;
      schema)
        if [ -z "${2:-}" ]; then
          echo "debug-cli: db schema 需要表名（db schema records）" >&2
          exit 2
        fi
        send --es cmd db --es op schema --es table "$2"
        ;;
      query)
        if [ -z "${2:-}" ]; then
          echo "debug-cli: db query 需要 SQL（db query \"select ...\" [limit]）" >&2
          exit 2
        fi
        if [ -n "${3:-}" ]; then
          send --es cmd db --es op query --es sql "$2" --ei limit "$3"
        else
          send --es cmd db --es op query --es sql "$2"
        fi
        ;;
      *)
        echo "debug-cli: db 需要 tables|schema|query（db tables / db schema <表> / db query \"<sql>\" [limit]）" >&2
        exit 2
        ;;
    esac
    ;;
  ui)
    op="${1:-}"
    [ $# -gt 0 ] && shift
    case "$op" in
      "")
        echo "debug-cli: ui 需要动作（dump|audit|click|longclick|scroll|swipe|back|home|recents|wait）" >&2
        exit 2
        ;;
      dump)
        if [ -n "${1:-}" ]; then
          send --es cmd ui --es op dump --es win "$1"
        else
          send --es cmd ui --es op dump
        fi
        ;;
      audit)
        # tag 走 --es win（与 dump 同一字段：--es tag 已被回执 token 占用）
        if [ -n "${1:-}" ]; then
          send --es cmd ui --es op audit --es win "$1"
        else
          send --es cmd ui --es op audit
        fi
        ;;
      click|longclick|scroll|swipe|wait)
        # 透传式：选择器参数原样交给广播（send 会逐参数 shell_quote，中文文案不用额外转义）
        send --es cmd ui --es op "$op" "$@"
        ;;
      back|home|recents)
        send --es cmd ui --es op "$op"
        ;;
      shot)
        # 与顶层 shot 同一条 screencap 通道：`ui shot` 只是让「界面动作」这一族写法自洽
        take_shot "${1:-shot}"
        exit $?
        ;;
      *)
        echo "debug-cli: ui 未知动作 $op（dump|audit|click|longclick|scroll|swipe|back|home|recents|wait）" >&2
        exit 2
        ;;
    esac
    ;;
  shot)
    take_shot "${1:-shot}"
    exit $?
    ;;
  *)
    echo "debug-cli: 未知子命令 $cmd" >&2
    usage >&2
    exit 2
    ;;
esac

lines=$(wait_result)
status=$?
# unlock 与写库命令（clear / seed）成功后回执都带 confirm：覆盖本地值，后续命令接着用
if [ "$status" -eq 0 ]; then
  save_confirm "$lines"
fi
report "$lines" "$status"
