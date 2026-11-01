package com.bsfeng.monitor.record

// 逆地理编码：把量化后的经纬度换成中文地址，只用于 LOCATION 记录的 label。
// 纯逻辑（缓存 / 限频 / 超时 / 失败计数）与 Android 数据源分离，前者可直接在 JVM 单测里跑。

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 地址查询数据源：同步阻塞，调用方保证在后台线程执行。生产实现见 [AndroidGeocodeSource] / [NominatimGeocodeSource]。 */
interface GeocodeSource {
    /** @return 地址文本；null = 无结果 / 失败 / 超时 */
    fun lookup(latitude: Double, longitude: Double, timeoutMs: Long): String?
}

/**
 * 地址 LRU 缓存（容量 [capacity] 条，按访问顺序淘汰）。
 *
 * 键 = 量化经纬度（3 位小数 ≈110m，与落库坐标同精度）：同一小区 / 同一栋楼只查一次，
 * 因此心跳每 10 分钟一次时，绝大多数记录都能瞬时取到地址、零网络请求。
 */
class AddressLruCache(private val capacity: Int = DEFAULT_CAPACITY) {

    private val map = object : LinkedHashMap<String, String>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun get(latitude: Double, longitude: Double): String? = map[key(latitude, longitude)]

    @Synchronized
    fun put(latitude: Double, longitude: Double, address: String) {
        map[key(latitude, longitude)] = address
    }

    @Synchronized
    fun size(): Int = map.size

    companion object {
        /** 64 条 ≈ 10 分钟一条 × 十小时的量级，够覆盖一天的活动范围。 */
        const val DEFAULT_CAPACITY = 64

        fun key(latitude: Double, longitude: Double): String =
            LocationPolicy.quantizeCoarse(latitude) + "," + LocationPolicy.quantizeCoarse(longitude)
    }
}

/**
 * 逆地理编码编排：缓存 → 限频 → 有界查询（[TIMEOUT_MS]）→ 失败静默。
 *
 * 线程口径：**同步、可在任意后台线程调用**；不回调、不阻塞主线程。
 * 失败口径：无网 / 超时 / 无结果 / 被限频一律返回 null 并累加诊断计数，调用方回落经纬度文案，
 * 坐标记录照常落库 —— 地址是锦上添花，不能拖垮定位采集。
 */
class LocationReverseGeocoder(
    private val source: GeocodeSource,
    private val cache: AddressLruCache = AddressLruCache(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val minIntervalMs: Long = MIN_REQUEST_INTERVAL_MS,
) {

    /** 上次真正发起查询的时刻（缓存命中不计）；0 = 从未查询。 */
    @Volatile
    private var lastRequestAt = 0L

    /** @return 地址文本（≤ [MAX_ADDRESS_LENGTH] 字）；null = 失败 / 限频 / 无结果 */
    fun resolve(latitude: Double, longitude: Double): String? {
        cache.get(latitude, longitude)?.let {
            cacheHitCount++
            return it
        }
        val now = clock()
        synchronized(this) {
            if (lastRequestAt != 0L && now - lastRequestAt < minIntervalMs) {
                throttledCount++
                return null
            }
            lastRequestAt = now
        }
        val raw = try {
            source.lookup(latitude, longitude, TIMEOUT_MS)
        } catch (e: Exception) {
            recordFailure(e.javaClass.simpleName)
            return null
        }
        val address = raw?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() }
        if (address == null) {
            recordFailure("empty")
            return null
        }
        val trimmed = if (address.length <= MAX_ADDRESS_LENGTH) address else address.take(MAX_ADDRESS_LENGTH)
        cache.put(latitude, longitude, trimmed)
        successCount++
        return trimmed
    }

    private fun recordFailure(reason: String) {
        failureCount++
        lastFailure = reason
    }

    companion object {
        /** 单次查询硬超时：3 秒（≤5 秒口径）。主动定位本身最多 5 秒，两者相加仍在广播预算内。 */
        const val TIMEOUT_MS = 3_000L

        /** 地址文本上限：label 里一行展示得下，也避免把「省市区+道路+门牌+POI」全存下来。 */
        const val MAX_ADDRESS_LENGTH = 40

        /** 两次真正查询的最小间隔：Nominatim 使用政策要求 ≤1 次/秒，这里再保守一些。 */
        const val MIN_REQUEST_INTERVAL_MS = 2_000L

        private val WHITESPACE = Regex("""\s+""")

        private const val TAG = "LocationReverseGeocoder"

        // 诊断计数：无 adb 时也能从设置页 / 日志判断「地址为什么没显示」
        @Volatile
        var successCount: Int = 0
        @Volatile
        var failureCount: Int = 0
        @Volatile
        var cacheHitCount: Int = 0
        @Volatile
        var throttledCount: Int = 0
        @Volatile
        var lastFailure: String = ""

        fun diagnostics(): String =
            "地址解析 成功 ${successCount} / 失败 ${failureCount} / 缓存命中 ${cacheHitCount} / 限频跳过 ${throttledCount}" +
                if (lastFailure.isBlank()) "" else "（最近失败：${lastFailure}）"

        @Volatile
        private var instance: LocationReverseGeocoder? = null

        /** 进程单例：缓存必须跨心跳保留，否则每 10 分钟都要重查一次。 */
        fun get(context: Context): LocationReverseGeocoder = instance ?: synchronized(this) {
            instance ?: LocationReverseGeocoder(
                ChainedGeocodeSource(
                    listOf(
                        AndroidGeocodeSource(context.applicationContext),
                        NominatimGeocodeSource(),
                    )
                )
            ).also { instance = it }
        }
    }
}

/**
 * 多来源串联：系统 Geocoder 优先（本地、免流量），失败再走免 key 的在线服务。
 *
 * 每个来源分到 [timeoutMs] 的 1/n，总耗时不超过 [timeoutMs]。
 */
internal class ChainedGeocodeSource(private val sources: List<GeocodeSource>) : GeocodeSource {

    override fun lookup(latitude: Double, longitude: Double, timeoutMs: Long): String? {
        if (sources.isEmpty()) return null
        val perSource = (timeoutMs / sources.size).coerceAtLeast(MIN_PER_SOURCE_MS)
        for (source in sources) {
            val address = try {
                source.lookup(latitude, longitude, perSource)
            } catch (e: Exception) {
                Log.w("LocationReverseGeocoder", "地理编码来源失败：${e.javaClass.simpleName}")
                null
            }
            if (!address.isNullOrBlank()) return address
        }
        return null
    }

    companion object {
        /** 单来源至少给 500ms，太短等于必然超时。 */
        const val MIN_PER_SOURCE_MS = 500L
    }
}

/**
 * 系统 Geocoder（离线 / 本地服务）。
 *
 * API 33+ 走异步回调 + 闩锁超时；更低版本走阻塞 API（本来就在后台线程）。
 * 无 Geocoder / 未装地图服务时直接返回 null，交给在线回落。
 */
internal class AndroidGeocodeSource(private val context: Context) : GeocodeSource {

    override fun lookup(latitude: Double, longitude: Double, timeoutMs: Long): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.CHINA)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val latch = CountDownLatch(1)
            var result: String? = null
            geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    result = format(addresses.firstOrNull())
                    latch.countDown()
                }

                override fun onError(errorMessage: String?) {
                    latch.countDown()
                }
            })
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result else null
        } else {
            @Suppress("DEPRECATION")
            val addresses = try {
                geocoder.getFromLocation(latitude, longitude, 1)
            } catch (e: Exception) {
                return null
            }
            format(addresses?.firstOrNull())
        }
    }

    private fun format(address: Address?): String? {
        val line = address?.getAddressLine(0)?.trim()
        if (!line.isNullOrEmpty()) return line
        val parts = listOf(
            address?.adminArea, address?.locality, address?.subLocality,
            address?.thoroughfare, address?.subThoroughfare,
        ).mapNotNull { it?.trim()?.takeIf { part -> part.isNotEmpty() } }
        return if (parts.isEmpty()) null else parts.joinToString("")
    }
}

/**
 * 在线回落：OpenStreetMap Nominatim（免 key）。
 *
 * 隐私口径：只发**量化后**的坐标（3 位小数 ≈110m），不带任何设备标识；
 * 遵守使用政策 —— 自定义 User-Agent、超时、限频（见 [LocationReverseGeocoder.MIN_REQUEST_INTERVAL_MS]）。
 */
internal class NominatimGeocodeSource(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : GeocodeSource {

    override fun lookup(latitude: Double, longitude: Double, timeoutMs: Long): String? {
        val urlText = buildString {
            append(endpoint)
            // 隐私口径不变：发给第三方的仍是 3 位小数（≈110m）的粗坐标
            append("?format=jsonv2&zoom=16&accept-language=zh-CN&lat=")
            append(LocationPolicy.quantizeCoarse(latitude))
            append("&lon=")
            append(LocationPolicy.quantizeCoarse(longitude))
        }
        val connection = try {
            (URL(urlText).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: Exception) {
            Log.w("LocationReverseGeocoder", "地理编码请求构造失败：${e.javaClass.simpleName}")
            return null
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("display_name").trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w("LocationReverseGeocoder", "地理编码网络失败：${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://nominatim.openstreetmap.org/reverse"

        /** 使用政策要求可识别的 UA；本项目只做本地展示，不带任何用户标识。 */
        const val DEFAULT_USER_AGENT = "android-monitor/1.0 (local-only reverse geocode)"
    }
}
