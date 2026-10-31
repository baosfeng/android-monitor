package com.bsfeng.monitor.ui.maps

// WGS84 → GCJ-02 → BD-09 的坐标纠偏（纯 Kotlin，零依赖）。
//
// ## 为什么必须有这一层
// App 采集的定位是 WGS84（GPS 原始坐标），但国内地图 App 展示的是 GCJ-02（国测局加密坐标，
// 俗称火星坐标）；百度在 GCJ-02 上再加一次偏移变成 BD-09。把 WGS84 直接塞给高德/百度，
// 落点会偏 300~600 米——「在地图中查看」会变成一个看起来像 bug 的功能。
//
// ## 算法口径
// 用的是国内通行的公开近似算法（GCJ-02 的正向变换是确定性的多项式扰动，逆向没有解析解，
// 但正向足够）。误差量级 ~1 米，远小于 GPS 本身的 10 米级噪声，对「打开地图看大致位置」够用。
//
// ## 境外不偏移
// GCJ-02 只在中国大陆范围内生效。境外点直接原样返回，否则东京 / 纽约的落点会被凭空挪走。
// 判据用通行的经纬度矩形（lng 72.004~137.8347、lat 0.8293~55.8271）。已知局限：该矩形把
// 港澳台也包含在内，因此港澳坐标同样会被偏移——这与高德 / 腾讯地图自身的港澳底图处理一致，
// 不偏移反而会对不上。
//
// ## 精度
// 结果固定保留 6 位小数（≈0.11 米）。不保留原精度是因为扰动项本身只有 ~1 米精度，
// 多留的位数是假精度，还会让单测断言变得脆弱。

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object CoordTransform {

    /** 克拉索夫斯基椭球长半轴（GCJ-02 扰动的标准参数，不要改成 WGS84 的 6378137）。 */
    private const val AXIS = 6378245.0

    /** 椭球偏心率平方。 */
    private const val EE = 0.00669342162296594323

    /** BD-09 变换用的角度偏移常数（百度公开算法里的固定值）。 */
    private const val X_PI = Math.PI * 3000.0 / 180.0

    /** 结果保留的小数位换算：6 位小数 ≈ 0.11 米。 */
    private const val ROUND_SCALE = 1_000_000.0

    /** 中国范围判定的经度上下界（境外不偏移）。 */
    private const val CHINA_LNG_MIN = 72.004
    private const val CHINA_LNG_MAX = 137.8347

    /** 中国范围判定的纬度上下界。 */
    private const val CHINA_LAT_MIN = 0.8293
    private const val CHINA_LAT_MAX = 55.8271

    /**
     * 把 WGS84 点转成目标坐标系。
     *
     * 非法坐标（NaN / 越界）原样返回：调用方已经把「能不能用」判过一次（[GeoPoint.isValid]），
     * 这里不做二次抛错，避免一个脏点把整条轨迹的跳转干掉。
     */
    fun to(point: GeoPoint, system: CoordSystem): GeoPoint = when (system) {
        CoordSystem.WGS84 -> point
        CoordSystem.GCJ02 -> toGcj02(point)
        CoordSystem.BD09 -> toBd09(point)
    }

    /** WGS84 → GCJ-02。境外点原样返回。 */
    fun toGcj02(point: GeoPoint): GeoPoint {
        if (!point.isValid || isOutOfChina(point)) return point
        val dLat = deltaLat(point.lng - 105.0, point.lat - 35.0)
        val dLng = deltaLng(point.lng - 105.0, point.lat - 35.0)
        val radLat = point.lat / 180.0 * Math.PI
        val magic = 1 - EE * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)
        val latDelta = (dLat * 180.0) / ((AXIS * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        val lngDelta = (dLng * 180.0) / (AXIS / sqrtMagic * cos(radLat) * Math.PI)
        return GeoPoint(round(point.lat + latDelta), round(point.lng + lngDelta))
    }

    /** WGS84 → BD-09（先到 GCJ-02，再做百度的二次偏移）。境外点原样返回。 */
    fun toBd09(point: GeoPoint): GeoPoint {
        if (!point.isValid || isOutOfChina(point)) return point
        val gcj = toGcj02(point)
        val z = sqrt(gcj.lng * gcj.lng + gcj.lat * gcj.lat) +
            0.00002 * sin(gcj.lat * X_PI)
        val theta = atan2(gcj.lat, gcj.lng) + 0.000003 * cos(gcj.lng * X_PI)
        return GeoPoint(round(z * sin(theta) + 0.006), round(z * cos(theta) + 0.0065))
    }

    /**
     * 是否在中国大陆范围之外（境外不纠偏）。
     *
     * 边界值本身按「在内」处理：贴边的点偏移量已经趋近于 0，偏与不偏对落点没有可观测差别。
     */
    fun isOutOfChina(point: GeoPoint): Boolean =
        point.lng < CHINA_LNG_MIN || point.lng > CHINA_LNG_MAX ||
            point.lat < CHINA_LAT_MIN || point.lat > CHINA_LAT_MAX

    /** GCJ-02 的纬度扰动量（输入是相对 (105,35) 的偏移量）。 */
    private fun deltaLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    /** GCJ-02 的经度扰动量（输入是相对 (105,35) 的偏移量）。 */
    private fun deltaLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    /** 保留 6 位小数；-0.0 归一成 0.0，避免 URI 里出现「-0.000000」。 */
    private fun round(value: Double): Double {
        val scaled = Math.round(value * ROUND_SCALE) / ROUND_SCALE
        return if (scaled == 0.0) 0.0 else scaled
    }
}
