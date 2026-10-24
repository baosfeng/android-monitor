# 多 provider 额度监控 · API 调研

> 抓取时间 2026-10-23，来源均为官方文档或官方端点实测。标注 **[已验证]** / **[推测]**。

## 结论

四家里只有 Ollama 同时提供余额、用量与按模型分组；DeepSeek 只有余额（用量走平台内部接口）；opencode-go 只有三窗口已用百分比、无用量明细；opendesign 无公开接口。opencode-go / opendesign 的字段与错误体见 [接入调研](接入调研-opencode-opendesign.md)，本文不重复。
统一模型必须容得下三种语义：**金额余额**（Ollama 内含/已购）、**百分比限额**（Ollama 旧版 session/weekly）、**多币种余额**（DeepSeek，金额是字符串）。
客户端只需 `INTERNET` 权限，全部走 `Authorization: Bearer`。

## 能力矩阵

| provider | 余额 | 用量 | 额度语义 | 限流 | 首版 |
|---|---|---|---|---|---|
| Ollama | `GET /api/balance` | `GET /api/usage` | 内含 USD（oneOf）+ 已购 USD / 旧版百分比 | 10 次/分钟/用户，跨 Key 与设备共享 | ✅ |
| DeepSeek | `GET /user/balance` | 平台内部 `/api/v0/usage/by_api_key/*` | 多币种余额（CNY/USD，字符串）+ 按模型按小时用量 | 平台会话 token（非 API Key） | WAF 限流，需 UA | ✅ 余额 + 用量 |
| opencode-go | 无 | 百分比三窗口，无明细 | 套餐百分比 | 官方未公布 | ✅ 仅额度卡 |
| opendesign | 无 | 无 | 套餐 credits | — | 手动录入 |

## Ollama [已验证]

- 端点：`https://ollama.com/api/balance`（无参数）、`/api/usage?range=24h|7d|30d&scope=self&group_by=model`。
- `included` 是 **oneOf 互斥**：新版 `balance_usd/allowance_usd/period{from,until}` 或旧版 `session/weekly{remaining_percent,resets_at}`，不会同时出现 → 必须三态解析（USD / 百分比 / 未知原样保留）。
- 周期**跟随订阅日**（示例 9-15 → 10-15，UTC，`until` 不含），不是自然月 1 号。
- 用量 `granularity` 由 range 决定：24h→hour（25 桶）、7d→day（8 桶）、30d→day（31 桶）；`partial=true` 只出现在当前进行中的桶。
- `groups` 最多 10 组 + `remainder`（第 11 名及之后合并）→ UI 必须补「其他」行，否则分组之和小于总量。
- 429 带 `Retry-After`（秒）；错误体 `{"error":"..."}`；`group_by` 仅支持 `model`（按 API Key 分组是官方 Coming soon）。
- 用量写入有延迟，**不能用 usage 反推余额**；余额以 `/api/balance` 为准。

## DeepSeek [已验证]

- 端点：`https://api.deepseek.com/user/balance`；`is_available` + `balance_infos[]{currency,total_balance,granted_balance,topped_up_balance}`。
- 金额字段是 **string**，必须 `BigDecimal`；多币种分行展示，**禁止跨币种相加**。
- 官方 API 文档没有用量端点，但**平台 Web 端有两个内部接口**能取到按模型、按小时的请求数、token 与花费（见下节）。
- 402 = 余额不足（比 `is_available` 快照可靠）；401 有两种响应体（网关纯文本 / 应用 JSON），客户端要同时兼容。

## DeepSeek 平台内部接口（用量 / 花费）

```
GET https://platform.deepseek.com/api/v0/usage/by_api_key/amount?start=<unix>&end=<unix>&tz=<秒偏移>
GET https://platform.deepseek.com/api/v0/usage/by_api_key/cost?start=<unix>&end=<unix>&tz=<秒偏移>
Authorization: Bearer <平台会话 token>      # 不是 sk- API Key
User-Agent: <任意非空>                        # 必需，缺了被 WAF 直接 429
```

- `amount` 返回 `data.biz_data.{bucket, models[], series[{api_key{name,sensitive_id,valid}, model, buckets[{time, usage{RESPONSE_TOKEN, REQUEST, PROMPT_CACHE_HIT_TOKEN, PROMPT_CACHE_MISS_TOKEN}}]}]}`。
- 控制台那两张图就是这份数据的两种画法：`sum(REQUEST)` = 请求次数曲线；`PROMPT_CACHE_HIT + PROMPT_CACHE_MISS` = 输入 token、`RESPONSE_TOKEN` = 输出 token，堆叠起来就是 Token 柱图。实测今天 19:00 桶 14 次、22:00 桶 20 次，与控制台峰位一致。
- `cost` 返回 `data.biz_data.data[{currency, series[{model, buckets[{time, cost:"0.00"}]}]}]`，按币种分组、金额是字符串。
- `bucket` 自动选：区间 ≤24 小时为 3600 秒，更长区间为 86400 秒；**区间上限 31 天**（32 天起 `biz_code:1 INVALID_PARAM`）。
- 错误恒为 HTTP 200，靠 body 判断：`code:40002` 缺 token、`code:40003` token 无效、`biz_code:1` 参数或范围非法。
- **cookie 不够**：只带 cookie 返回 `{"code":40002,"msg":"Missing Token"}`；只带 Bearer（不带 cookie）可正常取数。
- 风险：`/api/v0/` 是平台内部接口，无文档、无版本承诺；token 过期后需用户重新粘贴。

## 对客户端的约束

| 项 | 取值 | 依据 |
|---|---|---|
| Ollama 前台轮询 | 60 秒 | 10 次/分钟且跨设备共享，官方建议 1 分钟一次 |
| Ollama 后台轮询 | ≥15 分钟 | 系统后台调度下限 + 共享配额 |
| 手动刷新节流 | ≥10 秒 | 防连点打爆配额 |
| 429 退避 | 读 `Retry-After` + ±20% 抖动；无头时指数退避 1→2→4s，上限 60s | — |
| 金额存储 | `BigDecimal` 或整数分 | DeepSeek 字符串金额、Ollama 小数美元 |
| 时间 | 全 UTC 存储，展示转本地；周期进度按毫秒差 | `until` 不含端点 |
