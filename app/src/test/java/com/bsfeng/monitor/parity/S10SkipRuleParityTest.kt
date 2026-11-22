package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.ui.ScreenHead
import com.bsfeng.monitor.ui.SecHead
import com.bsfeng.monitor.ui.SkipHits
import com.bsfeng.monitor.ui.SkipRuleKpiRow
import com.bsfeng.monitor.ui.SkipRuleLegend
import com.bsfeng.monitor.ui.SkipRuleSourceCard
import com.bsfeng.monitor.ui.skipRuleItems
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
 * s10 订阅源与规则「实现真值」导出。
 *
 * 绕开 SkipRuleViewModel（它要读 SkipRuleManager 的订阅文件），用 SkipRuleScreen 的真实排布
 * （ScreenHead → 有数据态 LazyColumn：订阅源卡 → KPI 两格 → 规则列表头 + 规则行 → 图例）
 * 按原型 s10 的同一份数据组合，几何可与 proto-screens.json 的 s10 直接对照。
 *
 * 假数据照原型 s10:111-193：5 条应用规则（微信 3 / 哔哩哔哩 4 / 淘宝 2 / 高德地图 2 / 知乎 1 个选择器，
 * 知乎停用），规则总数 42、累计命中 1,286、今日 34。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S10SkipRuleParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private fun rule(
        app: String,
        name: String,
        enabled: Boolean,
        selectors: List<String>,
    ) = SkipRule(
        id = app,
        app = app,
        name = name,
        enabled = enabled,
        groups = listOf(
            RuleGroup(
                key = 1,
                name = "开屏广告",
                order = 0,
                // 四类选择器都要有：原型 s10-legend 是 4 行（全部匹配 / 任选其一 / 排除 / 规则组），
                // 只铺 matches 会让图例掉成 2 行、整块矮一半（几何判据会被误读成实现缺陷）。
                rules = selectors.mapIndexed { i, sel ->
                    MatchRule(
                        key = i + 1,
                        name = "跳过广告",
                        matches = listOf(sel),
                        anyMatches = if (i == 0) listOf(sel + "[any]") else emptyList(),
                        excludeMatches = if (i == 1) listOf(sel + "[exclude]") else emptyList(),
                    )
                },
            ),
        ),
    )

    /** 原型 s10 的 5 条规则：选择器数与启停逐条对齐。 */
    private fun fakeRules() = listOf(
        rule("com.tencent.mm", "微信", true, listOf("[text=\"跳过\"]", "[id=\"com.tencent.mm:id/jump_ad\"]", "[text^=\"跳过\"]")),
        rule("tv.danmaku.bili", "哔哩哔哩", true, listOf("[text=\"跳过广告\"]", "[id=\"tv.danmaku.bili:id/skip\"]", "[desc=\"跳过\"]", "[text=\"关闭\"]")),
        rule("com.taobao.taobao", "淘宝", true, listOf("[text=\"跳过\"][index=3]", "[id=\"com.taobao.taobao:id/skip_view\"]")),
        rule("com.autonavi.minimap", "高德地图", true, listOf("[text=\"跳过\"][clickable=true]", "[text=\"3\"][bounds.top>600]")),
        rule("com.zhihu.android", "知乎", false, listOf("[text=\"跳过\"]")),
    )

    @Test
    fun exportSkipRuleS10() {
        val rules = fakeRules()
        val expanded = mutableStateMapOf<String, Boolean>()
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                val t = LocalDesignTokens.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .testTag("screen-s10"),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        ScreenHead(
                            title = "订阅源与规则",
                            meta = "从镜像源拉取规则包，本地合并后按顺序匹配",
                            onBack = {},
                            modifier = Modifier.testTag("s10-head"),
                            consumeTopInset = true,
                        )
                        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                            LazyColumn(
                                // 与 SkipRuleScreen 的有数据态同构：原型 .content.pad = 8 顶 + 左右 16，
                                // 内边距在锚点外侧（锚点 s10-body 量的是内容列 x=16/w=361）。
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = t.space4, end = t.space4, top = t.space2)
                                    .testTag("s10-body"),
                                contentPadding = PaddingValues(bottom = t.space6),
                            ) {
                                item(key = "source") {
                                    SkipRuleSourceCard(
                                        urlInput = "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5",
                                        onUrlChange = {},
                                        updating = false,
                                        updatingUrl = null,
                                        subscriptionCount = 2,
                                        lastSyncAt = SYNC_AT,
                                        snapshotPath = null,
                                        snapshotCount = 3,
                                        onRequestUpdate = {},
                                        onAddSubscription = {},
                                        onRequestSnapshot = {},
                                        onRequestClearSnapshots = {},
                                    )
                                }
                                item(key = "gap-source-kpi") { Spacer(Modifier.height(t.space3)) }

                                item(key = "kpi") {
                                    SkipRuleKpiRow(
                                        ruleCount = 42,
                                        hits = SkipHits(
                                            total = 1286,
                                            today = 34,
                                            daily7 = listOf(96f, 142f, 88f, 210f, 176f, 240f, 334f),
                                        ),
                                        modifier = Modifier
                                            .padding(bottom = t.space3)
                                            .testTag("s10-summary"),
                                    )
                                }
                                item(key = "rules-head") {
                                    SecHead(
                                        "规则列表",
                                        Modifier.testTag("s10-rules").padding(bottom = t.space2),
                                        right = "点击行展开选择器明细",
                                    )
                                }
                                skipRuleItems(
                                    rules = rules,
                                    expandedRuleIds = expanded,
                                    onToggleExpand = {},
                                    onToggleEnabled = {},
                                    onCopySelector = {},
                                )
                                item(key = "legend") {
                                    SkipRuleLegend(
                                        rules = rules,
                                        modifier = Modifier
                                            .padding(top = t.space3)
                                            .testTag("s10-legend"),
                                    )
                                }
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
        writeScreens(mapOf("s10" to items), File("build/parity/impl-s10.json"))
        println("PARITY(s10) tags=" + items.size + " " + items.joinToString { it.id })
    }

    private companion object {
        /** 原型「上次同步 9:40」：当天 09:40 的毫秒时间戳。 */
        val SYNC_AT: Long = java.time.LocalDateTime.of(2026, 10, 14, 9, 40)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
