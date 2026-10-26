# 04 rounds · 桌面小组件视觉返工（r2）

## r1 · 方向发散（2026-10-25）

- 交付：`widgets-r2/versions/{a,b,c}.html`（三版同结构、不同视觉语言）+ `compare.html`
- **用户决策：选 C（柔和品牌色）**

## r2 · C 版定稿（2026-10-25）

- 交付：`widgets-r2/08-prototype.html`（4 屏）+ `00-decisions.md`（token 表 / Glance 落地表）
- `odops verify` exit 0；:root 缺官方 56 槽位、单尺寸未做断点核对（均 warn，记待办）
- 用户拍板：进实现（「开始，我只看最终的结果」）

## r3 · 实现（2026-10-26）

改的文件：
- `ui/theme/WidgetPalette.kt`：每卡 6 面 × 浅深 token（`CardTokens`）+ 全局/语义色
- `widget/WidgetStyle.kt`：`WidgetCard(face)` / `IconBadge(symbol)` / `CardHead` / `Pill` / `Section` / `Stat` / `VDivider` / `Bar` / `StatusDot`，字阶全 SansSerif（去等宽）
- `widget/{Finance,Health,Quota,AdSkip,Transfer,QuickAdd}Widget.kt`：按定稿原型重排（tint 卡底、图标块、分区面、三列读数、1×1 只留一个数字/状态词）
- `widget/WidgetFormat.kt`：新增 `tinyMoney`（1×1 主动丢小数）+ 2 条单测
- 图标方案：原型的矢量图标改成**字符符号**（¥ % ♥ » ↕），因为 `Image` + tint 在这条链路上不稳

门禁：`assembleDebug` + `testDebugUnitTest`（1280 全过）+ `lintDebug`（0 error / 0 warning）+ `checkUiStyle` 绿。

## 未完成 / 待验证

- **模拟器上桌面组件空白**：排查结论是 Glance session 失效（`Session is not available for appWidget-14/15`），不是渲染代码；需**删掉组件重新添加**后在真机/模拟器复验（见 `docs/踩坑/小组件Glance渲染踩坑.md`）。
- 原型里的图标块在真机上可能改为矢量图标（Session 问题排除后再试）。
