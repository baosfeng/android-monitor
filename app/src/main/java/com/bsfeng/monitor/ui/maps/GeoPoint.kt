package com.bsfeng.monitor.ui.maps

// 地图跳转的纯数据与坐标系声明。
//
// 这里刻意不碰 Android API：坐标系的换算与 URI 拼装都能在 JVM 单测里直接验证（见 CoordTransformTest）。
// 「采集是 WGS84、高德/腾讯要 GCJ-02、百度要 BD-09」这条口径只在 CoordSystem 上表达一次，
// MapTarget 与 MapUri 都从这里取，避免各调用点各写一份坐标假设。

/** 一个经纬度点。构造不抛异常，非法值用 [isValid] 表达，调用方自行降级。 */
data class GeoPoint(val lat: Double, val lng: Double) {

    /** 是否落在合法经纬度范围内；NaN 也判非法（Float/Double 比较对 NaN 恒 false，需单独判）。 */
    val isValid: Boolean
        get() = !lat.isNaN() && !lng.isNaN() &&
            lat >= -90.0 && lat <= 90.0 &&
            lng >= -180.0 && lng <= 180.0
}

/** 坐标系。App 采集侧是 WGS84，国内地图 App 默认吃 GCJ-02，百度吃 BD-09。 */
enum class CoordSystem {
    /** 原始 GPS 坐标，境外地图（Google / OpenStreetMap）与浏览器网页地图用这个。 */
    WGS84,

    /** 国测局加密坐标（GCJ-02，俗称火星坐标）。高德 / 腾讯用这个。 */
    GCJ02,

    /** 百度在 GCJ-02 上再偏一次的坐标（BD-09）。只有百度地图用。 */
    BD09,
}
