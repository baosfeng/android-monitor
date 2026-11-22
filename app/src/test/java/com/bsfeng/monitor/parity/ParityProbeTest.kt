package com.bsfeng.monitor.parity

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 探针：JVM 单元测试（Robolectric）里渲染 Compose 界面并读出每个 testTag 的几何/文字排版。
 * 只取证，不做业务断言。
 *
 * 三个前提（缺一不可，均已实测）：
 * 1. RobolectricTestRunner + @Config(sdk = [34]) + createAndroidComposeRule<ComponentActivity>()；
 * 2. ActivityScenario 启动的 ComponentActivity 默认不在清单里 → 需 ui-test-manifest（debugImplementation）
 *    或在规则链外层注册进 ShadowPackageManager（本文件做法，零额外依赖）；
 * 3. @GraphicsMode(NATIVE)：不加则字体度量是假的（每个字符宽 1px、ascent/descent = 0），
 *    含文字的节点宽高会全错；加了之后 Paint.measureText / TextLayoutResult 与真机一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParityProbeTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    /** 证明 1 + 2：能组合内容，并按 testTag 拿到 px / dp 两套几何；根节点 = 整块屏幕。 */
    @Test
    fun rendersContentAndReadsGeometry() {
        val startedAt = System.nanoTime()
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Column {
                    Text(text = "A", modifier = Modifier.testTag("probe-a"))
                    Text(text = "Big", modifier = Modifier.size(width = 120.dp, height = 48.dp).testTag("probe-box"))
                    Text(text = "Hello World", modifier = Modifier.testTag("probe-long"))
                }
            }
        }
        compose.waitForIdle()

        val a = compose.onNodeWithTag("probe-a").fetchSemanticsNode()
        val box = compose.onNodeWithTag("probe-box").fetchSemanticsNode()
        val long = compose.onNodeWithTag("probe-long").fetchSemanticsNode()
        val boundsA = compose.onNodeWithTag("probe-a").getUnclippedBoundsInRoot()
        val root = compose.onRoot().fetchSemanticsNode()
        val dm = RuntimeEnvironment.getApplication().resources.displayMetrics

        println("PARITY_PROBE(geometry) display=${dm.widthPixels}x${dm.heightPixels} density=${compose.density.density} " +
            "fontScale=${compose.density.fontScale} rootPx=${root.size.width}x${root.size.height}")
        println("PARITY_PROBE(geometry) probe-a px=[${a.boundsInRoot.left},${a.boundsInRoot.top},${a.boundsInRoot.right},${a.boundsInRoot.bottom}] " +
            "sizePx=${a.size.width}x${a.size.height} dp=${(boundsA.right - boundsA.left).value}x${(boundsA.bottom - boundsA.top).value} " +
            "posInRootPx=${a.positionInRoot.x},${a.positionInRoot.y}")
        println("PARITY_PROBE(geometry) probe-box sizePx=${box.size.width}x${box.size.height} " +
            "top=${box.boundsInRoot.top} bottom=${box.boundsInRoot.bottom} " +
            "probe-long sizePx=${long.size.width}x${long.size.height}")
        println("PARITY_PROBE(geometry) elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")

        assertEquals("根节点应铺满屏幕", "${dm.widthPixels}x${dm.heightPixels}", "${root.size.width}x${root.size.height}")
        assertEquals("120dp 在 xxhdpi 下应为 360px", 360, box.size.width)
        assertTrue("字号真实后 'Hello World' 应明显宽于 'A'", long.size.width > 5 * a.size.width)
        assertEquals("probe-box 紧贴 probe-a 下方", a.size.height, box.boundsInRoot.top.toInt())
    }

    /** 证明 3：能从语义动作拿到 TextLayoutResult（行数 / 行高 / 基线 / 已解析字号 / 颜色）。 */
    @Test
    fun readsTextLayoutResultFromSemantics() {
        val startedAt = System.nanoTime()
        compose.setContent {
            MaterialTheme {
                Text(
                    text = "Hello World",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Red,
                    modifier = Modifier.testTag("probe-text"),
                )
                Text(
                    text = "ThemeColor",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("probe-theme-text"),
                )
            }
        }
        compose.waitForIdle()
        // 对照：不显式传 color、颜色来自主题（LocalContentColor / colorScheme）时，TextLayoutResult 里还剩什么
        val themeStyleColor = run {
            val layouts = mutableListOf<TextLayoutResult>()
            val themeNode = compose.onNodeWithTag("probe-theme-text").fetchSemanticsNode()
            themeNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.firstOrNull()?.layoutInput?.style?.color
        }
        println("PARITY_PROBE(text) themeColorInLayout=$themeStyleColor")

        // 颜色对照的兜底：直接抓像素（NATIVE 图形模式下应可用）
        val image = compose.onNodeWithTag("probe-text").captureToImage()
        val pixels = image.toPixelMap()
        val sampled = (0 until pixels.width).map { pixels[it, pixels.height / 2] }
        val distinct = sampled.distinct().take(4)
        println("PARITY_PROBE(text) captureToImage=${image.width}x${image.height} " +
            "midRowColors=$distinct")

        val node = compose.onNodeWithTag("probe-text").fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        }
        val layout = results.firstOrNull()
        val style = layout?.layoutInput?.style
        val fontSizePx = style?.fontSize?.let { unit -> with(compose.density) { unit.toPx() } }

        println("PARITY_PROBE(text) available=${layout != null} count=${results.size} text=${layout?.layoutInput?.text} " +
            "lines=${layout?.lineCount} sizePx=${layout?.size?.width}x${layout?.size?.height} " +
            "fontSize=${style?.fontSize?.value}sp fontSizePx=$fontSizePx color=${style?.color} " +
            "fontWeight=${style?.fontWeight} firstBaseline=${layout?.firstBaseline} " +
            "lineBottom0=${layout?.getLineBottom(0)} lineWidth0=${layout?.let { it.getLineRight(0) - it.getLineLeft(0) }}")
        println("PARITY_PROBE(text) elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")

        assertNotNull("应能取到 TextLayoutResult", layout)
        assertEquals("bodyLarge 字号应为 16sp", 16f, style?.fontSize?.value ?: 0f, 0.01f)
        assertEquals("颜色应读回 Color.Red", Color.Red, style?.color)
        assertEquals("Hello World 应单行", 1, layout?.lineCount)
        assertTrue("行宽应 > 200px", (layout!!.getLineRight(0) - layout.getLineLeft(0)) > 200f)
    }

    /** 成本探针：一次读 N 个 testTag 的几何要多久（决定对照脚本能否逐屏跑）。 */
    @Test
    fun readsManyTagsGeometryCost() {
        // 30 行 × 72px = 2160px，仍在 2673px 高的屏幕内，避免 Column 把超限子项压成 0 高
        val tags = (1..30).map { "row-$it" }
        compose.setContent {
            Column {
                tags.forEach { tag ->
                    Text(text = "row $tag", modifier = Modifier.size(width = 200.dp, height = 24.dp).testTag(tag))
                }
            }
        }
        compose.waitForIdle()

        val startedAt = System.nanoTime()
        val collected = tags.map { tag ->
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            tag to "${node.size.width}x${node.size.height}@${node.boundsInRoot.top}"
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        println("PARITY_PROBE(bulk) tags=${collected.size} elapsedMs=$elapsedMs first=${collected.first().second} " +
            "last=${collected.last().second}")
        assertEquals(30, collected.size)
        assertTrue("每次查询应拿到正尺寸：${collected}", collected.all { it.second.startsWith("600x72") })
    }

    private class RegisterComponentActivityRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app: Application = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app, ComponentActivity::class.java)
                )
                base.evaluate()
            }
        }
    }
}
