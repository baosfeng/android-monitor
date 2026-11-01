# 50 · TOTP 验证器原型 · 本轮记录

> 分支 `feat/totp-verifier`，基线 commit `17d689e`。需求冻结在 [50-totp-brief.md](50-totp-brief.md)。
> 产物：`.open-design/08-prototype.html`（由 `node scripts/assemble-prototype.mjs` 从 `src/` 组装，勿手改）。

## 本轮范围

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `src/screens/s22-totp-list.html` | 验证器首页：搜索 + 平台分组 + 6 位码/环形倒计时 + 五态 |
| 新增 | `src/screens/s23-totp-add.html` | 添加入口三选一（扫码 / 手动 / 相册） |
| 新增 | `src/screens/s24-totp-scan.html` | 取景框 + 手电筒 + 相册；识别失败横幅 + 相机会鉴错误态 |
| 新增 | `src/screens/s25-totp-manual.html` | 平台/账户/Base32 密钥 + 高级参数折叠 + 格式校验 + 参数预演 |
| 新增 | `src/screens/s26-totp-detail.html` | 改名 / 掩码显示密钥 / 排序 / 删除二次确认 |
| 新增 | `src/screens/s27-totp-backup.html` | 加密导出（密码 ≥8 位校验）+ Aegis/2FAS 导入 + 失败/超大备份态 |
| 修改 | `src/screens/s01-今日概览.html` | 新增「验证码」卡片：前 3 个账户 + 倒计时，点标题进 s22 |
| 修改 | `src/screens/s09-设置.html` | 新增「验证器」分组：总开关 + 管理账户 + 备份 + 三项安全开关 |
| 产物 | `08-prototype.html` / `shots/totp-list.jpg` | 组装产物与 390×844 截图 |

**未动**：`app/`、`domain/` 下任何 Kotlin；`src/00-shell.html`、`src/99-app.js`、`src/styles/*`、devbar。

## 决策与取舍

1. **全部 push 屏，不加 Tab**：s22–s27 不出现在 `99-app.js` 的 TABS 里，外壳据此隐藏 tabbar、显示 appbar 与 `data-title`；入口只有概览卡片、设置分组、以及列表页底部按钮。
2. **五态**：s22 覆盖 loading / empty / error / edge / populated（空态带「添加第一个账户」CTA）；s24 另有「相机权限被拒」错误态与「相册里没有二维码」失败横幅；s27 有导入失败态与超大备份边界态。扫描失败态是**可达的产品态**（点「从相册选择」即出现），不是演示开关。
3. **倒计时**：每行 `data-period / data-left` + 16–18px conic-gradient 环，1s 一跳；剩余 ≤5s 加 `.is-warn`（环 `--warn`、秒数 `--warn-text`），60s 周期与 8 位码降一档字号在 edge 态验证。
4. **验证码排版**：`--font-mono` + `tabular-nums` + `--text-xl`（8 位时降 `--text-lg`），与设计规范 §3 的等宽数字族一致。
5. **不新增颜色**：平台「色块 + 首字」用 `color-mix(fg 8%)` 中性底，未引入品牌色；相机取景面用 `color-mix(fg 88%, bg)`；未引任何外部图片/字体/CDN。强调色每屏 ≤2 处（主 CTA + 失败态手动输入）。
6. **演示数据**：账户与密钥全部取 `50-totp-brief.md` 的占位值（`JBSWY3DPEHPK3PXP`、`alice@acme.com` 等），验证码为固定假数字，无真实账号。
7. **改密码/删除/导入都走二次确认或校验**：删除是 `alertdialog`；导出的密码 <8 位直接拒绝；手动输入的密钥按 `^[A-Z2-7]{16,}$` 校验后才提示成功。

## 自测（本机亲跑）

| 命令 | 结果 |
|---|---|
| `node scripts/assemble-prototype.mjs --check` | exit 0 · `产物与分片一致 ✓`（29 屏分片 · 样式/结构各 29 段） |
| `odops verify … --brief 50-totp-brief.md --platform mobile` | **2 条 P0（均为基线既有，见下）**；37 个顶层区块全有 data-od-id；无裸色值新增 |
| `odops lint … --fail-on p1` | 2 findings（P0 indigo + P1 raw-hex），与基线逐字相同 |
| `odops shot … --wait 2800 --out shots/totp-list.jpg` | exit 0 · 390×844 jpg q80 |
| 渲染层（`--browser`，自测补充） | 无横向溢出；s22–s27 点击区全部 ≥44px（脚本量测）；s24–s27 无 <14px 文字告警 |
| 交互实测（agent-browser 会话 totp-proto） | 复制→Toast、长按/右键→账户菜单、删除二次确认→返回列表、手电筒、导出密码校验、导入对话框、密钥格式报错、倒计时 ≤5s 变色 |

## 已知问题（**全部在基线 HEAD 已存在，本轮未新增，且修复点在授权范围外**）

1. `[P0] ai-default-indigo`：#6366f1 来自 `src/styles/10-tokens.html` 的 `--chart-10`（16 色分类色板）。
2. `[P1] raw-hex` 47 处：同一段 `--chart-1..16` 的裸色值，被 lint 判为「:root 之外」。
3. `--browser` 对比度：#07c160 上的白字「微」(s01 应用角标)、tabbar 选中「概览」4.31:1。

三条都在 `src/styles/` 与既有分屏里，改它们要动 write scope 之外的 `.open-design/src/styles/10-tokens.html` 与 s01/shell；**待 leader 授权后再修**（最小改法：把 `--chart-1..16` 换成 `color-mix()` 或迁入官方 schema 槽位，并改用 `--chart-*` 派生对比度达标的强调色）。

## 下一轮建议

1. 授权后统一修 chart 色板 → 两条 P0/P1 一次清掉，verify/lint 全绿才能作为实现阶段的 parity 基线。
2. 实现阶段按 `odops spec` 抽锚点做 parity；s26/s27 的对话框与密钥掩码要单独取数（原型里是 `data-od-id` 锚点，Compose 侧用 testTag 对齐）。
3. 入口已收敛为「概览右上角图标 + 设置分组」两处；产物里不得再加底部 Tab 或概览卡片。

## r2 · 入口改版（用户改方案，2026-11-02）

用户先要独立 Tab，得知 8 个 Tab 会压到 49dp 后改选**概览右上角图标**，并要求「只保留一个入口」，因此**撤掉 r1 的概览「验证码」卡片**。

| 改动 | 位置 | 说明 |
|---|---|---|
| 删 | `s01-今日概览.html` | 「验证码」卡片的三段一并移除：section 块、`.s01-totp-*` 样式段、卡片倒计时脚本 |
| 增 | `s01-今日概览.html` 顶部 `.screen-head` | 右上角新增验证器图标（`aria-label="验证器"`、`data-goto="s22"`、44×44），与原有提醒铃铛并排 |
| 留 | `s09-设置.html` | 「验证器」分组（总开关 / 管理账户 / 备份 / 三项安全开关）未改 |
| 截图 | `.open-design/shots/` | `totp-entry-overview.jpg`（概览带图标）· `totp-list.jpg`（s22）· `totp-add.jpg`（s23），1280×924 全机框 |

**机制说明（与派发描述的一处偏差，已回报）**：外壳 `#odAppbar` 只有返回键 + 标题，**没有 actions 槽位**；且 s01 是 Tab 屏，`syncChrome()` 在有 tabId 时把 appbar 整个隐藏。所以入口挂不到 appbar 上，用的是 Tab 屏既有的 `.screen-head` + `.icon-btn`（s01 提醒铃铛同款，44px 点击区），**未改 `00-shell.html` / `99-app.js`**。若实现阶段要「全局 appbar actions」，需另开一轮改外壳。

**r2 自测**：`assemble --check` exit 0（866.2 KB · 13139 行 · 29 屏分片）；393×852 视口实测图标 44×44、距 head 右边 16px、高 8px，点击 → `App.current()`=`s22`，`[data-back]` 回 `s01`；`grep -c 验证码 s01-今日概览.html` = **0**（DOM 里也无 `s01-totp` 节点）；`odops verify` 仍只有那 2 条既有 P0，未新增。
