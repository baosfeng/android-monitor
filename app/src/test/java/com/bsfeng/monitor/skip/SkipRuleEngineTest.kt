package com.bsfeng.monitor.skip

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.util.DebugConfig
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
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * SkipRuleEngine 单元测试（Robolectric）。
 *
 * 覆盖重构后修复的关键缺陷（对应 docs/开屏跳过/业界调研与改进方案.md）：
 * - P0-1：根节点为 null 时不再放弃，窗口内重试后可命中
 * - P0-2：只执行规则命中的节点，且带面积守卫
 * - P0-3：matchDelay 不会被后续扫描取消（旧实现下规则永不执行）
 * 以及白名单/熄屏/窗口/matchTime/actionMaximum/excludeMatches/activityIds 等防误触闸门。
 * - P2-2 增强「弱网顺延」：窗口末尾仍在渲染时顺延（有硬上限）、页面静止/熄屏/切 App 不顺延、
 *   复核优先且不受顺延影响、命中后不再顺延、顺延写调试日志。
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

    /** 最近一次上报发生的虚拟时刻（-1 = 未上报） */
    private var lastSkipAtMs = -1L

    /** 根节点被读取的次数与最近一次时刻（用于判断"窗口是否还在扫描"） */
    private var rootCalls = 0
    private var lastRootCallAtMs = -1L

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
        lastSkipAtMs = -1L
        rootCalls = 0
        lastRootCallAtMs = -1L
        rootProvider = { null }
        engine = SkipRuleEngine(manager).apply {
            currentTimeMillis = { clock }
            screenInteractive = { interactive }
            setRootNodeProvider {
                rootCalls++
                lastRootCallAtMs = clock
                rootProvider()
            }
            onSkipSuccess = {
                results.add(it)
                lastSkipAtMs = clock
            }
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

    // ==================== Activity 名可靠性（Toast 不得冲掉 Activity） ====================

    /**
     * 回归防护（真机取证 2026-10-13：30 份页面快照里 12 份把 className 记成了 widget 类名）：
     * Toast/弹窗也发 WINDOW_STATE_CHANGED 且 className 是 `android.widget.TextView`，
     * 旧实现直接采信 → 写了 activityIds 的规则在 Toast 之后**静默失效**，晚到的广告点不到。
     */
    @Test
    fun `窗口内出现 Toast 事件后仍能用 activityIds 规则命中晚到的广告`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), activityIds = listOf("com.test.app.MainActivity"))
        var adVisible = false
        rootProvider = {
            if (adVisible) tree(listOf(node(text = "跳过广告"))) else tree(listOf(node(text = "首页")))
        }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advance(300)                                                       // 首次扫描：广告还没渲染出来
        engine.onWindowChanged(testPackage, "android.widget.TextView")     // Toast 抢走窗口事件
        adVisible = true                                                   // 广告晚到
        engine.onContentChanged(testPackage)
        advance(300)

        assertEquals("Toast 不得让 activityIds 规则失效", 1, results.size)
    }

    @Test
    fun `首个窗口事件是 widget 类名时 activityIds 规则仍可命中`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), activityIds = listOf("com.test.app.MainActivity"))
        rootProvider = { tree(listOf(node(text = "跳过广告"))) }

        engine.onWindowChanged(testPackage, "android.widget.TextView")
        advance(500)

        assertEquals("Activity 未知时不得把规则锁死（否则整次启动漏检）", 1, results.size)
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

    // ==================== 弱网顺延（P2-2 增强） ====================

    /**
     * 模拟弱网页面持续渲染：[totalMs] 内每 [intervalMs] 触发一次 CONTENT_CHANGED。
     * [onTick] 收到累计毫秒数，可在中途改变页面状态（例如"广告此刻才渲染出来"）。
     */
    private fun render(totalMs: Long, intervalMs: Long = 250L, onTick: (Long) -> Unit = {}) {
        var elapsed = 0L
        while (elapsed < totalMs) {
            val step = minOf(intervalMs, totalMs - elapsed)
            advance(step)
            elapsed += step
            onTick(elapsed)
            engine.onContentChanged(testPackage)
        }
    }

    /** 顺延日志只在 [DebugConfig] 开关打开时输出；用完立刻关掉，避免影响其它用例 */
    private fun withDebugLog(block: () -> Unit) {
        DebugConfig.setEnabled(true)
        ShadowLog.clear()
        try {
            block()
        } finally {
            DebugConfig.setEnabled(false)
        }
    }

    private fun extendLogs(): List<String> = ShadowLog.getLogs()
        .filter { it.tag == "MonitorDebug" && it.msg.contains("弱网顺延") }
        .map { it.msg }

    @Test
    fun `窗口末尾仍在渲染时顺延并命中晚到的广告`() {
        // 规则自身的时间闸门 = 监控窗口 = 5 秒：顺延必须同时放宽闸门，否则扫到也不允许执行
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 5_000L)
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        // 弱网：页面一直在渲染（连续 CONTENT_CHANGED），广告 5.5 秒才渲染出来
        render(6_000) { elapsed -> if (elapsed >= 5_500) adVisible = true }

        assertEquals(1, results.size)
        assertTrue("必须在基础窗口(5s)之后才命中，说明顺延生效", lastSkipAtMs >= 5_500L)
        assertEquals(1, engine.extensionCount())
        assertEquals(2_000L, engine.extensionGrantedMs())
    }

    @Test
    fun `顺延期间规则时间闸门按顺延额度一起放宽`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 5_000L)
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        render(5_250)   // 到点顺延 1 次，额度 +2000ms
        assertEquals(2_000L, engine.extensionGrantedMs())

        // 此刻已超过规则自身的 matchTime(5000)，闸门须按顺延额度放宽到 7000 才允许执行。
        // 注意 CONTENT_CHANGED 有 100ms 节流，需先推进时钟再触发事件。
        adVisible = true
        advance(150)
        engine.onContentChanged(testPackage)
        advance(300)

        assertEquals("顺延额度应同时放宽 matchTime 闸门", 1, results.size)
        assertTrue("命中发生在规则自身 matchTime 之后", lastSkipAtMs > 5_000L)
    }

    @Test
    fun `顺延达到上限后窗口结束不再顺延`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { null }
        engine.watchWindowProvider = { 5_000L }
        val policy = WindowExtendPolicy()
        engine.windowExtendPolicy = policy

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        render(10_000)   // 持续渲染 10 秒，但广告始终没来
        val callsAtEnd = rootCalls

        assertNull("顺延用尽后窗口必须结束", engine.watchingPackage())
        assertEquals("最多顺延 " + policy.maxExtensions + " 次", policy.maxExtensions, engine.extensionCount())
        assertEquals(policy.maxExtensionMs, engine.extensionGrantedMs())
        assertTrue(
            "窗口必须在 基础窗口+顺延上限 内结束，实际最后一次扫描=" + lastRootCallAtMs,
            lastRootCallAtMs <= 5_000L + policy.maxExtensionMs
        )

        // 到点之后的内容变化不会再重新开窗
        engine.onContentChanged(testPackage)
        advanceSteps(1_000)
        assertEquals(callsAtEnd, rootCalls)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `页面静止时窗口到点即结束不顺延`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        advanceSteps(5_500)   // 窗口内没有任何 CONTENT_CHANGED → 页面静止
        assertNull("静止页面不得顺延窗口", engine.watchingPackage())
        assertEquals(0, engine.extensionCount())

        // 窗口结束后广告才出现 → 不再扫描、不命中（与旧版行为一致）
        val callsAtEnd = rootCalls
        adVisible = true
        engine.onContentChanged(testPackage)
        advanceSteps(1_000)
        assertEquals(callsAtEnd, rootCalls)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `关闭顺延时窗口末尾的渲染不再延长窗口`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 5_000L)
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }
        engine.watchWindowProvider = { 5_000L }
        engine.windowExtendPolicy = WindowExtendPolicy(maxExtensions = 0)   // 对照：旧版行为

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        render(6_000) { elapsed -> if (elapsed >= 5_500) adVisible = true }

        assertEquals("关闭顺延后晚到 500ms 的广告点不到（旧版行为）", 0, results.size)
        assertEquals(0, engine.extensionCount())
        assertNull(engine.watchingPackage())
    }

    @Test
    fun `窗口末尾熄屏立即终止且不顺延`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 5_000L)
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        render(6_000) { elapsed ->
            // 第 4.75 秒熄屏，广告恰好在同一刻渲染出来
            if (elapsed >= 4_750) {
                interactive = false
                adVisible = true
            }
        }

        assertTrue("熄屏后不得执行任何动作", results.isEmpty())
        assertNull("熄屏必须立即终止监控", engine.watchingPackage())
        assertEquals("熄屏属于立即终止，不得顺延", 0, engine.extensionCount())
    }

    @Test
    fun `窗口末尾切换 App 时旧窗口终止并重建顺延计数`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 5_000L)
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        render(5_250)   // 旧 App 页面上一直在渲染 → 到点顺延过 1 次
        assertEquals(1, engine.extensionCount())

        engine.onWindowChanged("com.other.app", "com.other.app.MainActivity")
        assertEquals("com.other.app", engine.watchingPackage())
        assertEquals("切换 App 重建窗口，已顺延次数归零", 0, engine.extensionCount())
        assertEquals("新窗口从切换时刻重新计时", 5_250L + 5_000L, engine.windowDeadlineMs())

        // 旧包名的内容变化不再受理，旧窗口的顺延不会延续到新 App
        adVisible = true
        engine.onContentChanged(testPackage)
        advanceSteps(500)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `复核期间到点照常上报且不被顺延`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        engine.verifyDelaysMs = listOf(500L)
        var adVisible = false
        rootProvider = {
            // 广告消失后仍要能拿到根节点，否则复核会因"根节点不可用"判不出结果
            if (adVisible) tree(listOf(node(text = "跳过广告"))) else tree(listOf(node(text = "首页")))
        }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        render(4_750) { elapsed -> if (elapsed >= 4_750) adVisible = true }   // 窗口末尾才命中
        assertEquals("命中后立即进入复核，不上报", 0, results.size)

        adVisible = false               // 广告消失
        advanceSteps(1_500)             // 越过基础窗口截止时间(5s)后复核到点

        assertEquals(1, results.size)
        assertEquals(true, results.first().confirmed)
        assertTrue("复核在窗口截止之后完成（复核不受窗口/顺延影响）", lastSkipAtMs > 5_000L)
        assertEquals("复核流程不触发顺延", 0, engine.extensionCount())
    }

    @Test
    fun `命中之后不再顺延`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        render(5_250) { elapsed -> if (elapsed >= 4_750) adVisible = true }

        assertEquals(1, results.size)
        assertEquals(4_750L, lastSkipAtMs)
        assertEquals("命中后窗口已结束，不得顺延", 0, engine.extensionCount())
        assertNull(engine.watchingPackage())

        val callsAtEnd = rootCalls
        engine.onContentChanged(testPackage)
        advance(1_000)
        assertEquals(callsAtEnd, rootCalls)
        assertEquals(1, results.size)
    }

    @Test
    fun `引擎长时间未扫描（时间跳变）时到点不顺延`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 5_000L)
        var calls = 0
        rootProvider = { calls++; null }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        // 大跳虚拟时钟：idle 期间引擎看到的 now 一直是 0（等价于进程被冻结/系统时间跳变）
        advance(6_000)
        val before = calls

        engine.onContentChanged(testPackage)   // 内容变化在"窗口早已过期"之后才被引擎看到
        advanceSteps(1_000)

        assertEquals("窗口已过期，不得靠顺延复活", before, calls)
        assertEquals("到点判定拒绝顺延", 0, engine.extensionCount())
        assertNull(engine.watchingPackage())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `到点判定晚于截止时间但在连续扫描范围内仍顺延`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"), matchTime = 5_000L)
        var adVisible = false
        rootProvider = { if (adVisible) tree(listOf(node(text = "跳过广告"))) else null }
        engine.watchWindowProvider = { 5_000L }

        engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
        // 弱网页面每 700ms 才刷出一次内容变化：到点判定发生在截止时间之后，但引擎仍在连续扫描
        render(7_000, intervalMs = 700L) { elapsed -> if (elapsed >= 6_000) adVisible = true }

        assertTrue("到点判定略有延迟时仍应顺延并命中晚到的广告", results.isNotEmpty())
        assertEquals(1, engine.extensionCount())
        assertTrue(lastSkipAtMs > 5_000L)
    }

    @Test
    fun `顺延写调试日志含触发原因新截止时间与已顺延次数`() {
        addRule(matches = listOf("[text=\"跳过广告\"]"))
        rootProvider = { null }
        engine.watchWindowProvider = { 5_000L }

        withDebugLog {
            engine.onWindowChanged(testPackage, "com.test.app.MainActivity")
            render(5_250)   // 页面仍在渲染，到点顺延第 1 次

            val logs = extendLogs()
            assertEquals(1, logs.size)
            assertTrue("日志需说明触发原因: " + logs, logs.first().contains("触发原因=页面仍在渲染"))
            assertTrue("日志需给出新的截止时间: " + logs, logs.first().contains("新截止=7000"))
            assertTrue("日志需给出已顺延次数: " + logs, logs.first().contains("#1/2"))
            assertTrue("日志需给出累计顺延时长: " + logs, logs.first().contains("累计顺延=2000ms"))
            assertEquals(1, engine.extensionCount())
        }
    }
}
