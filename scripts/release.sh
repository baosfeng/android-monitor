#!/usr/bin/env bash
# 发布脚本：递增版本号 → 提交 → 推提交 → 打 tag → 推 tag，由 GitHub Actions 构建签名 APK 并发布 Release。
#
# 用法：scripts/release.sh <x.y.z> [remote]
#   remote 缺省依次回退 github → origin（仓库历史上这两个名字都出现过）。
#
# 顺序为什么是「先推提交、再打 tag」（基建审计 I14）：以前是 commit + tag 之后分两次 push，
# push main 成功而 push tag 失败时，本地已经存在 v 标签，重跑直接撞「标签已存在」退出——
# version.properties 已 +1 却推不出去，只能手工收拾。现在 tag 在提交推成功之后才创建，
# 最坏情况是「main 上有版本号提交、tag 还没推」，且失败分支直接打印可复制的恢复命令。
set -euo pipefail

# 先切到仓库根：脚本内所有相对路径（version.properties、git 操作）都以仓库根为准。
# 以前参数校验发生在 cd 之前，从别的目录执行必然失败（基建审计 I14）。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
REMOTE="${2:-}"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "用法：scripts/release.sh <x.y.z> [remote]（例如 1.1.0 origin）"
  exit 1
fi

# remote 不再写死 github：先按参数，再按习惯名回退，最后报错让人显式指定。
if [ -z "$REMOTE" ]; then
  if git remote get-url github >/dev/null 2>&1; then
    REMOTE=github
  elif git remote get-url origin >/dev/null 2>&1; then
    REMOTE=origin
  else
    echo "找不到可推送的 remote（试过 github / origin）：用第二个参数显式指定。"
    exit 1
  fi
fi
if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
  echo "remote ${REMOTE} 不存在：git remote -v 看一下有哪些。"
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "工作区有未提交改动，请先提交或 stash 后再发版。"
  exit 1
fi

BRANCH="$(git branch --show-current)"
if [ "$BRANCH" != "main" ]; then
  echo "当前分支是 ${BRANCH}，请在 main 分支发版。"
  exit 1
fi

if git rev-parse "v$VERSION" >/dev/null 2>&1; then
  echo "标签 v${VERSION} 已存在。"
  echo "  上次推 tag 失败时补推：git push ${REMOTE} v${VERSION}"
  echo "  放弃本次发版：git tag -d v${VERSION} && git reset --hard HEAD~1"
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

echo "版本号：${VERSION}（versionCode=${NEW_CODE}）"
git add version.properties
git commit -m "chore: 发布 v${VERSION}"

if ! git push "$REMOTE" "HEAD:main"; then
  echo "推送提交失败：版本号提交已在本地，tag 尚未创建（重跑不会撞标签）。恢复命令：" >&2
  echo "  git push ${REMOTE} HEAD:main" >&2
  exit 1
fi

git tag "v${VERSION}"

if ! git push "$REMOTE" "v${VERSION}"; then
  echo "tag 推送失败：提交已在 ${REMOTE}/main 上，标签只在本地。恢复命令：" >&2
  echo "  git push ${REMOTE} v${VERSION}    # 网络恢复后补推，CI 会自动构建" >&2
  echo "  git tag -d v${VERSION}            # 或在本地撤销本次标签" >&2
  exit 1
fi

echo "已推送 v${VERSION}（${REMOTE}），GitHub Actions 将构建签名 APK 并创建 Release。"
