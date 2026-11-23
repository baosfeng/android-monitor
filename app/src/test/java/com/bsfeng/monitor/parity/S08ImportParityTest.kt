package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.bsfeng.monitor.finance.ui.ImportScreen
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
 * s08 数据导入「实现真值」导出。
 *
 * 和 S01/S04 同一套做法：不启 ViewModel（[ImportScreen] 的依赖全由参数注入），
 * 用默认态（未选文件、无结果）组合真实屏 —— 正是原型 s08 populated 态的形状：
 * 顶部「完成 / 导入数据」栏 → 三步说明 → 选择文件卡 → 开始导入按钮。
 *
 * 原型 s08 只有 3 个可见锚点：s08-content（滚动容器，只判存在）/ s08-drop / s08-go；
 * 其余（file / progress / result / failrows / fail / loading / empty / error / edge）
 * 在 proto-screens.json 里 visible=false，是别的态，不参与判定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S08ImportParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    @Test
    fun exportImportS08() {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("screen-s08")) {
                    ImportScreen(
                        importing = false,
                        importResult = null,
                        billImportResult = null,
                        onImport = {},
                        onDismiss = {},
                        onClearResult = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s08" to items), File("build/parity/impl-s08.json"))
        println("PARITY(s08) tags=" + items.size + " " + items.joinToString(",") { it.id })
    }
}
