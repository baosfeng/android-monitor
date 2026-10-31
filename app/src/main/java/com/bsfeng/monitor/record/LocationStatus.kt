package com.bsfeng.monitor.record

// 从 LocationCollector 拆出：采集状态快照（只读 API，供设置页自检与日志）。
// 只暴露数据，不碰 UI：设置页要展示什么由调用方决定。

/**
 * 最近一次采样失败的原因。
 *
 * 枚举而不是自由文本：设置页可以据此给出可执行的提示（未授权 -> 去开权限；精度不足 -> 换个地方再试），
 * 而日志里仍会带自由文本细节（[LocationStatus.lastFailureDetail]）。
 */
enum class LocationFailureReason {
    /** 上一次采样成功（或还没有失败过）。 */
    NONE,

    /** 未授权（只有前台定位时后台也拿不到点，见 [LocationPermissionStatus]）。 */
    PERMISSION,

    /** 按模式节流中 / 已有主动定位在飞 / 首次成功前退避未到：都不是故障，只是这次不发令。 */
    THROTTLED,

    /** 主动定位在预算内没拿到可用点（Doze / 室内 / provider 全关）。 */
    ACTIVE_FIX_TIMEOUT,

    /** 点位精度不够（低置信且没有更优位置可覆盖，或差到直接丢弃）。 */
    LOW_CONFIDENCE,

    /** 没有可用的 LocationManager / 订阅源（极少见：模拟器或 ROM 裁剪）。 */
    SOURCE_UNAVAILABLE,

    /** 底层抛异常（SecurityException / IllegalArgumentException），已兜底。 */
    EXCEPTION,
}

/**
 * 采集器的只读状态快照。
 *
 * [msSinceLastSuccess] 是「掉线」最直接的度量：用户看到「最后成功定位 3 分钟前」比看到
 * 「没有任何点」可诊断得多。[subscriptionActive] / [subscriptionIntervalMs] 用来判断
 * 持续订阅通道是否真的活着（静默超时会被重建，见 LocationSubscriptionSupervisor）。
 */
data class LocationStatus(
    val lastSuccessAtMs: Long? = null,
    val msSinceLastSuccess: Long? = null,
    val lastFailure: LocationFailureReason = LocationFailureReason.NONE,
    val lastFailureDetail: String? = null,
    val subscriptionActive: Boolean = false,
    val subscriptionIntervalMs: Long? = null,
    val subscriptionSilentMs: Long? = null,
    val mode: SampleMode = SampleMode.STILL,
    val intervalMs: Long = LocationPace.STILL_INTERVAL_MS,
    val lastProvider: String? = null,
    val lastAccuracyMeters: Float? = null,
    val confidence: FixConfidence? = null,
) {
    /** 一行可读摘要，供 LocationCollector 日志与设置页自检直接使用。 */
    fun summary(): String {
        val subscription = if (subscriptionActive) "活跃" else "未建立"
        val intervalSuffix = subscriptionIntervalMs?.let { "(" + (it / 1000) + "s)" } ?: ""
        val lastSuccess = msSinceLastSuccess?.let { (it / 1000).toString() + "s 前" } ?: "从未"
        val provider = lastProvider?.let { " provider=" + it } ?: ""
        val accuracy = lastAccuracyMeters?.let { " accuracy=" + it } ?: ""
        val confidenceText = confidence?.let { " 置信=" + it } ?: ""
        return "订阅=" + subscription + intervalSuffix +
            " 模式=" + mode + " 间隔=" + (intervalMs / 1000) + "s" +
            " 上次成功=" + lastSuccess +
            " 失败原因=" + lastFailure + provider + accuracy + confidenceText
    }
}

/**
 * 采集健康状态的可变持有者（采集器内部用）。
 *
 * 抽成独立类只为让 [LocationCollector] 保持在 400 行以内：这些字段是四条链路
 * （订阅 / 缓存 / 主动定位 / 心跳）都要读写的共享状态，集中在一处好审。
 * 全部读写加锁：订阅回调在主线程、心跳在另一条线程。
 */
internal class LocationHealthState {

    private val lock = Any()

    @Volatile
    var lastAccepted: LocationSample? = null
        private set

    @Volatile
    var lastSuccessAtMs: Long? = null
        private set

    @Volatile
    var failure: LocationFailureReason = LocationFailureReason.NONE
        private set

    @Volatile
    var failureDetail: String? = null
        private set

    /** 记录一次被接受的采样（成功时间 + 对照点）。 */
    fun onAccepted(sample: LocationSample, nowMs: Long) = synchronized(lock) {
        lastAccepted = sample
        lastSuccessAtMs = nowMs
        failure = LocationFailureReason.NONE
        failureDetail = null
    }

    /** 记录一次失败（只为可观测，不影响控制流）。 */
    fun onFailure(reason: LocationFailureReason, detail: String) {
        failure = reason
        failureDetail = detail
    }

    /** 断档后清掉对照点（旧点 30 分钟后本来就 stale，不该再拦住新点）。 */
    fun resetAccepted() = synchronized(lock) {
        lastAccepted = null
    }

    /** 生成只读快照。 */
    fun snapshot(
        nowMs: Long,
        mode: SampleMode,
        intervalMs: Long,
        subscription: LocationSubscriptionSupervisor.Snapshot?,
    ): LocationStatus {
        val last = lastAccepted
        return LocationStatus(
            lastSuccessAtMs = lastSuccessAtMs,
            msSinceLastSuccess = lastSuccessAtMs?.let { nowMs - it },
            lastFailure = failure,
            lastFailureDetail = failureDetail,
            subscriptionActive = subscription?.active == true,
            subscriptionIntervalMs = subscription?.intervalMs?.takeIf { it > 0L },
            subscriptionSilentMs = subscription?.silentMs,
            mode = mode,
            intervalMs = intervalMs,
            lastProvider = last?.provider,
            lastAccuracyMeters = last?.accuracyMeters,
            confidence = last?.let { LocationQuality.confidenceOf(it.provider, it.accuracyMeters) },
        )
    }
}
