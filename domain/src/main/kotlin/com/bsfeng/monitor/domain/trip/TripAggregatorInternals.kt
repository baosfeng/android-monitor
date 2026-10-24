package com.bsfeng.monitor.domain.trip

// 从 domain/trip/TripAggregator.kt 拆出：聚合过程中的内部数据结构
// （定位点 / 地名投票 / 同址段 / 地点登记表 / SSID 段 / 使用簇 / 候选停留区间）。
// 只服务 TripAggregator，不外泄；常量在 TripAggregatorConstants.kt。

import java.util.Locale

// ────────────────────────────── 内部数据结构（TripAggregator 专用）──────────────────────────────

internal class LocationPoint(
    val lat: Double,
    val lng: Double,
    val ts: Long,
    val label: String? = null,
    /** 采集精度（米）；缺失为 null。地名平票时用它挑更可信的那个。 */
    val accuracy: Double? = null,
) {
    fun cell(): Long {
        // 0.001° ≈ 111m（纬度）/ 100m（赤道经度）：只用来判「同格」，距离仍由 haversine 复核
        val latQ = Math.round(lat * 1000.0)
        val lngQ = Math.round(lng * 1000.0)
        return latQ * 1_000_000L + lngQ
    }
}

/**
 * 聚类内地名投票。
 *
 * 为什么不是「取第一个采样」：移动段的末点（ratio=1）会落在目标地点 300m 内被并进停留簇，
 * 而它的地址是「移动中」这类瞬态占位——按首个采样取，真地名就被占位顶掉了。
 * 规则：先剔除瞬态占位与空白，再取出现次数最多者；平票时取 accuracy 更小（更准）的点。
 */
internal class LabelTally {
    private val counts = LinkedHashMap<String, Int>()
    private val bestAccuracy = HashMap<String, Double>()

    fun add(label: String?, accuracy: Double?) {
        val name = label?.trim().orEmpty()
        if (name.isEmpty() || name in TripAggregatorRules.TRANSIENT_PLACE_LABELS) return
        counts[name] = (counts[name] ?: 0) + 1
        if (accuracy != null && accuracy > 0.0) {
            val prev = bestAccuracy[name]
            if (prev == null || accuracy < prev) bestAccuracy[name] = accuracy
        }
    }

    fun best(): String? = counts.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { bestAccuracy[it.key] ?: Double.MAX_VALUE },
        )
        .firstOrNull()
        ?.key
}

internal class Run(first: LocationPoint) {
    val samples = ArrayList<LocationPoint>()
    var start: Long = first.ts
        private set
    var end: Long = first.ts
        private set
    var lastTs: Long = first.ts
        private set
    var cell: Long = first.cell()
        private set
    private var sumLat: Double = first.lat
    private var sumLng: Double = first.lng
    private var count: Int = 1
    private var maxDeviation: Double = 0.0
    private val labels = LabelTally()

    init {
        samples.add(first)
        labels.add(first.label, first.accuracy)
    }

    val centroidLat: Double get() = sumLat / count
    val centroidLng: Double get() = sumLng / count

    fun add(point: LocationPoint) {
        samples.add(point)
        sumLat += point.lat
        sumLng += point.lng
        count++
        end = point.ts
        lastTs = point.ts
        cell = point.cell()
        labels.add(point.label, point.accuracy)
        val deviation = haversineMeters(centroidLat, centroidLng, point.lat, point.lng)
        if (deviation > maxDeviation) maxDeviation = deviation
    }

    fun isStay(): Boolean = end - start >= TripAggregatorRules.STAY_MIN_MS && maxDeviation <= TripAggregatorRules.STAY_RADIUS_M

    /** 聚类内的地名：出现次数最多者；平票取精度更好的那个。 */
    fun labelOrNull(): String? = labels.best()
}

/**
 * 地点登记表：同一份时间轴内**同一地点只发一个 [Place]**（id 稳定，不随段序号变化）。
 *
 * - GPS：`geo:<lat3>,<lng3>`（0.001° ≈ 111m，与聚类网格同尺度）；
 *   另外按 80m 半径做一次归并，避免两次访问恰好落在量化格两侧被当成两个地点；
 * - DEVICE：`ssid:<网络名>`；使用簇退化地点统一为 `device:activity`。
 *
 * 地点名取**首次出现的有效名字**；首次没名字、后续段有名字时回填，不会一直显示通用占位。
 */
internal class PlaceRegistry(private val fallbackLabel: String) {
    private val places = ArrayList<Place>()

    fun geo(lat: Double, lng: Double, label: String?): Place {
        val existing = places.firstOrNull { place ->
            val pLat = place.lat
            val pLng = place.lng
            pLat != null && pLng != null && haversineMeters(pLat, pLng, lat, lng) <= TripAggregatorRules.STAY_RADIUS_M
        }
        if (existing != null) {
            if (existing.label == fallbackLabel && label != null) {
                val renamed = existing.copy(label = label)
                places[places.indexOf(existing)] = renamed
                return renamed
            }
            return existing
        }
        val place = Place(
            id = TripAggregatorRules.GEO_ID_PREFIX + String.format(Locale.US, "%.3f,%.3f", lat, lng),
            label = label ?: fallbackLabel,
            lat = lat,
            lng = lng,
            source = Basis.GPS,
        )
        places.add(place)
        return place
    }

    fun device(id: String, label: String): Place {
        places.firstOrNull { it.id == id }?.let { return it }
        val place = Place(id = id, label = label, source = Basis.DEVICE)
        places.add(place)
        return place
    }
}

internal data class WifiSample(val ssid: String, val ts: Long, val disconnect: Boolean)

internal class SsidRun(val start: Long, val end: Long, val ssid: String)

internal class UsageCluster(var start: Long, var end: Long)

internal class StayRange(
    var start: Long,
    var end: Long,
    var label: String,
    val source: Basis,
)
