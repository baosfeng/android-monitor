package com.bsfeng.monitor.network

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.json.JSONArray
import org.json.JSONObject

// 从 network/ApiService.kt 拆出：请求 / 响应的数据结构与 JSON 编码。
// 只负责「长什么样」与「怎么序列化」，不含任何网络动作。

/** 只允许留在本地、任何情况下都不随事件上传的 detail 键。 */
internal val LOCAL_ONLY_DETAIL_KEYS: Set<String> = setOf("page_title", "address")

/** 上报事件批次（`POST api/monitor/events` 的请求体）。 */
data class EventsBatch(
    val events: List<Record>,
    val batchId: String,
    val deviceId: String,
    val batchCreatedAtMs: Long,
    val batchSize: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("events", JSONArray(events.map { record ->
            JSONObject().apply {
                put("id", record.id)
                put("type", RecordType.Companion.toString(record.type))
                put("action", record.action)
                put("timestamp", record.timestamp)
                record.duration?.let { put("duration", it) }
                put("label", record.label)
                // 隐私红线：页面标题 / 地址只存本地，上报前剔除（见 SECURITY.md / docs/事件采集）
                record.detail?.let { detail ->
                    val reportable = JSONObject()
                    for (key in detail.keys) {
                        if (key !in LOCAL_ONLY_DETAIL_KEYS) reportable.put(key, detail[key])
                    }
                    if (reportable.length() > 0) put("detail", reportable)
                }
            }
        }))
        put("batchId", batchId)
        put("deviceId", deviceId)
        put("batchCreatedAtMs", batchCreatedAtMs)
        put("batchSize", batchSize)
    }
}

/** 设备注册请求（`POST api/monitor/devices` 的请求体）。 */
data class DeviceRegisterRequest(
    val deviceId: String,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val appBuild: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("manufacturer", manufacturer)
        put("model", model)
        put("androidVersion", androidVersion)
        put("appVersion", appVersion)
        put("appBuild", appBuild)
    }
}

data class UploadResponse(
    val code: Int = 0,
    val message: String = "ok",
    val data: UploadData? = null
)

data class UploadData(
    val acceptedCount: Int = 0,
    val rejectedCount: Int = 0,
    val serverTimeMs: Long? = null
)

data class DeviceRegisterResponse(
    val code: Int = 0,
    val message: String = "ok",
    val data: DeviceRegisterData? = null
)

data class DeviceRegisterData(
    val deviceId: String = "",
    val token: String = "",
    val tokenExpiresAtMs: Long = 0L,
    val config: ServerConfig? = null
)

/** 服务端下发的上报节奏配置（注册 / 心跳响应共用）。 */
data class ServerConfig(
    val uploadIntervalMs: Int = 5000,
    val batchMaxSize: Int = 50,
    val retryMaxAttempts: Int = 5,
    val retryBackoffBaseMs: Int = 1000,
    val cacheMaxDays: Int = 3,
    val enableNotificationContent: Boolean = true
)

data class HeartbeatResponse(
    val code: Int = 0,
    val message: String = "ok",
    val data: HeartbeatData? = null
)

data class HeartbeatData(
    val serverTimeMs: Long = 0L,
    val config: ServerConfig? = null
)
