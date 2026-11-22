package com.bsfeng.monitor.parity

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.finance.ui.AddTransactionActionBar
import com.bsfeng.monitor.finance.ui.AddTransactionAmountCard
import com.bsfeng.monitor.finance.ui.AddTransactionFieldCard
import com.bsfeng.monitor.finance.ui.AddTransactionReceiptCard
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
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
import java.io.FileOutputStream

/**
 * s14a「记一笔 · 全抽屉」实现真值导出。
 *
 * 与 S01/S04/S16 同一套做法：不启动 FinanceViewModel（它读 finance.db），
 * 按 [com.bsfeng.monitor.finance.ui.AddTransactionScreen] 的真实排布组合固定假数据。
 *
 * 视口取 393×738 = 原型 .screen 的 box（proto-screens.json 的 s14a.box）：本屏「一屏不放滚」，
 * 吸底保存条必须落在屏底，y 才与原型可比（长页那套 h6000 会把保存条推到 5920）。
 *
 * 顶栏画在 screen-s14a **之外**：原型里它是外壳 .appbar（20-base.html:89，位于 .screen 之上），
 * 锚点量到的坐标相对 .screen；放进锚点会让整屏 y 平移一个顶栏高。
 *
 * 假数据照原型 s14a-记一笔-全抽屉.html:95-148：支出 23.90 / 分类 餐饮 / 账户 支付宝 /
 * 时间 今天 18:32:21 / 单据「淘宝闪购 · 招商银行信用卡(8888)」。
 *
 * 保存条只放「保存」：原型 s14a 的 .s14a-actions 里就这一颗（「删除这笔交易」属旧整页表单 s05），
 * 高度才对得上 12+48+20 = 80。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h738dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S14aAddTransactionParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    /** 编辑一笔自动记账交易：单据卡（s14a-receipt）只有带结构化字段时才出现。 */
    private fun editingWithReceipt() = Transaction(
        id = 7,
        type = 0,
        categoryName = "餐饮",
        amount = 23.90,
        accountName = "支付宝",
        remark = "",
        date = "2026-10-14",
        dateTime = "2026-10-14 18:32:21",
        source = "auto_book",
        merchant = "淘宝闪购",
        productDesc = "某某湘菜馆(示例店)外卖订单",
        payMethod = "招商银行信用卡(8888)",
        tradeTime = "2026-10-16 18:32:21",
        orderId = "CFP000000000000000000",
        platformCategory = "餐饮美食",
    )

    @Test
    fun exportAddTransactionS14a() {
        val editing = editingWithReceipt()
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                val t = LocalDesignTokens.current
                // 不画顶栏：原型把它放在 .screen 之外的 .appbar（20-base.html:89），s14a 的锚点坐标
                // 全部相对 .screen；实现里顶栏在屏幕内部，画进来会让 15 个锚点整体下移一个顶栏高。
                Box(Modifier.fillMaxSize().pageBackdrop().testTag("screen-s14a")) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .testTag("s14a-content"),
                    ) {
                        // .content 的 padding-top 8 + 首块 .pad 的 mt-3 12
                        Spacer(Modifier.height(t.space2 + t.space3))
                        AddTransactionAmountCard(
                            txType = 0,
                            amountText = "23.90",
                            onTypeChange = {},
                            onAmountChange = {},
                            isRefundEntry = false,
                        )
                        // 块间距 = .content gap 12 + .pad mt-3 12
                        Spacer(Modifier.height(t.space3 + t.space3))
                        AddTransactionFieldCard(
                            txType = 0,
                            selectedCategory = "餐饮",
                            accountName = "支付宝",
                            toAccountName = "",
                            timeText = "今天 18:32:21",
                            remark = "",
                            onOpenSheet = {},
                            onRemarkChange = {},
                        )
                        Spacer(Modifier.height(t.space3 + t.space3))
                        AddTransactionReceiptCard(editing = editing, onOpenSheet = {})
                        Spacer(Modifier.height(t.space12))
                    }
                    // 吸底保存条（原型 .s14a-actions：只有「保存」）
                    AddTransactionActionBar(
                        canSave = true,
                        showDelete = false,
                        onSave = {},
                        onDeleteClick = {},
                        modifier = Modifier.align(Alignment.BottomCenter).testTag("s14a-actions"),
                    )
                }
            }
        }
        compose.waitForIdle()
        // 字段行 / 保存条整行可点（clickable + mergeDescendants）：文案只在 merged 树里
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s14a" to items), File("build/parity/impl-s14a.json"))
        // 渲染图：几何与原型对平后，还要人眼确认「卡片没溢出、金额没被裁」
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val png = File("build/parity/s14a-impl.png")
        png.parentFile?.mkdirs()
        FileOutputStream(png).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PARITY(s14a) png=" + png.absolutePath + " " + image.width + "x" + image.height)
        println("PARITY(s14a) tags=" + items.size + " " + items.joinToString { it.id })
    }
}
