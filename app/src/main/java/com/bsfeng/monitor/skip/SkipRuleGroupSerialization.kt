package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleSerialization.kt 的 RuleGroup 部分；职责：规则组（RuleGroup）序列化/反序列化与 rules 的单值/数组兼容。

import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import org.json.JSONArray
import org.json.JSONObject

/** 序列化规则组。 */
fun RuleGroup.toJson(): JSONObject = JSONObject().apply {
    put("key", key)
    put("name", name)
    put("order", order)
    put("enabled", enabled)
    put("activityIds", JSONArray(activityIds))
    put("excludeActivityIds", JSONArray(excludeActivityIds))
    put("matchTime", matchTime)
    put("actionMaximum", actionMaximum)
    put("actionCd", actionCd)
    put("resetMatch", resetMatch.value)
    put("rules", JSONArray(rules.map { it.toJson() }))
}

/**
 * 反序列化规则组；缺失字段用 [RuleGroup] 默认值填充。
 *
 * `rules` 支持四种写法：单个对象、对象数组、单个字符串（等价于只有 `matches` 的规则）、字符串数组。
 * `resetMatch` 缺失时用模型默认值，写法非法时回落 [ResetMatch.ACTIVITY]（见 [ResetMatch.from]）。
 */
fun parseRuleGroup(json: JSONObject): RuleGroup = RuleGroup(
    key = readInt(json, "key", GROUP_DEFAULTS.key),
    name = readString(json, "name", GROUP_DEFAULTS.name),
    order = readInt(json, "order", GROUP_DEFAULTS.order),
    enabled = readEnabled(json, GROUP_DEFAULTS.enabled),
    activityIds = readStringList(json, "activityIds"),
    excludeActivityIds = readStringList(json, "excludeActivityIds"),
    matchTime = readLong(json, "matchTime", GROUP_DEFAULTS.matchTime),
    actionMaximum = readInt(json, "actionMaximum", GROUP_DEFAULTS.actionMaximum),
    actionCd = readLong(json, "actionCd", GROUP_DEFAULTS.actionCd),
    resetMatch = readResetMatch(json, GROUP_DEFAULTS.resetMatch),
    rules = parseRuleItems(json.opt("rules"))
)

/** 解析规则组列表；支持数组写法，非对象元素跳过。 */
internal fun parseRuleGroups(value: Any?): List<RuleGroup> = when (value) {
    is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it)?.let(::parseRuleGroup) }
    is Collection<*> -> value.filterIsInstance<JSONObject>().map(::parseRuleGroup)
    else -> emptyList()
}

/** 解析规则列表：支持单个对象、对象数组、单个字符串与字符串数组（GKD `IArray` 语义）。 */
internal fun parseRuleItems(value: Any?): List<MatchRule> = when (value) {
    is JSONObject -> listOf(parseMatchRule(value))
    is JSONArray -> (0 until value.length()).mapNotNull { parseRuleItem(value.opt(it)) }
    is Collection<*> -> value.mapNotNull(::parseRuleItem)
    else -> parseRuleItem(value)?.let(::listOf) ?: emptyList()
}

/** 解析单条规则项：对象 → [MatchRule]；非空字符串 → 只有 `matches` 的 [MatchRule]；其它 → null。 */
private fun parseRuleItem(value: Any?): MatchRule? = when {
    value is JSONObject -> parseMatchRule(value)
    value is String && value.isNotEmpty() -> MatchRule(matches = listOf(value))
    else -> null
}
