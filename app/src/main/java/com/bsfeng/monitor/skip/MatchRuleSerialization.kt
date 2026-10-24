package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleSerialization.kt 的 MatchRule 部分；职责：匹配规则（MatchRule）序列化/反序列化、preKeys 与点击位置。

import com.bsfeng.monitor.domain.model.ClickPosition
import com.bsfeng.monitor.domain.model.MatchAction
import com.bsfeng.monitor.domain.model.MatchRule
import org.json.JSONArray
import org.json.JSONObject

/** 序列化匹配规则；可空字段为 null 时写入 JSON null 占位，保证往返无损（不能写入 Kotlin null，会被 org.json 删键）。 */
fun MatchRule.toJson(): JSONObject = JSONObject().apply {
    put("key", key)
    put("name", name)
    put("matches", JSONArray(matches))
    put("anyMatches", JSONArray(anyMatches))
    put("excludeMatches", JSONArray(excludeMatches))
    put("action", action.value)
    put("matchDelay", matchDelay)
    put("actionDelay", actionDelay)
    put("matchTime", matchTime ?: JSONObject.NULL)
    put("actionMaximum", actionMaximum ?: JSONObject.NULL)
    put("actionCd", actionCd ?: JSONObject.NULL)
    put("actionCdKey", actionCdKey ?: JSONObject.NULL)
    put("actionMaximumKey", actionMaximumKey ?: JSONObject.NULL)
    put("preKeys", JSONArray(preKeys))
    put("fastQuery", fastQuery)
    put("position", position?.toJson() ?: JSONObject.NULL)
}

/**
 * 反序列化匹配规则；缺失字段用 [MatchRule] 默认值填充。
 *
 * - `action` 缺失或非法时回落 [MatchAction.CLICK]（见 [MatchAction.from]），大小写不敏感；
 * - `quickFind` 是 GKD 旧字段名，与 `fastQuery` 等价，二者都缺失时用模型默认值；
 * - `matchTime`/`actionMaximum`/`actionCd` 缺失或为 null 表示继承规则组配置；
 * - `position` 只认数值型 `{x, y}`（数字或数字字符串），其它写法返回 null。
 */
fun parseMatchRule(json: JSONObject): MatchRule = MatchRule(
    key = readInt(json, "key", RULE_DEFAULTS.key),
    name = readString(json, "name", RULE_DEFAULTS.name),
    matches = readStringList(json, "matches"),
    anyMatches = readStringList(json, "anyMatches"),
    excludeMatches = readStringList(json, "excludeMatches"),
    action = MatchAction.from(readString(json, "action", "")),
    matchDelay = readLong(json, "matchDelay", RULE_DEFAULTS.matchDelay),
    actionDelay = readLong(json, "actionDelay", RULE_DEFAULTS.actionDelay),
    matchTime = readOptionalLong(json, "matchTime"),
    actionMaximum = readOptionalInt(json, "actionMaximum"),
    actionCd = readOptionalLong(json, "actionCd"),
    actionCdKey = readOptionalInt(json, "actionCdKey"),
    actionMaximumKey = readOptionalInt(json, "actionMaximumKey"),
    preKeys = readIntList(json, "preKeys"),
    fastQuery = readFastQuery(json, RULE_DEFAULTS.fastQuery),
    position = parseClickPosition(json.optJSONObject("position"))
)

/** 读取整数数组（用于 `preKeys`）；缺字段或元素非法时忽略该项。 */
private fun readIntList(json: JSONObject, key: String): List<Int> {
    val array = json.optJSONArray(key) ?: return emptyList()
    val result = mutableListOf<Int>()
    for (i in 0 until array.length()) {
        val value = array.optInt(i, Int.MIN_VALUE)
        if (value != Int.MIN_VALUE) result.add(value)
    }
    return result
}

/** 序列化自定义点击位置（归一化 0..1，存 Double 保证 Float 精确往返）。 */
private fun ClickPosition.toJson(): JSONObject = JSONObject().apply {
    put("x", x.toDouble())
    put("y", y.toDouble())
}

/** 解析自定义点击位置；`x`/`y` 缺一不可，任一非法则返回 null（表示不指定位置）。 */
private fun parseClickPosition(json: JSONObject?): ClickPosition? {
    if (json == null) return null
    val x = readNumber(json, "x") ?: return null
    val y = readNumber(json, "y") ?: return null
    return ClickPosition(x.toFloat(), y.toFloat())
}
