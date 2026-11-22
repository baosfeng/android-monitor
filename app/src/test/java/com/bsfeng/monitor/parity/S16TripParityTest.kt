package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.Gap
import com.bsfeng.monitor.domain.trip.GapReason
import com.bsfeng.monitor.domain.trip.Move
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.domain.trip.TRIP_DAY_MS
import com.bsfeng.monitor.ui.ScreenHead
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorTheme
import com.bsfeng.monitor.ui.trip.TripDayBar
import com.bsfeng.monitor.ui.trip.TripLocationHint
import com.bsfeng.monitor.ui.trip.TripRulesCard
import com.bsfeng.monitor.ui.trip.TripSummaryCard
import com.bsfeng.monitor.ui.trip.TripTimelineCard
import com.bsfeng.monitor.ui.trip.TripDays
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
import java.time.ZoneId

/**
 * s16 每日行程「实现真值」导出。
 *
 * 与 S01/S04 同一套做法：绕过 TripViewModel（依赖 Room / MonitorApp 容器），按 TripScreen 的真实排布
 * （页头 → 日期条 → 今日结论 → 时间轴 → 口径说明 → 定位提示）用原型同一份 9 段数据组合。
 *
 * 原型锚点里 privacy-card / privacy-badge / privacy-label / privacy-switch / privacy-cap /
 * clicklog-row / clicklog-switch 属「待实现」（scripts/anchor-retired.tsv：产品无点击日志能力、
 * 临时预览档位无对应功能），实现侧**不渲染**，对照会报 P0 缺块 —— 那是产品口径，不是实现缺陷。
 *
 * 假数据照原型 s16-每日行程.html:285-386：9 段（家 00:00–09:10 / 移动 / 咖啡 45 分 / 移动 / 公司 25 分 /
 * 移动 / 家 11:20–14:20 / 未记录 40 分 / 家 15:00–24:00）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S16TripParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 原型页头的日期「2025 年 10 月 14 日 · 星期二」。 */
    private fun dayStart(): Long =
        LocalDate.of(2025, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun min(base: Long, m: Int): Long = base + m * 60_000L

    private fun fakeTimeline(): DayTimeline {
        val d = dayStart()
        val home = Place("p-home", "家", source = Basis.DEVICE)
        val cafe = Place("p-cafe", "某某咖啡", source = Basis.DEVICE)
        val office = Place("p-office", "公司", source = Basis.DEVICE)
        return DayTimeline(
            dayStart = d,
            segments = listOf(
                Stay(min(d, 0), min(d, 550), home),
                Move(min(d, 550), min(d, 570), from = home, to = cafe, distanceMeters = 1200.0),
                Stay(min(d, 570), min(d, 615), cafe),
                Move(min(d, 615), min(d, 635), from = cafe, to = office, distanceMeters = 1600.0),
                Stay(min(d, 635), min(d, 660), office),
                Move(min(d, 660), min(d, 680), from = office, to = home, distanceMeters = 2000.0),
                Stay(min(d, 680), min(d, 860), home),
                Gap(min(d, 860), min(d, 900), reason = GapReason.INTERRUPTED),
                Stay(min(d, 900), d + TRIP_DAY_MS, home),
            ),
            basis = Basis.DEVICE,
            coveragePct = 82,
        )
    }

    @Test
    fun exportTripS16() {
        val timeline = fakeTimeline()
        val now = min(dayStart(), 24 * 60 - 1)
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                val t = LocalDesignTokens.current
                Box(Modifier.fillMaxSize().background(Color.White).testTag("screen-s16")) {
                    Column(Modifier.fillMaxSize()) {
                        // 原型 .content 的 padding-top 8：锚点 y 从内容区顶端 8 起算
                        Spacer(Modifier.height(8.dp))
                        // 页头常驻：ScreenHead（返回 + 标题）+ 日期条（前后一天）
                        Column(Modifier.fillMaxWidth().testTag("s16-head")) {
                            ScreenHead(title = "每日行程", onBack = {})
                            TripDayBar(
                                dayStart = dayStart(),
                                now = now,
                                onPrevious = {},
                                onNext = {},
                            )
                        }
                        // 锚点盒 = 原型 .pad（宽 393、含左右 16）：水平内边距必须放在锚点的**外层**，
                        // 所以 LazyColumn 不再给 contentPadding，改为每个 item 外面包一层带 tag 的 Box。
                        LazyColumn(
                            Modifier.fillMaxSize().testTag("s16-content"),
                            contentPadding = PaddingValues(bottom = t.space6),
                        ) {
                            item(key = "summary") {
                                Box(Modifier.fillMaxWidth().testTag("s16-summary").padding(horizontal = t.space4)) {
                                    TripSummaryCard(timeline = timeline)
                                }
                            }
                            item(key = "timeline") {
                                Box(Modifier.fillMaxWidth().testTag("s16-timeline").padding(horizontal = t.space4)) {
                                    TripTimelineCard(
                                        timeline = timeline,
                                        dayStart = dayStart(),
                                        onSelect = { _, _ -> },
                                    )
                                }
                            }
                            item(key = "rules") {
                                Box(Modifier.fillMaxWidth().testTag("s16-rules").padding(horizontal = t.space4)) {
                                    TripRulesCard(basis = timeline.basis)
                                }
                            }
                            item(key = "goto") {
                                Box(Modifier.fillMaxWidth().padding(horizontal = t.space4)) {
                                    TripLocationHint(onClick = {})
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // 停留段整行可点（clickable + mergeDescendants）：文案只在 merged 树里
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s16" to items), File("build/parity/impl-s16.json"))
        println("PARITY(s16) tags=" + items.size + " " + items.joinToString { it.id })
    }
}