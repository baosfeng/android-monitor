package com.bsfeng.monitor.ui.trip

// 免费底图源：只用免 key 的公开瓦片服务，不引入任何地图 SDK。
//
// 用这些服务的共同前提（各家使用条款都写了）：
// - 请求带可识别的 User-Agent（见 TileFetcher.USER_AGENT），不做匿名批量抓取；
// - 本地缓存瓦片，同一块不重复下载（见 TileFetcher 的磁盘缓存）；
// - 图上署名（见 attribution），轨迹卡与导出图都印；
// - 个人小量使用；要做产品级分发就换有服务承诺的商用图源。
//
// 选源实测（2026-10，上海家宽）：
// - tile.openstreetmap.org：连接被重置（不可用）
// - a.tile.openstreetmap.fr/osmfr：1.6~4.2 s/块（可用，偏慢）
// - server.arcgisonline.com（Esri）：0.4 s/块（最快）
// 故默认用 Esri，并把 OSM 官方 + 法国镜像作为可选替换源。

/**
 * 一个瓦片源。
 *
 * [templates] 是同一份地图的多个入口，按顺序尝试：前一个连不上就自动落到下一个，
 * 连不上过的入口在进程内熔断 5 分钟（见 TileFetcher），不做每次都白等一遍。
 *
 * 模板支持 {z} / {x} / {y} / {s} 四个占位符，其中 {s} 取 [subdomains] 里的子域。
 */
data class TileSource(
    val id: String,
    val title: String,
    val templates: List<String>,
    /** 图上必须保留的署名文本。 */
    val attribution: String,
    val subdomains: List<String> = emptyList(),
    /** 图源支持的最高级别；超过会 404，选级时要夹住。 */
    val maxZoom: Int = TileMath.MAX_ZOOM,
) {
    init {
        require(templates.isNotEmpty()) { "瓦片源至少要有一个入口" }
        templates.forEach { template ->
            require(template.contains("{z}") && template.contains("{x}") && template.contains("{y}")) {
                "瓦片模板必须包含 {z} {x} {y}：" + template
            }
        }
    }

    /** 把一个模板展开成具体 URL；子域按行列取模，保证同一块每次都落到同一台服务器（缓存友好）。 */
    fun url(template: String, z: Int, x: Int, y: Int): String {
        var text = template
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        if (text.contains("{s}")) {
            val hosts = subdomains.ifEmpty { listOf("a", "b", "c") }
            val index = ((x + y) % hosts.size + hosts.size) % hosts.size
            text = text.replace("{s}", hosts[index])
        }
        return text
    }

    /** 该瓦片的全部候选 URL（按优先级从高到低）。 */
    fun urls(z: Int, x: Int, y: Int): List<String> = templates.map { url(it, z, x, y) }
}

/** 内置图源。 */
object TileSources {

    /**
     * 默认源：Esri 街道图。免 key、国内可直连、每块 0.4 s 量级，中国区域为中文标注。
     * 注意 x / y 的顺序与 OSM 相反（Esri 的 tile 路径是 {z}/{y}/{x}）。
     */
    val ESRI_STREET = TileSource(
        id = "esri_street",
        title = "Esri 街道图",
        templates = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
        ),
        attribution = "© Esri, HERE, Garmin",
        maxZoom = 19,
    )

    /** OpenStreetMap：官方站 + 法国镜像 + 德国镜像，三个入口自动回退。 */
    val OSM = TileSource(
        id = "osm",
        title = "OpenStreetMap",
        templates = listOf(
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://{s}.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png",
            "https://{s}.tile.openstreetmap.de/{z}/{x}/{y}.png",
        ),
        attribution = "© OpenStreetMap contributors",
        subdomains = listOf("a", "b"),
        maxZoom = 19,
    )

    /** 默认图源；换源只改这里。 */
    val DEFAULT: TileSource = ESRI_STREET

    val ALL: List<TileSource> = listOf(ESRI_STREET, OSM)
}
