package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.AdRecordList
import com.bsfeng.monitor.ui.AdScreenHead
import com.bsfeng.monitor.ui.SkipRecord
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
 * s03 广告跳过记录「实现真值」导出。
 *
 * 与 s01 / s04 同一套做法：不启动 AdViewModel（依赖 Room），用 AdScreen 的真实排布
 * （8dp 顶部内边距 → AdScreenHead → 内容区 AdRecordList）按原型 s03 的同一份数据组合，
 * 几何与 .open-design/parity/proto-screens.json 的 s03 直接可比。
 *
 * 假数据照原型 s03:201-280 的 10 条记录（时间 / 应用 / 方式 / 耗时逐条对齐），
 * chip 计数按 全部 10 / 规则 7 / 坐标兜底 2 / 无障碍 1。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S03AdParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    /** 当天 hh:mm:ss 的毫秒时间戳（页头「最近 17:34:04」与记录行时间由它决定）。 */
    private fun at(hour: Int, minute: Int, second: Int): Long =
        java.time.LocalDateTime.of(2026, 10, 14, hour, minute, second)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun record(
        appName: String,
        packageName: String,
        hour: Int,
        minute: Int,
        second: Int,
        selector: String,
        costMs: Long,
    ) = SkipRecord(
        appName = appName,
        packageName = packageName,
        timestampMs = at(hour, minute, second),
        selector = selector,
        summary = "AD_SKIPPED",
        confirmed = true,
        costMs = costMs,
        savedMs = null,
    )

    /** 原型 s03 的 10 条：规则 7 / 坐标兜底 2 / 无障碍 1。 */
    private fun fakeRecords() = listOf(
        record("微信", "com.tencent.mm", 17, 34, 4, "内置规则 · [text=\"跳过\"]", 128),
        record("哔哩哔哩", "tv.danmaku.bili", 17, 33, 51, "内置规则 · [id=\"skip_btn\"]", 96),
        record("微博", "com.sina.weibo", 17, 12, 37, "兜底坐标 · 右上角 (1050, 180)", 342),
        record("高德地图", "com.autonavi.minimap", 16, 48, 2, "内置规则 · [text=\"跳过 3s\"]", 87),
        record("小红书", "com.xingin.xhs", 15, 20, 44, "内置规则 · [desc=\"跳过\"]", 104),
        record("知乎", "com.zhihu.android", 14, 5, 19, "无障碍事件流 · 无稳定选择器", 512),
        record("网易云音乐", "com.netease.cloudmusic", 13, 41, 8, "内置规则 · [id$=\":id/skip\"]", 76),
        record("淘宝", "com.taobao.taobao", 12, 58, 33, "兜底坐标 · 底部中央 (540, 1980)", 388),
        record("微信", "com.tencent.mm", 11, 30, 26, "内置规则 · [text=\"跳过\"]", 92),
        record("抖音", "com.ss.android.ugc.aweme", 10, 12, 55, "内置规则 · [id=\"skip\"]", 118),
    )

    @Test
    fun exportAdS03() {
        val records = fakeRecords()
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .testTag("screen-s03"),
                ) {
                    // 原型 .content 顶部 8（s03-head 的 y=8），与 OverviewScreen 同一处置
                    Spacer(Modifier.height(8.dp))
                    AdScreenHead(
                        todayCount = 10,
                        lastSkipAt = at(17, 34, 4),
                        rotationZ = { 0f },
                        onRefresh = {},
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("s03-content"),
                    ) {
                        AdRecordList(
                            visible = records,
                            methodIndex = 0,
                            chipItems = listOf("全部 10", "规则 7", "坐标兜底 2", "无障碍 1"),
                            totalCount = 34,
                            confirmedCount = 30,
                            avgCost = 128L,
                            savedTotal = 408_000L,
                            // 原型 s03:292 的柱值 4,4,1,4,7,3,10（峰值 11 月 2 日 · 10 次）
                            dailyCounts7 = listOf(4f, 4f, 1f, 4f, 7f, 3f, 10f),
                            labels = listOf("10-27", "10-28", "10-29", "10-30", "10-31", "11-01", "11-02"),
                            rangeLabel = "10 月 27 日 – 11 月 2 日",
                            replayTick = 0,
                            onReplay = {},
                            onSelectMethod = {},
                            onOpenRules = {},
                            onOpenRecord = { _, _ -> },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s03" to items), File("build/parity/impl-s03.json"))
        println("PARITY(s03) tags=" + items.size + " " + items.joinToString { it.id })
    }
}
