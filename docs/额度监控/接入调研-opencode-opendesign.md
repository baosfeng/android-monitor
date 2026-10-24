# opencode-go / opendesign 额度接入调研

> 2026-10-23 调研、2026-10-24 订正为已接入。标注 **已验证** / **推测** / **未验证**。字段结构来自官方 console 路由源码 + 多个第三方客户端交叉印证；本机只用无效 key 验证了 401/403 与 UA 行为，**没有真实 key 的成功 200 响应**，响应体形态仍按推测对待。

## 结论

- **opencode-go 已接入**：`GET` + Bearer 直连，只有三窗口已用百分比、无用量明细（`supportsUsage = false`，只出额度卡）。
- **opendesign 没有公开额度 API**，只有控制台内部接口（要会话令牌 + workspace id）→ 首版做**手动录入**，不做直连。

## opencode-go

```
GET https://opencode.ai/zen/go/v1/usage
Authorization: Bearer sk-…            # Go API Key，与推理同一把
```

| 字段 | 含义 |
|---|---|
| `usage.rolling` / `.weekly` / `.monthly` | 滚动 5 小时 / 周 / 月三个窗口 |
| `*.percent` | 0–100，**该窗口已用比例**（不是余额、不是 token） |
| `*.resetsAt` | RFC3339 UTC 重置时刻 |
| `*.status` | `ok`；`rate-limited` = 该窗口耗尽 |

- **旧形态兼容**：2026-08 前后字段名是 `rollingUsage` / `weeklyUsage` / `monthlyUsage` + `usagePercent` + `resetInSec`（相对秒数，需换算成 `resetsAt`）；解析同时认新旧两套键名。
- **语义**：额度按月美元定义，5h 窗口 = 月限额 20%、周 = 50%、月 = 100%；月周期跟随订阅日（28/29/31 天），接口只给 `resetsAt`，不给周期起点，也不给绝对金额或次数。
- **口径**：`percent` 是**已用**，Ollama 的 `remaining_percent` 是**剩余**，两者相反；App 内统一存剩余，转换在 provider 内完成。
- **错误** [已验证]：无/错 key → `401 {"type":"error","error":{"type":"AuthError","message":"Unauthorized"}}`；key 有效但无 Go 订阅 → `403 {"type":"error","error":{"type":"EntitlementError","message":"OpenCode Go subscription required."}}`；`/zen/go/v1/balance` 等其它路径 404 —— **没有余额接口**。
- **UA 不受限** [已验证]：Dalvik、curl、任意 UA 都返回 401 而不是 403，与 Ollama 的 Cloudflare 拦截不同，请求按默认 UA 发出即可。
- **限流未验证**（无真实 key 拿不到 200）：官方未公布额度接口的限流，同域推理侧有过 IP / 账户限流，所以默认按 5 分钟起轮询（App 里 opencode-go 前台刷新间隔 5 分钟，不套 Ollama 的 60 秒，避免撞未知限流后整块失败）。
- **key 来源**：opencode 控制台（CLI 登录后写 `~/.local/share/opencode/auth.json` 的 `opencode-go.key`）。
- **降级**：401 提示重贴 key、403 提示订阅缺失；三窗口任一缺失按窗口独立渲染，不整块报错。

## opendesign

- 官方只提供「去控制台看用量」入口，推理网关 `amr-link.open-design.ai/v1` 不返回额度 [已验证]。
- 控制台内部接口：`GET https://amr-api.open-design.ai/api/v1/workspaces/{ws}/billing/coding-plan-usage`，Bearer 为**浏览器会话令牌**（不是 API key）+ workspace id；字段 `tier` / `eligible` / `windows[{durationSeconds,usedCredits,limitCredits,remainingCredits,resetsAt}]`，credits 是十进制字符串，`10000 credits = $1` [结构推测]。
- 直连不可行：会话令牌会过期、要 workspace id、是内部接口，不能放进 App 长期运行。

**首版降级顺序**：手动录入（档位 + 剩余百分比，参与预警）→ 局域网互传旁路（Mac 端取数经项目互传协议推到手机）→ 仅跳转控制台。

## 对 Android 的影响

| 项 | 结论 |
|---|---|
| 权限 | 仅 `INTERNET` |
| 凭据 | 全部走 Keystore 加密存 SharedPreferences；opendesign 无凭据（手动录入无 key） |
| 数据模型 | 「已用百分比」→「剩余百分比」的归一化在 provider 内完成，UI 只读剩余值 |
| 手动额度 | 需要一条「手动录入的额度项」通路：无 API 的 provider 也能进预警，不必等接口 |
