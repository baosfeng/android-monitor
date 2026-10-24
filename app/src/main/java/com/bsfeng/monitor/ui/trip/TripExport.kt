package com.bsfeng.monitor.ui.trip

// 轨迹图导出：把某一天的轨迹渲染成 1080px 宽的 PNG，写进系统相册「Pictures/行为记录」。
//
// - 命名与相册落点是纯逻辑（[TripExportNaming]），可在 JVM 单测里验证；
// - 渲染用 android.graphics（Bitmap + Canvas + Paint），不依赖 Compose 组合，可在 IO 线程跑；
// - 相册写入走 MediaStore（Android 10+ 无需存储权限，不申请 WRITE_EXTERNAL_STORAGE）；
// - 只导出轨迹本身（标题 + 画布 + 水印），不导整屏。

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.bsfeng.monitor.domain.trip.Stay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** 轨迹图渲染（纯 android.graphics，可在后台线程调用）。 */
object TripExportRenderer {

    /** 导出图片固定 1080px 宽（上限要求；不跟随设备密度）。 */
    const val WIDTH_PX = 1080

    private const val MAP_HEIGHT_PX = 540
    private const val HEADER_HEIGHT_PX = 140
    private const val FOOTER_HEIGHT_PX = 110
    private const val MARGIN_PX = 40f
    private const val LABEL_TEXT_PX = 28f
    private const val LABEL_HEIGHT_PX = 38f

    /**
     * 渲染一张导出图。
     *
     * @param title 标题（形如「2026年10月24日 今日轨迹」）
     * @param subtitle 副标题（样本数与时间段）
     */
    fun render(
        title: String,
        subtitle: String,
        samples: List<TraceSample>,
        stays: List<Stay>,
        style: TripExportStyle,
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
            drawTrace(canvas, mapRect, samples, stays, style)
        }

        // 水印：右下角，说明是自绘、没有底图
        val markPaint = paint(style.muted, 26f, align = Paint.Align.RIGHT)
        canvas.drawText(
            "自绘轨迹 · 无底图",
            width - MARGIN_PX,
            (height - 34f),
            markPaint,
        )
        val footPaint = paint(style.muted, 26f)
        canvas.drawText("行为记录 · 每日轨迹", MARGIN_PX, (height - 34f), footPaint)
        return bitmap
    }

    private fun drawTrace(
        canvas: Canvas,
        mapRect: RectF,
        samples: List<TraceSample>,
        stays: List<Stay>,
        colors: TripExportStyle,
    ) {
        // 导出图按「屏幕逻辑宽 360dp」折算密度：6..14dp 的停留圆在 1080px 图上约 18..42px
        val densityScale = WIDTH_PX / 360f
        val mapWidth = mapRect.width()
        val mapHeight = mapRect.height()
        val projection = TripTrace.project(
            samples = samples,
            stays = stays,
            widthPx = mapWidth,
            heightPx = mapHeight,
            paddingPx = 36f,
            charPx = LABEL_TEXT_PX,
            labelHeightPx = LABEL_HEIGHT_PX,
            density = densityScale,
        )
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
        val labelPaint = paint(colors.fg, LABEL_TEXT_PX)

        canvas.withTranslation(mapRect.left, mapRect.top) {
            projection.stays.forEach { stay ->
                val radius = stay.radiusDp * densityScale
                drawCircle(stay.x, stay.y, radius, fill)
                drawCircle(stay.x, stay.y, radius, ring)
            }

            if (projection.points.size > 1) {
                val path = Path()
                projection.points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                drawPath(path, line)
            }

            val start = projection.points.first()
            val end = projection.points.last()
            drawEndpoint(this, start.x, start.y, colors.success, colors.surface)
            if (projection.points.size > 1) {
                drawEndpoint(this, end.x, end.y, colors.danger, colors.surface)
            }

            projection.stays.forEach { stay ->
                if (!stay.labelVisible) return@forEach
                val radius = stay.radiusDp * densityScale
                val textWidth = labelPaint.measureText(stay.label)
                val left = (stay.x - textWidth / 2f).coerceIn(0f, (mapWidth - textWidth).coerceAtLeast(0f))
                val top = (stay.y - radius - LABEL_HEIGHT_PX).coerceAtLeast(0f)
                drawText(stay.label, left, top + LABEL_TEXT_PX, labelPaint)
            }
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
 * 导出流程编排：渲染在 Default 线程、写盘在 IO 线程，失败原因原样带回给 Toast。
 *
 * 放在这里而不是屏内：渲染 + 命名的口径只有这一个入口，屏只负责提示结果。
 */
object TripExporter {
    suspend fun export(
        context: Context,
        dayStart: Long,
        title: String,
        subtitle: String,
        samples: List<TraceSample>,
        stays: List<Stay>,
        style: TripExportStyle,
    ): Result<Uri> = runCatching {
        val bitmap = withContext(Dispatchers.Default) {
            TripExportRenderer.render(
                title = title,
                subtitle = subtitle,
                samples = samples,
                stays = stays,
                style = style,
            )
        }
        withContext(Dispatchers.IO) {
            val name = TripExportNaming.fileName(dayStart = dayStart, exportAt = System.currentTimeMillis())
            TripExportStore.saveToGallery(context, bitmap, name).getOrThrow()
        }
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
