package com.bsfeng.monitor.ui.quota

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.parity.RegisterComponentActivityRule
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 横条宽度口径：填充 = 轨道 × fraction（账号行 5dp / 展开区 6dp / 组件样张 4dp 共用同一个组件）。
 *
 * 为什么要量像素：截图里「12% 的黄条看着像 24%」这种偏差不是崩溃、也不会让文案断言变红，
 * 只能量宽度。判据 fill / track = fraction ± 0.02（0 值时没有填充节点）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuotaMeterTrackTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    @Test
    fun `填充宽度等于轨道乘剩余比例`() {
        val fractions = listOf(0.12f, 0.46f, 0.72f, 1f, 0.2f)
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                val t = LocalDesignTokens.current
                Column(Modifier.width(70.dp)) {
                    fractions.forEach { fraction ->
                        Box(Modifier.padding(vertical = 2.dp)) {
                            QuotaMeterTrack(fraction = fraction, color = t.fg2, height = 5.dp)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val density = compose.density.density
        val tracks = trackPairs(root, density)
        assertEquals("每题一根轨道", fractions.size, tracks.size)
        val rows = mutableListOf<String>()
        fractions.zip(tracks).forEach { (fraction, pair) ->
            val (track, fill) = pair
            assertEquals("轨道宽 70dp", 70f, track, 0.5f)
            assertEquals("填充 / 轨道 = fraction ± 0.02", fraction, fill / track, 0.02f)
            rows += "fraction=%.2f track=%.1fdp fill=%.1fdp ratio=%.3f".format(fraction, track, fill, fill / track)
        }
        println("METER(ratio)\n" + rows.joinToString("\n"))
    }

    @Test
    fun `纯余额型不画轨道`() {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Column { QuotaMeterTrack(fraction = null, color = LocalDesignTokens.current.fg2, height = 4.dp) }
            }
        }
        compose.waitForIdle()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("fraction = null 时不应该有轨道节点", trackPairs(root, compose.density.density).isEmpty())
    }
}

/**
 * 按语义树顺序把每根轨道与它的填充配对（轨道宽、填充宽，dp）。
 *
 * 填充是轨道的子节点；纯余额型 / 0% 没有填充 → 返回 (track, 0)。
 */
internal fun trackPairs(root: SemanticsNode, density: Float): List<Pair<Float, Float>> {
    val out = mutableListOf<Pair<Float, Float>>()
    fun fillWidth(node: SemanticsNode): Float? {
        var found: Float? = null
        for (child in node.children) {
            if (child.config.getOrNull(SemanticsProperties.TestTag) == "quota.track-fill") {
                found = child.size.width / density
                break
            }
            fillWidth(child)?.let { found = it }
            if (found != null) break
        }
        return found
    }
    fun walk(node: SemanticsNode) {
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        if (tag == "quota.track") {
            out += (node.size.width / density) to (fillWidth(node) ?: 0f)
            return
        }
        node.children.forEach { walk(it) }
    }
    walk(root)
    return out
}
