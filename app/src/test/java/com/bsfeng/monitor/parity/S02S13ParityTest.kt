package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.AppearanceScreen
import com.bsfeng.monitor.ui.EnterWindow
import com.bsfeng.monitor.ui.LiveEventTimeline
import com.bsfeng.monitor.ui.LiveFilterChips
import com.bsfeng.monitor.ui.LiveIntensityPanel
import com.bsfeng.monitor.ui.LivePageHead
import com.bsfeng.monitor.ui.live.LiveFilter
import com.bsfeng.monitor.ui.live.HourGroup
import com.bsfeng.monitor.ui.live.hourLabel
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
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * s02 实时事件流 / s13 外观 的「实现真值」导出。
 *
 * 和 S01/S04 同一套做法：绕过 ViewModel（Room / DataStore 在 JVM 测试里不起），
 * 用真实屏的排布（页头 → 类型 chip → 热力条 → 时间线 / 页头 + 主题选择器 + 预览 + 说明）
 * 组合固定假数据，几何与原型 proto-screens.json 对应屏直接可比。
 *
 * 视口 393×6000dp：宽度 = 原型 --screen-w，高度足够让 LazyColumn 一次组合完。
 * 假数据照原型 s02 的五个小时组（09/08/07 三组可见，06/05 在原型里也不可见）与 s13 的 8 套主题铺满，
 * 缺块报错 = 测试数据没铺满，不是实现缺陷。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S02S13ParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    /** 原型 s02 页头「最近更新 09:41」用的时刻（同 S01 的固定日期，避免跨天漂移）。 */
    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 14, hour, minute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun rec(
        id: String,
        type: RecordType,
        action: String,
        hour: Int,
        minute: Int,
        label: String,
        duration: Long? = null,
        detail: Map<String, String>? = null,
    ) = Record(id, type, action, at(hour, minute), duration, label, detail, "")

    /**
     * 原型 s02 的 15 条事件（09:41 → 05:12）：文案逐字取自原型行标题，
     * 页面轨迹 / 通知事件各一条（锚点 s02-ev-page / s02-ev-notification）。
     */
    private fun fakeEvents(): List<Record> = listOf(
        rec("e1", RecordType.APP_USAGE, "FOREGROUND", 9, 41, "微信", 12 * 60_000L,
            mapOf("package_name" to "com.tencent.mm", "app_name" to "微信")),
        // 页面轨迹行在原型里用「微」应用图标：真实记录也带 package_name（见 LiveRows 的分流注释）
        rec("e2", RecordType.SCREEN, "PAGE", 9, 38, "微信 › 朋友圈",
            detail = mapOf(
                "page_title" to "朋友圈",
                "page_fingerprint" to "a3f2",
                "package_name" to "com.tencent.mm",
                "app_name" to "微信",
            )),
        rec("e3", RecordType.SCREEN, "UNLOCKED", 9, 26, "屏幕点亮 · 解锁进入桌面"),
        rec("e4", RecordType.WIFI, "CONNECTED", 9, 12, "Wi-Fi 连接", detail = mapOf("ssid" to "Home-5G")),
        rec("e5", RecordType.AUDIO, "PLAYING", 9, 3, "网易云音乐 · 开始播放「岛屿」"),
        rec("e6", RecordType.CHARGING, "CHARGING", 8, 52, "充电中 · 电量 87%"),
        rec("e7", RecordType.PACKAGE, "PACKAGE_ADDED", 8, 34, "安装完成 · 高德地图 13.2.1"),
        rec("e8", RecordType.SCREEN, "NOTIFICATION", 8, 44, "通知摘要 · 即时通讯",
            detail = mapOf("channel" to "即时通讯频道")),
        rec("e9", RecordType.APP_USAGE, "FOREGROUND", 8, 11, "微信", 23 * 60_000L + 18_000L,
            mapOf("package_name" to "com.tencent.mm", "app_name" to "微信")),
        rec("e10", RecordType.LOCATION, "LOCATION", 8, 2, "定位请求 · 高德地图 后台"),
        rec("e11", RecordType.SCREEN, "UNLOCKED", 7, 46, "屏幕点亮 · 闹钟关闭"),
        rec("e12", RecordType.LOCATION, "LOCATION", 7, 31, "定位请求 · 墨迹天气 前台"),
        rec("e13", RecordType.APP_USAGE, "FOREGROUND", 6, 58, "小红书", 6 * 60_000L + 12_000L,
            mapOf("package_name" to "com.xingin.xhs", "app_name" to "小红书")),
        rec("e14", RecordType.WIFI, "DISCONNECTED", 6, 20, "Wi-Fi 断开 · 切换到 5G"),
        rec("e15", RecordType.CHARGING, "CHARGING", 5, 12, "充电完成 · 电量 100%"),
    )

    /**
     * 原型页面上**当前可见**的三组（09 / 08 / 07）：06 与 05 组在原型里折在
     * 「加载更早的事件（还有 118 条）」之后（#s02-more 未勾选时 display:none），
     * 对应锚点 s02-g06 / s02-g05 在 proto-screens.json 里 visible=false。
     *
     * 这里按同一份可见数据铺，否则 LazyColumn 会多组合两组、把 s02-timeline 的高度
     * 撑到原型的 6 倍（那是视口口径差，不是实现缺陷）。
     */
    private fun visibleEvents(): List<Record> = fakeEvents().filter { hourOf(it) in 7..9 }

    private fun hourOf(record: Record): Int =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(record.timestamp), ZoneId.systemDefault()).hour

    /** 原型 s02 热力条的 24 个逐时值（data-chart-values）。 */
    private val protoIntensity = listOf(
        0f, 0f, 0f, 0f, 0f, 0f, 6f, 18f, 34f, 54f, 33f, 22f,
        38f, 27f, 15f, 20f, 31f, 25f, 14f, 10f, 7f, 11f, 5f, 2f,
    )

    @Test
    fun exportLiveS02() {
        // 分组的**行序按原型**（08 组是 08:52 / 08:34 / 08:44 / 08:11 / 08:02 —— 原型刻意把通知行
        // 放在第 3 位，不是严格按时间倒序）；组仍按小时倒序。用固定顺序构造 HourGroup，
        // 不走 groupByHourDesc 的二次排序，才能与原型逐行对齐。
        val groups = listOf(
            HourGroup(9, hourLabel(9), visibleEvents().filter { hourOf(it) == 9 }),
            HourGroup(8, hourLabel(8), visibleEvents().filter { hourOf(it) == 8 }),
            HourGroup(7, hourLabel(7), visibleEvents().filter { hourOf(it) == 7 }),
        )
        val requesters = mutableMapOf<String, FocusRequester>()
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                val t = LocalDesignTokens.current
                Box(Modifier.fillMaxSize().background(Color.White).testTag("screen-s02")) {
                    Column(Modifier.fillMaxSize()) {
                        // 原型 .content 的上内边距 --space-2（页头 y = 8）
                        Spacer(Modifier.height(t.space2))
                        Column(Modifier.fillMaxSize().testTag("s02-content")) {
                            LivePageHead(count = 128, lastUpdatedAt = at(9, 41), onSortClick = {})
                            LiveFilterChips(
                                items = listOf(
                                    "全部 128", "屏幕 31", "应用 52", "充电 11", "网络 18", "音频 8",
                                ),
                                selectedIndex = 0,
                                onSelect = {},
                            )
                            LiveIntensityPanel(
                                hourlyIntensity = protoIntensity,
                                modifier = Modifier.testTag("s02-heatwrap"),
                            )
                            // 原型时间线容器 .pad.stack.mt-3（上外边距 12，与 MainScreen 一致）
                            Spacer(Modifier.height(t.space3))
                            Box(Modifier.weight(1f)) {
                                LiveEventTimeline(
                                    groups = groups,
                                    filter = LiveFilter.ALL,
                                    // 关掉入场窗口：入场动画从 opacity 0 起步，会让首帧几何测到未落位状态
                                    enterWindow = EnterWindow().also { it.close() },
                                    hasMore = true,
                                    isLoadingMore = false,
                                    todayEventCount = 128,
                                    // 原型「还有 118 条」= 今日 128 - 已加载 10（原型自身展示 12 行，文案按 10 算）
                                    loadedCount = 10,
                                    onSelect = {},
                                    onLoadMore = {},
                                    focusRequesterFor = { id ->
                                        requesters.getOrPut(id) { FocusRequester() }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s02" to items), File("build/parity/impl-s02.json"))
        println("PARITY(s02) tags=" + items.size + " groups=" + groups.size)
    }

    @Test
    fun exportAppearanceS13() {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                AppearanceScreen(onBack = {})
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s13" to items), File("build/parity/impl-s13.json"))
        println("PARITY(s13) tags=" + items.size)
    }
}
