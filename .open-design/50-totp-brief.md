# 50 需求澄清 · TOTP 验证器（多平台 2FA 码）

# 机器可读：key: value 一行一条。verify/gallery 用 --brief 显式指定本文件。

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
first_screen: 验证器首页 —— 多平台账户列表，每行 6 位等宽验证码 + 30s 环形倒计时，点行复制

## 背景

用户要替代「腾讯身份验证器」这类独立 App：把 TOTP 二次验证码集成进 android-monitor。
**本项目不是服务端**，只做验证器客户端 —— 解码 otpauth:// 后在本地算码显示。

「多平台」= 账户来自 GitHub / 腾讯云 / 阿里云 / Google / AWS / 微软等任意 issuer。
协议统一走 RFC 6238，差异只在 issuer 展示与账户级参数（SHA1/SHA256、6/8 位、30/60 秒）。

## 页面清单（全部为 push 屏，不加底部 Tab —— 现有 7 个已满）

| 落点 | 屏 | 要点 |
|---|---|---|
| 新增 | s22 验证器首页 | 账户列表 + 搜索 + 平台分组；空态 |
| 新增 | s23 添加账户 | 三入口：扫码 / 手动输入 / 从相册识别 |
| 新增 | s24 扫码 | 取景框 + 手电筒 + 相册入口 + 失败态 |
| 新增 | s25 手动输入 | 平台名 / 账户 / 密钥(Base32)，高级参数折叠 |
| 新增 | s26 账户详情 | 改名称与平台、排序、删除（二次确认） |
| 新增 | s27 备份与导入 | 加密导出、从 Aegis / 2FAS 导入 |
| 修改 | s01 今日概览 | 新增「验证码」卡片：前 3 个账户 + 倒计时，点标题进 s22 |
| 修改 | s09 设置 | 新增「验证器」分组：功能开关 + 三项安全开关 |

## 视觉与交互约束

- 只用既有 token（--accent / --surface / --fg / --muted / --border-soft / --warn / --danger），**不新增颜色**。
- 验证码用等宽族（--font-mono + tabular-nums），尺寸对齐 --text-xl 级；不用 Material 默认字。
- 倒计时：30s 环形（conic-gradient 或 SVG stroke-dasharray 均可），剩余 ≤5s 切 --warn。
- 点行复制 → Toast「已复制，30 秒后自动清空」；长按行 → 编辑菜单。
- 平台图标：色块 + 首字母（或极简内联 SVG），**不引外部图片**、不引品牌字体。
- 空态复用 .st-empty，列表骨架复用 .sk；5 态齐全（加载/空/错误/边界/正常）。

## 演示数据（占位，可安全展示）

- GitHub / alice@acme.com；腾讯云 / ops@acme.cn；阿里云 / dev@acme.cn；Google / alice.work@gmail.com；AWS / root@acme（仅占位）
- 密钥一律用公开测试值 `JBSWY3DPEHPK3PXP`，验证码用固定假数字；**绝不出现真实账号与真实验证码**
- 平台名与账号可截断展示，用于验证窄屏省略态

## 拍板记录

1. **入口 = 概览页顶部栏右上角图标**（用户 2 轮后定稿）：不加底部 Tab、不加概览卡片，全模块只留这一个入口。
   理由：7 个 Tab 已满，第 8 个会把单 Tab 压到 49dp；图标不占位且直达。
2. 概览页「验证码」卡片**不做**（已实现的原型卡要删掉），避免同功能双入口。
3. 验证器本身仍是 push 屏（无底部 Tab），由图标进入，返回即退出。
2. 安全默认全开：防截屏 / 复制即焚（30s 清空剪贴板）/ 后台遮罩，只留开关不放默认（用户已确认）。
3. 二维码解码：引入 `com.google.zxing:core`（纯 Java、无 native、Apache 2.0），红线措辞改为「零第三方**业务**依赖 + 算法库白名单」（用户已确认）。

## 不做（本原型范围内）

- 不画 Steam Guard 等非标准变体（模型留 variant 字段，UI 不体现）。
- 不画桌面小组件、不画通知栏常驻。
- 不画多语言、不画平板/横屏。