package com.bsfeng.monitor.skip

// 来源：原 skip/BuiltInRules.kt 的 globalSplashRule 与构造辅助；职责：内置规则对象与规则组/匹配规则的构造工厂。

import com.bsfeng.monitor.domain.model.MatchAction
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule

// ==================== 全局规则 ====================

internal fun globalSplashRule(matchTimeMs: Long): SkipRule = SkipRule(
    id = BuiltInRules.GLOBAL_SPLASH_RULE_ID,
    app = SkipRule.GLOBAL_APP,
    name = "开屏广告（全局）",
    enabled = true,
    groups = listOf(
        RuleGroup(
            key = 0,
            name = "开屏广告-跳过",
            order = 0,
            matchTime = matchTimeMs,
            actionMaximum = 1,
            actionCd = BUILT_IN_ACTION_CD_MS,
            resetMatch = ResetMatch.APP,
            rules = listOf(
                rule(0, "跳过文本", TEXT_SKIP),
                rule(1, "跳过描述", DESC_SKIP),
                rule(2, "跳过按钮 ID", VIEW_ID_SKIP)
            )
        ),
        RuleGroup(
            key = 1,
            name = "开屏广告-关闭广告",
            order = 1,
            matchTime = matchTimeMs,
            actionMaximum = 1,
            actionCd = BUILT_IN_ACTION_CD_MS,
            resetMatch = ResetMatch.APP,
            rules = listOf(rule(0, "关闭广告文本", CLOSE_AD_TEXT))
        )
    )
)

// ==================== 构造辅助 ====================

internal fun appRule(id: String, app: String, name: String, groups: List<RuleGroup>): SkipRule =
    SkipRule(id = id, app = app, name = name, enabled = true, groups = groups)

/** 开屏规则组：与监控窗口一致的匹配时间 + 每次进入 App 仅执行一次 */
internal fun splashGroup(
    matchTimeMs: Long,
    activityIds: List<String> = emptyList(),
    rules: List<MatchRule>
): RuleGroup = RuleGroup(
    key = 0,
    name = "开屏广告",
    order = 0,
    activityIds = activityIds,
    matchTime = matchTimeMs,
    actionMaximum = 1,
    actionCd = BUILT_IN_ACTION_CD_MS,
    resetMatch = ResetMatch.APP,
    rules = rules
)

/**
 * 单条匹配规则。
 *
 * @param actionDelayMs 命中后延迟执行（毫秒）：到期且仍然命中才点击，用于抗误触/等渲染完成
 * @param actionCdMs 本规则单独的冷却（毫秒），0 表示沿用组配置
 */
internal fun rule(
    key: Int,
    name: String,
    vararg matches: String,
    actionDelayMs: Long = 0L,
    actionCdMs: Long = 0L
): MatchRule = MatchRule(
    key = key,
    name = name,
    matches = matches.toList(),
    action = MatchAction.CLICK,
    actionDelay = actionDelayMs,
    actionCd = actionCdMs.takeIf { it > 0 },
    fastQuery = true
)
