# Android Monitor — 后端 REST API 接口文档

> 本文档供后端开发人员参考，列出了 Android 端当前已集成的所有后端接口。

---

## 基本信息

| 项目 | 值 |
|------|-----|
| **Base URL** | 由前端通过构建配置 `SERVER_BASE_URL` 指定，末尾自动补 `/` |
| **Content-Type** | `application/json` |
| **字符编码** | UTF-8 |
| **认证方式** | Bearer Token（除设备注册外，所有接口均需在 `Authorization` 请求头中携带） |

### 统一响应格式

所有接口的响应体采用统一结构：

```json
{
  "code": 0,
  "message": "ok",
  "data": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 业务状态码，`0` 表示成功，非零表示失败 |
| `message` | string | 状态描述信息 |
| `data` | object / null | 业务数据体 |

---

## 接口一览

| # | 方法 | 路径 | 认证 | 用途 |
|---|------|------|------|------|
| 1 | POST | `/devices` | 无需 Token | 设备注册，获取访问 Token |
| 2 | POST | `/events` | 需 Bearer Token | 批量上报采集的事件 |
| 3 | GET | `/heartbeat` | 需 Bearer Token | 心跳保活，同步服务端动态配置 |

---

## 接口 1：设备注册

首次启动或 Token 过期失效时，客户端调用此接口注册设备并获取 Token。

### 请求

```
POST /devices
Content-Type: application/json
```

### 请求体

```json
{
  "device_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "device_name": "小米14",
  "manufacturer": "Xiaomi",
  "model": "23127PN0CC",
  "android_version": "14",
  "app_version": "1.0.0",
  "app_build": 1
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `device_id` | string | 是 | 设备唯一标识（UUID，由 App 生成并持久化，重装不变） |
| `device_name` | string | 是 | 设备名称（如"小米14"） |
| `manufacturer` | string | 是 | 设备制造商（如 Xiaomi） |
| `model` | string | 是 | 设备型号（如 23127PN0CC） |
| `android_version` | string | 是 | Android 系统版本号（如 "14"、"13"） |
| `app_version` | string | 是 | App 版本名（如 "1.0.0"） |
| `app_build` | int | 是 | App 构建号（如 1） |

### 成功响应

**HTTP 状态码：** 200

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "device_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "token_expires_at_ms": 1767225600000,
    "config": {
      "upload_interval_ms": 5000,
      "batch_max_size": 50,
      "retry_max_attempts": 5,
      "retry_backoff_base_ms": 1000,
      "cache_max_days": 3,
      "enable_notification_content": true
    }
  }
}
```

| `data` 字段 | 类型 | 必含 | 说明 |
|-------------|------|------|------|
| `device_id` | string | 是 | 设备 ID（回声返回） |
| `token` | string | 是 | 后续请求使用的 Bearer Token（JWT 或自定义格式） |
| `token_expires_at_ms` | long | 是 | Token 过期时间戳（毫秒），客户端以此判断是否需要重新注册 |
| `config` | object | 否 | 服务端动态配置（可选），用于覆盖客户端本地默认参数 |

### ServerConfig 字段说明

`config` 对象中每个字段均有客户端本地默认值，服务端可按需下发覆盖：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `upload_interval_ms` | int | 5000 | 事件上传间隔（毫秒） |
| `batch_max_size` | int | 50 | 每批最大事件数 |
| `retry_max_attempts` | int | 5 | 上传失败最大重试次数 |
| `retry_backoff_base_ms` | int | 1000 | 退避基时（毫秒） |
| `cache_max_days` | int | 3 | 本地缓存保留天数 |
| `enable_notification_content` | boolean | true | 是否采集通知内容 |

> **⚠ 注意：** 如果 `code != 0`，客户端认为注册失败，不会保存 Token，后续会重试。

---

## 接口 2：批量上报事件

客户端定时将采集到的事件批量上报到服务端。

### 请求

```
POST /events
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

### 请求体

```json
{
  "events": [
    {
      "event_id": "evt-001",
      "event_type": "SCREEN_ON",
      "timestamp": 1718000000000,
      "device_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "extra": {
        "source": "broadcast"
      }
    },
    {
      "event_id": "evt-002",
      "event_type": "APP_FOREGROUND",
      "timestamp": 1718000001000,
      "device_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "extra": {
        "package_name": "com.tencent.mm",
        "app_name": "微信"
      }
    }
  ],
  "batch_id": "batch-uuid-xxxx",
  "device_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "batch_created_at_ms": 1718000002000,
  "batch_size": 2
}
```

### 事件对象（UnifiedEvent）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `event_id` | string | 是 | 事件唯一 ID（UUID） |
| `event_type` | string | 是 | 事件类型枚举值（见下方列表） |
| `timestamp` | long | 是 | 事件发生时间戳（毫秒） |
| `device_id` | string | 是 | 设备唯一标识 |
| `extra` | object | 是 | 附加字段（Map<String, String>），不同事件类型携带不同键值对，详见事件类型表 |

#### 事件类型枚举（event_type 取值）

| 枚举值 | 说明 | 常见 extra 键 |
|--------|------|--------------|
| `APP_FOREGROUND` | 前台 App 切换 | `package_name`, `app_name` |
| `ACTIVITY_CHANGE` | Activity 级界面切换 | `package_name`, `activity_name` |
| `SCREEN_ON` | 屏幕亮起 | `source` |
| `SCREEN_OFF` | 屏幕熄灭 | — |
| `UNLOCK_SUCCESS` | 解锁成功 | `lock_type` |
| `UNLOCK_FAILED` | 解锁失败 | `fail_reason` |
| `CHARGING_STARTED` | 开始充电 | `charge_type` (usb/ac/wireless) |
| `CHARGING_STOPPED` | 停止充电 | — |
| `WIFI_CONNECTED` | WiFi 连接 | `ssid`, `bssid` |
| `WIFI_DISCONNECTED` | WiFi 断开 | `ssid` |
| `HEADSET_PLUGGED` | 耳机插入 | `has_mic` |
| `HEADSET_UNPLUGGED` | 耳机拔出 | — |
| `APP_INSTALLED` | 应用安装 | `package_name` |
| `APP_UNINSTALLED` | 应用卸载 | `package_name` |
| `NOTIFICATION_RECEIVED` | 通知到达 | `package_name`, `title`, `text`（受隐私开关控制） |
| `NOTIFICATION_REMOVED` | 通知移除 | `package_name`, `notification_key` |
| `BATTERY_CHANGED` | 电量变化 | `level`, `scale`, `is_charging` |
| `RINGER_MODE_CHANGED` | 情景模式变化 | `mode` (normal/silent/vibrate) |
| `CONNECTED_TO_PC` | 连接电脑 | — |
| `DISCONNECTED_FROM_PC` | 断开电脑 | — |
| `BLUETOOTH_CONNECTED` | 蓝牙设备连接 | `device_name`, `device_address` |
| `BLUETOOTH_DISCONNECTED` | 蓝牙设备断开 | `device_name` |
| `AIRPLANE_MODE_CHANGED` | 飞行模式切换 | `enabled` (true/false) |
| `MOBILE_DATA_CHANGED` | 移动数据切换 | `enabled` (true/false) |
| `POWER_SAVE_MODE_CHANGED` | 省电模式切换 | `enabled` (true/false) |
| `SCREENSHOT_TAKEN` | 截屏事件 | — |
| `VPN_CONNECTED` | VPN 连接 | — |
| `VPN_DISCONNECTED` | VPN 断开 | — |
| `APP_CRASHED` | App 崩溃 | `package_name` |
| `STORAGE_LOW` | 存储不足 | `available_mb` |
| `STORAGE_OK` | 存储恢复 | `available_mb` |
| `HOTSPOT_CHANGED` | 热点切换 | `enabled` (true/false) |
| `VOLUME_CHANGED` | 音量变化 | `stream`, `old_level`, `new_level` |
| `TIMEZONE_CHANGED` | 时区变化 | `timezone` |
| `LOCALE_CHANGED` | 语言区域变化 | `locale` |
| `CALL_STATE_CHANGED` | 通话状态变化 | `state` (idle/ringing/offhook), `number`（仅限 ringing 状态） |
| `SCREEN_CONTENT_DETECTED` | 界面内容识别（无障碍） | `package_name`, `content_summary` |

### 请求体顶层字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `events` | array | 事件列表，本次批量上报的所有事件 |
| `batch_id` | string | 批次唯一标识（UUID） |
| `device_id` | string | 设备唯一标识 |
| `batch_created_at_ms` | long | 批次创建时间戳（毫秒） |
| `batch_size` | int | 本批事件数量（应与 events 数组长度一致） |

### 成功响应

**HTTP 状态码：** 200

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "accepted_count": 2,
    "rejected_count": 0,
    "server_time_ms": 1718000002500
  }
}
```

| `data` 字段 | 类型 | 说明 |
|-------------|------|------|
| `accepted_count` | int | 服务端成功接收的事件数 |
| `rejected_count` | int | 被拒绝的事件数 |
| `server_time_ms` | long | 服务端当前时间戳（毫秒），可选 |

> **⚠ 注意：** 客户端依据 `code == 0` 判断是否成功。成功后会将这批事件在本地标记为已上传并清理过期数据。如果 `code != 0` 或 HTTP 状态码非 200，客户端会保留事件并在下次重试。

---

## 接口 3：心跳

客户端定期调用此接口保持连接活跃，并同步服务端动态配置。

### 请求

```
GET /heartbeat
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

### 成功响应

**HTTP 状态码：** 200

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "server_time_ms": 1718000003000,
    "config": {
      "upload_interval_ms": 10000,
      "batch_max_size": 100,
      "retry_max_attempts": 3,
      "retry_backoff_base_ms": 2000,
      "cache_max_days": 7,
      "enable_notification_content": false
    }
  }
}
```

| `data` 字段 | 类型 | 必含 | 说明 |
|-------------|------|------|------|
| `server_time_ms` | long | 是 | 服务端当前时间戳（毫秒），用于客户端时间校准 |
| `config` | object | 否 | 服务端动态配置（可选），结构与设备注册中的 `config` 一致 |

`config` 字段结构与设备注册接口的 `ServerConfig` 完全相同，详见 [接口 1](#serverconfig-字段说明)。

> **⚠ 注意：** 如果 `code != 0`，客户端认为心跳失败，但不会停止运行，会在下一周期继续发送。

---

## 认证说明

### Token 获取

- 客户端首次启动时调用 `POST /devices` 获取 Token
- 服务端返回的 `token` 由客户端通过 `SharedPreferences` 持久化存储
- Token 过期时（客户端判断 `token_expires_at_ms`），客户端会重新调用 `POST /devices` 注册

### Token 使用

除 `POST /devices` 外，所有请求需在 HTTP 头中携带：

```
Authorization: Bearer <token>
```

- 客户端 OkHttp 拦截器自动注入此请求头
- 如果 Token 为空（首次注册前），不会添加 `Authorization` 头

### 服务端建议

1. **设备注册去重：** 同一 `device_id` 重复注册时，建议更新 Token 有效期并返回已有或新 Token，而非报错
2. **Token 格式：** 建议使用 JWT，至少包含 `device_id` 和 `exp`（过期时间）
3. **事件存储：** 接收到的 `events` 建议按 `(device_id, event_id)` 去重，避免重复写入
4. **配置覆盖：** 心跳和注册响应中的 `config` 用于动态调整客户端行为，建议服务端按需下发非默认值（不下发或下发 null 时客户端继续使用本地默认值）

---

## 错误码建议

| code | 含义 | 说明 |
|------|------|------|
| 0 | 成功 | 请求处理成功 |
| 1001 | Token 无效或过期 | 客户端收到后应重新注册 |
| 1002 | 设备未注册 | 指定 device_id 不存在 |
| 2001 | 请求参数不合法 | 缺少必填字段或格式错误 |
| 2002 | 批量过大 | events 数量超过服务端限制 |
| 5000 | 服务端内部错误 | 临时性错误，客户端应重试 |

> 以上错误码为建议值，具体可根据后端设计调整。客户端只检查 `code == 0` 判断成功，非零值均视为失败。
