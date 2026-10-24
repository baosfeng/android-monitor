package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleSerialization.kt 的 GKD 订阅部分；职责：GKD 订阅（apps / globalGroups）映射为领域模型。

import com.bsfeng.monitor.domain.model.SkipRule
import org.json.JSONArray
import org.json.JSONObject

/**
 * GKD 订阅（[com.bsfeng.monitor.skip.json.Json5Parser] 的解析结果）→ 规则列表。
 *
 * - `apps[]` → 每个应用一条应用规则：id/app 均为包名，name 缺失时回落包名，缺 id 的元素跳过；
 * - `globalGroups[]` → 每个全局规则组一条全局规则：app = [SkipRule.GLOBAL_APP]，id = `gkd_global_<key>`；
 * - 返回顺序为「先应用规则，后全局规则」，与订阅文件中的书写顺序一致；
 * - 无法映射的字段见 [SkipRuleSerialization] 文件头 KDoc（按应用禁用名单、`position` 表达式写法等一律忽略）。
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

/**
 * 把订阅的 `globalGroups[]` 转成全局规则；整个组对象即该规则唯一的规则组。
 *
 * GKD 用组内的 `apps:[{id, enable:false}]` 声明"该全局规则在这些 App 上不生效"，
 * 这里转成 [SkipRule.excludedApps]，由 [SkipRuleManager.findRulesFor] 过滤。
 */
private fun parseGkdGlobalGroups(groups: JSONArray): List<SkipRule> =
    (0 until groups.length()).mapNotNull { index ->
        val group = groups.optJSONObject(index) ?: return@mapNotNull null
        val key = readInt(group, "key", GROUP_DEFAULTS.key)
        SkipRule(
            id = "gkd_global_$key",
            app = SkipRule.GLOBAL_APP,
            name = readString(group, "name", "gkd_global_$key"),
            enabled = readEnabled(group, true),
            excludedApps = parseGkdDisabledApps(group.opt("apps")),
            groups = listOf(parseRuleGroup(group))
        )
    }

/** 解析 GKD 全局组的按应用开关：`enable == false` 的包名进入排除名单。 */
private fun parseGkdDisabledApps(apps: Any?): List<String> {
    val array = apps as? JSONArray ?: return emptyList()
    val result = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val id = readString(item, "id", "")
        if (id.isEmpty()) continue
        if (!readEnabled(item, true)) result.add(id)
    }
    return result
}
