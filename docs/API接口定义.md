# API 接口定义

后端 API 定义。后端语言/框架由用户自定，本文档只定义 HTTP 接口契约。

---

## 1. 基础信息

| 项目 | 值 |
|------|-----|
| 协议 | HTTP / HTTPS |
| 编码 | UTF-8 |
| Content-Type | `application/json; charset=utf-8` |
| 认证 | Bearer Token（详见第 5 节） |

---

## 2. 接口列表

### 2.1 上报事件 `POST /api/v1/events`

Android 端将采集到的事件批量上报给后端。

#### Request

```http
POST /api/v1/events
Authorization: Bearer <device_token>
Content-Type: application/json

{
  "events": [
    {
      "event_id": "550e8400-e29b-41d4-a716-446655440000",
      "event_type": "app_foreground",
      "timestamp": 1718000000123,
      "device_id": "a1b2c3d4-...",
      "extra": {
        "package_name": "com.tencent.mm",
        "app_name": "微信",
        "activity_name": ".ui.LauncherUI",
        "activity_class": "com.tencent.mm.ui.LauncherUI",
        "is_from_recent": false,
        "from_package": "com.miui.home",
        "from_activity": ".launcher.Launcher",
        "foreground_duration_ms": 30500
      }
    }
  ],
  "batch_id": "batch-uuid",
  "device_id": "a1b2c3d4-...",
  "batch_created_at_ms": 1718000000123,
  "batch_size": 1
}
```

#### Response

**`200 OK` — 全部成功**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "accepted_count": 1,
    "rejected_count": 0,
    "rejected_events": [],
    "server_time_ms": 1718000000500
  }
}
```

**`200 OK` — 部分失败**（例如某条事件格式错误）

```json
{
  "code": 0,
  "message": "partial_accepted",
  "data": {
    "accepted_count": 5,
    "rejected_count": 2,
    "rejected_events": [
      {
        "event_id": "xxx",
        "reason": "invalid_event_type"
      },
      {
        "event_id": "yyy",
        "reason": "missing_required_field: extra.package_name"
      }
    ],
    "server_time_ms": 1718000000500
  }
}
```

**`401 Unauthorized`**

```json
{
  "code": 401,
  "message": "invalid_token"
}
```

**`429 Too Many Requests`**

```json
{
  "code": 429,
  "message": "rate_limited",
  "data": {
    "retry_after_ms": 30000
  }
}
```

---

### 2.2 查询事件 `GET /api/v1/events`

（可选接口，用于后端调试或用户查看数据）

```http
GET /api/v1/events?device_id=a1b2c3...&event_type=app_foreground&from_ts=1718000000000&to_ts=1718086400000&limit=100&offset=0
Authorization: Bearer <token>
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `device_id` | string | 否 | 筛选设备 ID（不传则查所有设备） |
| `event_type` | string | 否 | 筛选事件类型 |
| `from_ts` | int64 | 否 | 起始时间戳（毫秒） |
| `to_ts` | int64 | 否 | 结束时间戳（毫秒） |
| `limit` | int | 否 | 每页数量（默认 100，最大 1000） |
| `offset` | int | 否 | 分页偏移量 |

#### Response

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "total": 1520,
    "limit": 100,
    "offset": 0,
    "events": [ /* 事件数组 */ ]
  }
}
```

---

### 2.3 注册设备 `POST /api/v1/devices`

首次启动时，Android 端向后端注册设备并获取 Token。

#### Request

```json
{
  "device_id": "a1b2c3d4-...",
  "device_name": "小米14",
  "manufacturer": "Xiaomi",
  "model": "23127PN0CC",
  "android_version": "14",
  "app_version": "1.0.0",
  "app_build": 1
}
```

#### Response

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "device_id": "a1b2c3d4-...",
    "token": "eyJhbGciOi...",
    "token_expires_at_ms": 1750000000000,
    "config": {
      "upload_interval_ms": 5000,
      "batch_max_size": 50,
      "retry_max_attempts": 5,
      "retry_backoff_base_ms": 1000,
      "cache_max_days": 3,
      "enable_notification_content": false
    }
  }
}
```

---

### 2.4 心跳 `GET /api/v1/heartbeat`

Android 端定期发送，确认设备存活并同步服务端配置。

```http
GET /api/v1/heartbeat
Authorization: Bearer <token>
```

#### Response

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "server_time_ms": 1718000000500,
    "config": {
      "upload_interval_ms": 5000,
      "batch_max_size": 50,
      "retry_max_attempts": 5,
      "retry_backoff_base_ms": 1000,
      "cache_max_days": 3,
      "enable_notification_content": false
    }
  }
}
```

---

## 3. Android 端上报策略

| 策略项 | 默认值 | 说明 |
|--------|--------|------|
| 上报间隔 | 每 5 秒或每 10 条（取先到） | 减少请求次数 |
| 最大批次 | 50 条/批 | 避免单请求体过大 |
| 失败重试 | 最多 5 次，指数退避（1s → 2s → 4s → 8s → 16s） | 减轻服务器压力 |
| 离线缓存 | Room 本地存储，最多保留 3 天 | 网络恢复后按序补传 |
| 网络检测 | 仅在 WiFi 或移动数据可用时上报 | 不触发非必要流量 |

---

## 4. 数据库表结构（后端参考）

以下为后端数据库参考设计（PostgreSQL / MySQL）：

### 4.1 `events` 表

```sql
CREATE TABLE events (
    event_id       UUID PRIMARY KEY,
    event_type     VARCHAR(50) NOT NULL,
    timestamp_ms   BIGINT NOT NULL,
    device_id      UUID NOT NULL,
    extra          JSONB NOT NULL,
    received_at_ms BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    created_at     TIMESTAMP DEFAULT NOW()
);

-- 查询索引
CREATE INDEX idx_events_device_id ON events(device_id);
CREATE INDEX idx_events_event_type ON events(event_type);
CREATE INDEX idx_events_timestamp ON events(timestamp_ms DESC);
CREATE INDEX idx_events_device_type_ts ON events(device_id, event_type, timestamp_ms DESC);
```

### 4.2 `devices` 表

```sql
CREATE TABLE devices (
    device_id         UUID PRIMARY KEY,
    device_name       VARCHAR(255),
    manufacturer      VARCHAR(100),
    model             VARCHAR(100),
    android_version   VARCHAR(20),
    app_version       VARCHAR(20),
    app_build         INT,
    token             TEXT,
    token_expires_at  TIMESTAMP,
    last_heartbeat_at TIMESTAMP,
    is_active         BOOLEAN DEFAULT TRUE,
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);
```

---

## 5. 认证机制

| 机制 | 说明 |
|------|------|
| 方式 | Bearer Token |
| 获取 | 设备注册接口返还 |
| 有效期 | 服务端设定（建议 90 天），过期后设备需重新注册 |
| 刷新 | 暂不提供自动刷新，重新注册获取新 Token |

---

## 6. 错误码汇总

| code | message | HTTP 状态码 | 说明 |
|------|---------|-------------|------|
| 0 | ok | 200 | 成功 |
| 1001 | invalid_json | 400 | 请求体 JSON 解析失败 |
| 1002 | invalid_event_type | 400 | 未知的事件类型 |
| 1003 | missing_required_field | 400 | 缺少必填字段 |
| 1004 | batch_too_large | 400 | 单批超过限制 |
| 1005 | duplicate_event | 200 | 重复事件（已存在相同 event_id） |
| 401 | invalid_token | 401 | Token 无效或过期 |
| 403 | forbidden | 403 | 设备被禁用 |
| 429 | rate_limited | 429 | 频率限制 |
| 500 | internal_error | 500 | 服务器内部错误 |
