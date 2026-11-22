package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.bsfeng.monitor.finance.ui.AddTransactionActionBar
import com.bsfeng.monitor.finance.ui.AddTransactionAmountCard
import com.bsfeng.monitor.finance.ui.AddTransactionFieldCard
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

/**
 * s05「新增交易（旧整页表单）」实现真值导出。
 *
 * **本屏已被 s14a 取代**：proto-screens.json 里 s05 的 13 个可见锚点（s05-content / -amount / -kind /
 * -kind-expense / -kind-income / -cats / -date-row / -accounts / -acct-cash / -acct-wechat /
 * -acct-alipay / -actions / -save）在 scripts/anchor-retired.tsv 里**逐条**登记为
 * 「作废（旧版整页表单已被 s14a-记一笔-全抽屉取代）」，对照脚本会全部跳过；s05 今天在真机上
 * 还活着的只有五态锚点（-loading / -empty / -empty-import / -error / -retry / -del / -del-*），
 * 而它们在原型里是 visible:false，脚本同样不判。
 *
 * 所以这里**不补假 testTag**（补了就是让报告好看、真机没有的节点）：按当前真实实现组合一次
 * （s14a 的表单 + 编辑态吸底条），把实现侧真实存在的锚点落盘，证明 s05 的缺块全部来自
 * anchor-retired 登记、不是实现回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h738dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S05AddTransactionParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    @Test
    fun exportAddTransactionS05() {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                val t = LocalDesignTokens.current
                Box(Modifier.fillMaxSize().pageBackdrop()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Spacer(Modifier.height(t.space2 + t.space3))
                        AddTransactionAmountCard(
                            txType = 0,
                            amountText = "38.00",
                            onTypeChange = {},
                            onAmountChange = {},
                            isRefundEntry = false,
                        )
                        Spacer(Modifier.height(t.space3 + t.space3))
                        AddTransactionFieldCard(
                            txType = 0,
                            selectedCategory = "餐饮",
                            accountName = "支付宝",
                            toAccountName = "",
                            timeText = "今天 18:32:21",
                            remark = "公司楼下简餐",
                            onOpenSheet = {},
                            onRemarkChange = {},
                        )
                        Spacer(Modifier.height(t.space12))
                    }
                    // 编辑态：吸底条多一颗「删除这笔交易」（GhostButton 内已自带 s05-del 锚点）
                    AddTransactionActionBar(
                        canSave = true,
                        showDelete = true,
                        onSave = {},
                        onDeleteClick = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s05" to items), File("build/parity/impl-s05.json"))
        println("PARITY(s05) tags=" + items.size + " " + items.joinToString { it.id })
    }
}
