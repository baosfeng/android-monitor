package com.bsfeng.monitor.ui.trip

// 轨迹图导出：把某一天的轨迹渲染成 1080px 宽的 PNG，写进系统相册「Pictures/行为记录」。
//
// - 命名与相册落点是纯逻辑（TripExportNaming），可在 JVM 单测里验证；
// - 渲染用 android.graphics（Bitmap + Canvas + Paint），不依赖 Compose 组合，可在后台线程跑；
// - 相册写入走 MediaStore（Android 10+ 无需存储权限，不申请 WRITE_EXTERNAL_STORAGE）；
// - 底图开时，导出前先把这块区域的瓦片取齐（带总超时），取不到的块留白，不拖住导出。

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.bsfeng.monitor.domain.trip.Stay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 导出文件命名与相册落点（纯逻辑，无 Android 依赖）。 */
object TripExportNaming {

    /** MediaStore 相对路径（不带前导斜杠）：相册里显示为「图片/行为记录」。 */
    const val RELATIVE_PATH = "Pictures/行为记录"

    /** 相册里的文件夹名（Toast 与失败提示用）。 */
    const val ALBUM_NAME = "行为记录"

    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val CLOCK = DateTimeFormatter.ofPattern("HHmm")

    /**
     * 文件名：`Trip-<所选日期 yyyyMMdd>-<导出时刻 HHmm>.png`。
     *
     * 前半段取所选日期（同一天重复导出会撞名，由 MediaStore 自增序号兜底），
     * 后半段取导出时刻（不同时刻导出不会互相覆盖）。
     */
    fun fileName(dayStart: Long, exportAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate().format(DAY)
        val time = Instant.ofEpochMilli(exportAt).atZone(zone).toLocalTime().format(CLOCK)
        return "Trip-" + day + "-" + time + ".png"
    }
}

/** 导出配色：由调用方从设计 token 转成 ARGB，导出图与页面同色。 */
data class TripExportStyle(
    val surface: Int,
    val warm: Int,
    val fg: Int,
    val muted: Int,
    val accent: Int,
    val success: Int,
    val danger: Int,
)

/** 导出用的一张底图瓦片（dp 落点）。 */
data class BitmapTile(val bitmap: Bitmap, val left: Float, val top: Float, val size: Float)

/** 导出图里的一层底图：投影 + 已取到的瓦片 + 署名。 */
data class TripMapLayer(
    val view: TileView,
    val tiles: List<BitmapTile>,
    val attribution: String,
)

/** 轨迹图渲染（纯 android.graphics，可在后台线程调用）。 */
object TripExportRenderer {

    /** 导出图片固定 1080px 宽（上限要求；不跟随设备密度）。 */
    const val WIDTH_PX = 1080

    /** 与屏内 360dp 逻辑宽对齐：停留圆半径、标签字号口径一致。 */
    const val DENSITY = 3f

    const val MAP_HEIGHT_PX = 540

    /** 画布在逻辑坐标里的大小与留白，底图选级与屏内用同一套。 */
    const val MAP_WIDTH_DP = (WIDTH_PX - 80f) / DENSITY
    const val MAP_HEIGHT_DP = MAP_HEIGHT_PX / DENSITY
    const val MAP_PADDING_DP = 12f

    private const val HEADER_HEIGHT_PX = 140
    private const val FOOTER_HEIGHT_PX = 110
    private const val MARGIN_PX = 40f
    private const val LABEL_TEXT_PX = 28f
    private const val LABEL_HEIGHT_PX = 38f
    private const val TRACE_PADDING_PX = 36f

    /**
     * 渲染一张导出图。
     *
     * @param title 标题（形如「2026年10月24日 今日轨迹」）
     * @param subtitle 副标题（样本数与时间段）
     * @param map 底图层；null = 纯自绘（与轨迹卡关闭底图时同款）
     */
    fun render(
        title: String,
        subtitle: String,
        samples: List<TraceSample>,
        stays: List<Stay>,
        style: TripExportStyle,
        map: TripMapLayer? = null,
    ): Bitmap {
        val width = WIDTH_PX
        val height = HEADER_HEIGHT_PX + MAP_HEIGHT_PX + FOOTER_HEIGHT_PX
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(style.surface)

        val titlePaint = paint(style.fg, 46f, bold = true)
        val subPaint = paint(style.muted, 28f)
        val notePaint = paint(style.muted, 26f)
        canvas.drawText(title, MARGIN_PX, HEADER_HEIGHT_PX - 62f, titlePaint)
        canvas.drawText(subtitle, MARGIN_PX, HEADER_HEIGHT_PX - 22f, subPaint)

        // 画布底：浅色圆角矩形，四周留白，与屏内卡片同色
        val mapRect = RectF(
            MARGIN_PX,
            HEADER_HEIGHT_PX.toFloat(),
            width - MARGIN_PX,
            (HEADER_HEIGHT_PX + MAP_HEIGHT_PX).toFloat(),
        )
        val mapPaint = paint(style.warm, 0f)
        canvas.drawRoundRect(mapRect, 24f, 24f, mapPaint)

        if (samples.isEmpty()) {
            val emptyPaint = paint(style.muted, 30f, align = Paint.Align.CENTER)
            canvas.drawText(
                "这一天没有定位样本",
                width / 2f,
                mapRect.centerY(),
                emptyPaint,
            )
        } else {
            if (map != null) drawTiles(canvas, mapRect, map.tiles)
            drawTrace(canvas, mapRect, samples, stays, style, map?.view)
        }

        // 水印：右下角，说明底图来源或「自绘无底图」
        val markPaint = paint(style.muted, 26f, align = Paint.Align.RIGHT)
        canvas.drawText(
            map?.attribution ?: "自绘轨迹 · 无底图",
            width - MARGIN_PX,
            (height - 34f),
            markPaint,
        )
        val footPaint = paint(style.muted, 26f)
        canvas.drawText("行为记录 · 每日轨迹", MARGIN_PX, (height - 34f), footPaint)
        return bitmap
    }

    /** 底图瓦片：裁到画布圆角内，相邻块按同一像素边界对齐避免细缝。 */
    private fun drawTiles(canvas: Canvas, mapRect: RectF, tiles: List<BitmapTile>) {
        val clip = Path().apply { addRoundRect(mapRect, 24f, 24f, Path.Direction.CW) }
        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.withClip(clip) {
            withTranslation(mapRect.left, mapRect.top) {
                tiles.forEach { tile ->
                    val left = tile.left * DENSITY
                    val top = tile.top * DENSITY
                    val dst = RectF(left, top, left + tile.size * DENSITY, top + tile.size * DENSITY)
                    drawBitmap(tile.bitmap, null, dst, tilePaint)
                }
            }
        }
    }

    private fun drawTrace(
        canvas: Canvas,
        mapRect: RectF,
        samples: List<TraceSample>,
        stays: List<Stay>,
        colors: TripExportStyle,
        mapView: TileView?,
    ) {
        val mapWidth = mapRect.width()
        val mapHeight = mapRect.height()
        val projection = if (mapView != null) {
            TripTrace.projectOnMap(
                samples = samples,
                stays = stays,
                view = mapView,
                widthPx = mapWidth,
                heightPx = mapHeight,
                density = DENSITY,
                charPx = LABEL_TEXT_PX,
                labelHeightPx = LABEL_HEIGHT_PX,
            )
        } else {
            TripTrace.project(
                samples = samples,
                stays = stays,
                widthPx = mapWidth,
                heightPx = mapHeight,
                paddingPx = TRACE_PADDING_PX,
                charPx = LABEL_TEXT_PX,
                labelHeightPx = LABEL_HEIGHT_PX,
                density = DENSITY,
            )
        }
        if (!projection.hasTrace) return

        val fill = paint(colors.accent, 0f).apply { alpha = 41 } // 16% 透明
        val ring = paint(colors.accent, 0f).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 3f
            alpha = 115
        }
        val line = paint(colors.accent, 0f).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // 低置信段虚线：与屏内同一套线型（4dp + 3dp），只换 dash、不换颜色与线宽
        val dashed = paint(colors.accent, 0f).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
            pathEffect = DashPathEffect(floatArrayOf(4f * DENSITY, 3f * DENSITY), 0f)
        }
        val labelPaint = paint(colors.fg, LABEL_TEXT_PX)

        canvas.withTranslation(mapRect.left, mapRect.top) {
            projection.stays.forEach { stay ->
                val radius = stay.radiusDp * DENSITY
                drawCircle(stay.x, stay.y, radius, fill)
                drawCircle(stay.x, stay.y, radius, ring)
            }

            if (projection.points.size > 1) {
                drawTraceSegments(projection.points, samples, line, dashed)
            }

            val start = projection.points.first()
            val end = projection.points.last()
            drawEndpoint(this, start.x, start.y, colors.success, colors.surface)
            if (projection.points.size > 1) {
                drawEndpoint(this, end.x, end.y, colors.danger, colors.surface)
            }

            projection.stays.forEach { stay ->
                if (!stay.labelVisible) return@forEach
                val radius = stay.radiusDp * DENSITY
                val textWidth = labelPaint.measureText(stay.label)
                val left = (stay.x - textWidth / 2f).coerceIn(0f, (mapWidth - textWidth).coerceAtLeast(0f))
                val top = (stay.y - radius - LABEL_HEIGHT_PX).coerceAtLeast(0f)
                drawText(stay.label, left, top + LABEL_TEXT_PX, labelPaint)
            }
        }
    }

    /**
     * 折线分档绘制：与屏内 [TracePrecision.segmentRuns] 同一套判定，导出图与截图不会两套口径。
     *
     * 分档长度与投影点对不上时退回整条实线（宁可少表达，不画错段）。
     */
    private fun Canvas.drawTraceSegments(
        points: List<TracePoint>,
        samples: List<TraceSample>,
        solid: Paint,
        dashed: Paint,
    ) {
        val runs = TracePrecision.segmentRuns(samples.map { TracePrecision.bandOf(it.accuracyM) })
        if (runs.isEmpty() || runs.last().to != points.lastIndex) {
            val path = Path()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            drawPath(path, solid)
            return
        }
        runs.forEach { run ->
            if (run.to <= run.from) return@forEach
            val path = Path()
            path.moveTo(points[run.from].x, points[run.from].y)
            for (index in run.from + 1..run.to) path.lineTo(points[index].x, points[index].y)
            drawPath(path, if (run.low) dashed else solid)
        }
    }

    private fun drawEndpoint(canvas: Canvas, x: Float, y: Float, color: Int, ring: Int) {
        canvas.drawCircle(x, y, 13f, paint(ring, 0f))
        canvas.drawCircle(x, y, 9f, paint(color, 0f))
    }

    private fun paint(color: Int, size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = align
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
}

/** 导出图副标题：样本数与时间段（与屏内卡头同一口径）。 */
internal fun exportSubtitle(samples: List<TraceSample>, dayStart: Long): String {
    if (samples.isEmpty()) return "这一天没有定位样本"
    val start = formatSpanClock(samples.first().timestamp, dayStart)
    val end = formatSpanClock(samples.last().timestamp, dayStart)
    return "样本 " + samples.size + " 个 · " + start + "–" + end
}

/**
 * 导出流程编排：取瓦片与渲染在后台线程，写盘在 IO 线程，失败原因原样带回给 Toast。
 *
 * 放在这里而不是屏内：渲染 + 命名的口径只有这一个入口，屏只负责提示结果。
 */
object TripExporter {

    /** 取底图的总预算：超时就不等了，缺的块留白，导出照常完成。 */
    private const val TILE_BUDGET_MS = 12_000L

    suspend fun export(
        context: Context,
        dayStart: Long,
        title: String,
        subtitle: String,
        samples: List<TraceSample>,
        stays: List<Stay>,
        style: TripExportStyle,
        basemap: Boolean = false,
        source: TileSource = TileSources.DEFAULT,
    ): Result<Uri> = runCatching {
        val layer = if (basemap && samples.isNotEmpty()) {
            withContext(Dispatchers.IO) { loadLayer(context, samples, source) }
        } else {
            null
        }
        val bitmap = withContext(Dispatchers.Default) {
            TripExportRenderer.render(
                title = title,
                subtitle = subtitle,
                samples = samples,
                stays = stays,
                style = style,
                map = layer,
            )
        }
        withContext(Dispatchers.IO) {
            val name = TripExportNaming.fileName(dayStart = dayStart, exportAt = System.currentTimeMillis())
            TripExportStore.saveToGallery(context, bitmap, name).getOrThrow()
        }
    }

    /**
     * 取导出用的底图：选级与屏内一致，瓦片并发受限，整体带 [TILE_BUDGET_MS] 超时。
     * 一块都没取到就返回 null（等于关底图，不留一张空底色图）。
     */
    private suspend fun loadLayer(
        context: Context,
        samples: List<TraceSample>,
        source: TileSource,
    ): TripMapLayer? {
        val view = TileMath.view(
            samples = samples,
            widthDp = TripExportRenderer.MAP_WIDTH_DP.toDouble(),
            heightDp = TripExportRenderer.MAP_HEIGHT_DP.toDouble(),
            paddingDp = TripExportRenderer.MAP_PADDING_DP.toDouble(),
            maxZoom = source.maxZoom,
        ) ?: return null
        val refs = TileMath.visibleTiles(
            view,
            TripExportRenderer.MAP_WIDTH_DP.toDouble(),
            TripExportRenderer.MAP_HEIGHT_DP.toDouble(),
        )
        if (refs.isEmpty()) return null
        val fetcher = TileFetcher.get(context)
        val gate = Semaphore(TileFetcher.MAX_PARALLEL)
        val tiles = withTimeoutOrNull(TILE_BUDGET_MS) {
            coroutineScope {
                refs.map { ref ->
                    async {
                        gate.withPermit {
                            fetcher.load(source, ref)?.let { bitmap ->
                                BitmapTile(bitmap, ref.leftDp, ref.topDp, ref.sizeDp)
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } ?: return null
        if (tiles.isEmpty()) return null
        return TripMapLayer(view, tiles, source.attribution)
    }
}

/** 写系统相册（MediaStore）。Android 10+ 走 RELATIVE_PATH + IS_PENDING，不需要存储权限。 */
object TripExportStore {

    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Result<Uri> {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, TripExportNaming.RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: return Result.failure(IllegalStateException("系统相册拒绝了写入请求"))
        return try {
            val stream = resolver.openOutputStream(uri)
                ?: error("无法打开相册写入流")
            stream.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    error("PNG 编码失败")
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result.success(uri)
        } catch (t: Throwable) {
            // 写一半失败要回滚，不留空图占位
            runCatching { resolver.delete(uri, null, null) }
            Result.failure(t)
        }
    }
}
