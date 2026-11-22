package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.bsfeng.monitor.finance.CategoryAmount
import com.bsfeng.monitor.finance.FinancePeriod
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.PeriodBucket
import com.bsfeng.monitor.finance.model.PeriodStats
import com.bsfeng.monitor.finance.ui.FinanceMonthBar
import com.bsfeng.monitor.finance.ui.FinanceStatsDailyAvgCard
import com.bsfeng.monitor.finance.ui.FinanceStatsHeatCard
import com.bsfeng.monitor.finance.ui.FinanceStatsKpiRow
import com.bsfeng.monitor.finance.ui.FinanceStatsRangeCard
import com.bsfeng.monitor.finance.ui.FinanceStatsRingCard
import com.bsfeng.monitor.finance.ui.FinanceStatsTopCategoriesCard
import com.bsfeng.monitor.finance.ui.FinanceStatsWeekCard
import com.bsfeng.monitor.finance.ui.FinanceStatsYearTrendCard
import com.bsfeng.monitor.finance.ui.hourlyPeaks
import com.bsfeng.monitor.finance.ui.monthLabelOf
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
import java.time.LocalDate

/**
 * s07 统计分析「实现真值」导出（屏级，12 个原型可见锚点）。
 *
 * 与 S01/S04 同一套做法：绕过 FinanceViewModel/Room，按 FinanceStatsScreen 的真实排布
 * （月份条 → 副标题 → KPI 三格 → 本月日均 → 时间范围 → 年度趋势 → 年度 Top → 分类环 → 近 7 天 → 热力）
 * 用原型同一份数据组合，几何与 .open-design/parity/proto-screens.json 的 s07 直接可比。
 *
 * 页头（返回 + 「统计分析」）不参与组合：原型把标题放在外壳 appbar 里，不在 .screen 内，
 * 它的 y 真值从内容区顶端起算（含 .content 的 padding-top 8）。组合进页头会让所有锚点整体下移。
 *
 * 假数据照原型 s07-统计分析.html:60-196：支出 2468 / 收入 5000 / 6 个分类 / 近 7 天 732+5000 / 热力峰值 12 点 9 笔。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S07StatsParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private val month = "2026-10"

    /** 原型 s07 的 6 个分类（686/214/862/328/268/110，合计 2468）。 */
    private val breakdown = linkedMapOf(
        "餐饮" to 686.0,
        "交通" to 214.0,
        "购物" to 862.0,
        "娱乐" to 328.0,
        "居住" to 268.0,
        "医疗" to 110.0,
    )

    private val stats = FinanceStats(totalIncome = 5000.0, totalExpense = 2468.0, transactionCount = 24)

    /** 原型 s07:152 的 7 组柱（10-08…10-14），支出合计 732、收入合计 5000。 */
    private fun week(): List<Triple<Float, Float, String>> = listOf(
        Triple(132f, 900f, "2026-10-08"),
        Triple(86f, 0f, "2026-10-09"),
        Triple(0f, 1100f, "2026-10-10"),
        Triple(246f, 0f, "2026-10-11"),
        Triple(0f, 1000f, "2026-10-12"),
        Triple(0f, 800f, "2026-10-13"),
        Triple(268f, 1200f, "2026-10-14"),
    )

    /**
     * 原型 s07:187 的 24 个小时笔数。原文峰值写在 12:00（9 笔）与 19:00（8 笔），
     * 但 data-chart-values 里第 19 格才是 8 —— 实现按 peaks 算文案，所以这里把 8 放在 19 点，
     * 让「峰值出现在 12:00（9 笔）与 19:00（8 笔）」与原型说明行逐字一致。
     */
    private fun hourly(): List<Float> = MutableList(24) { 0f }.also {
        it[7] = 1f; it[8] = 3f; it[9] = 2f; it[10] = 1f; it[11] = 4f
        it[12] = 9f; it[13] = 6f; it[14] = 2f; it[15] = 1f; it[16] = 3f
        it[17] = 7f; it[18] = 5f; it[19] = 8f; it[20] = 2f; it[21] = 1f
    }

    /** 年度聚合：支出 18,420 / 收入 32,000，12 个月桶按原型趋势线形状铺（值不参与文案判据）。 */
    private fun yearStats(): PeriodStats = PeriodStats(
        unitKey = "year",
        unitLabel = "本年",
        fromDate = "2026-01-01",
        toDate = "2026-12-31",
        totalExpense = 18420.0,
        totalIncome = 32000.0,
        transactionCount = 132,
        buckets = (1..12).map { PeriodBucket(label = it.toString(), expense = 1200.0, income = 2600.0) },
    )

    private fun yearTop(): List<CategoryAmount> = listOf(
        CategoryAmount("购物", 4862.0),
        CategoryAmount("餐饮", 3214.0),
        CategoryAmount("交通", 1532.0),
        CategoryAmount("娱乐", 1248.0),
        CategoryAmount("居住", 2868.0),
    )

    @Test
    fun exportStatsS07() {
        val weekData = week()
        val hourlyData = hourly()
        val sortedEntries = breakdown.entries.sortedByDescending { it.value }
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                val t = LocalDesignTokens.current
                Box(Modifier.fillMaxSize().background(Color.White).testTag("screen-s07")) {
                    // 原型 .content：padding 8 0 24 0（20-base.html:162-166）
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("s07-content"),
                        contentPadding = PaddingValues(top = t.space2, bottom = t.space6),
                    ) {
                        item(key = "month") {
                            Column {
                                // 原型 .pad 无纵向内边距：月份条紧贴内容区顶端的 8dp
                                FinanceMonthBar(
                                    monthText = monthLabelOf(month),
                                    onPrevious = {},
                                    onNext = {},
                                    anchorTag = "s07-monthbar",
                                    previousModifier = Modifier.testTag("s07-prev"),
                                    nextModifier = Modifier.testTag("s07-next"),
                                )
                                // 原型 p.meta.mt-3：副标题在月份条下 12dp（s07-统计分析.html:55）
                                Text(
                                    monthLabelOf(month) + " · 共 " + breakdown.size + " 个分类",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = t.meta,
                                    modifier = Modifier
                                        .padding(horizontal = t.space4)
                                        .padding(top = t.space3),
                                )
                            }
                        }
                        item(key = "kpis") {
                            // 原型 .pad.mt-2：KPI 三格与副标题之间 8dp
                            Spacer(Modifier.height(t.space2))
                            FinanceStatsKpiRow(stats)
                        }
                        item(key = "dailyavg") {
                            FinanceStatsDailyAvgCard(monthExpense = stats.totalExpense, elapsedDays = 14)
                        }
                        item(key = "range") {
                            FinanceStatsRangeCard(
                                selected = FinancePeriod.MONTH,
                                onSelect = {},
                                selectedExpense = stats.totalExpense,
                                yearExpense = 18420.0,
                                averageAnchor = LocalDate.of(2026, 10, 14),
                            )
                        }
                        item(key = "yeartrend") {
                            FinanceStatsYearTrendCard(yearStats = yearStats())
                        }
                        item(key = "topcats") {
                            FinanceStatsTopCategoriesCard(categories = yearTop())
                        }
                        item(key = "ring") {
                            FinanceStatsRingCard(stats = stats, sortedEntries = sortedEntries)
                        }
                        item(key = "week") {
                            FinanceStatsWeekCard(
                                week = weekData,
                                weekExpense = weekData.sumOf { it.first.toDouble() },
                                weekIncome = weekData.sumOf { it.second.toDouble() },
                            )
                        }
                        item(key = "heat") {
                            FinanceStatsHeatCard(hourly = hourlyData, peak = hourlyPeaks(hourlyData))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // 整卡可点 / mergeDescendants 的锚点（s07-kpicard、s07-dailyavg）在 unmerged 树里文案为空，
        // 必须用 merged 树补齐，否则文案判据会静默跳过（假绿）。
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s07" to items), File("build/parity/impl-s07.json"))
        println("PARITY(s07) tags=" + items.size + " " + items.joinToString { it.id })
    }
}