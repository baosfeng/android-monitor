# 交付自检 · 每日行程 + 页面轨迹（增量原型）

基线 `fa35ed6781429ea5e417971d4d502cf403fbbc4f` · 作者自测一遍，独立取证归审查。

## 门禁结果（本机实跑）

| 项 | 命令 | 结果 |
|---|---|---|
| 产物与分片一致 | `node scripts/assemble-prototype.mjs --check` | `产物与分片一致 ✓` · exit 0 |
| 产物自测 | `odops verify .open-design/08-prototype.html --platform mobile` | 无「打不开 / 破图 / 主流程不可用」；2 项 FAIL 为既有静态项（见下） |
| 渲染层 | 同上加 `--browser` | `ok 无横向溢出` · `ok 强调色用法 0 次` · `ok 23 个顶层区块都有 data-od-id` |
| 截图 | `odops shot … --out .open-design/shots/trips-390.jpg --wait 2600` | `1440x900，jpg q80，56 KB` |
| 手机尺寸截图 | `odops shot … --width 450 --height 930 --wait 2600` | `450x930，jpg q80，48 KB` |

> `odops shot` 默认在开屏 1.9s 内就抓图，会拍到开屏页；加 `--wait 2600` 才是界面。默认画布 1440×900，设备框（417 宽）在 450 宽视口才不被裁。

## 关键交互实测（agent-browser 真点击 + DOM 断言）

```json
{"net_notification":true,"net_page":false,"app_page":true,"app_notification":false,"all_page":true,"all_notification":true}
{"default_privacy":"a","a_visible":true,"b_visible":false,"after_b_attr":"b","after_b_a":false,"after_b_b":true,"badge":"档 B","after_c_texts":true}
{"s2_checked":true,"s2_sheet_visible":true,"focus":"关闭","s2_closed":false,"focus_back":"s16-seg-cafe"}
```

- 筛选并组：网络档只见通知、应用档只见页面、全部档两者都见。
- 隐私三档：默认 A；切 B 后 `.pv-a` 隐藏 / `.pv-b` 显示、角标「档 B」；切 C 后文本样例块显示。
- 停留段抽屉：点段打开、焦点进「关闭」、关闭后焦点回原段。

## 待办（不阻塞本轮拍板）

- P1 · 裸色值 47 处（既有 46 + 新增 1 = 新增 PAGE 行沿用微信品牌绿 `--app:#07c160`）。全部在主题 `[data-theme]` 命名空间块与 `app-icon` 内联变量，收敛方案归「token 化 app 图标」独立任务。
- P1 · `ai-default-indigo` P0 来自 `--chart-10: #6366f1`（token 命名空间块），既有问题，与本轮无关。
- P1 · `app-icon` 白字压品牌绿对比度 2.38:1（既有模式，新增 PAGE 行沿用）；Tab 文案「概览」4.31:1（既有）。
- P2 · 时间轴 5 个停留段复用 3 张详情抽屉（同地点共用），非逐段独立内容。
- P2 · 日期切换「前一天 / 后一天」只出 Toast，未做真实数据切换。
- P2 · 无 `00-brief.md`，`verify` 的画布/断点核对项未生效（沿用仓库既有状态）。
- P2 · 时间带轴标签 12px（已挂 `data-od-min-fs` 豁免），`verify` 仍会列进「小于 14px」提醒。

## 演示件（不进产品）

| 演示件 | 锚点 | 隔离方式 |
|---|---|---|
| 「重新检测」按序循环三态（未授权 → 缺后台定位 → 已就绪） | `s09-loc-recheck` | 实现时只保留真实自检结果，去掉循环 |
| 前一天 / 后一天 Toast | `s16-prev` / `s16-next` | 实现时接真实日期参数 |
| 文本样例占位「示例文本一 / 二」 | `.s16-texts` | 仅原型演示，实现侧不落库 |

## 取证截图（本地，不入库）

`shots/trips-390.jpg`（1440×900 首屏）· `trips-mobile.jpg`（450×930 首屏）· `trips-s01-band.jpg`（时间带）· `trips-s16.jpg` / `trips-s16-lower.jpg` / `trips-s16-b.jpg` / `trips-s16-sheet.jpg`（行程屏 + 口径 + 抽屉）· `trips-s09.jpg` / `trips-s09-collapsed.jpg`（定位卡两态）· `trips-s02-app.jpg`（应用档筛选）· `trips-band-zoom.jpg`（时间带放大）

---

# r2 增量自检（四档粒度 + 操作记录 + s17）

## 门禁

| 项 | 命令 | 结果 |
|---|---|---|
| 产物一致 | `node scripts/assemble-prototype.mjs --check` | `产物与分片一致 ✓` · exit 0 |
| 产物自测 | `odops verify … --platform mobile` | 无阻塞项；`ok 25 个顶层区块都有 data-od-id`、`ok 强调色用法 0 次` |
| 渲染层 | 同上 `--browser` | `ok 无横向溢出`；对比度 FAIL 仍是既有 3 项（`微` 2.38:1 ×2、`概览` 4.31:1） |
| 裸色值 | 同上 | 47 处，与 r1 持平（本轮新增页面全走 token，未新增字面量） |
| 截图 | `odops shot … --out .open-design/shots/trips-390.jpg --wait 2600` | `1440x900，jpg q80，56 KB` |

## DOM 断言（agent-browser 真点击）

```json
{"levels":4,"default":"a","c_texts":"flex","c_fold":"block","c_ctrl":"none",
 "sheet1_checked":true,"fold_lines":20,"fold_open":true,"d_ctrl":"block","d_chips":15,
 "log_checked":true,"log_hint":"flex","badge":"档 D"}
{"empty_main":"none","empty_block":"grid","populated_main":"block","opened":true}
{"after_reenter_s2":false,"sheet_visible":false,"active":true}
{"title":"微信 · 记录粒度","badge":"当前 标题","opt_a":"true","sheet_closed":true,
 "wechat_level":"c","wechat_tail":"全文","douyin_opt":"true","black_before":3,"black_after":2,"icon_size":44}
```

- 四档：默认 A；档 C 出 3 行文本 + 展开全文（20 行，可展开）；档 D 追加可操作项与 5 个 chip。
- 开关：默认关；开启后卡头隐私提示显示、三张抽屉的「操作记录」分组显示。
- 进屏复位：开过详情 → `App.go('s01')` → `App.go('s16')` → 抽屉全部取消勾选（观察器异步 50ms 内生效）。
- 空态：`App.state('s16','empty')` → 主区 `display:none`、`.st-empty` `display:grid`；`populated` 恢复。
- s17：抽屉标题/当前值随行变化；选「全文」写回行内档位并收起；黑名单移除 3→2，删除按钮 44px。

## 本轮修掉的既有坑（写一次防复发）

- **flex 子项收缩**：`.s16-full` 是 `flex-direction:column` + `max-height` + `li{overflow:hidden}`，`min-height:auto` 被 `overflow:hidden` 归零，20 行被压成 8px 一条（截图肉眼可见）。修法：`li{flex:0 0 auto}`。
- **说明文案共用名称变体**：`.pv-c` 同时被「名称」和「说明」使用，导致档 D 把 C/D 两句说明一起显示。修法：说明另起 `.cap-*`。

## 待办（不阻塞）

- P2 · 单个 App 的档位尚未与 s16 时间轴联动（s16 仍按屏内口径显示）。
- P2 · 敏感页「添加」只出 Toast，无真实规则编辑。
- P2 · `.app-icon` 白字压品牌色对比度问题在 s16/s17 同样存在（当前 `verify` 只量首屏，未命中）。

## 取证截图

`shots/r2-s16-sheet-d.jpg`（档 D + 全文展开 + chip）· `r2-s16-ops.jpg`（操作记录分组）· `r2-s17-list.jpg`（App 分档列表）· `r2-s17-sheet.jpg`（四档弹层）· `r2-s09-entry.jpg`（设置入口卡）· `r2-zoom-fulltext.jpg`（全文行高修复前后对照）
