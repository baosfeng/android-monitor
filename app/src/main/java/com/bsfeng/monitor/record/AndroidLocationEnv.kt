package com.bsfeng.monitor.record

// 从 record/LocationCollector.kt 拆出：生产环境依赖（系统 LocationManager + 单次主动定位）。
// 只在真机/集成路径里用；纯 JVM 单测注入假 Env，不碰这里。

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/** 生产环境依赖：系统 LocationManager + 单次主动定位。 */
internal class AndroidEnv(private val context: Context) : LocationCollector.Env {

    private val manager: LocationManager? by lazy {
        runCatching { context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }.getOrNull()
    }

    private val activeFix: ActiveFixResolver? by lazy {
        manager?.let {
            ActiveFixResolver(
                source = AndroidSingleFixSource(it, context, context.mainExecutor),
                log = ::log,
            )
        }
    }

    /** 持续订阅通道（[LocationCollector] 建立 / 释放，本类只负责持有实例）。 */
    private val subscriber: LocationSubscriptionSource by lazy { AndroidLocationSubscriber(context) }

    override fun subscriptionSource(): LocationSubscriptionSource? =
        if (manager == null) null else subscriber

    /**
     * 当前可用的 provider 列表（fused / gps / network）。
     *
     * isProviderEnabled 是受限调用，必须显式判权限（lint [MissingPermission] 只认方法内分支）；
     * 逐个 runCatching：个别 ROM 上对未启用的 provider 会抛 IllegalArgumentException。
     */
    override fun availableProviders(): List<String> {
        val lm = manager ?: return emptyList()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return SUBSCRIPTION_CANDIDATES.filter { provider ->
            runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    /**
     * 系统省电模式：开了就把静止的探测间隔从 30s 退回 180s（续航优先，模式判定变钝）。
     *
     * 读不到 BatteryManager（ROM 裁剪 / 抛异常）一律当「非省电」，不影响任何控制流：
     * 这只是省电降级的钩子，不是功能前提。
     */
    override fun isPowerSaveMode(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        pm.isPowerSaveMode
    }.getOrDefault(false)

    override fun log(message: String, warn: Boolean) {
        if (warn) Log.w(TAG, message) else Log.d(TAG, message)
    }

    /**
     * 只看「精确位置」本身。
     *
     * 旧实现写成 `FINE || COARSE`，于是 Android 12+ 只给「大致位置」时也被当成精确位置已授权，
     * 设置页显示绿标「已就绪」而行程页永远空白（系统只派 network 点，全被精度闸丢掉）。
     * 现在 COARSE 只算 [isCoarseGranted]，由 [LocationPermissionRules] 判成 COARSE_ONLY。
     */
    override fun isFineGranted(): Boolean = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun isCoarseGranted(): Boolean = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun isBackgroundGranted(): Boolean =
        hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    override fun readCached(): List<LocationSample> {
        val lm = manager ?: return emptyList()
        return LocationPolicy.PROVIDER_PRIORITY.mapNotNull { provider -> readLastKnown(lm, provider) }
    }

    override fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit) {
        val lm = manager
        val resolver = activeFix
        if (lm == null || resolver == null) {
            Log.w(TAG, "主动定位跳过：LocationManager 不可用")
            onResult(null)
            return
        }
        // lint MissingPermission：LocationManager 的每个调用点都要显式判权限（try/catch 不算处理）
        if (!hasLocationPermissionNow()) {
            Log.w(TAG, "主动定位跳过：未授权")
            onResult(null)
            return
        }
        // isProviderEnabled 也要求定位权限：在方法体内先算好，避免在 lambda 里做受限调用
        val availability = LocationPolicy.ACTIVE_PROVIDERS.associateWith { provider ->
            runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
        }
        resolver.resolve(
            nowMs = nowMs,
            isAvailable = { provider -> availability[provider] == true },
            onResult = onResult,
        )
    }

    /**
     * 调用受限 API 前的**显式**权限判断。
     *
     * 注意：这里刻意不包 `runCatching` —— lint 的 [MissingPermission] 只认方法内直接的
     * `checkSelfPermission(...) != PERMISSION_GRANTED` 分支，跨方法封装与 try/catch 都不算处理。
     * 运行时兜底仍由各调用点的 `runCatching` 负责（权限在检查后瞬间被撤的情况）。
     */
    private fun hasLocationPermissionNow(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasPermission(permission: String): Boolean = runCatching {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun readLastKnown(manager: LocationManager, provider: String): LocationSample? {
        // lint [MissingPermission] 只认「方法内直接的 checkPermission 分支」：抽成 helper 方法
        // （hasLocationPermissionNow）它会识别不到，所以这里内联写一遍，不图省事。
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return runCatching {
            if (!manager.isProviderEnabled(provider)) return@runCatching null
            val location = manager.getLastKnownLocation(provider) ?: return@runCatching null
            sampleOf(provider, location)
        }.onFailure { error ->
            // 第二层兜底：SecurityException（权限在检查后被撤）、IllegalArgumentException（provider 不存在）
            Log.w(TAG, "读取缓存位置失败 provider=$provider: ${error.javaClass.simpleName}")
        }.getOrNull()
    }

    private companion object {
        const val TAG = "LocationCollector"

        /** 订阅候选 provider：fused 优先，不可用时退 gps + network（顺序见 LocationSubscriptionPlan）。 */
        val SUBSCRIPTION_CANDIDATES = listOf(
            LocationSubscriptionPlan.FUSED_PROVIDER,
            "gps",
            "network",
        )
    }
}

/**
 * 生产实现的单次主动定位。
 *
 * - API 30+：`getCurrentLocation(provider, CancellationSignal, executor, consumer)`；
 * - minSdk 29：`requestLocationUpdates(0L, 0f, listener)` 拿首点后立刻 `removeUpdates`。
 *
 * 超时到点主动取消底层请求（不留下常驻监听）；三条出口（成功 / 超时 / 异常）共用同一个
 * `finish` 守卫，保证 `onResult` 恰好一次。
 */
private class AndroidSingleFixSource(
    private val manager: LocationManager,
    private val context: Context,
    private val mainExecutor: Executor,
) : SingleFixSource {

    override fun request(provider: String, timeoutMs: Long, onResult: (LocationSample?) -> Unit) {
        // lint MissingPermission：getCurrentLocation / requestLocationUpdates / removeUpdates
        // 都要求调用点显式判权限（try/catch 不算处理），这里先闸一道
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "主动定位跳过：未授权 provider=$provider")
            onResult(null)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var release: (() -> Unit)? = null

        fun finish(sample: LocationSample?, failureReason: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            // 每条出口都释放底层请求：超时也不能把监听留到下一个心跳
            runCatching { release?.invoke() }
            if (failureReason != null) {
                Log.w(TAG, "主动定位失败 provider=$provider 原因=$failureReason")
            }
            onResult(sample)
        }

        handler.postDelayed({ finish(null, "timeout(${timeoutMs}ms)") }, timeoutMs)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                release = { signal.cancel() }
                manager.getCurrentLocation(provider, signal, mainExecutor) { location ->
                    handler.post {
                        if (location == null) {
                            finish(null, "getCurrentLocation 返回 null")
                        } else {
                            finish(sampleOf(provider, location), null)
                        }
                    }
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        handler.post { finish(sampleOf(provider, location), null) }
                    }
                }
                release = { runCatching { manager.removeUpdates(listener) } }
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            finish(null, "SecurityException（权限不足）")
        } catch (e: IllegalArgumentException) {
            finish(null, "IllegalArgumentException（provider 不存在）")
        } catch (e: Exception) {
            finish(null, e.javaClass.simpleName)
        }
    }

    private companion object {
        const val TAG = "LocationCollector"
    }
}

