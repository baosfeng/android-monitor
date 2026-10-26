# 小组件在桌面空白或缺元素：先看 Column 子元素数，再看 Glance session

## 现象

装上改过渲染层的新 APK 后，桌面上的小组件出现两种失败形态，看起来都像"样式没生效"：

1. **整块空白**：原来的位置一块白，点上去没反应；
2. **卡片里少东西**：顶行和数字在，但某一行（常见于第三行）的进度条、间距凭空消失，布局挤压。

构建、单测、lint 全绿，App 本身正常。两种现象根因不同，别混着查。

## 先抓日志，两条命令定生死

```bash
adb logcat -d | grep -c "Truncated Column"        # >0 → 问题一（元素被截断）
adb logcat -d | grep "SessionWorker attempted restart"  # 命中 → 问题二（session 失效）
adb logcat -d | grep -E "E GlanceAppWidget" | head -5
```

再看实例本身在不在：`dumpsys appwidget | grep -A3 bsfeng`，`views=RemoteViews@...` 有值只说明系统里存了一份
RemoteViews，**不代表它内容完整** —— 播放器照样会给一份被截断的树。

## 问题一：Column 直接子元素超过 10 个被静默截断

**判据**：`E GlanceAppWidget: Truncated Column container from 14 to 10 elements` +
`java.lang.IllegalArgumentException: Column container cannot have more than 10 elements`。

**根因**：Glance 的 RemoteViews 翻译层（`LayoutSelectionKt.insertContainerView`）对 Column 有 10 个子元素上限，
超出部分直接丢弃，只打一行 E 日志；桌面既不报错也没有占位，看起来就像"这行设计里没有"。

**典型触发**：多行列表直铺。额度 Wide 是「顶行 + 间距 + 3×(行 + 间距 + 轨道 + 间距)」= **14 个**，
第三家的轨道被吃掉。六个组件里只有它在限外，其余都 ≤7。

**修法**：把「一行 + 轨道」包进内层 Column，外层从 14 降到 5，内层每行 3 个。规则写在
`app/src/main/java/com/bsfeng/monitor/widget/WidgetStyle.kt` 的 `WidgetCard` KDoc 里。

**排查命令**：`dumpsys appwidget | grep -A3 bsfeng` 之后再等一次 60s 心跳，
`logcat -d | grep -E "updateAppWidgetInstanceLocked.*bsfeng|Launcher.Widget: updateAppWidget"`
能同时看到"送达 launcher"和"Truncated"，就说明链路没问题、纯粹是布局被砍。

## 问题二：实例的 Glance session 失效（结构性问题才需要查）

**判据**：`GlanceSessionWorker: SessionWorker attempted restart but Session is not available for appWidget-<id>`。

实例从旧进程恢复、没重新走 `requestPinAppWidget` 时，新进程找不到对应 session → 更新写不进 RemoteViews → 整块空白。
**与渲染代码无关**：同样代码在新加的实例上正常显示。

**处理**：删掉组件重新添加（新实例 = 新 session）；`am force-stop <launcher>` 没用（session 不在 launcher 侧）。
排查期间加的 `WIDGET-DIAG` 日志要删干净：`grep -rn "WIDGET-DIAG" app/src/main` 必须为空。

## 教训

- **先 grep 日志再猜**：`Truncated Column` 和 `SessionWorker` 两条日志把两类问题分得干干净净，
  比"重装看看"快得多。
- 改小组件渲染后，**别用"重装 APK 看旧位置"验证**：旧实例的 session 可能已失效，空白会被误判成布局 bug。
- 验证顺序固定：**新加一个实例 → 抓日志 → 截图 → 再谈样式**。
