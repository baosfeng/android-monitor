package com.bsfeng.monitor.record

// 从 AndroidLocationEnv 拆出：持续订阅通道的生产实现（requestLocationUpdates + removeUpdates）。
// 只在真机/集成路径里用；纯 JVM 单测注入 LocationSubscriptionSource 的假实现，不碰这里。

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 生产实现的持续位置订阅。
 *
 * 每个 provider 注册一个 listener，收到点就回调；[stop] 一次性 removeUpdates 全部并丢弃引用。
 * 三条硬约束：
 * - **恰好一次**：已订阅时再 start 会先 stop，绝不叠加 listener（叠加会成倍耗电、成倍回调）；
 * - **不泄漏**：stop 后立刻 null 掉 listener 列表，服务销毁路径必须能重复调用；
 * - **不上抛**：SecurityException（权限被撤）/ IllegalArgumentException（provider 不存在）
 *   一律记日志后当失败返回，LocationListener 的回调里也不做可能抛异常的活。
 */
internal class AndroidLocationSubscriber(private val context: Context) : LocationSubscriptionSource {

    private val manager: LocationManager? by lazy {
        runCatching { context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }.getOrNull()
    }

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val lock = Any()

    /** provider -> 已注册的 listener；空表示未订阅。 */
    private var registered: Map<String, LocationListener> = emptyMap()

    /** 本次订阅的投递回调（stop 时置空，防止 stop 后仍有一帧回调打到调用方）。 */
    @Volatile
    private var consumer: ((LocationSample) -> Unit)? = null

    override fun start(
        providers: List<String>,
        intervalMs: Long,
        minDistanceMeters: Float,
        onSample: (LocationSample) -> Unit,
    ): Boolean {
        val lm = manager ?: run {
            Log.w(TAG, "位置订阅跳过：LocationManager 不可用")
            return false
        }
        // lint MissingPermission：requestLocationUpdates / removeUpdates 都要求调用点显式判权限
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "位置订阅跳过：未授权")
            return false
        }
        stop()
        consumer = onSample
        val listeners = mutableMapOf<String, LocationListener>()
        for (provider in providers) {
            val enabled = runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
            if (!enabled) {
                Log.w(TAG, "位置订阅跳过 provider=$provider（provider 未启用）")
                continue
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    // 系统在主线程回调：这里只做纯数据转换，异常一律吞掉（不能把主线程炸掉）
                    runCatching { consumer?.invoke(sampleOf(provider, location)) }
                        .onFailure { Log.w(TAG, "位置订阅回调异常 provider=$provider: " + it.javaClass.simpleName) }
                }
            }
            val ok = runCatching {
                lm.requestLocationUpdates(provider, intervalMs, minDistanceMeters, listener, Looper.getMainLooper())
                true
            }.getOrElse { error ->
                Log.w(TAG, "位置订阅注册失败 provider=$provider: " + error.javaClass.simpleName)
                false
            }
            if (ok) listeners[provider] = listener
        }
        if (listeners.isEmpty()) {
            consumer = null
            Log.w(TAG, "位置订阅建立失败：providers=" + providers.joinToString() + " 均未注册成功")
            return false
        }
        synchronized(lock) { registered = listeners }
        Log.d(
            TAG,
            "位置订阅已建立 providers=" + listeners.keys.joinToString() + " 间隔=" + (intervalMs / 1000) +
                "s minDistance=" + minDistanceMeters + "m",
        )
        return true
    }

    override fun stop() {
        val toRemove = synchronized(lock) {
            val current = registered
            registered = emptyMap()
            current
        }
        consumer = null
        if (toRemove.isEmpty()) return
        val lm = manager ?: return
        // 权限可能在订阅期间被撤：removeUpdates 也可能抛 SecurityException，逐个兜底
        toRemove.forEach { (provider, listener) ->
            runCatching { lm.removeUpdates(listener) }
                .onFailure { Log.w(TAG, "位置订阅释放失败 provider=$provider: " + it.javaClass.simpleName) }
        }
        Log.d(TAG, "位置订阅已释放 providers=" + toRemove.keys.joinToString())
    }

    override fun isActive(): Boolean = synchronized(lock) { registered.isNotEmpty() }

    private companion object {
        const val TAG = "LocationCollector"
    }
}

/** Location -> 纯数据采样（订阅与主动定位共用）。 */
internal fun sampleOf(provider: String, location: Location): LocationSample = LocationSample(
    provider = provider,
    latitude = location.latitude,
    longitude = location.longitude,
    accuracyMeters = location.accuracy,
    timeMs = location.time,
)
