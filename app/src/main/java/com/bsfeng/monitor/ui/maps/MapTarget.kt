package com.bsfeng.monitor.ui.maps

// 「在地图中查看」的候选目标。
//
// 为什么是这几个：Android 本身没有内置地图 App（Pixel/AOSP 只有浏览器），国内用户装的多是
// 高德 / 腾讯 / 百度，海外机型是 Google 地图。这些全部靠系统 Intent 唤起，零 SDK、零 key、零费用，
// 因此不需要任何三方依赖，也不受任何一家服务条款约束。
//
// 顺序即偏好顺序：国内三家在前（覆盖率最高、坐标系统一为 GCJ-02），Google 在后，
// 浏览器永远只是兜底（见 MapEntryPlan.fallback）。

/** 一个可唤起的地图目标。[packageName] 为空表示「不绑定具体包」（浏览器按 scheme 匹配）。 */
enum class MapTarget(
    val label: String,
    val packageName: String,
    val coordSystem: CoordSystem,
) {
    /** 高德地图：androidamap:// 方案，吃 GCJ-02。 */
    AMAP("高德地图", "com.autonavi.minimap", CoordSystem.GCJ02),

    /** 腾讯地图：qqmap:// 方案，吃 GCJ-02。 */
    TENCENT("腾讯地图", "com.tencent.map", CoordSystem.GCJ02),

    /** 百度地图：baidumap:// 方案，吃 BD-09（不是 GCJ-02，偏移约 300~600m）。 */
    BAIDU("百度地图", "com.baidu.BaiduMap", CoordSystem.BD09),

    /** Google 地图：geo: 方案，吃 WGS84（不做任何纠偏）。 */
    GOOGLE("Google 地图", "com.google.android.apps.maps", CoordSystem.WGS84),

    /** 浏览器兜底：https 网页地图（OpenStreetMap），吃 WGS84，零 key 零条款。 */
    BROWSER("浏览器", "", CoordSystem.WGS84),
    ;

    /** 是否是需要单独判定的地图 App（浏览器走 scheme 匹配，不按包名判定）。 */
    val isMapApp: Boolean get() = this != BROWSER

    companion object {
        /** 地图 App 的偏好顺序（不含浏览器兜底）。 */
        val MAP_APPS: List<MapTarget> = listOf(AMAP, TENCENT, BAIDU, GOOGLE)

        /** 需要写进 AndroidManifest `<queries>` 的包名（Android 11+ 才能 resolve 到别的 App）。 */
        val QUERY_PACKAGES: List<String> = MAP_APPS.map { it.packageName }
    }
}
