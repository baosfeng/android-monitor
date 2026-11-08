# 服务器存储 API（备份 / 恢复）

> 结论：服务端只做**不透明字节存储 + 元数据注册 + 访问控制**；解包、校验、还原全部在客户端完成。所有接口复用现有设备 Token（`Authorization: Bearer <token>`），Base URL 与统一响应体同 [设备上报 API](../API接口定义_后端需实现.md)。

统一响应体：`{ "code": 0, "message": "ok", "data": { } }`；`code=0` 才算成功。HTTP 2xx 且 `code != 0` 仍视为业务失败。

## 1. 资源模型 `BackupRecord`

| 字段 | 类型 | 说明 |
|---|---|---|
| backupId | string UUID | 客户端生成，两端共同主键 |
| deviceId | string | 所属设备（Token 派生，请求体不传） |
| createdAtMs | long | 客户端备份时的 Unix ms |
| receivedAtMs | long | 服务端接收时间（用于排序/限流） |
| appVersionName | string | 备份时的 App 版本 |
| schemaVersion | int | manifest schema 版本 |
| sizeBytes | long | zip 字节大小 |
| sha256 | string(64) | zip 的 SHA-256（小写 hex） |
| manifest | object | 完整 manifest.json（见数据备份文档） |
| status | enum | `UPLOADING / READY / CORRUPT / DELETED` |
| restoreSupported | boolean | 服务端依据 schemaVersion 给出的恢复建议 |

## 2. 上传（两阶段，支持断点续传）

### 2.1 注册上传 `POST /backups`

请求体（仅元数据，不带文件）：

```json
{ "backupId": "…", "createdAtMs": 1718000000000, "appVersionName": "1.4.0",
  "schemaVersion": 3, "sizeBytes": 123456, "sha256": "…", "manifest": { … } }
```

成功 200：

```json
{ "code": 0, "data": { "backupId": "…", "uploadUrl": "https://…/upload/…",
    "uploadMethod": "PUT", "uploadHeaders": { "Content-Type": "application/zip" },
    "expiresAtMs": 1718000600000, "maxPartSizeBytes": 5242880 } }
```

- `backupId` 已存在且 `sha256` 相同：返回已有记录与新 uploadUrl（幂等重传）。
- `backupId` 已存在且 `sha256` 不同：`code=2003`（冲突），客户端生成新 id 重试。
- `sizeBytes > maxPartSizeBytes * 100`：`code=2002`（批量/文件过大）。

### 2.2 上传字节

客户端按 `uploadMethod + uploadUrl + uploadHeaders` 直传 zip 原字节。若服务端返回分片参数，则分片上传；整文件 `PUT` 为兼容最小实现。传输失败 / 进程被杀：重新 `POST /backups` 拿新 URL，**不**追加半成品。

### 2.3 提交 `POST /backups/{backupId}/commit`

```json
{ "sizeBytes": 123456, "sha256": "…" }
```

服务端重新计算 sha256/size，匹配才把 status 置 `READY`；不匹配返回 `code=2004`（校验失败）并删除上传对象。成功返回完整 `BackupRecord`。

## 3. 查询与下载

- `GET /backups?limit=20&after={backupId}`：分页倒序（receivedAtMs）；返回 `{ "items": [BackupRecord…], "nextCursor": "…" }`。服务端应用保留策略：保留最近 `retentionCount` 份（默认 10），超出部分在列表中仍可下载/删除，但新上传触发清理。
- `GET /backups/{backupId}`：单条元数据（含 manifest）。
- `GET /backups/{backupId}/content`：下载原始 zip（`Content-Type: application/zip`，`Content-Disposition: attachment; filename=…`，`ETag` = sha256）。客户端必须流式写临时文件并做 sha256 校验。
- `DELETE /backups/{backupId}`：软删除（status=`DELETED`，物理清理异步），成功返回 `{ "backupId": "…", "deleted": true }`。

## 4. 认证与错误码
| code | 含义 | 客户端行为 |
|---|---|---|
| 0 | 成功 | — |
| 1001 | Token 无效/过期 | 重新注册设备后重试一次 |
| 1002 | 设备未注册 | 先 `POST /devices` |
| 2001 | 参数不合法 | 记 W，中止本次操作 |
| 2002 | 文件过大 / 超配额 | 提示用户清理或降配额 |
| 2003 | backupId 冲突 | 生成新 id 重试 |
| 2004 | sha256/size 校验失败 | 删除半成品，重新上传 |
| 2005 | schemaVersion 不兼容 | 仅展示、禁止恢复 |
| 5000 | 服务端错误 | 走离线队列重试 |
## 5. 不变量

- 服务端不得改写/重压/解密；GET …/content 字节与上传完全一致（客户端 sha256 断言）；同一 deviceId 隔离，越权返 1001/403。
- 上传 URL 短时有效（≤10 分钟）且单次；提交前半成品不入 list；保留计数以 `status=READY` 为准；UPLOADING 不占配额；`restoreSupported=false` 时客户端不恢复。
