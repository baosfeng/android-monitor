package com.bsfeng.monitor.widget

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.parity.RegisterComponentActivityRule
import com.bsfeng.monitor.ui.theme.WidgetPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 额度组件的「同构内容层」截图 + 溢出实测（Robolectric，NATIVE 图形）。
 *
 * 为什么要一份普通 @Composable 镜像：Glance 的 composition 只在真机 launcher 上出画面，
 * 单测里渲染不了 RemoteViews；但「4 格塞不塞得进 250×110」是纯排版问题，
 * 按 [QuotaWidgetMetrics] 的同一份数字、同一份 [quotaWidgetCells] 输出画一遍就能量出来。
 *
 * 镜像与 QuotaWidget.kt 的结构一一对应（顶栏 → 2 列网格 → 每格「值 / 说明 / 4dp 横条」），
 * 颜色取同一组 WidgetPalette.quota 亮色 token；Glance 侧改结构时必须同步改这里，否则截图会骗人。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuotaWidgetShotTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 固定「现在」：2026-11-28 10:00，重置时刻文案才可断言。 */
    private val now = LocalDateTime.of(2026, 11, 28, 10, 0).atZone(zone).toEpochSecond()

    private fun resetAt(month: Int, day: Int, hour: Int): Long =
        LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toEpochSecond()

    /** 原型 s18-widget 的四行示例数据（账号名已换成演示值）。 */
    private fun demoAccounts(): List<QuotaWidgetAccount> = listOf(
        QuotaWidgetAccount(
            instanceId = "oc-main",
            displayName = "opencode",
            items = listOf(percent("h", "5 小时", "12", resetsAt = resetAt(11, 28, 17))),
        ),
        QuotaWidgetAccount(
            instanceId = "ollama",
            displayName = "Ollama",
            items = listOf(percent("w", "本周", "96", resetsAt = resetAt(11, 30, 0))),
        ),
        QuotaWidgetAccount(
            instanceId = "od-a",
            displayName = "open-design",
            items = listOf(percent("m", "本月", "34", resetsAt = resetAt(12, 1, 0))),
        ),
        QuotaWidgetAccount(
            instanceId = "ds-2",
            displayName = "DeepSeek 账号 2",
            items = listOf(
                QuotaItem(
                    id = "bal",
                    title = "余额",
                    kind = QuotaKind.MONEY,
                    remaining = BigDecimal("8.60"),
                    unit = QuotaUnit.CNY,
                ),
            ),
        ),
    )

    private fun percent(id: String, title: String, remaining: String, resetsAt: Long) = QuotaItem(
        id = id,
        title = title,
        kind = QuotaKind.PERCENT,
        remaining = BigDecimal(remaining),
        unit = QuotaUnit.PERCENT,
        resetsAtEpochSecond = resetsAt,
    )

    @Test
    fun `4×2 最小箱子_四格与横条`() {
        val grid = quotaWidgetGrid(widthDp = 250f, heightDp = 110f)
        // 一格只有 109dp（< 120dp）：说明行整段省掉重置时刻，不留半截「·」
        val scale = QuotaWidgetMetrics.of(110f)
        val cellWidth = quotaCellWidthDp(250f, grid.columns, scale)
        val cells = quotaWidgetCells(demoAccounts(), grid.cells, now, captionWidthDp = cellWidth, zone = zone)
        val measured = shot("impl-widget.png", 250.dp, 110.dp, grid, cells)
        assertEquals(4, cells.size)
        // 3 个百分比格有横条、余额格没有
        assertEquals(3, measured.barHeights.size)
        assertEquals(3, measured.barRatios.size)
        assertEquals(4, measured.cellBottoms.size)
        assertNull(cells[3].fraction)
        assertNotNull(cells[0].fraction)
    }

    @Test
    fun `4×1 降档_两格`() {
        val grid = quotaWidgetGrid(widthDp = 350f, heightDp = 72f)
        val scale = QuotaWidgetMetrics.of(72f)
        val cellWidth = quotaCellWidthDp(350f, grid.columns, scale)
        val cells = quotaWidgetCells(demoAccounts(), grid.cells, now, captionWidthDp = cellWidth, zone = zone)
        val measured = shot("impl-widget-4x1.png", 350.dp, 72.dp, grid, cells)
        assertEquals(2, cells.size)
        assertEquals(2, measured.cellBottoms.size)
        assertEquals(2, measured.barHeights.size)
        assertEquals(2, measured.barRatios.size)
    }

    @Test
    fun `真机 4×2_带顶栏`() {
        val grid = quotaWidgetGrid(widthDp = 350f, heightDp = 170f)
        val scale = QuotaWidgetMetrics.of(170f)
        val cellWidth = quotaCellWidthDp(350f, grid.columns, scale)
        val cells = quotaWidgetCells(demoAccounts(), grid.cells, now, captionWidthDp = cellWidth, zone = zone)
        val measured = shot("impl-widget-4x2-real.png", 350.dp, 170.dp, grid, cells)
        assertTrue(grid.showHeader)
        assertEquals(4, measured.cellBottoms.size)
        assertEquals(3, measured.barRatios.size)
        assertTrue("顶栏应渲染", measured.headerBottom != null)
    }

    private class Measured(
        val cellBottoms: List<Float>,
        val barHeights: List<Float>,
        val barRatios: List<Pair<Float, Float>>,
        val headerBottom: Float?,
        val rootHeightDp: Float,
    )

    /**
     * 渲染 → 量 → 存 PNG。
     *
     * 溢出判据：任一格底边 / 横条底边超出箱子下沿即为溢出（Glance 的 RemoteViews 会把超出的部分裁掉，
     * 桌面上表现为「最后一格没横条」而不是报错）。镜像本身不裁（Compose 默认不 clipToBounds），
     * 所以量出来的是真实排版高度，不是被箱子截断后的高度。
     */
    private fun shot(
        fileName: String,
        width: Dp,
        height: Dp,
        grid: QuotaWidgetGrid,
        cells: List<QuotaWidgetCell>,
        headerText: String = "额度 · 更新于 4 分钟前",
    ): Measured {
        // 与 QuotaWidget.kt 同一处取值：字阶只由箱子高度决定
        val scale = QuotaWidgetMetrics.of(height.value)
        compose.setContent {
            Box(modifier = Modifier.size(width, height).testTag(TAG_ROOT)) {
                QuotaContentMirror(
                    cells = cells,
                    columns = grid.columns,
                    showHeader = grid.showHeader,
                    headerText = headerText,
                    scale = scale,
                )
            }
        }
        compose.waitForIdle()

        val density = compose.density.density
        val root = compose.onNodeWithTag(TAG_ROOT).fetchSemanticsNode().boundsInRoot
        val rootHeightDp = root.height / density

        val cellBottoms = cells.indices.map { index ->
            compose.onNodeWithTag("quota.shot.cell-" + index).fetchSemanticsNode().boundsInRoot.bottom / density
        }
        val barHeights = cells.indices.mapNotNull { index ->
            runCatching {
                compose.onNodeWithTag("quota.shot.bar-" + index).fetchSemanticsNode().boundsInRoot.height / density
            }.getOrNull()
        }
        // 每格实测可用宽 / 说明文字实测宽：说明行整段省略的判据必须让它真的放得下，
        // 不能出现「省略了半截还是被裁」或「明明放得下却把时刻省掉了」两种情况
        val cellWidths = cells.indices.map { index ->
            compose.onNodeWithTag("quota.shot.cell-" + index).fetchSemanticsNode().boundsInRoot.width / density
        }
        val captionPaint = Paint().apply { typeface = Typeface.DEFAULT; isAntiAlias = true }
        val captionWidths = cells.map { cell ->
            captionPaint.textSize = scale.captionSizeSp * density
            captionPaint.measureText(cell.captionText) / density
        }
        println(
            "SHOTCAP " + fileName + " cellW=" + cellWidths.joinToString { fmt(it) } +
                " captionW=" + captionWidths.joinToString { fmt(it) } +
                " texts=" + cells.joinToString(" | ") { it.captionText },
        )
        cellWidths.indices.forEach { index ->
            assertTrue(
                "第 " + (index + 1) + " 格说明行 " + fmt(captionWidths[index]) + "dp 超出格宽 " +
                    fmt(cellWidths[index]) + "dp（会被裁）",
                captionWidths[index] <= cellWidths[index] + 0.5f,
            )
        }

        // 横条长度必须 = fraction × 轨道宽（Glance 侧是 LinearProgressIndicator(progress = fraction)）：
        // 镜像曾经把填充画成满宽，截图好看但不是真机效果 —— 这条断言就是防它再漂回去
        val barRatios = cells.mapIndexedNotNull { index, cell ->
            val fraction = cell.fraction ?: return@mapIndexedNotNull null
            val trackWidth = compose.onNodeWithTag("quota.shot.bar-" + index).fetchSemanticsNode().boundsInRoot.width
            val fillWidth = compose.onNodeWithTag("quota.shot.bar-fill-" + index).fetchSemanticsNode().boundsInRoot.width
            fraction to (fillWidth / trackWidth)
        }
        val headerBottom = if (grid.showHeader) {
            compose.onNodeWithTag(TAG_HEADER).fetchSemanticsNode().boundsInRoot.bottom / density
        } else {
            null
        }

        println(
            "SHOT " + fileName + " box=" + fmt(root.width / density) + "x" + fmt(rootHeightDp) +
                " columns=" + grid.columns + " cells=" + cells.size +
                " header=" + grid.showHeader +
                " cellBottoms=" + cellBottoms.joinToString { fmt(it) } +
                " barHeights=" + barHeights.joinToString { fmt(it) } +
                " barRatios=" + barRatios.joinToString { fmt3(it.first) + "->" + fmt3(it.second) } +
                " headerBottom=" + (headerBottom?.let { fmt(it) } ?: "-"),
        )
        cellBottoms.forEachIndexed { index, bottom ->
            assertTrue(
                "第 " + (index + 1) + " 格底边 " + fmt(bottom) + "dp 超出箱子 " + fmt(rootHeightDp) + "dp",
                bottom <= rootHeightDp + 0.5f,
            )
        }
        barHeights.forEach { barHeight ->
            assertEquals(scale.barHeight.toFloat(), barHeight, 0.6f)
        }
        barRatios.forEach { (fraction, measured) ->
            assertEquals("横条填充比例应等于 fraction", fraction, measured, 0.02f)
        }
        // 点击域：Glance 侧整块 WidgetCard 是一枚 clickable，落在这里就是整个箱子
        assertTrue(root.width / density >= 48f && rootHeightDp >= 48f)

        val bitmap = compose.onNodeWithTag(TAG_ROOT).captureToImage().asAndroidBitmap()
        val file = savePng(bitmap, fileName)
        println("SHOT saved " + file.absolutePath + " " + bitmap.width + "x" + bitmap.height)
        return Measured(cellBottoms, barHeights, barRatios, headerBottom, rootHeightDp)
    }

    private fun savePng(bitmap: Bitmap, fileName: String): File {
        val dir = File(repoRoot(), ".open-design/impl-notes/quota-redesign/shots")
        dir.mkdirs()
        val file = File(dir, fileName)
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file
    }

    /** 单测工作目录是 app/，往上找到带 .open-design 的仓库根。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, ".open-design").isDirectory) dir = dir.parentFile
        return dir ?: error("找不到仓库根（.open-design 不在任何上级目录）")
    }

    private fun fmt(value: Float): String = String.format(java.util.Locale.US, "%.1f", value)

    private fun fmt3(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)

    private companion object {
        const val TAG_ROOT = "quota.shot.root"
        const val TAG_HEADER = "quota.shot.header"
    }
}