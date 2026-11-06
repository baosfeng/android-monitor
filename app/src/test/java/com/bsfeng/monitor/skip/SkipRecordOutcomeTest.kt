package com.bsfeng.monitor.skip

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.record.RecordCollector
import com.bsfeng.monitor.record.RecordHandlerRegistry
import com.bsfeng.monitor.record.RecordStore
import com.bsfeng.monitor.record.UsageTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 「未确认的跳过不再伪装成成功」的**落库口径**验收（A 项）。
 *
 * 断言的是**事件表里的行数**，不是某个内存标记：这是唯一能证明「数字不再骗人」的证据。
 *
 * | 复核结论 | 落什么 | 计入跳过成功？ |
 * |---|---|---|
 * | confirmed=true | `AD_SKIPPED`（confirmed=true） | 是 |
 * | confirmed=null（未复核） | `AD_SKIPPED`（无 confirmed 字段） | 是 |
 * | confirmed=false | `AD_SKIP_UNCONFIRMED` | **否** |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkipRecordOutcomeTest {

    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var store: RecordStore
    private lateinit var collector: RecordCollector
    private lateinit var manager: SkipRuleManager
    private lateinit var engine: SkipRuleEngine

    private var clock = 0L
    private val testPackage = "com.test.app"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        helper = DatabaseHelper(context)
        store = RecordStore(helper)
        collector = RecordCollector(
            store,
            UsageTracker { record -> store.insertOrMerge(record) },
            RecordHandlerRegistry.createDefault(),
            geocoder = null
        )
        manager = SkipRuleManager(context)
        manager.resetToBuiltIn()
        manager.setRuleEnabled(BuiltInRules.GLOBAL_SPLASH_RULE_ID, false)
        clock = 0L
        engine = SkipRuleEngine(manager).apply {
            currentTimeMillis = { clock }
            screenInteractive = { true }
            // 与服务层 MonitorAccessibilityService.onSkipSuccess / onSkipUnconfirmed 同口径：
            // 走真实 SkipEventRecorder → RecordCollector → SQLite，只把 MonitorApp 容器换成测试库
            onSkipSuccess = { result ->
                collector.onSkipAd(
                    appName = "测试应用",
                    packageName = result.packageName,
                    selectorExpr = result.selector,
                    confirmed = result.confirmed,
                    matchedText = result.matchedText,
                    verdictReason = result.verdictReason
                )
            }
            onSkipUnconfirmed = { result ->
                collector.onSkipAdUnconfirmed(
                    appName = "测试应用",
                    packageName = result.packageName,
                    selectorExpr = result.selector,
                    matchedText = result.matchedText,
                    verdictReason = result.verdictReason
                )
            }
        }
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        clock += ms
    }

    /** 小步推进虚拟时钟：`now` 只在 [advance] 返回后前进，一次大跳会让复核任务永不到期 */
    private fun advanceSteps(totalMs: Long, stepMs: Long = 50L) {
        var remaining = totalMs
        while (remaining > 0) {
            val step = minOf(stepMs, remaining)
            advance(step)
            remaining -= step
        }
    }

    private fun node(text: String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.text = text
        className = "android.widget.TextView"
        isClickable = true
        setBoundsInScreen(Rect(900, 100, 1100, 200))
    }

    private fun tree(children: List<AccessibilityNodeInfo>): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.FrameLayout"
            setBoundsInScreen(Rect(0, 0, 1200, 2670))
        }
        children.forEach { shadowOf(root).addChild(it) }
        return root
    }

    private fun addRule() {
        manager.addRule(
            SkipRule(
                id = "test_rule",
                app = testPackage,
                name = "测试规则",
                groups = listOf(
                    RuleGroup(
                        key = 0,
                        name = "开屏广告",
                        matchTime = 10_000L,
                        actionMaximum = 1,
                        actionCd = 0L,
                        resetMatch = ResetMatch.APP,
                        rules = listOf(MatchRule(key = 0, name = "跳过", matches = listOf("[text=\"跳过广告\"]")))
                    )
                )
            )
        )
    }

    private fun countOf(action: String): Int =
        store.queryByTypeAndAction(RecordType.SCREEN, action, 0L, Long.MAX_VALUE).size

    // ==================== A 项的硬证据 ====================

    @Test
    fun `未确认的命中不写 AD_SKIPPED 而写 AD_SKIP_UNCONFIRMED`() {
        addRule()
        engine.verifyDelaysMs = listOf(300L)
        // 广告（跳过按钮）始终在 → 复核结论 confirmed=false
        engine.setRootNodeProvider { tree(listOf(node("跳过广告"))) }

        val skippedBefore = countOf("AD_SKIPPED")
        val unconfirmedBefore = countOf("AD_SKIP_UNCONFIRMED")

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advanceSteps(800)

        assertEquals("未确认不得写成功记录", skippedBefore, countOf("AD_SKIPPED"))
        assertEquals("未确认必须留下一条独立记录", unconfirmedBefore + 1, countOf("AD_SKIP_UNCONFIRMED"))
    }

    @Test
    fun `未确认记录保留选择器与包名便于排查`() {
        addRule()
        engine.verifyDelaysMs = listOf(300L)
        engine.setRootNodeProvider { tree(listOf(node("跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advanceSteps(800)

        val record = store
            .queryByTypeAndAction(RecordType.SCREEN, "AD_SKIP_UNCONFIRMED", 0L, Long.MAX_VALUE)
            .single()
        assertEquals(testPackage, record.detail?.get("package_name"))
        assertEquals("[text=\"跳过广告\"]", record.detail?.get("selector"))
        assertEquals("false", record.detail?.get("confirmed"))
        assertTrue("label 不得为空，实时事件流靠它渲染主文案", record.label.isNotBlank())
    }

    @Test
    fun `跳过记录落库带命中文案与判据结论便于回溯误判`() {
        addRule()
        engine.verifyDelaysMs = listOf(300L)
        // 「跳过广告」自带广告字样：判据靠文案证据放行，证据随之落库
        engine.setRootNodeProvider { tree(listOf(node("跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advanceSteps(800)

        val record = store
            .queryByTypeAndAction(RecordType.SCREEN, "AD_SKIP_UNCONFIRMED", 0L, Long.MAX_VALUE)
            .single()
        // 只有 selector 的话，事后无法回答"点的是什么字、被哪条判据放行"（2026-11-02 的教训）
        assertEquals("跳过广告", record.detail?.get("matched_text"))
        val reason = record.detail?.get("verdict_reason")
        assertTrue("必须记录判据结论: " + reason, !reason.isNullOrBlank())
        assertTrue("结论要能看出是哪条证据放行的: " + reason, reason?.contains("白名单") == false)
    }

    @Test
    fun `证据文案截断到 32 字以内`() {
        // 纯结构候选：证据回落到 viewId，而 viewId 可以很长（混淆名带哈希）
        val longVid = "com.demo:id/tt_splash_skip_btn_" + "a".repeat(60)
        val candidate = SplashCandidate(
            viewId = longVid,
            ancestorViewIds = listOf("com.demo:id/gdt_splash_container"),
            elapsedSinceEnterMs = 200L
        )
        val evidence = SplashGate.check(
            candidate, "测试", "[vid*=\"skip\"]", SplashGate.ScreenContext("com.test.app", "com.test.app.MainActivity")
        )

        assertTrue("结构候选应被容器证据放行", evidence != null)
        assertEquals(
            "证据文案必须截断，否则整段 viewId 会进事件表",
            32,
            evidence?.matchedText?.length ?: -1
        )
    }

    @Test
    fun `已确认的命中仍写 AD_SKIPPED 且不带未确认记录`() {
        addRule()
        engine.verifyDelaysMs = listOf(300L)
        var adVisible = true
        engine.setRootNodeProvider {
            if (adVisible) tree(listOf(node("跳过广告"))) else tree(listOf(node("首页")))
        }

        val skippedBefore = countOf("AD_SKIPPED")
        val unconfirmedBefore = countOf("AD_SKIP_UNCONFIRMED")

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)
        adVisible = false
        advanceSteps(600)

        assertEquals(skippedBefore + 1, countOf("AD_SKIPPED"))
        assertEquals("已确认不得产生未确认记录", unconfirmedBefore, countOf("AD_SKIP_UNCONFIRMED"))
    }

    // ==================== 落库合法性（否则会被 RecordCollector.emit 静默丢弃） ====================

    @Test
    fun `未确认 action 通过 ScreenHandler 校验且文案不含"跳过成功"语义`() {
        val handler = com.bsfeng.monitor.domain.model.ScreenHandler()
        assertTrue(
            "未登记 action 会被 RecordCollector.emit 静默丢弃，必须先进 ScreenHandler.validate",
            handler.validate("AD_SKIP_UNCONFIRMED", mapOf("app_name" to "测试应用"))
        )
        val label = handler.createLabel("AD_SKIP_UNCONFIRMED", mapOf("app_name" to "测试应用"))
        assertEquals("点击 测试应用 跳过按钮（未确认）", label)
        assertTrue("文案必须写明未确认，不能伪装成成功", label.contains("未确认"))
    }

    @Test
    fun `未确认 action 能反推回 SCREEN 类型`() {
        assertEquals(
            "读库兜底（混淆名场景）靠 action 反推类型",
            RecordType.SCREEN,
            RecordType.inferFromAction("AD_SKIP_UNCONFIRMED")
        )
    }

    @Test
    fun `未复核的命中按成功记录`() {
        addRule()
        engine.verifyDelaysMs = emptyList()   // 关闭点击后确认
        engine.setRootNodeProvider { tree(listOf(node("跳过广告"))) }

        val skippedBefore = countOf("AD_SKIPPED")
        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)

        assertEquals("未复核（confirmed=null）按成功口径", skippedBefore + 1, countOf("AD_SKIPPED"))
        assertEquals(0, countOf("AD_SKIP_UNCONFIRMED"))
    }
}
