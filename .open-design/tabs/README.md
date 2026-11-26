# tabs/ · 每个 Tab 一个独立 html

由 `scripts/split-prototype-tabs.mjs` 从 `.open-design/src/` 分片自动生成，**不要手改**。
改界面改分片 → `node scripts/split-prototype-tabs.mjs` 重新生成。

## 与 08-prototype.html 的关系

| | 08-prototype.html | tabs/tab-*.html |
|---|---|---|
| 生成脚本 | `scripts/assemble-prototype.mjs` | `scripts/split-prototype-tabs.mjs` |
| 形态 | 单文件、六 Tab 同页切换 | 每 Tab 一个自包含文件，独立双击打开 |
| 用途 | GitHub Pages / README 截图 / `odops verify` | 单独点某个 Tab、按 Tab 评审 |
| 内容 | **完全不动**，仍由原脚本产出 | 各自只带本 Tab 的屏 + 必要样式 |

两条门禁都必须绿：

```bash
node scripts/assemble-prototype.mjs --check      # 产物与分片一致 ✓（exit 0）
node scripts/split-prototype-tabs.mjs --check    # 6 个 Tab 文件与分片一致 ✓（exit 0）
```

## Tab → 屏归属表

判据：`99-app.js` 的 `TABS`（六 Tab 首页）+ 全分片 `data-goto` / `App.go` 的跳转闭包。

| 文件 | 屏 | 为什么归这里 | 体积 / 行数 |
|---|---|---|---|
| `tab-overview.html` | s01, s16 | s16 每日行程只从 s01「今日行程」卡进入（`data-goto="s16"`） | 238.1 KB / 4551 |
| `tab-live.html` | s02 | 单屏 | 187.7 KB / 3420 |
| `tab-ad.html` | s03 | 单屏（s10 开屏跳过规则从 s03 空态 CTA 进，但主入口在设置，归设置） | 175.9 KB / 3392 |
| `tab-finance.html` | s04, s05, s06, s07, s08, s14a, s15 | s05–s08 全从 s04 进；s14a 记一笔由 s04 FAB 打开；s15 自动记账浮窗属于记账链路 | 513.0 KB / 6044 |
| `tab-quota.html` | s18, s19 | s19 额度设置从 s18 齿轮进（`data-goto="s19"`）；r3 重设计后两屏各自独立，评审稿 s20a/s20b/s21 已归档到 `drafts/archived/` | 213.1 KB / 3640 |
| `tab-settings.html` | s09, s10, s11, s12, s13, s17, s22–s27 | 全部从 s09 的各行进入；s22 验证器族虽也可从 s01 顶栏进，但主入口与管理动作都在设置 | 334.0 KB / 6427 |

s14 / s14b 是历史方案（仅样式、无结构），不进任何产物；s15 的浮窗结构落进记账 Tab。

## 实测结果（393×852，`file://` 直开）

`odops draft` 逐文件串行跑，全部 **exit 0**（横向溢出 0px、无页面 JS 报错）。

截图两套：
- `evidence/shots/<file>-393.jpg` —— `odops shot` 直出（会拍到开屏遮罩，overview 那张是 logo 页，**不能当首屏证据**）；
- `evidence/shots/<file>-settled.png` —— `verify-shots.mjs` 等开屏收起、`opacity==1` 后再截，**这才是首屏实况**。

| 文件 | draft | 真点击（步数） | innerText 字符 | 可见元素 | 有色元素 | console 报错 |
|---|---|---|---|---|---|---|
| tab-overview | 0 | PASS（10） | 597 | 1144 | 39 | 0 |
| tab-live | 0 | PASS（7） | 545 | 866 | 5 | 0 |
| tab-ad | 0 | PASS（8） | 500 | 590 | 9 | 0 |
| tab-finance | 0 | PASS（10） | 271 | 5372 | 34 | 0 |
| tab-quota | 0 | PASS（10） | 996 | 1236 | 21 | 0 |
| tab-settings | 0 | PASS（12） | 1150 | 2015 | 64 | 0 |

「可见元素」= `body *` 里 display≠none、visibility≠hidden 的个数，与 lead 独立复测的 1189 / 1286 / 2017 同一量级（差异来自入场动画时刻与隐藏态）。
「有色元素」= 计算样式 chroma>8 且非全透明的背景 / 文字色元素数（只数颜色真的有的）。
两列都证明页面有内容、有配色，**不是空屏**。

真点击脚本 `verify-tabs.mjs` 每文件的断言：首屏 = 本 Tab 首页、选中态正确、其余 5 个 Tab 是 `<a href>`、图表 100% 渲染、进子页后返回栈能回、弹窗开关、Toast、点别的 Tab 真跳转到对应文件、console 0 报错。
另有 `verify-geometry.mjs`（安全区五条边界）与 `verify-extra.mjs`（跨 Tab `data-goto`、主题延续、开屏时机）全 PASS，原始 JSON 在 `evidence/`。

### 安全区实测（第 8 条反馈）

| 项 | 值 | 结果 |
|---|---|---|
| 状态栏 | 44 | 全部一致 |
| 页头首行文字距状态栏 | 8px（s09 / s18）/ 16px（s01–s04） | 都不贴状态栏 |
| 底部 Tab | 56 | 全部一致 |
| 手势条 | 24 | 全部一致 |
| 内容区底边 = Tab 顶边 | 772 = 772 | 不压 Tab 栏 |

拆分页把 `--statusbar-h / --tab-h / --gesture-h` 覆盖成 44 / 56 / 24（原单文件是 36 / 52 / 22），并加 `.tabbar{height:var(--tab-h)}` 让底部 Tab 精确 56。

## 拆分页新增的三处行为

1. **底部导航**：本 Tab 仍是 `<button>`（原地切屏、保留选中态语义），其余 5 个是 `<a href="tab-*.html">` 真实链接——点别的 Tab 是整页跳转，不是原地切屏。
2. **跨文件兜底**：`App.go('s22')` 这类指向别 Tab 的跳转，目标屏不在本文件时改 `location.href = 'tab-settings.html#s22'`，落地后按 hash 直达该子页。底部导航对应的 `App.tab()` 同理。
3. **主题延续**：切主题写 `localStorage['od-theme']`，每个文件启动时读回，跨 Tab 跳转后主题不丢。

开屏只在「直接双击打开该 Tab」时播；从别的 Tab 跳进来（有 referrer）或带 `#屏号` 时不播，避免每次点「概览」都等 1.9s。

## devbar（原型控制条）保留情况

面板里的「主题 / 状态演示 / 重播开屏 / Toast」四个文件都有；跨 Tab 的演示按钮按屏存在与否自动摘除，不留空按钮：

| 文件 | 额外保留 |
|---|---|
| tab-finance | 自动记账浮窗 / 模拟落库 / 浮窗切页 |
| tab-quota | 无（原「额度聚合方案 A / B / 全额度聚合」三个按钮随 s20/s21 归档一并移除） |
| 其余四个 | 无 |

窄屏（≤460px）下 devbar 收成 44×44 圆钮并挪到左下——原来贴底居中偏右会压住右侧两个 Tab 与记账 FAB，导致「点不动」（实测 `agent-browser` 报 `covered by <button#devToggle>`）。

## 已知限制

- **跨 Tab 会整页刷新**：走 `location.href`，浏览器历史里会多一条记录；返回上一 Tab 用浏览器返回或再点底栏。
- **返回栈只在文件内**：从概览跳设置再返回，回到的是设置 Tab 的首页，不是概览（跨文件不进栈）。
- **主题是 localStorage 级的全局态**：六个文件共用一个 origin（`file://`），切主题会影响之后打开的所有文件——这是有意的跨 Tab 延续，不是串台。
- **draft 的 content/color 项偶发误报**：并发跑 `odops draft`（多个 agent-browser 会话同时抢浏览器）时会读到动画中途的页面而判「空屏 / 无配色」。**串行复跑 6/6 全绿**（`ok: true`）是本轮口径；有疑义以 `measure-tabs.mjs` 的 innerText 字符数与可见元素数为准，不采信并发时的那次读数。
- **不改设计**：拆分只覆盖安全区 4 个 token 与外壳（机身外框在手机宽度下取消、devbar 挪位），屏内部结构 / 配色 / 排版一律不动。

## 自测命令

```bash
node scripts/split-prototype-tabs.mjs --check      # 产物与分片同步
node .open-design/tabs/verify-tabs.mjs             # 真点击：Tab 跳转 / 返回栈 / 弹窗 / Toast / 图表 / console
node .open-design/tabs/verify-geometry.mjs         # 安全区五条边界
node .open-design/tabs/verify-extra.mjs            # 跨 Tab data-goto / 主题延续 / 开屏时机
node .open-design/tabs/measure-tabs.mjs            # innerText 字符数 + 有色元素数
node .open-design/tabs/verify-shots.mjs             # 等开屏收起后的首屏截图（evidence/shots/*-settled.png）
```

`odops shot` 也可直出单张：`odops shot .open-design/tabs/tab-quota.html --out x.jpg --width 393 --height 852`
（但它不等开屏，会拍到 logo 页——取证用 `verify-shots.mjs`）。
