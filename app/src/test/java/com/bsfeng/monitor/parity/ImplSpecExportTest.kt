package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.bsfeng.monitor.ui.ComponentGalleryScreen
import com.bsfeng.monitor.ui.theme.MonitorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 实现侧锚点导出（原型 ↔ 实现 对照的「实现真值」）。
 *
 * 视口固定 390×844dp = 原型 00-brief.md 的 canvas，几何可直接与 proto-screens.json 比。
 * 输出 app/build/parity/impl-screens.json，交给 scripts/parity-native.mjs。
 *
 * 目前只接通无数据依赖的屏（组件与状态 s12）作为样板；业务屏需要 fake 状态，逐屏追加。
 */
@RunWith(RobolectricTestRunner::class)
// 视口高度给到 4200dp：长页（s12 约 3900dp）一次组合完，避免 LazyColumn 回收导致导出不稳定。
// 宽度仍是 390dp = 原型 canvas，几何可直接对照。
@Config(sdk = [34], qualifiers = "w390dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImplSpecExportTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    @Test
    fun exportComponentGallery() {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                ComponentGalleryScreen(onBack = {})
            }
        }
        compose.waitForIdle()
        val items = collectTaggedNodes(compose.onRoot().fetchSemanticsNode(), compose.density)
        writeScreens(mapOf("s12" to items), File("build/parity/impl-screens.json"))
        println("PARITY(s12) tags=" + items.size + " " + items.take(5).joinToString { it.id })
    }
}
