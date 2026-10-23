package com.bsfeng.monitor.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executor

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

/**
 * COORDINATE 落库节流（纯逻辑）。
 *
 * 用「先看能不能采、采到了才记号」的两步式：一次采集失败（provider 全不可用 / 超时）
 * 不占用 10 分钟窗口，否则一次失败会白白浪费两个心跳周期。
 */
class LocationThrottle(private val minIntervalMs: Long = LocationPolicy.MIN_INTERVAL_MS) {

    private var lastSampleMs: Long? = null

    fun canAcquire(nowMs: Long): Boolean {
        val last = lastSampleMs ?: return true
        return nowMs - last >= minIntervalMs
    }

    fun markAcquired(nowMs: Long) {
        lastSampleMs = nowMs
    }

    fun reset() {
        lastSampleMs = null
    }
}

/**
 * 单次主动定位请求的抽象。
 *
 * 抽成接口只为让「缓存为空 → 发起请求 / 超时 → 放弃」这两条路径能在纯 JVM 单测里跑，
 * 生产实现是 [AndroidSingleFixSource]。
 *
 * 契约：`request` 必须**恰好回调一次** `onResult`（成功、失败、超时各一条路径），
 * 并在回调前释放底层请求（`CancellationSignal.cancel()` / `removeUpdates()`）。
 */
interface SingleFixSource {
    fun request(provider: String, timeoutMs: Long, onResult: (LocationSample?) -> Unit)
}

/**
 * 缓存为空时的主动定位编排（纯逻辑）：按 [providers] 顺序逐个要一个点，
 * 每段独立超时（总预算 = 各段之和），全部落空返回 null。
 */
class ActiveFixResolver(
    private val source: SingleFixSource,
    val providers: List<String> = LocationPolicy.ACTIVE_PROVIDERS,
    val timeoutPerProviderMs: Long = LocationPolicy.ACTIVE_FIX_BUDGET_MS / LocationPolicy.ACTIVE_PROVIDERS.size,
    /** 日志出口：纯 JVM 单测里 android.util.Log 未 mock，只能由生产侧注入。 */
    private val log: (String, Boolean) -> Unit = { _, _ -> },
) {
    fun resolve(
        nowMs: Long,
        isAvailable: (String) -> Boolean,
        onResult: (LocationSample?) -> Unit,
    ) {
        val queue = providers.filter(isAvailable)
        if (queue.isEmpty()) {
            log("主动定位跳过：没有可用的 provider（${providers.joinToString()}）", true)
            onResult(null)
            return
        }
        var index = 0
        fun next() {
            if (index >= queue.size) {
                log("主动定位放弃：${queue.joinToString()} 均未拿到可用点", true)
                onResult(null)
                return
            }
            val provider = queue[index++]
            source.request(provider, timeoutPerProviderMs) { sample ->
                if (sample != null && LocationPolicy.isUsable(sample, nowMs)) {
                    log("主动定位成功 provider=$provider accuracy=${sample.accuracyMeters}", false)
                    onResult(sample)
                } else {
                    next()
                }
            }
        }
        next()
    }
}

/**
 * 定位采集器。
 *
 * 两级取点，都不常驻：
 * 1. 先读系统缓存（`getLastKnownLocation`，零功耗）；有可用点直接用；
 * 2. 缓存为空时发起**一次**主动定位（network → gps，总预算 5 秒），拿到首个可用点后立即释放。
 *
 * 由已有的 1 分钟 [com.bsfeng.monitor.service.AlarmReceiver] 心跳驱动，不新开前台服务、
 * 不注册常驻 Listener。
 *
 * 异常契约：未授权 / 无 LocationManager / provider 不可用 / 系统抛 SecurityException
 * 一律回调 null，不向调用方抛异常（心跳链路不能被定位拖垮）。
 */
class LocationCollector internal constructor(
    private val env: Env,
    private val throttle: LocationThrottle = LocationThrottle(),
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

    /**
     * 节流采集（异步）：10 分钟内最多交付一次非空结果。
     *
     * @param onResult **恰好回调一次**（缓存命中时同步回调，需要主动定位时异步回调）；
     *   被节流 / 未授权 / 超时 / 过滤后为空都回 null
     */
    fun collectThrottledAsync(
        nowMs: Long = System.currentTimeMillis(),
        onResult: (LocationSample?) -> Unit,
    ) {
        if (!throttle.canAcquire(nowMs)) {
            env.log("定位节流中：${LocationPolicy.MIN_INTERVAL_MS / 60_000} 分钟内已采过", false)
            onResult(null)
            return
        }
        if (!env.isFineGranted()) {
            env.log("定位跳过：未授权", true)
            onResult(null)
            return
        }
        val cached = LocationPolicy.pick(env.readCached(), nowMs)
        if (cached != null) {
            throttle.markAcquired(nowMs)
            env.log("定位命中缓存 provider=${cached.provider} accuracy=${cached.accuracyMeters}", false)
            onResult(cached)
            return
        }
        env.log(
            "定位缓存为空，发起单次主动定位（${LocationPolicy.ACTIVE_PROVIDERS.joinToString()} / " +
                "${LocationPolicy.ACTIVE_FIX_BUDGET_MS}ms）",
            false,
        )
        try {
            env.requestActiveFix(nowMs) { sample ->
                if (sample == null) {
                    onResult(null)
                } else {
                    throttle.markAcquired(nowMs)
                    onResult(sample)
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
        if (!throttle.canAcquire(nowMs)) return null
        val sample = collect(nowMs) ?: return null
        throttle.markAcquired(nowMs)
        return sample
    }

    /** 权限是否至少给了前台定位（后台定位缺失时后台采样会拿到 null，见 [status]）。 */
    fun hasLocationPermission(): Boolean = env.isFineGranted()

    /** 当前定位权限三态，供设置页自检。 */
    fun status(): LocationPermissionStatus =
        resolveStatus(env.isFineGranted(), env.isBackgroundGranted())

    companion object {
        private const val TAG = "LocationCollector"

        @Volatile
        private var instance: LocationCollector? = null

        /**
         * 进程内单例。
         *
         * 节流状态（上次落库时间）必须跨心跳保留：心跳每分钟来一次，每次 new 一个采集器会让
         * 10 分钟节流失效，变成每分钟一条坐标。懒加载 + 双重校验，与仓库其它单例一致。
         */
        fun get(context: Context): LocationCollector = instance ?: synchronized(this) {
            instance ?: LocationCollector(context.applicationContext).also { instance = it }
        }

        /**
         * 权限三态判定（纯函数，设置页与单测共用）。
         *
         * minSdk 29 起，Android 10+ 在后台读位置必须拿到 `ACCESS_BACKGROUND_LOCATION`，
         * 只有前台授权时后台取不到点 —— 这正是「已开定位但没有记录」的常见原因。
         */
        fun resolveStatus(fineGranted: Boolean, backgroundGranted: Boolean): LocationPermissionStatus = when {
            !fineGranted -> LocationPermissionStatus.NOT_GRANTED
            !backgroundGranted -> LocationPermissionStatus.MISSING_BACKGROUND
            else -> LocationPermissionStatus.GRANTED
        }
    }
}

/** 生产环境依赖：系统 LocationManager + 单次主动定位。 */
private class AndroidEnv(private val context: Context) : LocationCollector.Env {

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

    override fun log(message: String, warn: Boolean) {
        if (warn) Log.w(TAG, message) else Log.d(TAG, message)
    }

    override fun isFineGranted(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

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

private fun sampleOf(provider: String, location: Location): LocationSample = LocationSample(
    provider = provider,
    latitude = location.latitude,
    longitude = location.longitude,
    accuracyMeters = location.accuracy,
    timeMs = location.time,
)
