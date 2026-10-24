package com.bsfeng.monitor.record

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// 从 record/LocationCollector.kt 拆出：定位规则、采样节奏与模式判定、落库节流、主动定位编排。
// 都是纯逻辑（不碰 Android API），因此能在纯 JVM 单测里逐条验证。

/**
 * 定位采集的**全部规则**：过滤 / 挑选 / 量化。纯逻辑，可单测。
 *
 * 阈值来源见 docs/事件采集/扩容与每日行程调研.md「定位与地址」：
 * 精度 > 100m 的点丢掉；坐标量化到 3 位小数（≈110m）。
 */
object LocationPolicy {

    /** 超过 30 分钟的位置点视为 stale（进程被冻结/无新定位时不要拿旧点冒充当前位置）。 */
    const val MAX_AGE_MS = 30 * 60 * 1000L

    /** 精度差于 100m 的点丢弃（NETWORK_PROVIDER 室内常到 100–2000m）。 */
    const val MAX_ACCURACY_METERS = 100f

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

/** 采样模式：移动（约 10 秒一个点）/ 静止（约 3 分钟一个点）。 */
enum class SampleMode {
    MOVING,
    STILL,
}

/**
 * 采样节奏与模式判定（纯函数 + 无副作用的小状态机，可单测）。
 *
 * 判定口径（用户可见行为，改动前先读）：
 * - 静止 → 移动：相邻两点距离 > 100m 且时间差 ≤ 5 分钟；或精度明显改善（≥30m）且位移 > 50m；
 * - 移动 → 静止：连续 3 次采样位移都 < 50m；
 * - 跨天 / 长断档（> 5 分钟）不做「移动」判定：隔夜的两个点不是「刚走了一步」；
 * - 重启回 [SampleMode.STILL]：最多 3 分钟就能重新识别出移动（静止间隔就是 3 分钟）。
 */
object LocationPace {

    /** 移动时的采样间隔：10 秒。 */
    const val MOVING_INTERVAL_MS = 10_000L

    /** 静止时的采样间隔：3 分钟（省电优先；1–3 分钟区间取上限）。 */
    const val STILL_INTERVAL_MS = 3 * 60_000L

    /** 移动时系统缓存不超过 30 秒就直接用：不每次都为 10 秒节奏去主动定位（省电）。 */
    const val CACHE_FRESH_MS = 30_000L

    /** 静止 → 移动：位移阈值（米）。 */
    const val MOVE_DISTANCE_M = 100.0

    /** 静止 → 移动的观察窗口：超过这个时间差说明中间断档，不当作「刚移动」。 */
    const val MOVE_WINDOW_MS = 5 * 60_000L

    /** 精度改善阈值（米）：新点比旧点准这么多，才把「小位移」也算成移动。 */
    const val ACCURACY_GAIN_M = 30f

    /** 精度改善判定的位移门槛（米）。 */
    const val ACCURACY_MOVE_DISTANCE_M = 50.0

    /** 移动 → 静止：位移足够小（米）。 */
    const val STILL_DISTANCE_M = 50.0

    /** 移动 → 静止：需要连续多少次「原地」采样。 */
    const val STILL_STREAK = 3

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 模式对应的采样间隔。 */
    fun intervalOf(mode: SampleMode): Long =
        if (mode == SampleMode.MOVING) MOVING_INTERVAL_MS else STILL_INTERVAL_MS

    /** 两点间大圆距离（米）。 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }
}

/**
 * 采样模式状态机（进程内存，不落库）。
 *
 * 只吃「上一次采样」与「本次采样」，所以能在纯 JVM 单测里把边界钉死：
 * 零位移、一次大跳变、时钟回拨 / 跨天断档、来回抖动（抖动由 3 次连续静止吸收）。
 */
class LocationModeTracker(private val initialMode: SampleMode = SampleMode.STILL) {

    /** 当前模式；重启（新实例）回 [initialMode]。 */
    var mode: SampleMode = initialMode
        private set

    private var last: LocationSample? = null
    private var stillStreak: Int = 0

    /** 喂一次采样，返回更新后的模式。 */
    fun onSample(sample: LocationSample): SampleMode {
        val previous = last
        last = sample
        if (previous == null) return mode

        val gap = sample.timeMs - previous.timeMs
        val distance = LocationPace.distanceMeters(
            previous.latitude, previous.longitude, sample.latitude, sample.longitude,
        )
        // 时钟回拨（gap < 0）按「刚采样过」处理；跨天 / 长断档（gap > 5 分钟）不判移动
        val withinWindow = gap <= LocationPace.MOVE_WINDOW_MS

        when (mode) {
            SampleMode.STILL -> {
                val movedFar = distance > LocationPace.MOVE_DISTANCE_M && withinWindow
                val accuracyGain = previous.accuracyMeters - sample.accuracyMeters
                val movedWithBetterFix = withinWindow &&
                    distance > LocationPace.ACCURACY_MOVE_DISTANCE_M &&
                    accuracyGain >= LocationPace.ACCURACY_GAIN_M
                if (movedFar || movedWithBetterFix) {
                    mode = SampleMode.MOVING
                    stillStreak = 0
                }
            }
            SampleMode.MOVING -> {
                if (distance < LocationPace.STILL_DISTANCE_M) {
                    stillStreak++
                    if (stillStreak >= LocationPace.STILL_STREAK) {
                        mode = SampleMode.STILL
                        stillStreak = 0
                    }
                } else {
                    stillStreak = 0
                }
            }
        }
        return mode
    }

    /** 恢复到初始状态（单测 / 用户关闭再开启位置采集时调用）。 */
    fun reset() {
        last = null
        stillStreak = 0
        mode = initialMode
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
