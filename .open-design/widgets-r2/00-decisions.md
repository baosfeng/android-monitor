# 00 decisions · 桌面小组件视觉返工 r2（定稿：C 柔和品牌色）

> 用户在 A/B/C 三版对照里拍了 **C**。交付件是 `08-prototype.html`（C 收敛版，4 屏 390×844）。`versions/a.html · b.html · c.html` 是三方向对照存档，只作记录、不再迭代。

## 1 每版一句话定位

| 版 | 定位 | 一句话 |
|---|---|---|
| A Bento 分块 | 最稳、最像 iOS | 白卡 + 内嵌浅灰分区 + tinted 图标眉标 + SansSerif Bold 大数字，靠"分区面"制造秩序感。 |
| B 深色仪表盘 | 信息密度最高 | 深底卡 + 最高对比读数 + 胶囊进度条 + 右对齐指标列，"有仪表盘的存在感"。 |
| **C 柔和品牌色（定稿）** | 最有温度 | 每卡一个色相 + 极浅 tint 卡底 + 实色圆角图标块（白色图标）+ 同色系进度条；数字仍是最重元素。 |

C 的收敛改动（按 review 意见）：彩度降一档、卡底改极浅 tint、分区面同色系深一档、图标块改为**实色小块 + 白色图标**（无渐变）、三处硬缺陷全修（health 2×1 右半填满 / 1×1 只留图标 + 一个词或一个大数字 / adskip 三列 1fr 均分左对齐不贴边）。

## 2 色值 token 表（实现方照此建 WidgetPalette）

浅色 = `light` 列，深色 = `dark` 列。所有值都是**算好的实色**（Glance 无 alpha 叠色）。

| 组件 | 卡底 tint（浅/深） | 内嵌分区面（浅/深） | 图标块（浅/深） | 进度条填充（浅/深） | 进度条轨道（浅/深） |
|---|---|---|---|---|---|
| finance | `#F4F8FF` / `#16223A` | `#E2ECFD` / `#1D2C49` | `#2F6BFF` / `#3D78FF` | `#6A93F0` / `#5285F0` | `#D9E5FA` / `#22314F` |
| quota | `#F7F4FF` / `#1E1934` | `#E7DFFC` / `#2A2247` | `#5B4BE0` / `#6B58E8` | `#8D7AE8` / `#7A68EA` | `#E3DBF8` / `#2B2447` |
| health | `#F2FBF5` / `#13281F` | `#DAF2E4` / `#1A382C` | `#0E9F5B` / `#12A862` | `#4FBF8B` / `#33AB74` | `#D3EDDF` / `#1B382C` |
| adskip | `#FFF8F0` / `#2A1F13` | `#FCE2CD` / `#3A2A1B` | `#CF5F25` / `#D9691F` | `#E29160` / `#CC7D43` | `#F8E0CB` / `#3B2B1D` |
| transfer | `#F1FBFC` / `#122730` | `#D8EFF2` / `#193A44` | `#0F97AC` / `#11A2B8` | `#40B3C2` / `#33A2B6` | `#D2EDF1` / `#173740` |
| quickadd | 实色块 `#007AFF`（深色同值） | — | 白色 `+` 图标 | — | — |

**全局**：主文字 `#0E1A2E` / `#FFFFFF`；次要文字 `#5C6B85` / `#A2B1CB`；卡描边 `#DDE7FA…`（深色比卡底亮一档，如 `#27385A`）。
**语义色**（全卡预算 ≤2 处）：正常/监控中 `#0B7A3B` / `#4ADE9B`；异常/未运行 `#BF3A2E` / `#FF7A70`；额度告警 `#A9601F` / `#FFBE6B`。
**对比度**：主文字对卡底 ≥12:1，次要文字 ≥5.2:1（深色 ≥7:1），语义色作为**文字色**时取上述已加深值（不直接用系统原色）。

## 3 元素 → Glance 1.2.0 落地表

| 视觉元素 | Glance 落地方式 | 结论 |
|---|---|---|
| 卡片 24dp 圆角 | `GlanceModifier.cornerRadius(24.dp)` | 可（仅 API31+；31 以下退直角，用 shape drawable 兜底） |
| 卡底 tint 面 | `GlanceModifier.background(ColorProvider)` 纯色 | 可 |
| 内嵌分区（底栏两块 / 额度三行） | Box + `background(color)` + `cornerRadius(14.dp)` + padding | 可 |
| 彩色圆角图标块 | Box(`background(hue)`, `cornerRadius(10.dp)`, 26×26dp) | 可 |
| 图标本体（白/单色） | `Image(ImageProvider(R.drawable.ic_cat_*), contentDescription=null, contentScale=Crop, colorFilter=ColorFilter.tint(White))` | 可（复用项目已有 150 个 `ic_cat_*` 矢量） |
| 眉标 / 标签文字 | `Text(text, style=TextStyle(fontSize=12.sp, fontWeight=Bold, color=…))` | 可 |
| 主数值（72dp 字号、¥ 上标、小数小一号） | `Text` 用 SansSerif + `FontWeight.Bold` | **部分可**：Glance 1.2 的 Row 无 baseline 对齐，三档字号的 ¥/整数/小数**不能**一根基线对齐 → **砍掉大小字号混排**，实现时整数与小数同一字号（68sp），`¥` 用单独 24sp 的 Text 靠 Row(`GlanceModifier.defaultWeight()` + 顶部对齐) 近似，或整体并入同一 Text |
| 进度条 8–10dp 圆角 | `LinearProgressIndicator(progress, modifier.height(10.dp), color, backgroundColor)` | 可（粗细靠 height，端头圆角靠 cornerRadius API31+；31 以下直角） |
| 三列读数 + 1px 分隔线 | `Row` + `GlanceModifier.defaultWeight()` 子 Column；分隔线用 `Box(width=1.dp, height=fillMaxHeight, background(borderColor))` | 可 |
| 分隔线/描边 | `Box` 细条 或 shape drawable 的 `<stroke>` | 可 |
| 状态点（绿/红/灰 7dp） | `Box(size 7.dp, cornerRadius(999.dp), background(语义色))` | 可 |
| 空态文案（未运行 / 今天还没跳过 / 未设预算 / 未配置额度） | `Text` | 可 |
| 卡片阴影 / 模糊 / 玻璃 | — | **砍**（Glance 无阴影）。浮起感只靠"卡底与壁纸色差 + 卡描边 + 内嵌分区"三件套 |
| letter-spacing（紧凑字距） | — | **砍**（Glance Text 不支持字距）。紧凑感靠 Bold + 大字号 + 数字左对齐实现 |
| 渐变卡底 / 渐变文字 | — | **砍**（RemoteViews 混色不可控；渐变 shape 是否被 rasterize 未实测，不押版式） |
| 环形/弧形进度 | — | **砍**，一律 LinearProgressIndicator |
| 自定义字体 | — | **砍**，用系统 SansSerif |
| 桌面壁纸 / App 图标 / dock / 页面点 | 原型里的桌面装饰 | 不属于组件，组件代码不涉及 |

**待实现验证（1 条）**：`GlanceModifier.background(ImageProvider, contentScale, colorFilter)` + XML 渐变 shape 的 rasterize 行为——本版**没有**依赖渐变，只有纯色与 shape 圆角，风险为零。

## 4 用户 5 条"丑"的成因 → 对应解法

1. 只有灰标签 + 黑数字的行堆叠 → 每卡 ≥1 个图形锚点：实色圆角图标块（白图标）+ 进度条 / 状态点。
2. 4×2 中间 60% 空白 → 主数值块占卡高 ~36%（72dp 字 / 200dp 卡），底栏分区压到底，内容垂直占满 ≈95%。
3. Monospace 数字显土 → 全卡 SansSerif + Bold（800），无等宽、无 letter-spacing；数字"厚"靠字号与字重。
4. 无层次 → 主数值 72dp / 次读数 15–24dp / 标签 9–12dp（≥2.2 倍差）+ 三级色阶（主文字 / 次要 / 分区面）。
5. 卡片贴死壁纸 → 卡底 tint 与深色壁纸形成明显色差 + 1dp 描边 + 内嵌分区面，完全不依赖阴影。

**硬判据自查**：4×2 主数值字面高 ≈50dp / 200dp = 25%（行盒 74dp = 37%）；每卡饱和色块 1 个（图标块），语义色 ≤2 处（health 红/绿点、quota 告警一行）；1×1 均为"图标 + 一个数字或一个状态词"，不是两行字。

## 5 自测结论（逐条，不写"全绿"）

| 产物 | 命令 | 退出码 | 结论 |
|---|---|---|---|
| `08-prototype.html`（交付件） | `odops verify … --platform mobile` | **0** | 自包含 ✓ / CSS 内联 ✓ / :root 含 6 个核心语义变量 ✓ / 无裸色值 ✓ / 官方 anti-slop lint P0·P1 全过 ✓。两条 warn：① :root 缺 50 个官方 schema 槽位（本原型是组件级 token，不是整包 apple 风格）② 找不到 `.open-design/00-brief.md`（需求书在 `00-brief-widgets-r2.md`，画布/断点已写在文档里）→ 均为 warn，不阻塞 |
| `versions/a.html` | 同上 | **1** | 未过：无 :root token 块 + 49 处裸色值（P1 raw-hex）。A/B/C 是三方向**对照存档**，只做视觉比较，不做 token 化；定稿 C 已 token 化并通过 |
| `versions/b.html` | 同上 | **1** | 同上 |
| `versions/c.html` | 同上 | **1** | 同上（c.html 是收敛前的 C，存档用；定稿看 08-prototype.html） |
| 渲染层度量 | `--browser`（点击区/对比度/溢出） | 未跑 | **未覆盖**：本机未接 agent-browser 会话，这次只做了静态检查 + 我逐屏看图。对比度按上表手算（主 ≥12:1、次 ≥5.2:1），点击区不适用（桌面组件整块点击，最小 1×1 = 80dp ≥44dp） |

**机械覆盖度**：结构 / 内联 / token / 裸色值 / anti-slop 五项已扫（见上），渲染层 0 项 → 标"未覆盖"，不当作通过证据。

## 6 截图

- 全页：`.open-design/shots/widgets-r2-proto-full.jpg`（390×3448，143KB）
- 四屏：`widgets-r2-proto-1-light.jpg` · `-2-dark.jpg` · `-3-sizes.jpg` · `-4-states.jpg`（各 390×844、≤55KB）
- 三方向存档：`.open-design/widgets-r2/versions/{a,b,c}.html`（对照页 `compare.html` 由 lead 维护，本文件未改动）

## 7 已知取舍

- 1×1 只有 80×80dp：只保留"图标 + 一个大数字或一个状态词"（`未运行` / `0 · 今日跳过` / `监控中`），完整句「今天还没跳过」放到 2×1 档位（第 4 屏可见）。
- 陈列屏（第 3 屏）用白底是为了看清每档形态，不用壁纸；桌面实景看第 1/2 屏。
- 第 3/4 屏是**演示件**，不进产品；实现只做第 1/2 屏的 6 个组件与第 4 屏的状态分支。
