# 踩坑：GKD 订阅源在真机上的可用性（jsdelivr 不可用、URL 形态、404 处理）

## 问题描述

同一份 GKD 订阅地址，Mac 上能下载，**手机上下载失败**；更换镜像后秒下。
另有一类"地址 404"的情况：订阅包确实存在，但 URL 里的**包内文件路径写错**。

## 实测结论（2026-10-13，小米 14 / HyperOS）

| 地址 | 结果 |
|------|------|
| `https://fastly.jsdelivr.net/gh/...` | ❌ 真机下载不稳定/失败（Mac 可下） |
| `https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5` | ✅ 2 秒内 611 条规则（version 186） |
| `https://registry.npmmirror.com/@aisouler/gkd_subscription/latest/files/dist/AIsouler_gkd.json5` | ✅ 889 条规则（version 406） |
| `https://registry.npmmirror.com/@aisouler/gkd_subscription/latest/files/dist/gkd.json5` | ❌ 404（该包内没有这个文件名） |

**推荐地址：npm 镜像**（`registry.npmmirror.com`），已在规则页「添加订阅」的对话框里预填。

## URL 形态

```
https://registry.npmmirror.com/<包名>/latest/files/<包内路径>
```

`<包内路径>` 必须与 npm 包内真实文件一致。查包内文件：

```bash
curl -s https://registry.npmmirror.com/@aisouler/gkd_subscription/latest \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['dist']['tarball'])"
# 下载 tarball 后列出文件
curl -sL -o pkg.tgz <tarball> && tar tzf pkg.tgz | head
# → package/dist/AIsouler_gkd.json5 等
```

## 应用侧行为（不是缺陷，是设计）

- 下载失败/404：`GkdSubscription.Outcome.Failed`，**保留该订阅源原有规则**，只弹提示，不清空；
- 某个订阅源解析失败或文件损坏：`SkipRuleManager` 只跳过该源（`readRules` 返回空），
  其它订阅源的规则照常加载（见 `SubscriptionSourcesTest`）；
- 未添加订阅 / 距上次检查不足 24 小时：`autoCheck` 返回空列表，**零网络请求**。

## 验证方法

```bash
A=~/android-platform-tools/adb
$A logcat -c && $A logcat -d | grep -E 'GkdSubscription|SkipRuleManager'
# 成功：I/SkipRuleManager: 订阅已更新: <url> version=186 规则=611（共 N 个订阅源）
# 失败：W/GkdSubscription: 订阅返回 404: <url>
```

## 合规提醒

社区订阅仓库（AIsouler 12k★ / Lin-arm 5.9k★）**没有 LICENSE**，其规则数据不可内置进本仓库，
只能由用户自行添加订阅 URL；内置规则（`BuiltInRules.kt`）为自研。调试时临时添加的订阅
验证后应清除（规则页「删除」/「重置」）。

## 相关文件

- 订阅拉取：`app/src/main/java/com/bsfeng/monitor/skip/GkdSubscription.kt`
- 订阅落库与多源管理：`app/src/main/java/com/bsfeng/monitor/skip/SkipRuleManager.kt`
- 规则页入口：`app/src/main/java/com/bsfeng/monitor/ui/SkipRuleScreen.kt`
- 模块文档：`docs/开屏跳过/设计.md` §5 规则来源与订阅
