package com.bsfeng.monitor.skip

import org.json.JSONArray
import org.json.JSONObject

/**
 * 开屏跳过规则数据模型。
 *
 * 代表一个 App 的完整跳过配置，包含多个 [RuleGroup]，每个组包含多条 [MatchRule]。
 */
data class SkipRule(
    /** 规则唯一标识 */
    val id: String,
    /** 应用包名 */
    val app: String,
    /** 应用名称 */
    val name: String,
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 规则组列表（按组管理多条匹配规则） */
    val groups: List<RuleGroup> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("app", app)
        put("name", name)
        put("enabled", enabled)
        put("groups", JSONArray(groups.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): SkipRule = SkipRule(
            id = json.getString("id"),
            app = json.getString("app"),
            name = json.getString("name"),
            enabled = json.optBoolean("enabled", true),
            groups = json.optJSONArray("groups")?.let { arr ->
                (0 until arr.length()).map { RuleGroup.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList()
        )
    }
}

/**
 * 规则组。
 *
 * 一组针对特定 Activity 的匹配规则。
 */
data class RuleGroup(
    /** 组名（如"开屏广告"、"弹窗广告"） */
    val name: String,
    /** 限定的 Activity 类名列表（空表示不限定） */
    val activityIds: List<String> = emptyList(),
    /** 匹配规则列表 */
    val rules: List<MatchRule> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("activityIds", JSONArray(activityIds))
        put("rules", JSONArray(rules.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): RuleGroup = RuleGroup(
            name = json.getString("name"),
            activityIds = json.optJSONArray("activityIds")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            rules = json.optJSONArray("rules")?.let { arr ->
                (0 until arr.length()).map { MatchRule.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList()
        )
    }
}

/**
 * 匹配规则。
 *
 * 定义在什么条件下、对什么节点执行什么操作。
 */
data class MatchRule(
    /** 规则键（组内唯一） */
    val key: Int = 0,
    /** 匹配延迟（进入页面后等待多少毫秒再匹配） */
    val matchDelay: Long = 500,
    /** 执行冷却时间（毫秒，执行后冷却期内不重复触发） */
    val actionCd: Long = 30000,
    /** 最大执行次数（<=0 表示不限） */
    val actionMaximum: Int = 1,
    /** 选择器表达式列表（按顺序尝试，任意一个匹配即执行） */
    val matches: List<String>,
    /** 动作类型 */
    val action: String = "click",
    /** 是否启用快速查询（优先使用原生 API 加速） */
    val fastQuery: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("matchDelay", matchDelay)
        put("actionCd", actionCd)
        put("actionMaximum", actionMaximum)
        put("matches", JSONArray(matches))
        put("action", action)
        put("fastQuery", fastQuery)
    }

    companion object {
        fun fromJson(json: JSONObject): MatchRule = MatchRule(
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
    }
}
