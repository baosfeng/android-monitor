package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.finance.ui.FinanceMainListHead
import com.bsfeng.monitor.finance.ui.FinanceScreenHead
import com.bsfeng.monitor.finance.ui.FinanceSubTabBar
import com.bsfeng.monitor.finance.ui.S04ImportButton
import com.bsfeng.monitor.finance.ui.S04MonthBar
import com.bsfeng.monitor.finance.ui.financeMainItems
import com.bsfeng.monitor.ui.components.AppFab
import com.bsfeng.monitor.ui.components.FiveState
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
 * s04 记账首页「实现真值」导出。
 *
 * 和 s01 同一套做法：绕过 ViewModel/Room，用 FinanceMainScreen 的真实排布
 * （页头 → 月份条 + 子 Tab → 明细标题 → financeMainItems）组合固定假数据，
 * 几何与 .open-design/parity/proto-screens.json 的 s04 直接可比。
 *
 * 假数据照原型 s04:115-175 的 8 笔：同一天（10-14）分一组，5 笔支出 3 笔收入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S04FinanceParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private fun tx(
        id: Long,
        type: Int,
        category: String,
        amount: Double,
        remark: String,
        source: String = "manual",
        date: String = "2026-10-14",
    ) =
        Transaction(
            id = id,
            type = type,
            categoryName = category,
            amount = amount,
            remark = remark,
            date = date,
            dateTime = date + " 12:30:00",
            source = source,
            accountName = "支付宝",
        )

    private fun fakeTransactions() = listOf(
        tx(1, 0, "午餐", 38.0, "公司楼下", "auto_book"),
        tx(2, 0, "公交地铁", 12.0, "早高峰通勤"),
        tx(3, 0, "超市便利", 96.0, "日用品", "auto_book"),
        tx(4, 0, "电影", 58.0, "晚场 2 张"),
        tx(5, 0, "水电燃气", 64.0, "本月水费 + 燃气"),
        tx(6, 1, "基本工资", 1200.0, "10 月工资"),
        tx(7, 1, "项目奖金", 800.0, "项目尾款结清", date = "2026-10-13"),
        tx(8, 1, "兼职收入", 1000.0, "周末兼职", date = "2026-10-12"),
    )

    @Test
    fun exportFinanceS04() {
        val list = fakeTransactions()
        val grouped = linkedMapOf(
            "2026-10-14" to list.filter { it.date == "2026-10-14" },
            "2026-10-13" to list.filter { it.date == "2026-10-13" },
            "2026-10-12" to list.filter { it.date == "2026-10-12" },
        )
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                val t = LocalDesignTokens.current
                Box(Modifier.fillMaxSize().background(Color.White).testTag("screen-s04")) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("s04-content"),
                    contentPadding = PaddingValues(top = 8.dp),
                ) {
                    item(key = "head") {
                        Box(Modifier.testTag("s04-head")) {
                            FinanceScreenHead(
                                title = "记账",
                                subtitle = "2026 年 10 月 · 共 8 笔",
                                trailing = { S04ImportButton(onClick = {}) },
                            )
                        }
                    }
                    item(key = "month") {
                        Column {
                            S04MonthBar(monthText = "2026 年 10 月", onPrevious = {}, onNext = {})
                            FinanceSubTabBar(
                                selected = 0,
                                onSelect = {},
                                modifier = Modifier.padding(horizontal = t.space4),
                            )
                        }
                    }
                    item(key = "listhead") {
                        Box(Modifier.fillMaxWidth().padding(top = t.space4).testTag("s04-pane-tx")) {
                            FinanceMainListHead(
                                currentMonth = "2026-10-14",
                                groupedIsEmpty = false,
                                recentCount = 8,
                                transactionCount = 8,
                            )
                        }
                    }
                    financeMainItems(
                        state = FiveState.POPULATED,
                        grouped = grouped,
                        highlightId = -1L,
                        tokens = t,
                        onRowClick = {},
                        onAddClick = {},
                        onRetry = {},
                    )
                }
                AppFab(
                    onClick = {},
                    contentDescription = "新增交易",
                    modifier = Modifier
                        .testTag("s04-fab")
                        .align(Alignment.BottomEnd)
                        .padding(t.space4),
                )
                }
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s04" to items), File("build/parity/impl-s04.json"))
        println("PARITY(s04) tags=" + items.size)
    }
}