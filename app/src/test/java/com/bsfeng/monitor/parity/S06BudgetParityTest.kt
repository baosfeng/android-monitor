package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.ui.BudgetCategoryRowsSection
import com.bsfeng.monitor.finance.ui.BudgetKpiSection
import com.bsfeng.monitor.finance.ui.FinanceMonthBar
import com.bsfeng.monitor.finance.ui.monthLabelOf
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
 * s06 预算设置「实现真值」导出（屏级，13 个原型可见锚点）。
 *
 * 与 S04 同一套做法：绕过 FinanceViewModel/Room，按 BudgetScreen 的真实排布
 * （月份条 → 总预算卡 + 圆环 → 分类预算列表）用原型同一份数据组合。
 *
 * 页头（返回 + 「预算设置」）不参与组合：原型把标题放在外壳 appbar 里，不在 .screen 内，
 * 原型锚点的 y 从内容区顶端起算（含 .content 的 padding-top 8）。
 *
 * 假数据照原型 s06-预算设置.html:64-116：6 个分类 1200/400/1000/500/700/200（合计 4000），
 * 已支出 686/214/862/328/268/110（合计 2468），剩余 1532，使用率 62%。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S06BudgetParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private val month = "2026-10"

    /** 原型 6 行：分类名 → (预算, 已支出)。 */
    private val rowsData = listOf(
        Triple("餐饮", 1200.0, 686.0),
        Triple("交通", 400.0, 214.0),
        Triple("购物", 1000.0, 862.0),
        Triple("娱乐", 500.0, 328.0),
        Triple("居住", 700.0, 268.0),
        Triple("医疗", 200.0, 110.0),
    )

    private fun categories(): List<Category> = rowsData.mapIndexed { index, row ->
        Category(id = (index + 1).toLong(), name = row.first, type = 0, parentId = -1, sortOrder = index)
    }

    private fun budgets(): Map<String, Budget> = rowsData.associate { row ->
        row.first to Budget(
            month = month,
            categoryName = row.first,
            budgetAmount = row.second,
            spentAmount = row.third,
        )
    }

    @Test
    fun exportBudgetS06() {
        val total = rowsData.sumOf { it.second }
        val spent = rowsData.sumOf { it.third }
        val rows = categories()
        val byCategory = budgets()
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("screen-s06")) {
                    // 原型 .content：padding 8 0 24 0（20-base.html:162-166）
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("s06-content"),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    ) {
                        item(key = "month") {
                            FinanceMonthBar(
                                monthText = monthLabelOf(month),
                                onPrevious = {},
                                onNext = {},
                                anchorTag = "s06-monthbar",
                                previousModifier = Modifier.testTag("s06-prev"),
                                nextModifier = Modifier.testTag("s06-next"),
                            )
                        }
                        item(key = "card") {
                            // 原型 .pad.mt-3：卡片与月份条之间 12（外边距）
                            Column {
                                Spacer(Modifier.height(12.dp))
                                BudgetKpiSection(
                                    totalBudget = total,
                                    spentTotal = spent,
                                    remaining = total - spent,
                                    fraction = (spent / total).toFloat(),
                                    onEditTotal = {},
                                    modifier = Modifier.testTag("s06-card"),
                                )
                            }
                        }
                        item(key = "list") {
                            // 原型 .pad.mt-4：列表与 KPI 卡之间 16
                            Column {
                                Spacer(Modifier.height(16.dp))
                                BudgetCategoryRowsSection(
                                    rows = rows,
                                    budgetByCategory = byCategory,
                                    onEditCategory = { _, _ -> },
                                    modifier = Modifier.testTag("s06-list"),
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // 分类行整行可点（clickable + mergeDescendants）：锚点文案只在 merged 树里，必须补齐
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s06" to items), File("build/parity/impl-s06.json"))
        println("PARITY(s06) tags=" + items.size + " " + items.joinToString { it.id })
    }
}
