package com.bsfeng.monitor.parity

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import com.bsfeng.monitor.finance.ui.FinanceStatsDailyAvgCard
import com.bsfeng.monitor.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * s07「本月日均」卡的实现真值导出（原型 ↔ 实现 对照）。
 *
 * 视口宽度取 393dp = 原型 canvas（.open-design/src/styles/10-tokens.html:368 --screen-w:393px），
 * 与 scripts/export-proto-shots.sh 的元素截图同宽，几何可直接与 proto-screens.json 比。
 * 只组合这一张卡（业务屏 s07 需要 fake 状态与 ViewModel，见 ImplSpecExportTest 头注），
 * 所以 y 不可比、只比 x/w/h 与文案；字号 / 颜色从 TextLayoutResult 读。
 *
 * 产物（都不入库）：
 * - app/build/parity/impl-s07-dailyavg.json：与 proto-screens.json 同构，交给 scripts/parity-native.mjs
 * - app/build/parity/s07-dailyavg-card.png：卡片渲染图，与 .open-design/impl-notes/s07-proto-card.png 对照
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S07DailyAvgParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    /** 取一个 Text 节点的排版结果（字号 / 颜色 / 字重都从这里读，断言不依赖界面截图）。 */
    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val out = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()
    }

    private fun hex(color: Color?): String =
        if (color == null) "-" else String.format("#%08X", color.toArgb())

    @Test
    fun exportDailyAvgCard() {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Column(Modifier.fillMaxWidth()) {
                        FinanceStatsDailyAvgCard(monthExpense = 2468.0, elapsedDays = 14)
                    }
                }
            }
        }
        compose.waitForIdle()

        val card = compose.onNodeWithTag("s07-dailyavg").fetchSemanticsNode()
        // 卡内两处锚点被整卡的 mergeDescendants 合并，必须从 unmerged tree 取
        val amount = compose.onNodeWithTag("s07-dailyavg-amount", useUnmergedTree = true).fetchSemanticsNode()
        val note = compose.onNodeWithTag("s07-dailyavg-note", useUnmergedTree = true).fetchSemanticsNode()
        val value = compose.onNodeWithText("¥176", useUnmergedTree = true)
        val unit = compose.onNodeWithText("元/天", useUnmergedTree = true)
        val title = compose.onNodeWithText("本月日均", useUnmergedTree = true)
        val noteLayout = layoutOf(note)
        val valueLayout = layoutOf(value.fetchSemanticsNode())
        val unitLayout = layoutOf(unit.fetchSemanticsNode())
        val titleLayout = layoutOf(title.fetchSemanticsNode())

        val d = compose.density.density
        fun w(n: SemanticsNode) = n.boundsInRoot.width / d
        fun h(n: SemanticsNode) = n.boundsInRoot.height / d

        println(
            "PARITY(s07-card) card=" + w(card) + "x" + h(card) +
                " amount=" + w(amount) + "x" + h(amount) +
                " note=" + w(note) + "x" + h(note),
        )
        println(
            "PARITY(s07-card) fs value=" + valueLayout?.layoutInput?.style?.fontSize?.value +
                " unit=" + unitLayout?.layoutInput?.style?.fontSize?.value +
                " title=" + titleLayout?.layoutInput?.style?.fontSize?.value +
                " note=" + noteLayout?.layoutInput?.style?.fontSize?.value +
                " weight value=" + valueLayout?.layoutInput?.style?.fontWeight +
                " unit=" + unitLayout?.layoutInput?.style?.fontWeight,
        )
        println(
            "PARITY(s07-card) color value=" + hex(valueLayout?.layoutInput?.style?.color) +
                " unit=" + hex(unitLayout?.layoutInput?.style?.color) +
                " title=" + hex(titleLayout?.layoutInput?.style?.color) +
                " note=" + hex(noteLayout?.layoutInput?.style?.color),
        )
        println(
            "PARITY(s07-card) text value=" + valueLayout?.layoutInput?.text +
                " unit=" + unitLayout?.layoutInput?.text +
                " note=" + noteLayout?.layoutInput?.text,
        )

        // 几何：视口 393dp - 两侧 --space-4(16) = 361dp；卡内 361 - 2×(16 + 1px 边框) = 327dp。
        // 1dp 是 border-box 补偿：原型 .card 的 1px 描边占位，Compose 的 border 绘制不占位，
        // 所以 AppCard 的 padding 加了 1dp（见 FinanceStatsDailyAvgCard 里的注释）。
        assertEquals("卡片宽 = 393 - 2×16", 361f, w(card), 0.5f)
        assertEquals("卡内容宽 = 361 - 2×17", 327f, w(amount), 0.5f)
        assertEquals("口径行取满内容宽（原型的块级 .meta）", 327f, w(note), 0.5f)
        // 高度对照原型量得的 123.4 / 30.8：容差取台账的「几何每边 ≤3dp」。
        // 标题行盒已按原型 .h2 钉成 22dp（S07_DAILY_AVG_TITLE_LINE_HEIGHT），残差只来自
        // 等宽数字的自然行盒（原型 30.8px vs 实现 33dp，+2.2dp）。
        assertEquals("卡片高对照原型 123.4", 123.4f, h(card), 3f)
        assertEquals("大号数字行对照原型 30.8", 30.8f, h(amount), 3f)
        // 字号：原型 .kpi-value 28 / .kpi-unit 14 / .h2 21 / .meta 14
        assertEquals(28f, valueLayout?.layoutInput?.style?.fontSize?.value ?: 0f, 0.01f)
        assertEquals(14f, unitLayout?.layoutInput?.style?.fontSize?.value ?: 0f, 0.01f)
        assertEquals(21f, titleLayout?.layoutInput?.style?.fontSize?.value ?: 0f, 0.01f)
        assertEquals(14f, noteLayout?.layoutInput?.style?.fontSize?.value ?: 0f, 0.01f)
        // 文案与原型逐字（¥176 = 2,468 ÷ 14 天）
        assertEquals("本月累计 ¥2,468 · 已过 14 天", noteLayout?.layoutInput?.text?.text)
        assertEquals("¥176", valueLayout?.layoutInput?.text?.text)
        assertEquals("元/天", unitLayout?.layoutInput?.text?.text)

        // 导出与 proto-screens.json 同构的几何 JSON
        val nodes = collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density)
        writeScreens(mapOf("s07-card" to nodes), File("build/parity/impl-s07-dailyavg.json"))

        // 导出卡片渲染图（与原型 [data-od-id=s07-dailyavg] .card 元素截图同宽 361）
        val image = compose.onNodeWithTag("s07-dailyavg").captureToImage().asAndroidBitmap()
        val png = File("build/parity/s07-dailyavg-card.png")
        png.parentFile?.mkdirs()
        FileOutputStream(png).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PARITY(s07-card) png=" + png.absolutePath + " " + image.width + "x" + image.height)
        assertNotNull("应导出 3 个锚点", nodes.firstOrNull { it.id == "s07-dailyavg" })
    }
}