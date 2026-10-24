package com.bsfeng.monitor.record

// 定位采集入口：两级取点（系统缓存 → 一次主动定位）+ 落库节流。
// 规则在 LocationRules / LocationThrottle / ActiveFixResolver，Android 实现在 AndroidLocationEnv。

import android.content.Context
import android.content.pm.PackageManager
import java.util.Locale

/**
 * 一次位置采样（纯数据）。
 *
 * 不直接用 `android.location.Location` 是为了让过滤规则能在纯 JVM 单测里跑
 * （Robolectric 沙箱能造 Location，但纯数据类更快、更稳）。
 */
data class LocationSample(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timeMs: Long,
)

/** 定位权限三态（设置页自检展示用）。 */
enum class LocationPermissionStatus {
    /** 前台与后台定位都已授权，后台也能采到点。 */
    GRANTED,

    /** 只有前台定位授权：息屏/切后台后取不到点。 */
    MISSING_BACKGROUND,

    /** 完全未授权。 */
    NOT_GRANTED,
}

/**
 * 定位采集器。
 *
 * 两级取点，都不常驻：
 * 1. 先读系统缓存（`getLastKnownLocation`，零功耗）；有可用点直接用；
 * 2. 缓存为空 / 移动模式下缓存不够新时发起**一次**主动定位（network → gps，总预算 5 秒），
 *    拿到首个可用点后立即释放。
 *
 * 采样节奏按模式自适应（[LocationPace]）：移动 10 秒、静止 3 分钟。由两条链路驱动：
 * - 无障碍前台服务里的 10 秒轻量定时器：只在 [SampleMode.MOVING] 时补采；
 * - 已有 1 分钟 [com.bsfeng.monitor.service.AlarmReceiver] 心跳：兜底（服务被杀后仍按静止节奏补点）。
 *
 * 不新开前台服务、不注册常驻 Listener。
 *
 * 异常契约：未授权 / 无 LocationManager / provider 不可用 / 系统抛 SecurityException
 * 一律回调 null，不向调用方抛异常（心跳与轮询链路不能被定位拖垮）。
 */
class LocationCollector internal constructor(
    private val env: Env,
    private val throttle: LocationThrottle = LocationThrottle(),
    private val modeTracker: LocationModeTracker = LocationModeTracker(),
) {

    /** 采集依赖：可替换以便单测（生产实现见 [AndroidEnv]）。 */
    internal interface Env {
        fun isFineGranted(): Boolean
        fun isBackgroundGranted(): Boolean

        /** 读系统缓存位置（三个 provider 各一条，可能为空）。 */
        fun readCached(): List<LocationSample>

        /** 发起一次主动定位；无 LocationManager 时回调 null。 */
        fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit)

        /**
         * 诊断日志出口。
         *
         * 纯 JVM 单测里 `android.util.Log` 会抛「not mocked」，所以这一层的日志必须可注入；
         * 生产实现转给 Log.d / Log.w，标签统一 `LocationCollector`。
         */
        fun log(message: String, warn: Boolean) {}
    }

    constructor(context: Context) : this(AndroidEnv(context.applicationContext))

    /**
     * 采集一次（不节流，只读缓存）。
     *
     * @return 可用的位置点；无权限、无可信 provider、点全部 stale 或精度不达标时返回 null
     */
    fun collect(nowMs: Long = System.currentTimeMillis()): LocationSample? {
        if (!env.isFineGranted()) return null
        return LocationPolicy.pick(env.readCached(), nowMs)
    }

    /** 当前采样模式（进程内状态；重启回静止）。 */
    val mode: SampleMode get() = modeTracker.mode

    /** 当前模式对应的采样间隔（毫秒），供定时器与日志使用。 */
    fun samplingIntervalMs(): Long = LocationPace.intervalOf(mode)

    /**
     * 「现在该不该采」的纯判断：只读节流与模式状态，**不读定位、不发起请求**。
     *
     * 10 秒定时器在静止时只用它判断（然后什么都不做），移动时才真的去采。
     */
    fun shouldSampleNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        throttle.canAcquire(nowMs, samplingIntervalMs())

    /**
     * 按模式间隔采集（异步）：移动 10 秒 / 静止 3 分钟。
     *
     * 移动时优先用系统缓存，但缓存必须「新鲜」（≤ [LocationPace.CACHE_FRESH_MS]）——
     * 10 秒节奏靠 30 分钟内的旧缓存会画出原地打转的假轨迹，这时宁愿主动定位一次。
     *
     * @param onResult **恰好回调一次**（缓存命中时同步回调，需要主动定位时异步回调）；
     *   被节流 / 未授权 / 超时 / 过滤后为空都回 null
     */
    fun collectThrottledAsync(
        nowMs: Long = System.currentTimeMillis(),
        onResult: (LocationSample?) -> Unit,
    ) {
        val modeAtStart = mode
        val interval = LocationPace.intervalOf(modeAtStart)
        if (!throttle.canAcquire(nowMs, interval)) {
            env.log("定位节流中：${modeAtStart.name} 间隔 ${interval / 1000}s 内已采过", false)
            onResult(null)
            return
        }
        if (!env.isFineGranted()) {
            env.log("定位跳过：未授权", true)
            onResult(null)
            return
        }
        val cached = LocationPolicy.pick(env.readCached(), nowMs)
        val cachedFresh = cached != null && nowMs - cached.timeMs <= LocationPace.CACHE_FRESH_MS
        // 静止：缓存只要没过期就用；移动：必须够新鲜，否则主动定位一次（10 秒节奏不能被旧点糊弄）
        if (cached != null && (modeAtStart == SampleMode.STILL || cachedFresh)) {
            deliver(cached, nowMs, modeAtStart, onResult)
            env.log("定位命中缓存 provider=${cached.provider} accuracy=${cached.accuracyMeters} age=${nowMs - cached.timeMs}ms", false)
            return
        }
        env.log(
            "定位发起单次主动定位（${LocationPolicy.ACTIVE_PROVIDERS.joinToString()} / " +
                "${LocationPolicy.ACTIVE_FIX_BUDGET_MS}ms，模式=$modeAtStart）",
            false,
        )
        try {
            env.requestActiveFix(nowMs) { sample ->
                if (sample == null) {
                    onResult(null)
                } else {
                    deliver(sample, nowMs, modeAtStart, onResult)
                }
            }
        } catch (e: Exception) {
            // 兜底：底层抛异常也必须把「失败」交出去，否则调用方的 PendingResult 永远不 finish
            env.log("定位采集异常：${e.javaClass.simpleName}", true)
            onResult(null)
        }
    }

    /** 兼容旧调用点：只读缓存的同步节流版（不做主动定位）。 */
    fun collectThrottled(nowMs: Long = System.currentTimeMillis()): LocationSample? {
        if (!throttle.canAcquire(nowMs, samplingIntervalMs())) return null
        val sample = collect(nowMs) ?: return null
        deliver(sample, nowMs, mode) { }
        return sample
    }

    /**
     * 交付一个点：更新模式（状态机）→ 占节流窗口 → 回调。
     *
     * 顺序有意义：模式要在下一次「该不该采」判断前更新，否则移动的第一个点之后还会按静止间隔等。
     */
    private fun deliver(
        sample: LocationSample,
        nowMs: Long,
        modeAtStart: SampleMode,
        onResult: (LocationSample?) -> Unit,
    ) {
        val updated = modeTracker.onSample(sample)
        throttle.markAcquired(nowMs)
        if (updated != modeAtStart) {
            env.log(
                "定位采样模式切换：${modeAtStart.name} → ${updated.name}" +
                    "（间隔 ${LocationPace.intervalOf(updated) / 1000}s）",
                false,
            )
        }
        env.log(
            "定位采样成功 provider=${sample.provider} accuracy=${sample.accuracyMeters}" +
                " 模式=$updated 间隔=${LocationPace.intervalOf(updated) / 1000}s",
            false,
        )
        onResult(sample)
    }

    /** 权限是否至少给了前台定位（后台定位缺失时后台采样会拿到 null，见 [status]）。 */
    fun hasLocationPermission(): Boolean = env.isFineGranted()

    /** 当前定位权限三态，供设置页自检。 */
    fun status(): LocationPermissionStatus =
        LocationPermissionRules.resolveStatus(env.isFineGranted(), env.isBackgroundGranted())

    companion object {
        private const val TAG = "LocationCollector"

        @Volatile
        private var instance: LocationCollector? = null

        /**
         * 进程内单例。
         *
         * 节流状态（上次落库时间）与采样模式必须跨心跳 / 定时器保留：每次 new 一个采集器会让
         * 节流失效，变成每次调用都落一条坐标，模式也会一直停在静止。懒加载 + 双重校验，
         * 与仓库其它单例一致。
         */
        fun get(context: Context): LocationCollector = instance ?: synchronized(this) {
            instance ?: LocationCollector(context.applicationContext).also { instance = it }
        }

        /** 权限三态判定（委托到 [LocationPermissionRules]，纯函数，设置页与单测共用）。 */
        fun resolveStatus(fineGranted: Boolean, backgroundGranted: Boolean): LocationPermissionStatus =
            LocationPermissionRules.resolveStatus(fineGranted, backgroundGranted)
    }
}
