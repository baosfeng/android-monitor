# 交付自检 · 记账查询与图表优化（r5）

基线 `5797250`（`原型：统计 tab 增加日/周/月/年/全部单位切换与交互式图表`）· 作者自测 + 独立审计，结论归 leader。

## 本轮交付（用户拍板的原型 → 实现）

| 交付 | 落点 |
|---|---|
| 记账 Tab 两个子 Tab（交易明细 / 统计汇总） | `finance/ui/FinanceSubTabBar.kt`、`FinanceMainScreen.kt` |
| 五档时间单位查询（今日 / 本周 / 本月 / 本年 / 全部） | `finance/FinancePeriod*.kt`（数据层）+ `finance/ui/FinancePeriodSection.kt` |
| 每档「两格 KPI + 带坐标轴图表」 | `ui/components/ChartFrame.kt`（Y 轴刻度 / X 轴标签 / 单位 / 数值直标） |
| 概览页内嵌记账图表卡 | `ui/overview/OverviewFinanceCard.kt` + `OverviewViewModel` |
| 设置页「记账功能（可按需开启）」三开关 | `ui/foundation/FinanceUiPrefs.kt` + `ui/SettingsFinanceUiSection.kt` |

## 门禁结果（本机实跑）

| 项 | 命令 | 结果 |
|---|---|---|
| 构建 | `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL；`adb install -r` Success |
| 原型一致 | `node scripts/assemble-prototype.mjs --check` | `产物与分片一致 ✓` · exit 0 |
| 锚点账 | `node scripts/check-anchors.mjs` | 账本 740 / 落地 617 / 缺失 123（全在作废清单）· **自增 0** · exit 0 |
| 无障碍 | `bash scripts/check-a11y.sh` | PASS（ERROR 0 / WARN 1 存量 totp） |
| UI 规则 | `bash scripts/check-ui-rules.sh` | PASS（4 项 0 命中） |
| 覆盖率 | `bash scripts/check-coverage.sh` | PASS（domain 78.71% ≥ 35%） |
| 文档 | `node scripts/check-docs.mjs` | PASS（死链 0 / 孤立 0） |
| 单测（本改动范围） | `./gradlew :app:testDebugUnitTest --tests 'com.bsfeng.monitor.finance.*'` | 351 例 0 失败（含新增 14 例） |

> 全量 `./gradlew :app:testDebugUnitTest`（2560 例）在 `transfer` 包有 3–4 例偶发失败（TransferResendSourceTest / TransferEngineDeletionTest / TransferEngineSendTimeoutTest），**单独重跑该组即绿**，且本轮未触碰 `transfer/**`（`git status` 佐证）。属既有 flaky，不阻塞本轮。

## 关键交互实测（模拟器 emulator-5554，`scripts/debug-cli.sh` 语义树 + 截图）

| 动作 | 断言 |
|---|---|
| 子 Tab 切换 | 两段 bounds 可点，`selected` 语义随之翻转（selectable + role Tab） |
| 五档切换 | 今日 ¥96 / 本周 ¥96 / 本月 ¥588 / 本年 ¥2,116 / 全部 ¥2,226，与库表 SUM 逐位一致 |
| 图表带轴 | 每档有 Y 刻度值 + X 标签 + 右上单位 + 图注峰值；今日档 24 格有 0/6/12/18/24 刻度 |
| 概览记账卡 | 三行金额 + 分类环 + 带轴趋势（X 7 个日期、Y 3 档刻度、峰值标注） |
| 三开关联动 | 关首页卡 133→125 节点、关多周期入口 144→120 节点、开预算与预警出现「预算」环 |
| 默认值 | 清 prefs 后：预算与预警关 / 首页记账卡开 / 多周期查询入口开（与原型 s09 的 aria-checked 一致） |

## 有意偏离（逐条报备，均已复核）

| id | 属性 | 原型值 | 实现值 | 类型 | 保留理由 |
|---|---|---|---|---|---|
| s01-legend | 图例条数 | 固定 6 类 | Top3 + 「其他 N 类」 | C 原型没画 | 真实数据分类数不固定，固定 6 行会出现空行 |
| s04-chart-day | 今日档图表口径 | 逐时**支出金额**热力 | 同（金额口径，标题「今日 24 小时消费金额」） | 一致 | 审计曾误报「实现用笔数」，已改为金额并订正 |
| s04-multiperiod | 卡底说明 | 两段长文案 | 一行「按日 / 周 / 月 / 年 / 全部统计，收入与结余同口径」 | A 翻译损耗 | 移动端一行放不下两段；信息量保留 |
| s04-stats-kpis | KPI 卡位置 | stats pane 内 | stats pane 内（多周期卡之后、双入口之前）；与明细 pane 同源同值 | 一致 | 两份不挂同名锚点，避免 testTag 双渲染 |
| s09-finance | 行副标题 | 完整一句 | 一行 clamp（不省略） | A 翻译损耗 | 390dp 宽卡内一行放不下原文 |

## 待办（不阻塞交付，用户决定做不做）

- 原型侧：`.open-design/src/styles/10-tokens.html` :root 外 47 处裸色值（`--chart-1..16`）、未使用槽 `--chart-10 #6366f1`、底部 Tab 文案对比度 4.31:1。
- 门禁侧：`check-anchors` 的 `missing` 只从账本算，原型 790 vs 账本 740 的差集结构上看不见 → 建议加「spec ⊄ 账本」检查。
- 运行时 a11y：设置细则页顶部演示条 4 处 41.9dp（既有演示件，非本轮引入）。
- `transfer` 包 3–4 例 flaky（与本轮无关），建议单独跟进。
