package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleSerialization.kt 的读取辅助部分；职责：样式各异的 JSON 字段读取（字符串/数字/可空值/启用状态/列表）与
// JSON5→org.json 归一化，供本地规则与 GKD 订阅共用。

import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import org.json.JSONArray
import org.json.JSONObject

/** 规则组默认值载体：直接引用 [RuleGroup] 的默认参数，避免默认值在两处漂移。 */
internal val GROUP_DEFAULTS = RuleGroup()

/** 匹配规则默认值载体：直接引用 [MatchRule] 的默认参数，避免默认值在两处漂移。 */
internal val RULE_DEFAULTS = MatchRule()

/**
 * JSON5 解析结果（Map/List/标量）→ org.json 结构，使 GKD 订阅与本地规则共用同一套字段解析逻辑。
 * 非 Map/List 的值原样返回；无值转 [JSONObject.NULL] 占位。
 */
private fun toJsonValue(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply {
        value.forEach { (key, item) -> put(key?.toString() ?: "", toJsonValue(item)) }
    }
    is List<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
    else -> value
}

/** 把任意输入规范成 [JSONObject]；输入不是对象（或为 null）时返回 null。 */
internal fun toJsonObject(root: Any?): JSONObject? = toJsonValue(root) as? JSONObject

/** 读取字符串；缺失、JSON null 时返回 [fallback]，数字/布尔转成字面量字符串。 */
internal fun readString(json: JSONObject, key: String, fallback: String): String {
    val value = json.opt(key)
    if (value == null || value == JSONObject.NULL) return fallback
    return if (value is String) value else value.toString()
}

/** 读取数字（兼容数字与数字字符串）；缺失、JSON null 或非法时返回 null。 */
internal fun readNumber(json: JSONObject, key: String): Double? = when (val value = json.opt(key)) {
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
}

/** 读取 Int；缺失或非法时用 [fallback]。 */
internal fun readInt(json: JSONObject, key: String, fallback: Int): Int =
    readNumber(json, key)?.toInt() ?: fallback

/** 读取 Long；缺失或非法时用 [fallback]。 */
internal fun readLong(json: JSONObject, key: String, fallback: Long): Long =
    readNumber(json, key)?.toLong() ?: fallback

/** 读取可空 Int：缺失或为 JSON null 时返回 null（表示继承上级配置）。 */
internal fun readOptionalInt(json: JSONObject, key: String): Int? =
    if (json.isNull(key)) null else readNumber(json, key)?.toInt()

/** 读取可空 Long：缺失或为 JSON null 时返回 null（表示继承上级配置）。 */
internal fun readOptionalLong(json: JSONObject, key: String): Long? =
    if (json.isNull(key)) null else readNumber(json, key)?.toLong()

/** 读取启用状态：兼容 `enabled`/`enable`/`disabled` 三种写法（GKD 用 `enable`，历史本地规则用 `disabled`）。 */
internal fun readEnabled(json: JSONObject, fallback: Boolean): Boolean = when {
    json.has("enabled") -> json.optBoolean("enabled", fallback)
    json.has("enable") -> json.optBoolean("enable", fallback)
    json.has("disabled") -> !json.optBoolean("disabled", false)
    else -> fallback
}

/** 读取快速查询开关：`quickFind`（GKD 旧字段名）与 `fastQuery` 等价，二者都缺失时用 [fallback]。 */
internal fun readFastQuery(json: JSONObject, fallback: Boolean): Boolean = when {
    json.has("fastQuery") -> json.optBoolean("fastQuery", fallback)
    json.has("quickFind") -> json.optBoolean("quickFind", fallback)
    else -> fallback
}

/** 读取 resetMatch；缺失时用 [fallback]，写法非法时回落 [ResetMatch.ACTIVITY]（见 [ResetMatch.from]）。 */
internal fun readResetMatch(json: JSONObject, fallback: ResetMatch): ResetMatch {
    val raw = readString(json, "resetMatch", "")
    return if (raw.isEmpty()) fallback else ResetMatch.from(raw)
}

/** 读取字符串列表：兼容单个字符串与字符串数组两种写法（GKD `IArray` 语义），非字符串元素跳过。 */
internal fun readStringList(json: JSONObject, key: String): List<String> {
    val value = json.opt(key)
    return when {
        value == null || value == JSONObject.NULL -> emptyList()
        value is String -> if (value.isEmpty()) emptyList() else listOf(value)
        value is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }
        value is Collection<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }
}
