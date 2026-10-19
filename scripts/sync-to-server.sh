#!/usr/bin/env bash
# 把发布产物同步到自建更新源服务器（国内下载更快）。
# 配置从 local.properties 读取（该文件不入库）：
#   updateServerHost=your-server.example.com
#   updateServerUser=root
#   updateServerKey=/path/to/ssh-key.pem
#   updateServerPath=/var/www/updates
#
# 用法：scripts/sync-to-server.sh <apk 路径> <version.json 路径>
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
MANIFEST="${2:-updates/version.json}"

[ -f "$APK" ] || { echo "找不到 APK：$APK"; exit 1; }
[ -f "$MANIFEST" ] || { echo "找不到版本清单：$MANIFEST"; exit 1; }

cd "$(dirname "$0")/.."

read_prop() {
  grep -E "^$1=" local.properties 2>/dev/null | tail -1 | cut -d= -f2- || true
}

HOST="$(read_prop updateServerHost)"
USER_NAME="$(read_prop updateServerUser)"
KEY="$(read_prop updateServerKey)"
REMOTE_PATH="$(read_prop updateServerPath)"

if [ -z "$HOST" ] || [ -z "$USER_NAME" ] || [ -z "$REMOTE_PATH" ]; then
  echo "请先在 local.properties 配置 updateServerHost / updateServerUser / updateServerPath（以及可选的 updateServerKey）。"
  exit 1
fi

SSH_OPTS=(-o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new)
if [ -n "$KEY" ]; then
  SSH_OPTS+=(-i "$KEY")
fi

echo "同步 APK 与版本清单到 $USER_NAME@$HOST:$REMOTE_PATH"
scp "${SSH_OPTS[@]}" "$APK" "$USER_NAME@$HOST:$REMOTE_PATH/app-release.apk"
scp "${SSH_OPTS[@]}" "$MANIFEST" "$USER_NAME@$HOST:$REMOTE_PATH/version.json"
echo "完成。App 更新检测将优先从该地址拉取。"
