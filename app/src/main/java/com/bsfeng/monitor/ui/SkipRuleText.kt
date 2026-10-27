package com.bsfeng.monitor.ui

// 从 ui/SkipRuleScreen.kt 拆出：规则明细文案 —— groupScopeText / matchRuleText / selectorLines 与两个计数助手。SkipRuleFormatTest 直接测这三个函数，语义固定。

import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 规则组作用域与闸门摘要 */
internal fun groupScopeText(group: RuleGroup): String {
    val window = if (group.matchTime > 0) "matchTime=" + (group.matchTime / 1000) + "s" else "matchTime=不限"
    val scope = if (group.activityIds.isEmpty()) "界面=全部" else "界面=" + group.activityIds.joinToString("|")
    val excluded = if (group.excludeActivityIds.isEmpty()) "" else " · 排除界面=" + group.excludeActivityIds.joinToString("|")
    return window + " · actionMaximum=" + group.actionMaximum + " · actionCd=" + group.actionCd + "ms" +
        " · reset=" + group.resetMatch.value + " · " + scope + excluded
}

/** 单条匹配规则的摘要（动作 + 非常规闸门） */
internal fun matchRuleText(matchRule: MatchRule): String {
    val guards = mutableListOf<String>()
    if (matchRule.matchDelay > 0) guards.add("matchDelay=" + matchRule.matchDelay + "ms")
    if (matchRule.actionDelay > 0) guards.add("actionDelay=" + matchRule.actionDelay + "ms")
    matchRule.matchTime?.let { guards.add("matchTime=" + (it / 1000) + "s") }
    matchRule.actionMaximum?.let { guards.add("actionMaximum=" + it) }
    matchRule.actionCd?.let { guards.add("actionCd=" + it + "ms") }
    matchRule.actionMaximumKey?.let { guards.add("actionMaximumKey=" + it) }
    matchRule.actionCdKey?.let { guards.add("actionCdKey=" + it) }
    if (matchRule.preKeys.isNotEmpty()) guards.add("preKeys=" + matchRule.preKeys.joinToString(","))
    if (!matchRule.fastQuery) guards.add("fastQuery=false")
    val name = "#" + matchRule.key + " " + matchRule.name.ifEmpty { "未命名" } + " · " + matchRule.action.value
    return if (guards.isEmpty()) name else name + "（" + guards.joinToString(" · ") + "）"
}

/** 选择器明细：matches 全命中才动作，anyMatches 任一命中即动作，excludeMatches 命中则放弃 */
internal fun selectorLines(matchRule: MatchRule): List<String> {
    val lines = mutableListOf<String>()
    matchRule.matches.forEach { lines.add("matches: " + it) }
    matchRule.anyMatches.forEach { lines.add("any: " + it) }
    matchRule.excludeMatches.forEach { lines.add("exclude: " + it) }
    if (lines.isEmpty()) lines.add("（无选择器）")
    return lines
}

/** 单条规则的选择器总数（matches + anyMatches + excludeMatches）。 */
internal fun selectorCountOf(rule: SkipRule): Int =
    rule.groups.sumOf { group -> group.rules.sumOf { it.matches.size + it.anyMatches.size + it.excludeMatches.size } }

internal fun selectorKindCount(rules: List<SkipRule>, pick: (MatchRule) -> Int): Int =
    rules.sumOf { rule -> rule.groups.sumOf { group -> group.rules.sumOf { pick(it) } } }

internal fun hostOf(url: String): String =
    url.substringAfter("://", url).substringBefore("/").ifEmpty { "未填写订阅源" }

/** 订阅源卡底「镜像」段：URL 为空显示「—」（契约 s10-url 输入行边界）。 */
internal fun mirrorHostOf(url: String): String =
    url.substringAfter("://", url).substringBefore("/").ifEmpty { "—" }

/** 千分位整数（契约 s10-summary：超 9,999 用千分位，字号不变）。 */
internal fun formatRuleCount(value: Int): String = String.format(Locale.CHINA, "%,d", value)

internal fun formatSyncTime(atMs: Long): String =
    if (atMs <= 0L) "尚未同步"
    else Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).format(SYNC_TIME_FORMATTER)

/** 静态复用（线程安全）；原实现每次调用都 new SimpleDateFormat（J8 热点）。 */
private val SYNC_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
