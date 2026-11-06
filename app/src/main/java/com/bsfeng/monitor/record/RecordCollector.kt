package com.bsfeng.monitor.record

import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import java.util.UUID

/**
 * 采集层入口。
 *
 * 各采集源（BroadcastReceiver、AccessibilityService）调用对应方法，
 * RecordCollector 通过 handlerRegistry 校验并生成 label，最终写入 store。
 *
 * 前台 App 切换采用状态驱动：由轮询每秒调用 [onTick]，
 * UsageTracker 内部累加时长，切换时自动写入一条带时长的记录。
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
    private val handlerRegistry: RecordHandlerRegistry,
    /**
     * 逆地理编码（定位地址）：默认走进程单例；单测 / 不需要地址时传 null。
     *
     * 调用发生在本类已有的后台协程里（心跳的 IO 协程），自身带硬超时与 LRU 缓存，
     * 因此不阻塞主线程、不阻塞无障碍采集，失败也不影响记录落库。
     */
    private val geocoder: LocationReverseGeocoder? = defaultGeocoder(),
) {
    /** WiFi 状态缓存 — 避免 WIFI_STATE_CHANGED_ACTION 重复触发 */
    private var lastWifiAction: String? = null
    /** WiFi 时间窗口防抖 — 30 秒内不重复记录 WiFi 切换事件（防止网络波动） */
    private var lastWifiTimestamp: Long = 0L
    private companion object {
        private const val WIFI_DEBOUNCE_MS = 30_000L

        /** 进程单例；构造过早（单测 / 无 Application）时降级为「不解析地址」。 */
        fun defaultGeocoder(): LocationReverseGeocoder? =
            runCatching { LocationReverseGeocoder.get(MonitorApp.instance) }.getOrNull()
    }

    /** 当前最新电量（持续更新，供充电开始/结束使用） */
    private var currentBatteryLevel: Int = 0

    /** 未充电时的电量缓存 — 避免 ACTION_BATTERY_CHANGED 粘性广播重复触发 */
    private var lastBatteryLevel: Int? = null

    /**
     * 落库前的暂停闸门（锚点 s09-monitor「暂停后停止写新事件、实时流不再增长」）。
     *
     * 闸门放在本类而不是 [RecordStore]：定位采样、广播事件、广告跳过、使用时长全部经由
     * [RecordCollector] 进入事件表，一处短路就覆盖全部采集源；放 Store 会把「用户暂停」和
     * 「清库 / 还原备份」这类系统写操作混在一起。
     */
    private fun emit(type: RecordType, action: String, timestamp: Long, duration: Long? = null, detail: Map<String, String>? = null) {
        if (MonitorPauseGate.isPaused) return
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
        // 暂停中连「熄屏 flush」一起跳过：UsageTracker 的回调绕过 emit 直接写库，
        // 这里不拦就会出现「暂停期间实时流还在长」——正是 s09-monitor 要修的那个假反馈。
        if (MonitorPauseGate.isPaused) {
            discardUsageSession()
            return
        }
        emit(RecordType.SCREEN, action, timestamp)
        if (action == "OFF") usageTracker.flush(timestamp)
    }

    fun onChargingChanged(action: String, chargeType: String?, timestamp: Long) {
        val detail = mutableMapOf("level" to currentBatteryLevel.toString())
        chargeType?.let { detail["type"] = it }
        emit(RecordType.CHARGING, action, timestamp, detail = detail)
    }

    /**
     * 前台轮询 tick：每秒由 MonitorAccessibilityService 调用。
     *
     * @param packageName 当前前台 App 包名，null 表示桌面/系统 UI/息屏
     * @param appName 当前前台 App 显示名
     * @param timestamp 当前毫秒时间戳
     */
    fun onTick(packageName: String?, appName: String?, timestamp: Long) {
        // [UsageTracker] 的落库回调不经 emit，闸门必须在这里单独拦一次。
        if (MonitorPauseGate.isPaused) {
            discardUsageSession()
            return
        }
        usageTracker.tick(packageName, appName, timestamp)
    }

    /**
     * 暂停期间丢弃用量 session（不写库）。
     *
     * 只 return 不清状态的话，恢复后第一次 tick 的 delta 会覆盖整段暂停时间，
     * 于是「暂停了 10 分钟」被记成「这个 App 用了 10 分钟」。
     */
    private fun discardUsageSession() = usageTracker.discard()

    fun onWifiChanged(action: String, ssid: String?, timestamp: Long) {
        // 去重1：相同 action 跳过
        if (action == lastWifiAction) return
        // 去重2：30 秒内不重复记录 WiFi 切换事件（防止网络波动导致频繁断开重连）
        if (timestamp - lastWifiTimestamp < WIFI_DEBOUNCE_MS) return
        lastWifiAction = action
        lastWifiTimestamp = timestamp
        val detail = if (ssid != null) mapOf("ssid" to ssid) else null
        emit(RecordType.WIFI, action, timestamp, detail = detail)
    }

    fun onAudioChanged(action: String, device: String?, timestamp: Long) {
        val detail = if (device != null) mapOf("device" to device) else null
        emit(RecordType.AUDIO, action, timestamp, detail = detail)
    }

    fun onBatteryChanged(level: Int, plugged: Boolean, timestamp: Long) {
        // 始终更新最新电量（供充电开始/结束事件使用）
        currentBatteryLevel = level

        // 充电中：不记录中间电量变化，只更新缓存（充电开始/结束由 onChargingChanged 处理）
        if (plugged) return

        // 未充电时：仅电量真正变化时记录
        if (level == lastBatteryLevel) return
        lastBatteryLevel = level
        emit(RecordType.BATTERY, "CHANGED", timestamp, detail = mapOf("level" to level.toString()))
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

    /**
     * 记录一次广告跳过。
     *
     * @param confirmed 点击后确认结果（true 已确认广告消失 / false 未确认 / null 未复核）
     */
    fun onSkipAd(
        appName: String,
        packageName: String,
        selectorExpr: String,
        confirmed: Boolean? = null,
        matchedText: String? = null,
        verdictReason: String? = null
    ) {
        val detail = mutableMapOf(
            "app_name" to appName,
            "package_name" to packageName,
            "selector" to selectorExpr
        )
        confirmed?.let { detail["confirmed"] = it.toString() }
        // 判据证据：只有 selector 无法回溯"点的是什么字"（2026-11-02 小红书误判的教训）
        matchedText?.takeIf { it.isNotBlank() }?.let { detail["matched_text"] = it }
        verdictReason?.takeIf { it.isNotBlank() }?.let { detail["verdict_reason"] = it }
        emit(RecordType.SCREEN, "AD_SKIPPED", System.currentTimeMillis(), detail = detail)
    }

    /**
     * 记录一次「已点击但未确认」的跳过（点击后复核时跳过按钮仍在）。
     *
     * 为什么不复用 [onSkipAd]：`AD_SKIPPED` 的语义是「跳过成功」，所有成功口径都按它计数
     * （广告页列表 / 概览 / 桌面小组件）。把它写成独立 action，计数口径自动分离，
     * 未确认既不会伪装成成功，也不会丢证据 —— 误判排查全靠这批数据。
     */
    fun onSkipAdUnconfirmed(
        appName: String,
        packageName: String,
        selectorExpr: String,
        matchedText: String? = null,
        verdictReason: String? = null
    ) {
        val detail = mutableMapOf(
            "app_name" to appName,
            "package_name" to packageName,
            "selector" to selectorExpr,
            "confirmed" to "false"
        )
        matchedText?.takeIf { it.isNotBlank() }?.let { detail["matched_text"] = it }
        verdictReason?.takeIf { it.isNotBlank() }?.let { detail["verdict_reason"] = it }
        emit(RecordType.SCREEN, "AD_SKIP_UNCONFIRMED", System.currentTimeMillis(), detail = detail)
    }

    /**
     * 写入一次定位采样（COORDINATE）。
     *
     * 坐标为量化后的 3 位小数（≈110m）；有逆地理编码结果时额外写一个中文地址（仅地址文本）。
     *
     * 地址查询在落库前完成，但**有界且失败静默**：命中缓存瞬时返回，未命中最多
     * [LocationReverseGeocoder.GEOCODE_TIMEOUT_MS]（3 秒，≤5 秒口径），失败 / 无网 / 限频
     * 一律不写 address，记录照常落库 —— 坐标记录不会因网络问题丢失。
     */
    fun onLocation(sample: LocationSample) {
        val detail = mutableMapOf(
            "lat" to LocationPolicy.quantize(sample.latitude),
            "lng" to LocationPolicy.quantize(sample.longitude),
            "provider" to sample.provider,
            "accuracy_m" to sample.accuracyMeters.toInt().toString()
        )
        geocoder?.resolve(sample.latitude, sample.longitude)
            ?.takeIf { it.isNotBlank() }
            ?.let { detail["address"] = it }
        emit(RecordType.LOCATION, "COORDINATE", sample.timeMs, detail = detail)
    }
}
