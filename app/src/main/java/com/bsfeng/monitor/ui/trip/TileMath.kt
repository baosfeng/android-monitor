package com.bsfeng.monitor.ui.trip

// 免费底图的投影与瓦片编排：纯逻辑，不依赖 Android / Compose，可在 JVM 单测里直接验证。
//
// 口径：
// - 底图走 Web Mercator（在线瓦片服务的通用投影），画布坐标单位是 dp；
// - 「世界像素」= 某缩放级别下整张地图的像素边长，z 级为 256·2^z；
// - 缩放级别按轨迹包围盒选：取能装下包围盒的最大整数级，整体居中并留白；
// - 瓦片级别比视图级别高 1 级（[RETINA_BOOST]），每张按 128dp 画，2x/3x 屏不糊；
// - 纬度按 ±85.05° 截断：Mercator 在极点发散，超出范围的点不画。

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** 一张可见瓦片在画布上的落点（dp；[leftDp] / [topDp] 是左上角）。 */
data class TileRef(
    val z: Int,
    val x: Int,
    val y: Int,
    val leftDp: Float,
    val topDp: Float,
    val sizeDp: Float,
)

/**
 * 画布对应的地图视图。
 *
 * [worldX0] / [worldY0] 是画布左上角在「[tileZoom] 级世界像素」里的坐标；
 * 画布 dp → 世界像素的换算就是乘 [pixelScale] 再加原点。
 */
data class TileView(
    val tileZoom: Int,
    val tileSizeDp: Double,
    val worldX0: Double,
    val worldY0: Double,
    val pixelScale: Double,
)

object TileMath {

    /** 一张瓦片的边长（世界像素，固定 256，服务端约定）。 */
    const val TILE_PX = 256.0

    /** Mercator 可用纬度上限；再往上 y 会发散到无穷。 */
    const val MAX_LAT = 85.05112878

    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 19

    /** 瓦片级别比视图级别高 1 级：等于把图源分辨率翻倍，屏上更锐。 */
    const val RETINA_BOOST = 1

    /** 正常情况下 360x180dp 的画布只需 3~8 张瓦片；超过这个数是参数算错了。 */
    const val MAX_TILES = 120

    private const val EPS = 1e-6

    /** 经度 → 世界像素 x。 */
    fun worldX(lng: Double, zoom: Int): Double = (lng + 180.0) / 360.0 * worldSize(zoom)

    /**
     * 纬度 → 世界像素 y（向下为正，与画布一致）。
     *
     * 比例再夹一次 [0,1]：MAX_LAT 是四舍五入值，边界上会算出 -5e-8 这种「负的像素」，
     * 夹一下免得下游出现负的瓦片下标。
     */
    fun worldY(lat: Double, zoom: Int): Double {
        val rad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        val mercator = ln(tan(rad) + 1.0 / cos(rad))
        val ratio = ((1.0 - mercator / PI) / 2.0).coerceIn(0.0, 1.0)
        return ratio * worldSize(zoom)
    }

    /** 该级别的世界像素边长。 */
    fun worldSize(zoom: Int): Double = TILE_PX * (1 shl zoom.coerceIn(0, 22)).toDouble()

    /**
     * 选缩放级别：能把这组坐标的包围盒装进 [innerWidthDp] x [innerHeightDp] 的最大整数级。
     *
     * 全部点重合（跨度为 0）时返回 [maxZoom]，等价于「贴近看一个点」。
     */
    fun pickZoom(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        innerWidthDp: Double,
        innerHeightDp: Double,
        minZoom: Int = MIN_ZOOM,
        maxZoom: Int = MAX_ZOOM,
    ): Int {
        if (innerWidthDp <= 0.0 || innerHeightDp <= 0.0) return minZoom
        for (zoom in maxZoom downTo minZoom) {
            val spanX = worldX(maxLng, zoom) - worldX(minLng, zoom)
            val spanY = worldY(minLat, zoom) - worldY(maxLat, zoom)
            if (spanX <= innerWidthDp + EPS && spanY <= innerHeightDp + EPS) return zoom
        }
        return minZoom
    }

    /**
     * 由轨迹采样算出画布视图；采样为空或画布非法时返回 null（调用方回落到无底图自绘）。
     *
     * @param paddingDp 四周留白（dp），会被夹到不超过短边的一半
     * @param maxZoom 图源支持的最高级别（见 [TileSource.maxZoom]）
     */
    fun view(
        samples: List<TraceSample>,
        widthDp: Double,
        heightDp: Double,
        paddingDp: Double,
        maxZoom: Int = MAX_ZOOM,
    ): TileView? {
        if (samples.isEmpty() || widthDp <= 0.0 || heightDp <= 0.0) return null
        val pad = paddingDp.coerceIn(0.0, min(widthDp, heightDp) / 2.0)
        val innerW = widthDp - 2.0 * pad
        val innerH = heightDp - 2.0 * pad
        if (innerW <= 0.0 || innerH <= 0.0) return null

        val minLat = samples.minOf { it.lat }
        val maxLat = samples.maxOf { it.lat }
        val minLng = samples.minOf { it.lng }
        val maxLng = samples.maxOf { it.lng }
        val zoom = pickZoom(minLat, maxLat, minLng, maxLng, innerW, innerH, MIN_ZOOM, maxZoom)
        val tileZoom = (zoom + RETINA_BOOST).coerceAtMost(maxZoom)
        val scale = 2.0.pow((tileZoom - zoom).toDouble())
        val centerX = (worldX(minLng, tileZoom) + worldX(maxLng, tileZoom)) / 2.0
        val centerY = (worldY(minLat, tileZoom) + worldY(maxLat, tileZoom)) / 2.0
        return TileView(
            tileZoom = tileZoom,
            tileSizeDp = TILE_PX / scale,
            worldX0 = centerX - widthDp / 2.0 * scale,
            worldY0 = centerY - heightDp / 2.0 * scale,
            pixelScale = scale,
        )
    }

    /** 经度 → 画布 x（dp）。 */
    fun canvasX(lng: Double, view: TileView): Double =
        (worldX(lng, view.tileZoom) - view.worldX0) / view.pixelScale

    /** 纬度 → 画布 y（dp）。 */
    fun canvasY(lat: Double, view: TileView): Double =
        (worldY(lat, view.tileZoom) - view.worldY0) / view.pixelScale

    /**
     * 覆盖画布所需的瓦片。
     *
     * [TileRef.x] 是**取模后**的瓦片列号（跨 180° 经线时列号会回绕），
     * 落点按未回绕的列号算，因此跨经线的瓦片仍然接得上。
     */
    fun visibleTiles(view: TileView, widthDp: Double, heightDp: Double): List<TileRef> {
        if (widthDp <= 0.0 || heightDp <= 0.0 || view.tileSizeDp <= 0.0) return emptyList()
        val columns = 1 shl view.tileZoom
        val firstX = floor(view.worldX0 / TILE_PX).toInt()
        val lastX = floor((view.worldX0 + widthDp * view.pixelScale - EPS) / TILE_PX).toInt()
        val firstY = floor(view.worldY0 / TILE_PX).toInt()
        val lastY = floor((view.worldY0 + heightDp * view.pixelScale - EPS) / TILE_PX).toInt()
        if (lastX - firstX + 1 > MAX_TILES || lastY - firstY + 1 > MAX_TILES) return emptyList()
        if ((lastX - firstX + 1).toLong() * (lastY - firstY + 1) > MAX_TILES) return emptyList()

        val out = ArrayList<TileRef>()
        for (ty in firstY..lastY) {
            if (ty < 0 || ty >= columns) continue
            for (tx in firstX..lastX) {
                val left = (tx * TILE_PX - view.worldX0) / view.pixelScale
                val top = (ty * TILE_PX - view.worldY0) / view.pixelScale
                out.add(
                    TileRef(
                        z = view.tileZoom,
                        x = ((tx % columns) + columns) % columns,
                        y = ty,
                        leftDp = left.toFloat(),
                        topDp = top.toFloat(),
                        sizeDp = view.tileSizeDp.toFloat(),
                    ),
                )
            }
        }
        return out
    }

    /** 瓦片缓存键（源 + 级别 + 行列），内存与磁盘共用。 */
    fun tileKey(sourceId: String, ref: TileRef): String = sourceId + "/" + ref.z + "/" + ref.x + "/" + ref.y

}
