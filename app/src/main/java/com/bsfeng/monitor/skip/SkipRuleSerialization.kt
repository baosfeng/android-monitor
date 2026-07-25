package com.bsfeng.monitor.skip

import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import org.json.JSONArray
import org.json.JSONObject

// ==================== SkipRule JSON 序列化扩展 ====================

fun SkipRule.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("app", app)
    put("name", name)
    put("enabled", enabled)
    put("groups", JSONArray(groups.map { it.toJson() }))
}

fun parseSkipRule(json: JSONObject): SkipRule = SkipRule(
    id = json.getString("id"),
    app = json.getString("app"),
    name = json.getString("name"),
    enabled = json.optBoolean("enabled", true),
    groups = json.optJSONArray("groups")?.let { arr ->
        (0 until arr.length()).map { parseRuleGroup(arr.getJSONObject(it)) }
    } ?: emptyList()
)

// ==================== RuleGroup JSON 序列化扩展 ====================

fun RuleGroup.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("activityIds", JSONArray(activityIds))
    put("rules", JSONArray(rules.map { it.toJson() }))
}

fun parseRuleGroup(json: JSONObject): RuleGroup = RuleGroup(
    name = json.getString("name"),
    activityIds = json.optJSONArray("activityIds")?.let { arr ->
        (0 until arr.length()).map { arr.getString(it) }
    } ?: emptyList(),
    rules = json.optJSONArray("rules")?.let { arr ->
        (0 until arr.length()).map { parseMatchRule(arr.getJSONObject(it)) }
    } ?: emptyList()
)

// ==================== MatchRule JSON 序列化扩展 ====================

fun MatchRule.toJson(): JSONObject = JSONObject().apply {
    put("key", key)
    put("matchDelay", matchDelay)
    put("actionCd", actionCd)
    put("actionMaximum", actionMaximum)
    put("matches", JSONArray(matches))
    put("action", action)
    put("fastQuery", fastQuery)
}

fun parseMatchRule(json: JSONObject): MatchRule = MatchRule(
    key = json.optInt("key", 0),
    matchDelay = json.optLong("matchDelay", 500),
    actionCd = json.optLong("actionCd", 30000),
    actionMaximum = json.optInt("actionMaximum", 1),
    matches = json.getJSONArray("matches").let { arr ->
        (0 until arr.length()).map { arr.getString(it) }
    },
    action = json.optString("action", "click"),
    fastQuery = json.optBoolean("fastQuery", true)
)
