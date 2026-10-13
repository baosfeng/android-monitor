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
        }
    }

    // ==================== 工具 ====================

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        clock += ms
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
}
