# 00 需求澄清 · T3 轨迹卡精度诚实呈现
#
# 机器可读：key: value 一行一条，gallery 的 --frame、verify 的 --platform / 断点检查都从这里取。

platform: mobile
canvas: 390x844
frames: [iphone-15-pro]

style: apple

responsive: no
breakpoints: [390]
min_supported: 390

dark_mode: optional
locale: zh-CN
deliverable: interactive-prototype
first_screen: 每日行程页的轨迹卡（卡头 + 180px 画布 + 图例 + 卡尾说明 + 覆盖条），同页并列「常规日（含低精度点）」与「历史粗坐标日」两张卡
fixed_constraints: 画布高度恒 180px；不加按钮；颜色只用现有 token（success/warn/danger/accent/fg-fade/muted）；不新增交互锚点
notes: 用户原话「5m 的 GPS 点与 400m 的 network 点看起来一样可信 —— 这是不表达不确定」「历史数据里有大量 3 位小数（≈110m）坐标，画出来是网格阶梯」；低精度不能靠颜色单独表达（色盲/灰度屏要也能看出来）
