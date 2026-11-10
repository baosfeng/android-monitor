# TOTP 验证器

> 本机客户端：解码 otpauth:// 后在本地算码，不联网、不上传、不是服务端。

## 入口与开关

- 唯一入口是**概览页标题行右上角的盾形图标**（锚点 `s01-goto-totp`）；不开底部 Tab。
- 默认开启（`Feature.TOTP` 不在 `DEFAULT_OFF`）。设置 → 功能开关 → 验证器 可关；关掉只是收起入口，账户与密钥都保留。
- 模块内六屏都是 push 屏：列表 / 添加 / 扫码 / 手动输入 / 账户详情 / 备份与导入。

## 协议支持面

| 项 | 支持范围 | 说明 |
|---|---|---|
| 类型 | TOTP / HOTP | HOTP 按 `counter` 换码，界面给「下一码」按钮推进 |
| 算法 | SHA1 / SHA256 / SHA512 / MD5 | MD5 只在迁移与第三方导入出现，手动输入不引导选择 |
| 位数 | 1–10 位 | 按真实位数出码，不再把 7 位静默算成 6 位 |
| 周期 | 1–86400 秒 | 同上，15 / 20 / 45 / 50 秒等真实步长都按真值计时 |
| 密钥编码 | Base32（严格）/ Base64 | 先按 Base32 解，失败再按标准与 URL-safe Base64（Steam 的 shared_secret 就是 Base64） |
| 变体 | Steam Guard | 5 位字母码（字母表 `23456789BCDFGHJKMNPQRTVWXY`），不分段，周期固定 30 秒 |
| 二维码 | otpauth:// · otpauth-migration:// | 迁移格式一图多账户，批量落库；未知 protobuf 字段跳过，单条坏只跳过不中断 |

- 上限 4096 字符：够装迁移批量串，又能在解析前挡掉粘贴错的大段文本。
- 参数越界一律拒绝并给中文原因（`OtpauthUri.reasonMessage`），不静默降级；只有算法层内部会把越界值夹紧到合法区间。
- 不认识的参数、http(s) 钓鱼链接、`otpauth://motp` 之类的非标变体明确拒绝。

## 安全设计（三项默认全开，可关）

| 开关 | 落点 | 真实行为 |
|---|---|---|
| 禁止截屏 | `TotpSecureScreen` → 窗口 `FLAG_SECURE` | 进模块加、离开清；截图 / 录屏 / 多任务缩略图都空白 |
| 复制即焚 | `TotpClipboard` | 复制后 30 秒清空；**只清自己写进去的那串**（用户改过就不动），读不到也不清 |
| 后台遮罩 | `TotpScreenHost` 生命周期 | `ON_STOP` 盖一层遮罩，`ON_START` 撤回 |

- 密钥用 Android Keystore **AES-256-GCM** 加密后存独立 prefs（alias `totp_secret_key`），独立库 `totp.db`（表 `totp_accounts`，V2 起带 `otp_type` 与 `counter`）。
- 解密失败**不清数据**：账户仍在列表，显示「密钥不可读」占位，由用户自行删除 —— 唯一一份 2FA 密钥删了不可恢复。

## 备份格式

导出是可存成文件的纯文本三行：

```
AMTOTP/1
pbkdf2-sha256:210000
base64(salt16 || iv12 || ciphertext)
```

- 口令经 PBKDF2-HMAC-SHA256 派生 256 位密钥，载荷 AES-256-GCM 加密；**与 Keystore 无关**，换机可恢复。
- 载荷 version 2 起每条账户带 `type` 与 `counter`；导入兼容 version 1（缺字段按 TOTP / 0）。
- 口令不写进文件；口令丢失 = 备份不可解，没有后门。
- 导入：本应用加密包按内容自动识别（`AMTOTP/1` 头 → 要密码）；Aegis / 2FAS 明文 JSON 直接解析。Aegis 加密导出用 scrypt，JDK 标准库没有该 KDF，**明确报错并提示改用明文导出**，不用错算法解出垃圾。

## 边界与不支持

- 不做 Yandex `yaotp`（需 PIN 的自定义算法）、mOTP（MD5 + PIN 摘要）、Battle.net 私有掩码密钥 —— 三者都不是 otpauth 标准形态。
- 不支持桌面小组件、通知栏常驻。
- 扫码页申请 CAMERA 权限仅用于取景与手电筒；**相册识别**走系统选择器，不申请存储权限。
- 单条导入失败只跳过并展示中文原因，不中断整批；超过 1,000 条走分批提示。

## 代码位置

`domain/…/totp`（纯算法与模型：`TotpAlgorithm` / `SecretCodec` / `OtpauthUri` / `OtpauthMigration` / `ProtoWire` / `Base32`）· `app/…/totp`（数据层与加密）· `app/…/totp/ui`（六屏 + `TotpViewModel` + `TotpParamEditor` + `TotpScreenHost`）。
