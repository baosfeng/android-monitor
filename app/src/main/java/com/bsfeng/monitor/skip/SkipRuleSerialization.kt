package com.bsfeng.monitor.skip

import com.bsfeng.monitor.domain.model.ClickPosition
import com.bsfeng.monitor.domain.model.MatchAction
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
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
 * 模型里没有对应属性、因此**解析时直接忽略**的 GKD 字段（不改模型，也不写额外 JSON 键）：
 * - 全局规则的按应用禁用名单 `globalGroups[].apps[{id,enable:false}]`：领域模型只有一个「全局/单应用」
 *   维度（[SkipRule.app]），无法表达「同一全局规则对某应用禁用」；
 * - `position` 的 GKD 表达式写法（`left/top/right/bottom`、`'width/2'`、`'screenWidth/2'`）：它是
 *   **相对目标节点**且依赖运行时屏幕/节点尺寸，无法无损换算成归一化屏幕比例；
 * - `desc`/`preKeys`/`actionCdKey`/`actionMaximumKey`/`matchRoot`/`scopeKeys`/`forcedTime`/
 *   `priorityTime`/`priorityActionMaximum`/`swipeArg`/`matchLauncher`/`matchAnyApp`/`matchSystemApp`/
 *   `categories`/`versionNames`/`versionCodes` 等。
 *
 * 选择器字符串一律**原样保存**，交给选择器引擎解析，本文件不做语法校验。
 */

/** 规则组默认值载体：直接引用 [RuleGroup] 的默认参数，避免默认值在两处漂移。 */
private val GROUP_DEFAULTS = RuleGroup()

/** 匹配规则默认值载体：直接引用 [MatchRule] 的默认参数，避免默认值在两处漂移。 */
private val RULE_DEFAULTS = MatchRule()

// ==================== SkipRule ====================

/** 序列化规则；`groups` 逐项序列化，字段与领域模型一一对应。 */
fun SkipRule.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("app", app)
    put("name", name)
    put("enabled", enabled)
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
    groups = parseRuleGroups(json.opt("groups"))
)

/** 反序列化规则数组；元素不是 JSON 对象时跳过（不抛异常），便于容错读取本地规则文件。 */
fun parseSkipRules(array: JSONArray): List<SkipRule> =
    (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parseSkipRule) }

// ==================== RuleGroup ====================

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

// ==================== MatchRule ====================

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
    fastQuery = readFastQuery(json, RULE_DEFAULTS.fastQuery),
    position = parseClickPosition(json.optJSONObject("position"))
)

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

// ==================== GKD 订阅 ====================

/**
 * GKD 订阅（[com.bsfeng.monitor.skip.json.Json5Parser] 的解析结果）→ 规则列表。
 *
 * - `apps[]` → 每个应用一条应用规则：id/app 均为包名，name 缺失时回落包名，缺 id 的元素跳过；
 * - `globalGroups[]` → 每个全局规则组一条全局规则：app = [SkipRule.GLOBAL_APP]，id = `gkd_global_<key>`；
 * - 返回顺序为「先应用规则，后全局规则」，与订阅文件中的书写顺序一致；
 * - 无法映射的字段见文件头 KDoc（按应用禁用名单、`position` 表达式写法等一律忽略）。
 *
 * @param root 订阅根节点，需为对象（Map/JSONObject）；其它类型返回空列表
 */
fun parseGkdSubscription(root: Any?): List<SkipRule> {
    val json = toJsonObject(root) ?: return emptyList()
    val rules = mutableListOf<SkipRule>()
    json.optJSONArray("apps")?.let { rules += parseGkdApps(it) }
    json.optJSONArray("globalGroups")?.let { rules += parseGkdGlobalGroups(it) }
    return rules
}

/**
 * 读取 GKD 订阅版本号（顶层 `version`）。
 *
 * @param root 订阅根节点（JSON5 解析结果）
 * @return 版本号；根节点不是对象、缺 `version` 或值非法时返回 null
 */
fun readGkdSubscriptionVersion(root: Any?): Long? {
    val json = toJsonObject(root) ?: return null
    return readNumber(json, "version")?.toLong()
}

/** 把订阅的 `apps[]` 转成应用规则（id/app 均为包名）。 */
private fun parseGkdApps(apps: JSONArray): List<SkipRule> =
    (0 until apps.length()).mapNotNull { index ->
        val app = apps.optJSONObject(index) ?: return@mapNotNull null
        val appId = readString(app, "id", "")
        if (appId.isEmpty()) return@mapNotNull null
        SkipRule(
            id = appId,
            app = appId,
            name = readString(app, "name", appId),
            enabled = readEnabled(app, true),
            groups = parseRuleGroups(app.opt("groups"))
        )
    }

/** 把订阅的 `globalGroups[]` 转成全局规则；整个组对象即该规则唯一的规则组。 */
private fun parseGkdGlobalGroups(groups: JSONArray): List<SkipRule> =
    (0 until groups.length()).mapNotNull { index ->
        val group = groups.optJSONObject(index) ?: return@mapNotNull null
        val key = readInt(group, "key", GROUP_DEFAULTS.key)
        SkipRule(
            id = "gkd_global_$key",
            app = SkipRule.GLOBAL_APP,
            name = readString(group, "name", "gkd_global_$key"),
            enabled = readEnabled(group, true),
            groups = listOf(parseRuleGroup(group))
        )
    }

// ==================== JSON / JSON5 读取辅助 ====================

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
private fun toJsonObject(root: Any?): JSONObject? = toJsonValue(root) as? JSONObject

/** 读取字符串；缺失、JSON null 时返回 [fallback]，数字/布尔转成字面量字符串。 */
private fun readString(json: JSONObject, key: String, fallback: String): String {
    val value = json.opt(key)
    if (value == null || value == JSONObject.NULL) return fallback
    return if (value is String) value else value.toString()
}

/** 读取数字（兼容数字与数字字符串）；缺失、JSON null 或非法时返回 null。 */
private fun readNumber(json: JSONObject, key: String): Double? = when (val value = json.opt(key)) {
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
}

/** 读取 Int；缺失或非法时用 [fallback]。 */
private fun readInt(json: JSONObject, key: String, fallback: Int): Int =
    readNumber(json, key)?.toInt() ?: fallback

/** 读取 Long；缺失或非法时用 [fallback]。 */
private fun readLong(json: JSONObject, key: String, fallback: Long): Long =
    readNumber(json, key)?.toLong() ?: fallback

/** 读取可空 Int：缺失或为 JSON null 时返回 null（表示继承上级配置）。 */
private fun readOptionalInt(json: JSONObject, key: String): Int? =
    if (json.isNull(key)) null else readNumber(json, key)?.toInt()

/** 读取可空 Long：缺失或为 JSON null 时返回 null（表示继承上级配置）。 */
private fun readOptionalLong(json: JSONObject, key: String): Long? =
    if (json.isNull(key)) null else readNumber(json, key)?.toLong()

/** 读取启用状态：兼容 `enabled`/`enable`/`disabled` 三种写法（GKD 用 `enable`，历史本地规则用 `disabled`）。 */
private fun readEnabled(json: JSONObject, fallback: Boolean): Boolean = when {
    json.has("enabled") -> json.optBoolean("enabled", fallback)
    json.has("enable") -> json.optBoolean("enable", fallback)
    json.has("disabled") -> !json.optBoolean("disabled", false)
    else -> fallback
}

/** 读取快速查询开关：`quickFind`（GKD 旧字段名）与 `fastQuery` 等价，二者都缺失时用 [fallback]。 */
private fun readFastQuery(json: JSONObject, fallback: Boolean): Boolean = when {
    json.has("fastQuery") -> json.optBoolean("fastQuery", fallback)
    json.has("quickFind") -> json.optBoolean("quickFind", fallback)
    else -> fallback
}

/** 读取 resetMatch；缺失时用 [fallback]，写法非法时回落 [ResetMatch.ACTIVITY]（见 [ResetMatch.from]）。 */
private fun readResetMatch(json: JSONObject, fallback: ResetMatch): ResetMatch {
    val raw = readString(json, "resetMatch", "")
    return if (raw.isEmpty()) fallback else ResetMatch.from(raw)
}

/** 读取字符串列表：兼容单个字符串与字符串数组两种写法（GKD `IArray` 语义），非字符串元素跳过。 */
private fun readStringList(json: JSONObject, key: String): List<String> {
    val value = json.opt(key)
    return when {
        value == null || value == JSONObject.NULL -> emptyList()
        value is String -> if (value.isEmpty()) emptyList() else listOf(value)
        value is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }
        value is Collection<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }
}

/** 解析规则组列表；支持数组写法，非对象元素跳过。 */
private fun parseRuleGroups(value: Any?): List<RuleGroup> = when (value) {
    is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it)?.let(::parseRuleGroup) }
    is Collection<*> -> value.filterIsInstance<JSONObject>().map(::parseRuleGroup)
    else -> emptyList()
}

/** 解析规则列表：支持单个对象、对象数组、单个字符串与字符串数组（GKD `IArray` 语义）。 */
private fun parseRuleItems(value: Any?): List<MatchRule> = when (value) {
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
