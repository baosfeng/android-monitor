package com.bsfeng.monitor.domain.trip

// 从 domain/trip/TripAggregator.kt 拆出：Record → 内部结构的解析辅助
// （定位点 / 使用段 / 页面采样 / WiFi 采样）以及类型判定。
// 纯函数：解析失败一律返回 null，由聚合层跳过该条记录。

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType

// ────────────────────────────── 解析辅助 ──────────────────────────────

internal fun Record.toPoint(): LocationPoint? {
    val d = detail ?: return null
    val lat = d["lat"]?.toDoubleOrNull() ?: return null
    val lng = d["lng"]?.toDoubleOrNull() ?: return null
    if (lat.isNaN() || lng.isNaN()) return null
    if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return null
    return LocationPoint(
        lat = lat,
        lng = lng,
        ts = timestamp,
        label = d["address"]?.takeIf { it.isNotBlank() },
        accuracy = (d["accuracy_m"] ?: d["accuracy"])?.toDoubleOrNull()?.takeIf { it > 0.0 },
    )
}

internal fun Record.toUsage(dayStart: Long): UsageSlice? {
    val ms = duration ?: detail?.get("duration_ms")?.toLongOrNull() ?: return null
    if (ms <= 0L) return null
    val app = label.ifBlank { detail?.get("app_name").orEmpty() }.ifBlank { TripAggregatorRules.UNKNOWN_APP }
    return UsageSlice(
        app = app,
        pkg = detail?.get("package_name")?.takeIf { it.isNotBlank() },
        start = (timestamp - ms).coerceAtLeast(dayStart),
        end = timestamp,
        ms = ms,
    )
}

internal fun Record.toPageSample(): PageSample? {
    val app = detail?.get("app_name")?.takeIf { it.isNotBlank() }
        ?: label.substringBefore('›').trim().ifBlank { null }
        ?: return null
    return PageSample(app, timestamp)
}

internal fun Record.toWifi(): WifiSample? {
    val ssid = detail?.get("ssid")?.takeIf { it.isNotBlank() }
        ?: label.substringAfter("WiFi", "").trim().trimStart('：', ':').ifBlank { null }
        ?: return null
    return WifiSample(ssid, timestamp, action == "DISCONNECTED")
}

internal fun isLocation(record: Record, name: String): Boolean {
    if (name == TripAggregatorRules.NAME_LOCATION) return true
    val d = record.detail ?: return false
    return d["lat"] != null && d["lng"] != null
}

internal fun isPage(record: Record, name: String): Boolean {
    if (name == TripAggregatorRules.NAME_PAGE) return true
    return record.detail?.containsKey("page_key") == true
}

/**
 * 类型名走 `::class.simpleName`：PAGE 由并行任务在 `RecordType` 里新增，
 * 这里按名字识别，聚合层不依赖它的编译期符号（避免与另一路的改动互相锁死）。
 */
internal fun typeNameOf(type: RecordType): String = type::class.simpleName ?: ""
