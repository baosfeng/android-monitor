#!/usr/bin/env bash
# 发布脚本：递增版本号 → 提交 → 打 tag → 推送，由 GitHub Actions 构建签名 APK 并发布 Release。
# 用法：scripts/release.sh 1.1.0
set -euo pipefail

VERSION="${1:-}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "用法：scripts/release.sh <x.y.z>（例如 1.1.0）"
  exit 1
fi

cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
  echo "工作区有未提交改动，请先提交或 stash 后再发版。"
  exit 1
fi

BRANCH="$(git branch --show-current)"
if [ "$BRANCH" != "main" ]; then
  echo "当前分支是 $BRANCH，请在 main 分支发版。"
  exit 1
fi

if git rev-parse "v$VERSION" >/dev/null 2>&1; then
  echo "标签 v$VERSION 已存在。"
  exit 1
fi

NEW_CODE="$(python3 - "$VERSION" <<'PY'
import re
import sys

version = sys.argv[1]
path = "version.properties"
text = open(path, encoding="utf-8").read()
code = int(re.search(r"^versionCode=(\d+)", text, re.M).group(1)) + 1
text = re.sub(r"^versionCode=\d+", f"versionCode={code}", text, flags=re.M)
text = re.sub(r"^versionName=.*", f"versionName={version}", text, flags=re.M)
open(path, "w", encoding="utf-8").write(text)
print(code)
PY
)"

echo "版本号：$VERSION（versionCode=$NEW_CODE）"
git add version.properties
git commit -m "chore: 发布 v$VERSION"
git tag "v$VERSION"

git push github main
git push github "v$VERSION"

echo "已推送 v$VERSION，GitHub Actions 将构建签名 APK 并创建 Release。"
