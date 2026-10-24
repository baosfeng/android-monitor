package com.bsfeng.monitor.domain.trip

// 从 domain/trip/TripAggregator.kt 拆出：聚合阈值常量与二分查找辅助。
// 提成文件级 internal 是为了让 TripAggregator 与 TripAggregatorInternals 共用同一份常量定义。

/** 行程聚合的阈值常量与二分辅助（TripAggregator 与其内部结构的共用定义）。 */
internal object TripAggregatorRules {
    const val NAME_LOCATION = "LOCATION"
    const val NAME_PAGE = "PAGE"
    const val NAME_APP_USAGE = "APP_USAGE"
    const val NAME_WIFI = "WIFI"

    /** 少于 3 个定位点无法支撑「连续 10 分钟」，直接退化到 DEVICE 基准。 */
    const val MIN_POINTS_FOR_GPS = 3

    const val STAY_MIN_MS = 10 * 60_000L
    const val STAY_RADIUS_M = 80.0
    const val MOVE_MIN_METERS = 300.0
    const val MOVE_MIN_COVERAGE = 0.6
    const val MAX_SAMPLE_GAP_MS = 60 * 60_000L
    const val USAGE_MERGE_GAP_MS = 5 * 60_000L

    const val UNKNOWN_APP = "未知应用"
    const val USAGE_PLACE_LABEL = "使用中"
    const val GEO_ID_PREFIX = "geo:"
    const val SSID_ID_PREFIX = "ssid:"
    const val DEVICE_ACTIVITY_ID = "device:activity"
    const val GENERIC_PLACE_LABEL = "地点"

    /** 移动段/过渡态的地址占位：不参与地名投票，避免把停留簇的地名顶成「移动中」。 */
    val TRANSIENT_PLACE_LABELS = setOf("移动中", "路上", "行驶中", "骑行中")

    fun lowerBoundByStart(list: List<UsageSlice>, start: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].start < start) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun lowerBoundByTs(list: List<PageSample>, ts: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].ts < ts) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
