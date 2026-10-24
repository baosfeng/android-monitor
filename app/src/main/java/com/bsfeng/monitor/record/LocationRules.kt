package com.bsfeng.monitor.record

import java.util.Locale

// 从 record/LocationCollector.kt 拆出：定位规则、落库节流、缓存为空时的主动定位编排。
// 三段都是纯逻辑（不碰 Android API），因此能在纯 JVM 单测里逐条验证。

/**
 * 定位采集的**全部规则**：过滤 / 挑选 / 节流 / 量化。纯逻辑，可单测。
 *
 * 阈值来源见 docs/事件采集/扩容与每日行程调研.md「定位与地址」：
 * 精度 > 100m 的点丢掉；坐标量化到 3 位小数（≈110m）；落库节流 ≤1 条/10 分钟。
 */
object LocationPolicy {

    /** 超过 30 分钟的位置点视为 stale（进程被冻结/无新定位时不要拿旧点冒充当前位置）。 */
    const val MAX_AGE_MS = 30 * 60 * 1000L

    /** 精度差于 100m 的点丢弃（NETWORK_PROVIDER 室内常到 100–2000m）。 */
    const val MAX_ACCURACY_METERS = 100f

    /** COORDINATE 落库节流：10 分钟内最多一条。 */
    const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    /** 本地坐标保留 3 位小数（≈110m），上报与行程判定都用这个精度。 */
    const val COORDINATE_DECIMALS = 3

    /**
     * provider 优先级：PASSIVE（系统缓存 / 别的 App 刚定位过）→ NETWORK（室内可用）→ GPS（室外兜底）。
     * 与 [LocationManager] 的常量值一致，避免在这里引入 Android 依赖做单测。
     */
    val PROVIDER_PRIORITY = listOf("passive", "network", "gps")

    /**
     * **主动定位**的 provider 顺序：network 先（室内/模拟器更快出点）→ gps。
     * PASSIVE 不参与：它只产出别人的缓存点，不会主动去测。
     */
    val ACTIVE_PROVIDERS = listOf("network", "gps")

    /** 主动定位的总预算：5 秒拿不到可用点就放弃（不猜位置、不落库）。 */
    const val ACTIVE_FIX_BUDGET_MS = 5_000L

    /** 时间新鲜度；时钟回拨（now < time）不算 stale。 */
    fun isFresh(timeMs: Long, nowMs: Long): Boolean = nowMs - timeMs <= MAX_AGE_MS

    /** 精度过滤；`accuracy <= 0` 表示系统未给出精度，按不可用处理。 */
    fun isAccurate(accuracyMeters: Float): Boolean =
        accuracyMeters > 0f && accuracyMeters <= MAX_ACCURACY_METERS

    fun isUsable(sample: LocationSample, nowMs: Long): Boolean =
        isFresh(sample.timeMs, nowMs) && isAccurate(sample.accuracyMeters)

    /** 按 [PROVIDER_PRIORITY] 取第一个可用点；全都不可用返回 null（不猜位置）。 */
    fun pick(candidates: List<LocationSample>, nowMs: Long): LocationSample? =
        PROVIDER_PRIORITY.firstNotNullOfOrNull { provider ->
            candidates.firstOrNull { it.provider == provider && isUsable(it, nowMs) }
        }

    /** 坐标量化成 3 位小数字符串；-0.000 归一成 0.000（避免同一地点出现两种写法）。 */
    fun quantize(value: Double): String {
        val factor = 1_000.0
        val rounded = Math.round(value * factor) / factor
        return if (rounded == 0.0) "0.000" else String.format(Locale.US, "%.3f", rounded)
    }
}

/** 定位权限三态判定（纯函数，设置页与单测共用）。 */
object LocationPermissionRules {
    /**
     * minSdk 29 起，Android 10+ 在后台读位置必须拿到 `ACCESS_BACKGROUND_LOCATION`，
     * 只有前台授权时后台取不到点 —— 这正是「已开定位但没有记录」的常见原因。
     */
    fun resolveStatus(fineGranted: Boolean, backgroundGranted: Boolean): LocationPermissionStatus = when {
        !fineGranted -> LocationPermissionStatus.NOT_GRANTED
        !backgroundGranted -> LocationPermissionStatus.MISSING_BACKGROUND
        else -> LocationPermissionStatus.GRANTED
    }
}
