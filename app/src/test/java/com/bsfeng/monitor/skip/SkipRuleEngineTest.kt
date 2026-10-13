package com.bsfeng.monitor.skip

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * SkipRuleEngine 单元测试（Robolectric）。
 *
 * 覆盖重构后修复的关键缺陷（对应 docs/开屏跳过/业界调研与改进方案.md）：
 * - P0-1：根节点为 null 时不再放弃，窗口内重试后可命中
 * - P0-2：只执行规则命中的节点，且带面积守卫
 * - P0-3：matchDelay 不会被后续扫描取消（旧实现下规则永不执行）
 * 以及白名单/熄屏/窗口/matchTime/actionMaximum/excludeMatches/activityIds 等防误触闸门。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkipRuleEngineTest {

    private lateinit var context: Context
    private lateinit var manager: SkipRuleManager
    private lateinit var engine: SkipRuleEngine

    private var clock = 0L
    private var interactive = true
    private val results = mutableListOf<SkipRuleEngine.SkipResult>()
    private var rootProvider: () -> AccessibilityNodeInfo? = { null }

    private val testPackage = "com.test.app"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication() as Context
        manager = SkipRuleManager(context)
        manager.resetToBuiltIn()
        // 关闭内置全局规则，避免干扰本用例的自定义规则
        manager.setRuleEnabled(BuiltInRules.GLOBAL_SPLASH_RULE_ID, false)
        clock = 0L
        interactive = true
        results.clear()
        rootProvider = { null }
        engine = SkipRuleEngine(manager).apply {
            currentTimeMillis = { clock }
            screenInteractive = { interactive }
            setRootNodeProvider { rootProvider() }
            onSkipSuccess = { results.add(it) }
            // 默认关闭「点击后确认」，否则每个用例都要多等复核延时；
            // 复核行为由「点击后确认」小节里的专门用例覆盖
            verifyDelaysMs = emptyList()
        }
    }

    // ==================== 工具 ====================

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        clock += ms
    }

    /**
     * 小步推进虚拟时钟。
     *
     * 引擎的 `now` 只在 [advance] 返回后才前进，因此"等待 400ms 再复核"这类逻辑必须小步走：
     * 一次大跳会让引擎在整个 idle 期间都看到旧时间，反复重排复核任务而永不到期。
     */
    private fun advanceSteps(totalMs: Long, stepMs: Long = 50L) {
        var remaining = totalMs
        while (remaining > 0) {
            val step = minOf(stepMs, remaining)
            advance(step)
            remaining -= step
        }
    }

    private fun node(
        text: String? = null,
        clickable: Boolean = true,
        className: String = "android.widget.TextView",
        bounds: Rect? = Rect(900, 100, 1100, 200)
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.text = text
        this.className = className
        this.isClickable = clickable
        bounds?.let { setBoundsInScreen(it) }
    }

    /** root(FrameLayout) + 子节点 */
    private fun tree(children: List<AccessibilityNodeInfo>): AccessibilityNodeInfo {
        val root = node(className = "android.widget.FrameLayout", clickable = false, bounds = Rect(0, 0, 1200, 2670))
        children.forEach { shadowOf(root).addChild(it) }
        return root
    }

    private fun addRule(
        matches: List<String>,
        anyMatches: List<String> = emptyList(),
        excludeMatches: List<String> = emptyList(),
        actionCd: Long = 0L,
        actionMaximum: Int = 1,
        matchDelay: Long = 0L,
        matchTime: Long = 10_000L,
        activityIds: List<String> = emptyList(),
        excludeActivityIds: List<String> = emptyList(),
        resetMatch: ResetMatch = ResetMatch.APP
    ) {
        manager.addRule(
            SkipRule(
                id = "test_rule",
                app = testPackage,
                name = "测试规则",
                groups = listOf(
                    RuleGroup(
                        key = 0,
                        name = "开屏广告",
                        activityIds = activityIds,
                        excludeActivityIds = excludeActivityIds,
                        matchTime = matchTime,
                        actionMaximum = actionMaximum,
                        actionCd = actionCd,
                        resetMatch = resetMatch,
                        rules = listOf(
                            MatchRule(
                                key = 0,
                                name = "跳过",
                                matches = matches,
                                anyMatches = anyMatches,
                                excludeMatches = excludeMatches,
                                matchDelay = matchDelay,
                                actionCd = actionCd
                            )
                        )
                    )
                )
            )
        )
    }

    // ==================== 基本命中 ====================

    @Test
    fun `命中规则后回调跳过结果并携带选择器`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)

        assertEquals(1, results.size)
        assertEquals(testPackage, results.first().packageName)
        assertEquals("[text=\"跳过广告\"]", results.first().selector)
    }

    @Test
    fun `actionMaximum 为 1 时同一 App 内不再重复执行`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)
        assertEquals(1, results.size)

        // 新一轮窗口（同 App）仍不应重复执行
        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(500)
        assertEquals(1, results.size)
    }

    @Test
    fun `未命中时不回调`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { tree(listOf(node(text = "确定"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(200)
        assertTrue(results.isEmpty())
    }

    // ==================== P0-1 根节点重试 ====================

    @Test
    fun `根节点为 null 时窗口内重试并最终命中`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        var calls = 0
        rootProvider = {
            calls++
            if (calls <= 2) null else tree(listOf(node(text = "跳过广告")))
        }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(1_000)

        assertTrue("根节点恢复后应命中", results.isNotEmpty())
        assertTrue("必须发生重试", calls >= 3)
    }

    // ==================== P0-3 matchDelay 不被饿死 ====================

    @Test
    fun `matchDelay 期间反复扫描也不会取消到期执行`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchDelay = 500L)
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        // 模拟 CONTENT_CHANGED 风暴：每 120ms 触发一次扫描（超过节流窗口）
        repeat(4) {
            advance(120)
            engine.onContentChanged(testPackage)
        }
        assertTrue("matchDelay 未到期不得执行", results.isEmpty())

        advance(200)  // 越过 500ms
        engine.onContentChanged(testPackage)
        advance(400)
        assertTrue("到期后必须执行（旧实现会被扫描取消导致永不执行）", results.isNotEmpty())
    }

    // ==================== 防误触闸门 ====================

    @Test
    fun `熄屏时不下发任何动作`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }
        interactive = false

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(1_000)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `白名单 App 不处理`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }
        val prefs = context.getSharedPreferences("skip_whitelist", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("whitelist", setOf(testPackage)).apply()

        try {
            engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
            advance(500)
            assertTrue(results.isEmpty())
        } finally {
            prefs.edit().clear().apply()
        }
    }

    @Test
    fun `规则 matchTime 过期后不再执行`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 1_000L)
        // 规则窗口内没有广告（root 为 null），窗口过期后广告才出现 → 不得执行
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(2_000)
        adVisible = true
        engine.onContentChanged(testPackage)
        advance(1_000)
        assertTrue("规则窗口过期后不得再执行", results.isEmpty())
    }

    // ==================== 点击后确认（P2-4） ====================

    @Test
    fun `点击后广告消失则标记为已确认`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        engine.verifyDelaysMs = listOf(400L)
        var adVisible = true
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else tree(listOf(node(text = "首页"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)              // 首次扫描 → 命中并点击 → 进入确认等待
        adVisible = false        // 广告消失
        advanceSteps(800)        // 到点复核

        assertEquals(1, results.size)
        assertEquals(true, results.first().confirmed)
    }

    @Test
    fun `点击后广告仍在则标记为未确认`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        engine.verifyDelaysMs = listOf(300L)
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advanceSteps(800)

        assertEquals(1, results.size)
        assertEquals(false, results.first().confirmed)
    }

    @Test
    fun `复核期内再复核一次，消失后仍算已确认`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        engine.verifyDelaysMs = listOf(300L, 300L)
        var adVisible = true
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else tree(listOf(node(text = "首页"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)           // 点击 → 第一次复核在 +300ms
        advanceSteps(500)     // 第一次复核：广告仍在 → 安排了第二次复核
        assertTrue("复核未结束不得上报", results.isEmpty())
        adVisible = false
        advanceSteps(800)     // 第二次复核：广告已消失

        assertEquals(1, results.size)
        assertEquals(true, results.first().confirmed)
    }

    @Test
    fun `复核期间不会重复点击（即使规则允许无限次）`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), actionCd = 0L, actionMaximum = 0)
        engine.verifyDelaysMs = listOf(1_000L)
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)              // 命中并点击
        advanceSteps(2_000)      // 复核期间（1 秒）+ 复核结束后

        assertEquals("复核期间不得再次执行规则", 1, results.size)
    }

    @Test
    fun `未启用确认时直接上报且不带确认标记`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)

        assertEquals(1, results.size)
        assertNull(results.first().confirmed)
    }

    // ==================== P2-2 监控窗口可配置 ====================

    @Test
    fun `窗口调大后晚出现的广告仍能命中`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 15_000L)
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }
        engine.watchWindowProvider = { 15_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        // 越过旧的硬编码 10 秒窗口后广告才渲染出来
        advance(11_000)
        adVisible = true
        engine.onContentChanged(testPackage)
        advance(600)

        assertTrue("窗口调大后必须仍能命中晚到的广告", results.isNotEmpty())
    }

    @Test
    fun `窗口调小后到点即停止扫描`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 30_000L)
        var calls = 0
        rootProvider = { calls++; null }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(6_000)
        val before = calls

        engine.onContentChanged(testPackage)
        advance(1_000)
        assertEquals("窗口结束后不得再扫描", before, calls)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `excludeMatches 命中时放弃规则`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), excludeMatches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(500)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `activityIds 不匹配时不执行`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), activityIds = listOf("com.test.app.SplashActivity"))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(500)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `excludeActivityIds 命中时不执行`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), excludeActivityIds = listOf("com.test.app.MainActivity"))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(500)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `面积守卫丢弃整屏命中`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        // 命中的是可点击的整屏容器（点中心会进广告落地页）
        rootProvider = {
            tree(listOf(node(text = "跳过广告", bounds = Rect(0, 0, 1200, 2670))))
        }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(500)
        assertTrue("超过根节点 1/3 面积的命中必须丢弃", results.isEmpty())
    }

    // ==================== 事件驱动 ====================

    @Test
    fun `非监控包名的 CONTENT_CHANGED 被忽略`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        var calls = 0
        rootProvider = {
            calls++
            null
        }
        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)
        val before = calls

        engine.onContentChanged("com.other.app")
        assertEquals("非监控包名事件不得触发扫描", before, calls)
    }

    @Test
    fun `停止监控后不再扫描`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        var calls = 0
        rootProvider = {
            calls++
            null
        }
        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)
        engine.stopWatching()
        val before = calls

        engine.onContentChanged(testPackage)
        advance(1_000)
        assertEquals("停止监控后不得再扫描", before, calls)
        assertNull(engine.watchingPackage())
    }

    @Test
    fun `进入本 App 时停止监控并重置状态`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { null }
        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(50)
        assertNotNull(engine.watchingPackage())

        engine.onWindowChanged("com.bsfeng.monitor", "com.bsfeng.monitor.MainActivity")
        assertNull(engine.watchingPackage())
    }

    // ==================== 跨规则闸门（GKD 兼容） ====================

    /** 一条规则下挂多个匹配规则，用于验证 actionDelay / preKeys / 共享 Key 语义 */
    private fun addRuleWithRules(rules: List<MatchRule>, ruleId: String = "test_rule") {
        manager.addRule(
            SkipRule(
                id = ruleId,
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
                        rules = rules
                    )
                )
            )
        )
    }

    @Test
    fun `actionDelay 到期且仍命中才执行`() {
        addRuleWithRules(listOf(MatchRule(key = 0, matches = listOf("[text=\"跳过广告\"]"), actionDelay = 500L)))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(200)
        assertTrue("actionDelay 等待期内不得执行", results.isEmpty())

        advance(500)
        engine.onContentChanged(testPackage)
        advance(100)
        assertEquals("到期且仍命中应执行", 1, results.size)
    }

    @Test
    fun `actionDelay 期间目标消失则不执行`() {
        var visible = true
        addRuleWithRules(listOf(MatchRule(key = 0, matches = listOf("[text=\"跳过广告\"]"), actionDelay = 500L)))
        rootProvider = { if (visible) tree(listOf(node(text = "跳过广告"))) else null }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(100)   // 首次命中，挂起等待复核
        visible = false
        advance(1_000)
        engine.onContentChanged(testPackage)
        advance(200)
        assertTrue("复核时目标已消失，不得执行", results.isEmpty())
    }

    @Test
    fun `preKeys 前置未执行时阻止规则`() {
        addRuleWithRules(listOf(MatchRule(key = 1, matches = listOf("[text=\"关闭广告\"]"), preKeys = listOf(0))))
        rootProvider = { tree(listOf(node(text = "关闭广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(1_000)
        assertTrue("前置 key 未执行过时不得触发", results.isEmpty())
    }

    @Test
    fun `preKeys 前置执行过后放行`() {
        addRuleWithRules(
            listOf(
                MatchRule(key = 0, matches = listOf("[text=\"跳过广告\"]")),
                MatchRule(key = 1, matches = listOf("[text=\"关闭广告\"]"), preKeys = listOf(0))
            )
        )
        rootProvider = { tree(listOf(node(text = "跳过广告"), node(text = "关闭广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(300)
        assertEquals("窗口内只应执行第一条", 1, results.size)

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(300)
        assertEquals("前置 key 执行过后应放行第二条", 2, results.size)
    }

    @Test
    fun `actionCdKey 共享冷却`() {
        addRuleWithRules(
            listOf(
                MatchRule(key = 0, matches = listOf("[text=\"跳过广告\"]"), actionCd = 5_000L),
                MatchRule(key = 1, matches = listOf("[text=\"关闭广告\"]"), actionCd = 5_000L, actionCdKey = 0)
            )
        )
        rootProvider = { tree(listOf(node(text = "跳过广告"), node(text = "关闭广告"))) }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(300)
        assertEquals(1, results.size)

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(1_000)
        assertEquals("共享冷却期内不得执行同 Key 规则", 1, results.size)

        // 注：虚拟时钟只在 advance() 内推进，因此按步行进才能让引擎"看到"时间流逝
        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        repeat(18) {
            advance(300)
            engine.onContentChanged(testPackage)
        }
        assertEquals("共享冷却过期后应放行", 2, results.size)
    }
}
