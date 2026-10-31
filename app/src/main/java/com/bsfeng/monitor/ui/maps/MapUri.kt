package com.bsfeng.monitor.ui.maps

// 各家地图 App 的 URI 拼装（纯字符串函数，不碰 Android API）。
//
// ## 为什么是 URI 而不是 SDK
// 高德 / 腾讯 / 百度 / Google 都注册了自家的 scheme，用系统 Intent 直接唤起即可：零 SDK、零 key、
// 零费用、零条款。代价是只能做「打开某个点」，做不了内嵌地图控件——这正是本功能要的粒度。
//
// ## 每家吃的坐标系不同（差一个就偏 300~600 米）
// | 目标 | scheme | 坐标系 |
// |---|---|---|
// | 高德 | androidamap:// | GCJ-02 |
// | 腾讯 | qqmap:// | GCJ-02 |
// | 百度 | baidumap:// | BD-09（注意不是 GCJ-02） |
// | Google | geo: | WGS84 |
// | 浏览器 | https://www.openstreetmap.org | WGS84 |
//
// 浏览器兜底刻意选 OpenStreetMap 而不是高德 / 百度网页版：OSM 原生就是 WGS84（不用纠偏）、
// 免 key、无服务条款约束，且不需要登录。缺点是国内加载略慢，但兜底场景够用。

import com.bsfeng.monitor.BuildConfig
import java.net.URLEncoder

/** 一个「可打开的候选」：目标地图 + 已经算好坐标系的 URI。 */
data class MapCandidate(val target: MapTarget, val uri: String)

object MapUri {

    /** 高德 URI 要求的来源标识（它用它做统计，缺失时部分机型会拒绝打开）。 */
    private const val AMAP_SOURCE = "androidamap"

    /** 腾讯 URI 要求的来源标识。 */
    private const val TENCENT_REFERER = "bsfengAndroidMonitor"

    /** 百度 URI 要求的来源标识。 */
    private const val BAIDU_SRC = "android.app"

    /** 浏览器兜底的缩放级别（OSM 的 #map=z/lat/lng）。 */
    private const val OSM_ZOOM = 16

    /**
     * 为 [target] 拼出打开 [point] 的 URI。
     *
     * @param label 地点名，可空；空时不拼标题参数（不塞空串，避免地图 App 显示一个空标题气泡）
     * @param appPackage 本应用包名，供高德之类的来源标识用
     * @return URI 字符串；坐标非法时返回 null（调用方据此跳过该候选）
     */
    fun uriFor(
        target: MapTarget,
        point: GeoPoint,
        label: String?,
        appPackage: String = BuildConfig.APPLICATION_ID,
    ): String? {
        if (!point.isValid) return null
        // 先按目标坐标系纠偏，再拼 URI：URI 里的数值必须已经是地图 App 期望的坐标系
        val p = CoordTransform.to(point, target.coordSystem)
        val name = label?.trim()?.takeIf { it.isNotEmpty() }
        return when (target) {
            MapTarget.AMAP -> amap(p, name, appPackage)
            MapTarget.TENCENT -> tencent(p, name)
            MapTarget.BAIDU -> baidu(p, name)
            MapTarget.GOOGLE -> google(p, name)
            MapTarget.BROWSER -> openStreetMap(p)
        }
    }

    // ── 各家 URI ──

    /** androidamap://viewMap?sourceApplication=..&poiname=..&lat=..&lon=..&dev=0 */
    private fun amap(p: GeoPoint, name: String?, appPackage: String): String = buildString {
        append("androidamap://viewMap?sourceApplication=").append(encode(appPackage))
        append("&poiname=").append(encode(name ?: ""))
        append("&lat=").append(formatLat(p.lat))
        append("&lon=").append(formatLng(p.lng))
        // dev=0 表示「不是开发者调试」；部分高德版本把它当必填
        append("&dev=0")
    }

    /** qqmap://map/marker?marker=coord:<lat>,<lng>;title:<name>;addr:<name>&referer=.. */
    private fun tencent(p: GeoPoint, name: String?): String {
        val marker = buildString {
            append("coord:").append(formatLat(p.lat)).append(",").append(formatLng(p.lng))
            name?.let { append(";title:").append(it).append(";addr:").append(it) }
        }
        return "qqmap://map/marker?marker=" + encode(marker) + "&referer=" + encode(TENCENT_REFERER)
    }

    /** baidumap://map/marker?location=<lat>,<lng>&title=..&coord_type=bd09ll&src=.. */
    private fun baidu(p: GeoPoint, name: String?): String = buildString {
        append("baidumap://map/marker?location=")
        append(formatLat(p.lat)).append(",").append(formatLng(p.lng))
        name?.let { append("&title=").append(encode(it)) }
        // 显式声明坐标系：不写时百度按 gcj02 解释，落点会偏
        append("&coord_type=bd09ll")
        append("&src=").append(encode(BAIDU_SRC))
    }

    /** geo:<lat>,<lng>?q=<lat>,<lng>(<name>)；name 为空时退化成纯坐标 */
    private fun google(p: GeoPoint, name: String?): String {
        val coord = formatLat(p.lat) + "," + formatLng(p.lng)
        return if (name == null) "geo:$coord?q=$coord" else "geo:$coord?q=" + coord + "(" + encode(name) + ")"
    }

    /** OSM 网页地图：https://www.openstreetmap.org/?mlat=..&mlon=..#map=z/lat/lng */
    private fun openStreetMap(p: GeoPoint): String {
        val lat = formatLat(p.lat)
        val lng = formatLng(p.lng)
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lng +
            "#map=" + OSM_ZOOM + "/" + lat + "/" + lng
    }

    // ── 数值与编码 ──

    /**
     * 纬度格式化成 6 位小数字符串。
     *
     * 不用 [String.format] 是为了避免 Locale 差异：默认 Locale 是阿拉伯语 / 德语环境时，
     * 格式化结果里可能出现「٫」或「,」作为小数点，拼进 URI 会让地图 App 解析失败。
     */
    internal fun formatLat(value: Double): String = formatCoordinate(value.coerceIn(-90.0, 90.0))

    /** 经度格式化成 6 位小数字符串（同上，不用 Locale 敏感的格式化）。 */
    internal fun formatLng(value: Double): String = formatCoordinate(value.coerceIn(-180.0, 180.0))

    private fun formatCoordinate(value: Double): String {
        val scaled = Math.round(value * 1_000_000.0)
        val negative = scaled < 0
        val abs = if (negative) -scaled else scaled
        val intPart = abs / 1_000_000
        val fracPart = abs % 1_000_000
        val frac = fracPart.toString().padStart(6, '0')
        // -0.000000 归一成 0.000000：同一个点在两地图 App 里不该有两种写法
        val sign = if (negative && abs != 0L) "-" else ""
        return sign + intPart + "." + frac
    }

    /** URI 组件编码；编码失败时返回原串（宁可少转义也不让整条 URI 变 null）。 */
    internal fun encode(raw: String): String = runCatching {
        URLEncoder.encode(raw, "UTF-8")
    }.getOrDefault(raw)
}
