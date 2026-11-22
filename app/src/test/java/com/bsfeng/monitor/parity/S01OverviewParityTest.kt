package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.Move
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.FinanceCardState
import com.bsfeng.monitor.ui.FinanceCategorySlice
import com.bsfeng.monitor.ui.OverviewState
import com.bsfeng.monitor.ui.OverviewHead
import com.bsfeng.monitor.ui.RangeSegment
import com.bsfeng.monitor.ui.todayPanelS01
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
 * s01 概览首屏「实现真值」导出。
 *
 * 业务屏的 ViewModel 依赖 Room/DataStore，JVM 测试里不启动它们；这里按 OverviewScreen 的真实
 * 排布（顶部 8 + OverviewHead + RangeSegment + LazyColumn/todayPanelS01）用固定假数据组合，
 * 几何与原型 proto-screens.json 的 s01 直接可比。
 *
 * 视口 393×6000dp：宽度 = 原型 --screen-w，高度足够让 LazyColumn 一次组合完。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S01OverviewParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private fun fakeState() = OverviewState(
        isLoading = false,
        todayEventCount = 128,
        todaySkipCount = 34,
        todayActiveMinutes = 372,
        todayExpense = 268.0,
        todayIncome = 1200.0,
        yesterdayEventCount = 110,
        yesterdaySkipCount = 38,
        yesterdayActiveMinutes = 330,
        last24hActiveMinutes = 372,
        dailyEventCounts7 = listOf(118f, 132f, 96f, 141f, 128f, 105f, 172f),
        dailySkipCounts7 = listOf(3f, 5f, 2f, 6f, 4f, 8f, 6f),
        dailyActiveMinutes7 = listOf(7f, 11f, 9f, 14f, 12f, 10f, 16f),
        // 行程 / 最近事件 / 记账卡：原型这些区块都有锚点，缺数据会让对照报「缺块」——
        // 这不是实现缺陷，是测试数据没铺满，所以按原型同口径补齐。
        // 行程卡的时间窗是 [tripDayStart, lastUpdatedAt]，后者为 0 时窗口为空、band/source 不渲染
        tripDayStart = 0L,
        lastUpdatedAt = 4_200_000L,
        trip = DayTimeline(
            dayStart = 0L,
            segments = listOf(
                Stay(0L, 3_600_000L, Place("p0", "家", source = Basis.DEVICE)),
                Move(3_600_000L, 4_200_000L, from = null, to = null, distanceMeters = 1200.0),
            ),
            basis = Basis.DEVICE,
            coveragePct = 70,
        ),
        recentEvents = listOf(
            Record("1", RecordType.SCREEN, "", 0L, 60_000L, "微信", mapOf("package_name" to "com.tencent.mm", "app_name" to "微信"), ""),
            Record("2", RecordType.APP_USAGE, "", 0L, 120_000L, "抖音", mapOf("package_name" to "com.ss.android.ugc.aweme", "app_name" to "抖音"), ""),
            Record("3", RecordType.SCREEN, "", 0L, 30_000L, "浏览器", mapOf("package_name" to "com.android.chrome", "app_name" to "浏览器"), ""),
        ),
        financeCard = FinanceCardState(
            monthLabel = "2026 年 10 月",
            monthExpense = 2468.0,
            monthIncome = 5000.0,
            monthBalance = 2532.0,
            categorySlices = listOf(
                FinanceCategorySlice("餐饮", 686.0),
                FinanceCategorySlice("交通", 214.0),
                FinanceCategorySlice("购物", 862.0),
            ),
            dailyExpense7 = listOf(120f, 80f, 200f, 0f, 90f, 150f, 268f),
            dailyLabels7 = listOf("10-08", "10-09", "10-10", "10-11", "10-12", "10-13", "10-14"),
        ),
    )

    @Test
    fun exportOverviewS01() {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    Spacer(Modifier.height(8.dp))
                    OverviewHead(
                        dateText = "2025 年 10 月 14 日 · 星期二",
                        onBell = {},
                        onOpenTotp = {},
                    )
                    RangeSegment(
                        selectedIndex = 0,
                        onSelect = {},
                        onHaptic = {},
                        // 与 OverviewScreen 调用处一致：锚点绑在外层（含 16 内边距），不是内层分段控件
                        modifier = Modifier.testTag("s01-range").padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("s01-content"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        todayPanelS01(
                            state = fakeState(),
                            onOpenLive = {},
                            onOpenFinance = {},
                            onOpenTrip = {},
                            onOpenLiveEvent = {},
                            onHaptic = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // 必须用 unmerged tree：整卡可点（s01-trip 带 clickable）时，子锚点会被合并进父节点
        val items = collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density)
        writeScreens(mapOf("s01" to items), File("build/parity/impl-s01.json"))
        println("PARITY(s01) tags=" + items.size)
    }
}
