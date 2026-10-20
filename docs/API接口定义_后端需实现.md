# Android Monitor — 后端 REST API 接口文档

> 供后端实现。Android 端已按此集成。
> Base URL 由构建配置 `SERVER_BASE_URL` 指定（末尾自动补 `/`）；`Content-Type: application/json`；UTF-8；除设备注册外均需 `Authorization: Bearer <token>`。

统一响应体：

```json
{ "code": 0, "message": "ok", "data": { } }
```

`code = 0` 表示成功（客户端只看这个字段），非零表示失败；`data` 可为 object 或 null。

| # | 方法 | 路径 | 认证 | 用途 |
|---|---|---|---|---|
| 1 | POST | `/devices` | 无 | 设备注册，获取 Token |
| 2 | POST | `/events` | Bearer | 批量上报事件 |
| 3 | GET | `/heartbeat` | Bearer | 心跳保活，同步动态配置 |

## 1. 设备注册 `POST /devices`

首次启动或 Token 过期时调用。

```json
{
  "device_id": "a1b2c3d4-…", "device_name": "小米14", "manufacturer": "Xiaomi",
  "model": "23127PN0CC", "android_version": "14", "app_version": "1.0.0", "app_build": 1
}
```

字段：`device_id`（UUID，App 生成并持久化，重装不变）、`device_name`、`manufacturer`、`model`、`android_version`、`app_version`、`app_build`(int) —— 全部必填。

成功响应 200：

```json
{ "code": 0, "message": "ok", "data": {
    "device_id": "…", "token": "eyJhbGciOiJIUzI1NiIs…", "token_expires_at_ms": 1767225600000,
    "config": { "upload_interval_ms": 5000, "batch_max_size": 50, "retry_max_attempts": 5,
                "retry_backoff_base_ms": 1000, "cache_max_days": 3, "enable_notification_content": true } } }
```

| `data` 字段 | 类型 | 必含 | 说明 |
|---|---|---|---|
| `device_id` | string | 是 | 回声 |
| `token` | string | 是 | Bearer Token（JWT 或自定义） |
| `token_expires_at_ms` | long | 是 | 过期时间戳，客户端据此决定是否重注册 |
| `config` | object | 否 | 服务端动态配置，覆盖本地默认值 |

`ServerConfig`（默认值即客户端本地的缺省，服务端按需覆盖，不下发或 null 则用默认）：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `upload_interval_ms` | int | 5000 | 上传间隔 |
| `batch_max_size` | int | 50 | 每批最大事件数 |
| `retry_max_attempts` | int | 5 | 失败最大重试 |
| `retry_backoff_base_ms` | int | 1000 | 退避基时 |
| `cache_max_days` | int | 3 | 本地缓存保留天数 |
| `enable_notification_content` | boolean | true | 是否采集通知内容 |

> ⚠️ `code != 0` 视为注册失败，客户端不保存 Token，稍后重试。

## 2. 批量上报事件 `POST /events`

```json
{ "events": [ { "event_id": "evt-001", "event_type": "SCREEN_ON", "timestamp": 1718000000000,
                "device_id": "…", "extra": { "source": "broadcast" } } ],
  "batch_id": "batch-uuid-xxxx", "device_id": "…", "batch_created_at_ms": 1718000002000, "batch_size": 2 }
```

事件对象 `UnifiedEvent`：`event_id`（UUID）、`event_type`（枚举）、`timestamp`(ms)、`device_id`、`extra`（Map<String,String>，按键随类型不同）。

请求体顶层：`events`（数组）、`batch_id`（UUID）、`device_id`、`batch_created_at_ms`、`batch_size`（应与数组长度一致）。

`event_type` 枚举与常见 extra 键：

| 类型 | extra | 类型 | extra |
|---|---|---|---|
| `APP_FOREGROUND` | package_name, app_name | `RINGER_MODE_CHANGED` | mode(normal/silent/vibrate) |
| `ACTIVITY_CHANGE` | package_name, activity_name | `CONNECTED_TO_PC` / `DISCONNECTED_FROM_PC` | — |
| `SCREEN_ON` / `SCREEN_OFF` | source | `BLUETOOTH_CONNECTED` | device_name, device_address |
| `UNLOCK_SUCCESS` / `UNLOCK_FAILED` | lock_type / fail_reason | `BLUETOOTH_DISCONNECTED` | device_name |
| `CHARGING_STARTED` | charge_type(usb/ac/wireless) | `AIRPLANE_MODE_CHANGED` | enabled |
| `CHARGING_STOPPED` | — | `MOBILE_DATA_CHANGED` | enabled |
| `WIFI_CONNECTED` | ssid, bssid | `POWER_SAVE_MODE_CHANGED` | enabled |
| `WIFI_DISCONNECTED` | ssid | `SCREENSHOT_TAKEN` | — |
| `HEADSET_PLUGGED` | has_mic | `VPN_CONNECTED` / `VPN_DISCONNECTED` | — |
| `HEADSET_UNPLUGGED` | — | `APP_CRASHED` | package_name |
| `APP_INSTALLED` / `APP_UNINSTALLED` | package_name | `STORAGE_LOW` / `STORAGE_OK` | available_mb |
| `NOTIFICATION_RECEIVED` | package_name, title, text（受隐私开关控制） | `HOTSPOT_CHANGED` | enabled |
| `NOTIFICATION_REMOVED` | package_name, notification_key | `VOLUME_CHANGED` | stream, old_level, new_level |
| `BATTERY_CHANGED` | level, scale, is_charging | `TIMEZONE_CHANGED` | timezone |
| `CALL_STATE_CHANGED` | state(idle/ringing/offhook), number（仅 ringing） | `LOCALE_CHANGED` | locale |
| `SCREEN_CONTENT_DETECTED` | package_name, content_summary | `APP_KILLED` | package_name, app_name, exit_reason, kill_timestamp |

成功响应 200：

```json
{ "code": 0, "message": "ok", "data": { "accepted_count": 2, "rejected_count": 0, "server_time_ms": 1718000002500 } }
```

`accepted_count` / `rejected_count`(int)、`server_time_ms`(long，可选)。

> ⚠️ 客户端只认 `code == 0`：成功后本地标记已上传并清理过期数据；否则（或 HTTP 非 200）保留事件，下次重试。

## 3. 心跳 `GET /heartbeat`

```json
{ "code": 0, "message": "ok", "data": { "server_time_ms": 1718000003000,
    "config": { "upload_interval_ms": 10000, "batch_max_size": 100, "retry_max_attempts": 3,
                "retry_backoff_base_ms": 2000, "cache_max_days": 7, "enable_notification_content": false } } }
```

`server_time_ms`（必含，用于时间校准）、`config`（可选，结构同 `ServerConfig`）。

> ⚠️ `code != 0` 视为心跳失败，客户端继续运行，下周期重发。

## 认证

- 首次启动 `POST /devices` 取 Token，用 `SharedPreferences` 持久化；按 `token_expires_at_ms` 判断过期后重新注册。
- 除注册外所有请求带 `Authorization: Bearer <token>`（OkHttp 拦截器注入；Token 为空时不加该头）。

服务端建议：同一 `device_id` 重复注册时更新 Token 有效期并返回 Token（而非报错）；Token 用 JWT，至少含 `device_id` 与 `exp`；事件按 `(device_id, event_id)` 去重；`config` 只下发非默认值。

## 错误码建议

| code | 含义 | 客户端行为 |
|---|---|---|
| 0 | 成功 | — |
| 1001 | Token 无效或过期 | 重新注册 |
| 1002 | 设备未注册 | — |
| 2001 | 参数不合法 | — |
| 2002 | 批量过大 | — |
| 5000 | 服务端内部错误 | 重试 |

具体取值可按后端设计调整：客户端只检查 `code == 0`，非零一律视为失败。
