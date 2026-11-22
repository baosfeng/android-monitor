# Android Monitor · 项目自有设计风格 v1

> 这是"原型画得出的东西 = 实现写得出的东西"的操作契约。OD 生成原型时会注入本文件；
> 写原型、评审原型、以及实现前对表，都按这里逐条走。取值在 tokens.css，本文件只解释**为什么**。

## 1 这套风格从哪里来（证据等级）

- **源码实测**：所有颜色 / 字号 / 间距 / 圆角取自 `.open-design/src/styles/10-tokens.html` 的 `:root`，与实现 `ui/theme/DesignTokenTable.kt` 同一来源（生成器 `tools/gen-design-tokens.mjs`）。
- **规范明文**：组件边界与反模式取自 `docs/UI设计/设计规范.md`、`docs/UI设计/能力矩阵.md`、`docs/UI设计/审查清单.md`。
- **推导**：`--container-max`、`--section-y-*` 是 web 端 schema 必需槽位，Android 全屏页面用不到，按 1024 / 100 / 64 / 40 占位，**不是量出来的**。
- **冲突处理**：文档写 `MonitorRadius.lg = 12`，源码 `--radius-lg = 18px`；以源码为准，圆角优先取 `LocalDesignTokens.current.radius*`。

## 2 气质：iPhone 分组卡 + 高信息密度

- 基础骨架是 iPhone 设置式的分组卡片：分组灰底、白色卡片、1px 发丝描边、圆角分档。
- 信息密度优先：一屏尽量多信息；能用图表表达的不堆文字；行高克制（`--leading-body 1.47` 只在正文）。
- 只有浅色。运行时 8 套主题（apple / glassmorphism / notion / stripe / claude / ibm / kami / bento）**只换色，不换结构**；原型不要提出深色或"换个气质"的版本。

## 3 颜色角色：四类互不混

| 角色 | token | 用法 |
|---|---|---|
| 语义 | `--bg` `--surface` `--surface-warm` `--fg` `--fg-2` `--muted` `--meta` `--border` `--border-soft` `--accent` | 页面、卡片、文字、描边、主按钮 |
| 状态 | `--success` `--warn` `--danger` | 收支、预算、错误；不参与分类色板 |
| 分类 | `--chart-1..16` | 财务分类圆点、分类图标；按 `sort_order % 16` 取 |
| 强度 | `--heat-*`（实现侧 heatRamp） | 热力格；只表达量级，不表达类别 |

- 强调色一屏 ≤ 2 次；链接与选中态同色。
- 应用品牌色当"色块 + 白字"时用压暗档（原色 60% + 前景 40%），原色过不了 4.5:1。
- 文字对比度 ≥ 4.5:1；纯装饰色不承载信息。

## 4 字体：族 / 字阶 / 行高 / 字距

- 字族：系统字体栈（`-apple-system` / Android 系统字体），不引入自定义字体文件；等宽族只用于数字与时间列，配 `tabular-nums`。
- 字阶（px）：12 / 14 / 17 / 21 / 28 / 40 / 56 / 80，正文基线 **17**。
- 实现侧映射：headlineLarge 34 · headlineMedium 28 · headlineSmall 21 · titleLarge 17 · bodyMedium 16 · bodySmall 14 · labelSmall 12；9sp 只给类型角标。
- 标题 Bold、列表标题 Medium、正文 Normal。字号只从 token 取，不写裸 `font-size`。

## 5 间距、圆角、行高

- 间距只用 8 档：4 / 8 / 12 / 16 / 20 / 24 / 32 / 48。2 / 6 / 7px 不在档位内——**原型里出现即视为待处理**（补 token 或改档位，见 `docs/UI设计/原型实现对齐台账.md`）。
- 圆角：控件与标签 `--radius-sm 8`、卡片 `--radius-md 12`、弹窗 `--radius-lg 18`、胶囊 `--radius-pill 980`。圆角跨主题会变，原型不要写死。
- 触达区 ≥ 44（`--tap-min`）；列表行最小高 52（`--row-min-h`），记账明细是 48 的登记例外。
- 卡片内边距默认 16；紧凑行内 gap 用 8。

## 6 组件基元：只能用这些

实现里存在同名 Compose 基元（`ui/components/`）。原型只用这一组，**不要自拼新形态**：

- 卡片：`AppCard` / `GlassCard` / `InsetGroupedCard` / `InsetGroupedSection`
- 行与设置项：`IOSRow` / `SettingsRow`；分组列表一律 `InsetGroupedSection`
- 导航：`IOSNavigationBar`（禁止手拼 Row + Text）
- 浮层：`AppDialog` / `ConfirmDialog` / `BottomSheet` / `AnimatedScreenOverlay`
- 状态：`StateContainer` 五态（加载 / 空 / 错 / 边缘 / 有数据）+ `StateSkeleton` / `SkeletonRow`
- 控件：`Chip` / `SegmentedControl` / `Badge` / `AppSwitch` / `TextField`

新增基元的前提是先在 `Components.kt` 落地并登记进能力矩阵；原型先画、实现后补的形态一律视为"待实现"，要进 `scripts/anchor-retired.tsv`。

## 7 图表：一套轴与刻度

- 只用实现已具备的类型：`BarChart` / `GroupedBarChart` / `LineChart` / `AreaChart` / `ScatterPlot` / `PieChart` / `RingChart` / `Sparkline` / `HeatBar`。
- 轴规则：Y 轴 3 格、网格与刻度共线；柱与面积上限取实际峰值；全 0 不直标；X 轴刻度只标原型声明的档位。
- 颜色角色单一（序列 / 分类 / 强度三选一）；不做双 Y 轴、缩放、平移、十字准线。
- 图表高度 `--chart-h 148`、轴标签 11px、热力格 44 高。

## 8 动效：三档 + 单入口

- 强度三档 `FULL / NORMAL / REDUCED`；系统动画缩放为 0 时强制 `REDUCED`，且只降不升。
- 时长只取 `--motion-fast 150` / `--motion-base 220` / `--dur-page 340` / `--dur-chart 600`；缓动取 `--ease-standard` / `--ease-spring` / `--ease-out` / `--ease-in`。
- 转场唯一入口 `foundation/Transitions.kt`；按压 0.96 + 触感；数字滚动用 `AnimatedNumber`。
- reduced-motion 下直接落终态，不保留位移与缩放。

## 9 可访问性四红线

1. 每个可交互元素有可读名；
2. 有稳定标识，且 `testTag` 与原型 `data-od-id` 逐字一致；
3. 触达 ≥ 44；
4. 状态语义（选中 / 开关 / 进度）可被读屏读到。

纯装饰图标用 `clearAndSetSemantics {}` 或注释标记；ERROR 0 无豁免。

## 10 反模式（原型里出现即判不可实现）

- 裸 hex、裸字号、裸圆角、非档位间距（2 / 6 / 7px 等）。
- 深色主题、毛玻璃模糊（`backdrop-filter`）、三层以上阴影叠加。
- 自定图表刻度、双 Y 轴、缩放平移。
- 未登记组件类、用图标或图片冒充文字。
- 字号小于 12（9sp 角标除外）、正文超过两行不设截断。

## 11 平台特例：Android / Compose

- **量纲**：1px = 1dp（无缩放）；sp 只用于字号，不参与间距。
- **字体 metric**：同一字号在 Android 与浏览器里的宽度不同，**行高会变**。原型里靠 `line-height` 控制的换行，在实现里默认写成 `min-height + maxLines`，关键块给期望行数。
- **视口**：原型设备框内容宽 390 对应 dp 390 的可用宽度（不含状态栏与导航栏）；实现按真实可用宽度布局，卡片左右各 16。
- **动态字号**：正文用 sp，放大字号时用 `maxLines` 兜底，不撑破卡片高度。
- **系统 inset**：Edge-to-Edge 强制；底部导航与 FAB 的避让由实现负责，原型不必画。

## 12 交付前自检

```bash
node scripts/check-prototype-rules.mjs   # 新增孤儿值判红
node scripts/assemble-prototype.mjs      # 分片 → 单文件产物
odops style-check .open-design/design-system
```

原型定稿后 `odops spec` 导出锚点几何，实现按锚点对表；差异按 `odops parity` 的口径分级（缺块 / 字号 / 圆角 / 背景色为 P0）。
