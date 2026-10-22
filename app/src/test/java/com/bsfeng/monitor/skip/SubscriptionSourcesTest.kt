package com.bsfeng.monitor.skip

import android.content.Context
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 多订阅源测试（P1-7）。
 *
 * 每个订阅地址独立存一个规则文件：新增/更新/删除某个源不得影响其它源；
 * 早期版本的单订阅数据要能平滑迁移成多订阅。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionSourcesTest {

    private lateinit var context: Context
    private lateinit var manager: SkipRuleManager

    private val urlA = "https://cdn.example.com/a/gkd.json5"
    private val urlB = "https://cdn.example.com/b/gkd.json5"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication() as Context
        manager = SkipRuleManager(context)
        manager.resetToBuiltIn()
    }

    private fun rule(app: String, text: String = "跳过") = SkipRule(
        id = "sub_" + app,
        app = app,
        name = app,
        groups = listOf(
            RuleGroup(
                key = 0,
                name = "开屏广告",
                rules = listOf(
                    MatchRule(key = 0, matches = listOf("[text=\"" + text + "\"]"))
                )
            )
        )
    )

    // ==================== 正常路径 ====================

    @Test
    fun `可同时保存多个订阅源且规则都生效`() {
        manager.saveSubscription(urlA, 1L, listOf(rule("com.a.app")))
        manager.saveSubscription(urlB, 2L, listOf(rule("com.b.app"), rule("com.b2.app")))

        assertEquals(2, manager.subscriptionMetas().size)
        assertEquals(1, manager.subscriptionMeta(urlA)?.ruleCount)
        assertEquals(2, manager.subscriptionMeta(urlB)?.ruleCount)
        val ids = manager.getAllRules().map { it.id }
        assertTrue(ids.contains("sub_com.a.app"))
        assertTrue(ids.contains("sub_com.b.app"))
        assertTrue(ids.contains("sub_com.b2.app"))
    }

    @Test
    fun `更新一个订阅源不影响另一个`() {
        manager.saveSubscription(urlA, 1L, listOf(rule("com.a.app")))
        manager.saveSubscription(urlB, 1L, listOf(rule("com.b.app")))

        manager.saveSubscription(urlA, 5L, listOf(rule("com.a.new")))

        assertEquals(5L, manager.subscriptionMeta(urlA)?.version)
        assertEquals(1, manager.subscriptionMeta(urlB)?.ruleCount)
        val ids = manager.getAllRules().map { it.id }
        assertFalse("被替换的旧规则应消失", ids.contains("sub_com.a.app"))
        assertTrue("新规则应生效", ids.contains("sub_com.a.new"))
        assertTrue("另一个订阅源不受影响", ids.contains("sub_com.b.app"))
    }

    @Test
    fun `重复更新同一地址不会新增订阅源`() {
        manager.saveSubscription(urlA, 1L, listOf(rule("com.a.app")))
        manager.saveSubscription(urlA, 2L, listOf(rule("com.a.app")))

        assertEquals(1, manager.subscriptionMetas().size)
        assertEquals(2L, manager.subscriptionMeta(urlA)?.version)
    }

    @Test
    fun `删除一个订阅源只清除它自己的规则`() {
        manager.saveSubscription(urlA, 1L, listOf(rule("com.a.app")))
        manager.saveSubscription(urlB, 1L, listOf(rule("com.b.app")))

        manager.clearSubscription(urlA)

        assertEquals(1, manager.subscriptionMetas().size)
        assertNull(manager.subscriptionMeta(urlA))
        val ids = manager.getAllRules().map { it.id }
        assertFalse(ids.contains("sub_com.a.app"))
        assertTrue(ids.contains("sub_com.b.app"))
    }

    @Test
    fun `不传地址时清空全部订阅`() {
        manager.saveSubscription(urlA, 1L, listOf(rule("com.a.app")))
        manager.saveSubscription(urlB, 1L, listOf(rule("com.b.app")))

        manager.clearSubscription()

        assertTrue(manager.subscriptionMetas().isEmpty())
        assertFalse(manager.getAllRules().any { it.id.startsWith("sub_") })
    }

    @Test
    fun `订阅源在重新加载后仍然存在`() {
        manager.saveSubscription(urlA, 3L, listOf(rule("com.a.app")))

        manager.load()

        assertEquals(1, manager.subscriptionMetas().size)
        assertEquals(3L, manager.subscriptionMeta(urlA)?.version)
        assertTrue(manager.getAllRules().any { it.id == "sub_com.a.app" })
    }

    // ==================== 边界条件 ====================

    @Test
    fun `同一地址映射到同一文件且不同地址不同`() {
        assertEquals(manager.fileNameOf(urlA), manager.fileNameOf(urlA))
        assertNotEquals(manager.fileNameOf(urlA), manager.fileNameOf(urlB))
    }

    @Test
    fun `一个订阅源解析失败不影响其它源`() {
        manager.saveSubscription(urlA, 1L, listOf(rule("com.a.app")))
        manager.saveSubscription(urlB, 1L, listOf(rule("com.b.app")))
        // 把 B 的规则文件写成坏数据
        val broken = File(File(context.filesDir, "subscriptions"), manager.fileNameOf(urlB))
        broken.writeText("{ 不是合法 JSON 数组 ")

        manager.load()

        assertTrue("A 的规则仍应加载", manager.getAllRules().any { it.id == "sub_com.a.app" })
    }

    // ==================== 迁移 ====================

    @Test
    fun `单订阅数据迁移为多订阅且不丢规则`() {
        val legacyUrl = "https://legacy.example.com/gkd.json5"
        // 旧版布局：prefs 里的单订阅元信息 + files/skip_subscription.json
        // 用 commit 而非 apply：apply 是异步落盘，紧接着 new SkipRuleManager 可能读到空值，
        // 迁移被跳过（CI 上偶发 expected:<1> but was:<0>）。
        context.getSharedPreferences("skip_rule_prefs", Context.MODE_PRIVATE).edit()
            .putString(
                "subscription_meta",
                JSONObject().put("url", legacyUrl).put("version", 9).put("ruleCount", 1).put("updatedAt", 1L).toString()
            )
            .commit()
        val array = JSONArray().apply { put(rule("com.legacy.app").toJson()) }
        File(context.filesDir, "skip_subscription.json").writeText(array.toString())

        val migrated = SkipRuleManager(context)

        assertEquals(1, migrated.subscriptionMetas().size)
        val meta = migrated.subscriptionMeta(legacyUrl)
        assertNotNull(meta)
        assertEquals(9L, meta!!.version)
        assertTrue("规则文件应搬到多订阅目录", File(File(context.filesDir, "subscriptions"), meta.fileName).exists())
        assertTrue("迁移后规则仍生效", migrated.getAllRules().any { it.id == "sub_com.legacy.app" })
        assertFalse("旧文件应被清理", File(context.filesDir, "skip_subscription.json").exists())
    }
}
