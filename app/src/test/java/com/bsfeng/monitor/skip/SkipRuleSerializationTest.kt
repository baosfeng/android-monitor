package com.bsfeng.monitor.skip

import com.bsfeng.monitor.domain.model.ClickPosition
import com.bsfeng.monitor.domain.model.MatchAction
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.skip.json.Json5Parser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SkipRule 序列化单元测试（JUnit4）。
 *
 * 覆盖三路径：
 * - 正常路径：完整字段往返相等、GKD 订阅解析；
 * - 边界：字符串/数组两种写法等价、`quickFind`/`fastQuery` 等价、`rules` 单对象/单元素数组等价、空数组；
 * - 异常：缺字段、`matches` 为空、非法 `action`/`resetMatch` 回落默认值。
 *
 * 说明：被测代码用 org.json，而单元测试的 android.jar 里 org.json 全是 `throw new RuntimeException("Stub!")`
 * 的空实现，**普通 JVM 测试跑不起来**，因此这里与 [com.bsfeng.monitor.skip.SnapshotExporterTest] 一样
 * 用 Robolectric 提供真实实现（不涉及任何 Android 组件，加载开销很小）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkipRuleSerializationTest {

    /** 完整字段样本：所有字段都取非默认值，用于验证往返无损。 */
    private fun fullRule(): SkipRule = SkipRule(
        id = "com.example.app",
        app = "com.example.app",
        name = "示例应用",
        enabled = false,
        groups = listOf(
            RuleGroup(
                key = 7,
                name = "开屏广告",
                order = 3,
                enabled = false,
                activityIds = listOf("com.example.app.SplashActivity", ".MainActivity"),
                excludeActivityIds = listOf("com.example.app.SearchActivity"),
                matchTime = 8_000L,
                actionMaximum = 2,
                actionCd = 1_500L,
                resetMatch = ResetMatch.MATCH,
                rules = listOf(
                    MatchRule(
                        key = 11,
                        name = "跳过按钮",
                        matches = listOf("[text*=\"跳过\"]", "[vid=\"count_down\"]"),
                        anyMatches = listOf("[vid=\"skip\"]", "[vid*=\"count\"]"),
                        excludeMatches = listOf("[vid=\"tv_user_name\"]"),
                        action = MatchAction.CLICK_CENTER,
                        matchDelay = 250L,
                        actionDelay = 120L,
                        matchTime = 9_000L,
                        actionMaximum = 3,
                        actionCd = 800L,
                        fastQuery = false,
                        position = ClickPosition(0.25f, 0.75f)
                    ),
                    // 全默认值规则：验证可空字段（matchTime/actionMaximum/actionCd/position）往返仍是 null
                    MatchRule(key = 12)
                )
            )
        )
    )

    // ==================== 正常路径 ====================

    @Test
    fun `完整字段往返相等`() {
        val origin = fullRule()
        assertEquals(origin, parseSkipRule(origin.toJson()))
    }

    @Test
    fun `全局规则往返相等`() {
        val origin = SkipRule(id = "gkd_global_0", app = SkipRule.GLOBAL_APP, name = "开屏广告")
            .copy(groups = fullRule().groups)
        assertEquals(origin, parseSkipRule(origin.toJson()))
        assertTrue(origin.isGlobal)
    }

    @Test
    fun `规则数组往返相等`() {
        val array = JSONArray().put(fullRule().toJson()).put(SkipRule(id = "b", app = "b", name = "B").toJson())
        val rules = parseSkipRules(array)
        assertEquals(2, rules.size)
        assertEquals(fullRule(), rules[0])
        assertEquals(SkipRule(id = "b", app = "b", name = "B"), rules[1])
    }

    // ==================== 写法等价 ====================

    @Test
    fun `matches 字符串与数组等价`() {
        val single = parseMatchRule(JSONObject().put("matches", "A > B"))
        val array = parseMatchRule(JSONObject().put("matches", JSONArray().put("A > B")))
        assertEquals(single, array)
        assertEquals(listOf("A > B"), single.matches)
    }

    @Test
    fun `anyMatches 与 excludeMatches 同样兼容两种写法`() {
        val rule = parseMatchRule(
            JSONObject()
                .put("anyMatches", "[vid=\"skip\"]")
                .put("excludeMatches", JSONArray().put("[vid=\"a\"]").put("[vid=\"b\"]"))
        )
        assertEquals(listOf("[vid=\"skip\"]"), rule.anyMatches)
        assertEquals(listOf("[vid=\"a\"]", "[vid=\"b\"]"), rule.excludeMatches)
    }

    @Test
    fun `activityIds 字符串与数组等价`() {
        val single = parseRuleGroup(JSONObject().put("activityIds", ".SplashActivity"))
        val array = parseRuleGroup(JSONObject().put("activityIds", JSONArray().put(".SplashActivity")))
        assertEquals(single, array)
        assertEquals(listOf(".SplashActivity"), single.activityIds)
    }

    @Test
    fun `quickFind 与 fastQuery 等价`() {
        val fast = parseMatchRule(JSONObject().put("fastQuery", false))
        val quick = parseMatchRule(JSONObject().put("quickFind", false))
        assertFalse(fast.fastQuery)
        assertEquals(fast, quick)
        // 两者都缺失时沿用领域模型默认值 true
        assertTrue(parseMatchRule(JSONObject()).fastQuery)
    }

    @Test
    fun `rules 单对象与单元素数组等价`() {
        val single = parseRuleGroup(JSONObject().put("rules", JSONObject().put("matches", "A")))
        val array = parseRuleGroup(JSONObject().put("rules", JSONArray().put(JSONObject().put("matches", "A"))))
        assertEquals(single, array)
        assertEquals(listOf("A"), single.rules.single().matches)
    }

    @Test
    fun `rules 字符串写法等价于只有 matches 的规则`() {
        val group = parseRuleGroup(JSONObject().put("rules", "TextView[text*=\"跳过\"]"))
        assertEquals(1, group.rules.size)
        assertEquals(listOf("TextView[text*=\"跳过\"]"), group.rules.single().matches)
        assertEquals(MatchAction.CLICK, group.rules.single().action)
    }

    @Test
    fun `enabled enable disabled 三种写法都认`() {
        assertFalse(parseSkipRule(JSONObject().put("disabled", true)).enabled)
        assertFalse(parseSkipRule(JSONObject().put("enable", false)).enabled)
        assertTrue(parseSkipRule(JSONObject().put("enabled", true)).enabled)
        assertTrue(parseSkipRule(JSONObject()).enabled)
    }

    // ==================== GKD 订阅 ====================

    /** 真实形态的 GKD 订阅片段：单引号字符串、注释、尾随逗号、`IArray` 两种写法。 */
    private val gkdSubscription = """
        {
          id: 1,
          name: '示例订阅',
          version: 1919810,
          // 全局规则组：apps 里的按应用禁用名单模型无法表达，解析时忽略
          globalGroups: [
            {
              key: 0, name: '开屏广告', actionMaximum: 2, matchTime: 10000, resetMatch: 'app',
              apps: [ { id: 'com.example.optout', enable: false } ],
              rules: [
                { key: 0, quickFind: true, matches: '[text*="跳过"][text.length<10][visibleToUser=true]' },
                {
                  key: -1,
                  matches: [ '[vid*="skip"]', '[vid*="count" && vid*="down"]' ],
                  action: 'clickCenter',
                },
              ],
            },
          ],
          apps: [
            {
              id: 'com.example.app', name: '示例应用',
              groups: [
                {
                  key: 1, name: '开屏广告', order: 1,
                  activityIds: ['com.example.app.SplashActivity'],
                  excludeActivityIds: '.SearchActivity',
                  matchTime: 8000, actionMaximum: 1, actionCd: 1500, resetMatch: 'match',
                  rules: [ 'TextView[text*="跳过"]' ],
                },
              ],
            },
            { id: 'com.example.off', name: '已禁用应用', enable: false,
              groups: [ { key: 2, name: '弹窗', rules: { matches: 'A > B', actionDelay: 200 } } ] },
          ],
        }
    """.trimIndent()

    @Test
    fun `解析 GKD 订阅生成应用规则与全局规则`() {
        val rules = parseGkdSubscription(Json5Parser.parse(gkdSubscription))

        // 2 个应用规则 + 1 个全局规则
        assertEquals(3, rules.size)

        val appRule = rules[0]
        assertEquals("com.example.app", appRule.id)
        assertEquals("com.example.app", appRule.app)
        assertEquals("示例应用", appRule.name)
        assertTrue(appRule.enabled)
        val group = appRule.groups.single()
        assertEquals(1, group.key)
        assertEquals("开屏广告", group.name)
        assertEquals(1, group.order)
        assertEquals(listOf("com.example.app.SplashActivity"), group.activityIds)
        assertEquals(listOf(".SearchActivity"), group.excludeActivityIds)
        assertEquals(8_000L, group.matchTime)
        assertEquals(1, group.actionMaximum)
        assertEquals(1_500L, group.actionCd)
        assertEquals(ResetMatch.MATCH, group.resetMatch)
        assertEquals(listOf("TextView[text*=\"跳过\"]"), group.rules.single().matches)

        val offRule = rules[1]
        assertEquals("com.example.off", offRule.id)
        assertFalse(offRule.enabled)
        assertEquals(200L, offRule.groups.single().rules.single().actionDelay)

        val globalRule = rules[2]
        assertEquals(SkipRule.GLOBAL_APP, globalRule.app)
        assertEquals("gkd_global_0", globalRule.id)
        assertEquals("开屏广告", globalRule.name)
        assertTrue(globalRule.isGlobal)
        val globalGroup = globalRule.groups.single()
        assertEquals(2, globalGroup.actionMaximum)
        assertEquals(10_000L, globalGroup.matchTime)
        assertEquals(ResetMatch.APP, globalGroup.resetMatch)
        val firstRule = globalGroup.rules[0]
        assertTrue(firstRule.fastQuery)
        assertEquals(listOf("[text*=\"跳过\"][text.length<10][visibleToUser=true]"), firstRule.matches)
        val secondRule = globalGroup.rules[1]
        assertEquals(listOf("[vid*=\"skip\"]", "[vid*=\"count\" && vid*=\"down\"]"), secondRule.matches)
        assertEquals(MatchAction.CLICK_CENTER, secondRule.action)
        assertEquals(-1, secondRule.key)
    }

    @Test
    fun `读取 GKD 订阅版本号`() {
        assertEquals(1919810L, readGkdSubscriptionVersion(Json5Parser.parse(gkdSubscription)) ?: -1L)
        assertEquals(7L, readGkdSubscriptionVersion(Json5Parser.parse("{ version: 7 }")) ?: -1L)
        assertNull(readGkdSubscriptionVersion(Json5Parser.parse("{ id: 1 }")))
        assertNull(readGkdSubscriptionVersion(Json5Parser.parse("{ version: null }")))
        assertNull(readGkdSubscriptionVersion(null))
        assertNull(readGkdSubscriptionVersion("不是对象"))
    }

    // ==================== 边界与异常 ====================

    @Test
    fun `空输入与缺字段用默认值`() {
        assertTrue(parseSkipRules(JSONArray()).isEmpty())
        assertTrue(parseSkipRule(JSONObject()).groups.isEmpty())

        val empty = parseSkipRule(JSONObject())
        assertEquals("", empty.id)
        assertEquals("", empty.app)
        assertEquals("", empty.name)
        assertTrue(empty.enabled)

        assertTrue(parseGkdSubscription(null).isEmpty())
        assertTrue(parseGkdSubscription(Json5Parser.parse("{ }")).isEmpty())
        assertTrue(parseGkdSubscription(Json5Parser.parse("[1, 2]")).isEmpty())
        assertTrue(parseGkdSubscription(Json5Parser.parse("{ apps: [ { name: '缺少 id' } ] }")).isEmpty())

        val group = parseRuleGroup(JSONObject())
        assertEquals(10_000L, group.matchTime)
        assertEquals(1, group.actionMaximum)
        assertEquals(1_000L, group.actionCd)
        assertEquals(ResetMatch.APP, group.resetMatch)

        val rule = parseMatchRule(JSONObject())
        assertEquals(0L, rule.matchDelay)
        assertEquals(0L, rule.actionDelay)
        assertNull(rule.matchTime)
        assertNull(rule.actionMaximum)
        assertNull(rule.actionCd)
        assertNull(rule.position)
    }

    @Test
    fun `matches 为空与非法 action 回落`() {
        assertTrue(parseMatchRule(JSONObject().put("matches", JSONArray())).matches.isEmpty())
        assertTrue(parseMatchRule(JSONObject().put("matches", "")).matches.isEmpty())

        // 非法/缺失 action → CLICK；大小写不敏感
        assertEquals(MatchAction.CLICK, parseMatchRule(JSONObject().put("action", "不存在的动作")).action)
        assertEquals(MatchAction.CLICK, parseMatchRule(JSONObject()).action)
        assertEquals(MatchAction.BACK, parseMatchRule(JSONObject().put("action", "BACK")).action)
        assertEquals(MatchAction.LONG_CLICK, parseMatchRule(JSONObject().put("action", "longclick")).action)
    }

    @Test
    fun `非法 resetMatch 回落 ACTIVITY 缺省保持 APP`() {
        // 写法非法 → 回落 ACTIVITY（ResetMatch.from 的兜底）
        assertEquals(ResetMatch.ACTIVITY, parseRuleGroup(JSONObject().put("resetMatch", "bogus")).resetMatch)
        // 缺失或为 JSON null → 沿用领域模型默认值 APP
        assertEquals(ResetMatch.APP, parseRuleGroup(JSONObject()).resetMatch)
        assertEquals(ResetMatch.APP, parseRuleGroup(JSONObject().put("resetMatch", JSONObject.NULL)).resetMatch)
    }

    @Test
    fun `parseSkipRules 跳过非对象元素`() {
        val rules = parseSkipRules(JSONArray().put("坏数据").put(fullRule().toJson()).put(JSONObject.NULL))
        assertEquals(1, rules.size)
        assertEquals(fullRule(), rules.single())
    }

    @Test
    fun `position 只认数值型坐标`() {
        val rule = parseMatchRule(JSONObject().put("position", JSONObject().put("x", 0.25).put("y", 0.5)))
        assertEquals(ClickPosition(0.25f, 0.5f), rule.position)
        // GKD 的节点相对表达式写法无法换算成归一化屏幕坐标 → 返回 null
        assertNull(parseMatchRule(JSONObject().put("position", JSONObject().put("left", "width/2"))).position)
        assertNull(parseMatchRule(JSONObject().put("position", JSONObject().put("x", 0.5))).position)
    }
}
