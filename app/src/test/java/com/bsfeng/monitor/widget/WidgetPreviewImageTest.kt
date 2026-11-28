package com.bsfeng.monitor.widget

// 生成 6 个组件的预览图（appwidget-provider 的 android:previewImage）。
//
// 为什么要单测来出图：预览图必须是「没有真实数据的一张静态图」，而 Glance 的 RemoteViews
// 只能在真机 launcher 上渲染。这里用 WidgetPreviewSkin / WidgetPreviewScenes 的同构镜像，
// 在 Robolectric NATIVE 图形下画一遍、存成 PNG 落进 res/drawable-nodpi，再被 6 个 info 引用。
//
// 重生成命令（改完组件视觉后必须跑一次，否则预览图与真机不一致）：
//   ./gradlew :app:testDebugUnitTest --tests "*WidgetPreviewImageTest*"

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.parity.RegisterComponentActivityRule
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

/**
 * 6 张预览图的出图与落盘。
 *
 * 尺寸取组件声明的目标占格（4×2 → 350×170dp、2×1 → 170×110dp、1×1 → 110×110dp），
 * 密度取 xhdpi（2x）—— 预览图只是样张，再高的分辨率换不来更好的列表观感，只会让 APK 变大。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPreviewImageTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    @Test
    fun `记账 4×2 预览图`() {
        // 190dp 而不是 170dp：Wide 档「顶栏 + 40sp 金额 + 进度条 + 底栏两块」实测需要 189dp，
        // 170dp 会把底栏的数值裁掉（预览图只剩「收入 / 预算剩余」两个标签）。真机 4×2 实测 177dp，
        // 这个缺口属于既存的真机适配问题，不在预览图里掩盖。
        render("widget_finance_preview", 350.dp, 190.dp) { FinancePreviewScene() }
    }

    @Test
    fun `额度 4×2 预览图`() {
        render("widget_quota_preview", 350.dp, 170.dp) { QuotaPreviewScene() }
    }

    @Test
    fun `监控健康 2×1 预览图`() {
        render("widget_health_preview", 170.dp, 110.dp) { HealthPreviewScene() }
    }

    @Test
    fun `跳过战报 2×1 预览图`() {
        render("widget_adskip_preview", 170.dp, 110.dp) { AdSkipPreviewScene() }
    }

    @Test
    fun `传送状态 2×1 预览图`() {
        render("widget_transfer_preview", 170.dp, 110.dp) { TransferPreviewScene() }
    }

    @Test
    fun `快捷记一笔 1×1 预览图`() {
        render("widget_quickadd_preview", 110.dp, 110.dp) { QuickAddPreviewScene() }
    }

    private fun render(name: String, width: Dp, height: Dp, content: @Composable () -> Unit) {
        compose.setContent {
            Box(modifier = Modifier.size(width, height).testTag(TAG_ROOT)) { content() }
        }
        compose.waitForIdle()

        val captured = compose.onNodeWithTag(TAG_ROOT).captureToImage().asAndroidBitmap()
        val density = compose.density.density
        // 圆角外必须透明：capture 到的根背景是 Activity 的默认底色，
        // 不挖掉的话预览图在深色壁纸上会带一圈浅灰方角。
        val rounded = roundCorners(captured, (PREVIEW_CARD_RADIUS.value * density))
        val dir = nodpiDir()
        dir.mkdirs()
        val file = File(dir, name + ".png")
        file.outputStream().use { out -> rounded.compress(Bitmap.CompressFormat.PNG, 100, out) }
        println("PREVIEW " + name + " -> " + file.absolutePath + " " + rounded.width + "x" + rounded.height)
        assertTrue("预览图应落在 res/drawable-nodpi", file.isFile && file.length() > 0L)
    }

    /** 按 [radius] 圆角裁一次，路径外像素保持透明。 */
    private fun roundCorners(source: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
                radius,
                radius,
                Path.Direction.CW,
            )
        }
        canvas.clipPath(path)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    /** 单测工作目录是 app/，往上找到带 app/src/main/res 的仓库根。 */
    private fun nodpiDir(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/res").isDirectory) dir = dir.parentFile
        return File(dir ?: error("找不到仓库根（app/src/main/res 不在任何上级目录）"), "app/src/main/res/drawable-nodpi")
    }

    private companion object {
        const val TAG_ROOT = "widget.preview.root"
    }
}
