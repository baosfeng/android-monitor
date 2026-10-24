package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleSerialization.kt；职责：规则序列化入口与 SkipRule 层（本地规则文件的读写约定）。

import com.bsfeng.monitor.domain.model.SkipRule
import org.json.JSONArray
import org.json.JSONObject

/**
 * 开屏跳过规则序列化（org.json 实现，零第三方依赖）。
 *
 * 覆盖两条来源：
 * 1. **本地规则文件** —— [SkipRule.toJson] / [parseSkipRule] / [parseSkipRules]，字段与领域模型一一对应；
 * 2. **GKD 订阅规则** —— [parseGkdSubscription] / [readGkdSubscriptionVersion]，输入是
 *    [com.bsfeng.monitor.skip.json.Json5Parser] 的解析结果，字段名兼容 GKD 规范：
 *    `quickFind` 与 `fastQuery` 等价、`enable`/`enabled`/`disabled` 三种启用写法、
 *    `matches`/`anyMatches`/`excludeMatches`/`activityIds`/`excludeActivityIds`/`rules` 的单值/数组两种写法。
 *
 * 选择器字符串一律**原样保存**，交给选择器引擎解析，本文件不做语法校验。
 *
 * 实现按模型分文件：规则组见 `SkipRuleGroupSerialization.kt`，匹配规则见 `MatchRuleSerialization.kt`，
 * GKD 订阅映射见 `GkdSubscriptionSerialization.kt`，字段读取辅助见 `SkipRuleJsonReader.kt`。
 */

/** 序列化规则；`groups` 逐项序列化，字段与领域模型一一对应。 */
fun SkipRule.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("app", app)
    put("name", name)
    put("enabled", enabled)
    put("excludedApps", JSONArray(excludedApps))
    put("groups", JSONArray(groups.map { it.toJson() }))
}

/**
 * 反序列化规则；缺失字段用领域模型默认值填充（传入空 [JSONObject] 也安全，不抛异常）。
 *
 * `enabled` 同时兼容 GKD 的 `enable` 与历史写法 `disabled`。
 */
fun parseSkipRule(json: JSONObject): SkipRule = SkipRule(
    id = readString(json, "id", ""),
    app = readString(json, "app", ""),
    name = readString(json, "name", ""),
    enabled = readEnabled(json, true),
    excludedApps = readStringList(json, "excludedApps"),
    groups = parseRuleGroups(json.opt("groups"))
)

/** 反序列化规则数组；元素不是 JSON 对象时跳过（不抛异常），便于容错读取本地规则文件。 */
fun parseSkipRules(array: JSONArray): List<SkipRule> =
    (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parseSkipRule) }
