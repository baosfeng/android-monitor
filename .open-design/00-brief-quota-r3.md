# 00 需求澄清 · 额度 Tab 重新设计（r3）

# 机器可读：key: value 一行一条，gallery 的 --frame、verify 的 --platform 都从这里取。

platform: mobile
canvas: 393x852
frames: [iphone-15-pro]

style: user:android-monitor
responsive: no
breakpoints: [393]
min_supported: 393

dark_mode: no
locale: zh-CN
deliverable: multi-page-prototype（按 Tab 拆成独立 html，每个文件可单独打开）
first_screen: 额度 Tab —— 一屏内同时看到所有账号 × 所有额度窗口的剩余量与重置时刻，不需要切换子 Tab

## 用户原话（8 条反馈，逐条为验收判据）

1. 各额度分散在子 Tab 下，看不到统一全貌；「80%」分不清是周额度还是小时额度，容易看错。
2. 同一个 provider 不支持多个子账号。
3. 不支持额度恢复提醒（重置后恢复时通知我）。
4. 额度后台更新不及时，经常要一小时才更新一次。
5. 复制 API Key 等信息时不能粘贴，只能一个个输入。
6. 排版设计不合理。
7. 小组件设计不合理。
8. 各页面适配不一致：有的适配水滴屏，有的没适配好；返回/页头贴着菜单栏（真机截图见 .open-design/impl-notes/quota-redesign/now-current.png）。

## leader 已拍板（可直接执行，不必再问）

- 风格沿用项目自有风格 user:android-monitor（既有界面重排不重选风格）。
- 取消「聚合 / 单家」分段：额度 Tab 变成单一滚动页，一屏看到全部账号，账号行内直接展开各窗口。
- 每个百分比必须**带窗口标签**（按小时 / 今日 / 本周 / 本月 / 订阅周期）与**重置时刻**，不允许出现裸百分比。
- 交付形态：按 Tab 拆成独立 html（规格见下），不再是单文件一个大原型。

## 待原型回答的结构问题（作者自行决定并写进 variants.md）

- 多窗口账号在一屏里怎么排：行内嵌窗口条 / 账号卡 + 窗口网格 / 时间轴式。
- 「恢复提醒」在额度页与设置页各占什么位置（本次只做界面，不做通知系统）。
- 小组件重排方向（额度组件与总览关系）。

## 断点取舍表（只有 393 一档）

| 断点 | 保留 | 收起 | 隐藏 |
|---|---|---|---|
| 393 | 账号名 + 窗口标签 + 百分比 + 重置时刻 + 更新时间 | 用量柱图 → 账号展开 | 历史趋势 |

## 硬约束

- 画布 393×852；状态栏 44px、页头 56px、底部 Tab 56px、手势条 24px 的安全区一律在骨架里，任何新页不得让页头贴住状态栏或让内容压到 Tab 栏。
- 颜色只用 `:root` token（裸色值 = 门禁红）；一屏强调色 ≤2 次。
- 所有可交互元素 ≥44px、有 aria-label、有 data-od-id 锚点。

## 交付形态（新增，用户本轮明确要求）

- `.open-design/tabs/` 下每个 Tab 一个独立 html：tab-overview / tab-live / tab-ad / tab-finance / tab-quota / tab-settings，各自的子页（如额度设置）跟在该 Tab 文件的返回栈里。
- 每个文件自包含（CSS 内联、无外链），单独双击即可打开与点击。
- 现有 `.open-design/08-prototype.html` 单文件产物保留不动（Pages / README 截图仍用它）。
