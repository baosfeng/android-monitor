package com.bsfeng.monitor.record

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import java.util.UUID

/**
 * 采集层入口。
 *
 * 各采集源（BroadcastReceiver、AccessibilityService）调用对应方法，
 * RecordCollector 通过 handlerRegistry 校验并生成 label，最终写入 store。
 *
 * 与 UsageTracker 的循环依赖处理：
 * - 构造时传入 UsageTracker
 * - 构造完成后调用 usageTracker.setOnRecordCallback { ... }
 *
 * 状态去重：WiFi、电量等高频广播携带状态缓存，只有值真正变化时才写入事件。
 */
class RecordCollector(
    private val store: RecordStore,
    private val usageTracker: UsageTracker,
    private val handlerRegistry: RecordHandlerRegistry
) {
    /** WiFi 状态缓存 — 避免 WIFI_STATE_CHANGED_ACTION 重复触发 */
    private var lastWifiAction: String? = null

    /** 电量状态缓存 — 避免 ACTION_BATTERY_CHANGED 粘性广播重复触发 */
    private var lastBatteryLevel: Int? = null
    private var lastBatteryPlugged: Boolean? = null

    private fun emit(type: RecordType, action: String, timestamp: Long, duration: Long? = null, detail: Map<String, String>? = null) {
        if (!handlerRegistry.validate(type, action, detail)) return
        val label = handlerRegistry.createLabel(type, action, detail)
        store.insert(Record(
            id = UUID.randomUUID().toString(),
            type = type,
            action = action,
            timestamp = timestamp,
            duration = duration,
            label = label,
            detail = detail
        ))
    }

    fun onScreenChanged(action: String, timestamp: Long) {
        emit(RecordType.SCREEN, action, timestamp)
        if (action == "OFF") usageTracker.onScreenOff(timestamp)
        if (action == "ON") usageTracker.onScreenOn(timestamp)
    }

    fun onChargingChanged(action: String, chargeType: String?, timestamp: Long) {
        val detail = if (chargeType != null) mapOf("type" to chargeType) else null
        emit(RecordType.CHARGING, action, timestamp, detail = detail)
    }

    fun onAppSwitched(packageName: String, appName: String, timestamp: Long) {
        usageTracker.onAppSwitch(packageName, appName, timestamp)
    }

    fun onWifiChanged(action: String, ssid: String?, timestamp: Long) {
        // 去重：WiFi 状态未变化时跳过
        if (action == lastWifiAction) return
        lastWifiAction = action
        val detail = if (ssid != null) mapOf("ssid" to ssid) else null
        emit(RecordType.WIFI, action, timestamp, detail = detail)
    }

    fun onAudioChanged(action: String, device: String?, timestamp: Long) {
        val detail = if (device != null) mapOf("device" to device) else null
        emit(RecordType.AUDIO, action, timestamp, detail = detail)
    }

    fun onBatteryChanged(level: Int, plugged: Boolean, timestamp: Long) {
        // 去重：电量 level 和 plugged 状态均未变化时跳过（ACTION_BATTERY_CHANGED 是粘性广播）
        if (level == lastBatteryLevel && plugged == lastBatteryPlugged) return
        lastBatteryLevel = level
        lastBatteryPlugged = plugged
        emit(RecordType.BATTERY, "CHANGED", timestamp, detail = mapOf("level" to level.toString(), "plugged" to plugged.toString()))
    }

    fun onBatteryLow(level: Int, timestamp: Long) {
        emit(RecordType.BATTERY, "LOW", timestamp, detail = mapOf("level" to level.toString()))
    }

    fun onBatteryOkay(level: Int, timestamp: Long) {
        emit(RecordType.BATTERY, "OKAY", timestamp, detail = mapOf("level" to level.toString()))
    }

    fun onPackageChanged(action: String, packageName: String, appName: String?, timestamp: Long) {
        val detail = mutableMapOf("package_name" to packageName)
        appName?.let { detail["app_name"] = it }
        emit(RecordType.PACKAGE, action, timestamp, detail = detail)
    }

    fun onPowerSaveChanged(enabled: Boolean, timestamp: Long) {
        emit(RecordType.POWER_SAVE, if (enabled) "ON" else "OFF", timestamp)
    }

    fun onAirplaneChanged(enabled: Boolean, timestamp: Long) {
        emit(RecordType.AIRPLANE, if (enabled) "ON" else "OFF", timestamp)
    }

    fun onPhoneStateChanged(state: String, number: String?, timestamp: Long) {
        val detail = if (number != null) mapOf("number" to number.takeLast(4)) else null
        emit(RecordType.PHONE_STATE, state, timestamp, detail = detail)
    }

    fun onSkipAd(appName: String, packageName: String, selectorExpr: String) {
        emit(RecordType.SCREEN, "AD_SKIPPED", System.currentTimeMillis(), detail = mapOf(
            "app_name" to appName,
            "package_name" to packageName,
            "selector" to selectorExpr
        ))
    }
}
