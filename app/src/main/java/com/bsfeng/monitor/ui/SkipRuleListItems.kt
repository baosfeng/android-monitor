package com.bsfeng.monitor.ui

// 从 ui/SkipRuleScreen.kt 拆出：规则列表的 LazyListScope 扩展（规则行 + 选择器种类图例）。
// 拆成扩展是为了让屏幕文件只描述「有哪些块」，行级细节留在本文件。

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.chartRamp

/**
 * 规则行：一卡一行（不是一卡一规则卡）。
 *
 * StackRow 首行圆上角 / 末行圆下角、行间 0 间距，视觉上仍是改动前那一张连续卡片。
 * 用 itemsIndexed 而非 items 是为了拿到 index：首尾圆角与错落入场都依赖它；
 * **但 id 不能单独作 key**：rule.id 只在单个来源内唯一（GKD 订阅的 apps[] 用包名、
 * globalGroups[] 用 gkd_global_N、内置规则用自定义短名，多订阅源/自定义规则之间可以撞），
 * 而 getAllRules() 只是拼接、不去重 —— key 撞车会直接抛 "Key was already used"。
 */
internal fun LazyListScope.skipRuleItems(
    rules: List<SkipRule>,
    expandedRuleIds: MutableMap<String, Boolean>,
    onToggleExpand: (SkipRule) -> Unit,
    onToggleEnabled: (SkipRule) -> Unit,
    onCopySelector: (String) -> Unit,
) {
    itemsIndexed(
        items = rules,
        key = { index, rule -> rule.id + "#" + index },
    ) { index, rule ->
        // 入场动画只给首屏可见项：staggerEnter 会给每行挂一个 graphicsLayer，
        // 长列表滚动时合成成本被放大（AdScreen 同一处理）。
        val enterMod = if (index < STAGGER_MAX_STEPS) Modifier.staggerEnterAt(index) else Modifier
        StackRow(
            first = index == 0,
            last = index == rules.lastIndex,
            modifier = Modifier.padding(horizontal = LocalDesignTokens.current.space4).then(enterMod),
        ) {
            RuleRow(
                rule = rule,
                expanded = expandedRuleIds[rule.id] == true,
                onToggleExpand = { onToggleExpand(rule) },
                onToggleEnabled = { onToggleEnabled(rule) },
                onCopySelector = onCopySelector,
            )
        }
    }
}

/**
 * 选择器种类图例：四类选择器的计数。
 *
 * 计数缓存进 [remember]：旧实现每次重组都对全量规则做 4 轮嵌套求和，
 * 620 条规则下是纯主线程开销。
 */
@androidx.compose.runtime.Composable
internal fun SkipRuleLegend(rules: List<SkipRule>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val legendItems = remember(rules, t) {
        listOf(
            Triple("全部匹配 matches", selectorKindCount(rules) { it.matches.size }, t.chartRamp[0]),
            Triple("任选其一 anyMatches", selectorKindCount(rules) { it.anyMatches.size }, t.chartRamp[1]),
            Triple("排除 excludeMatches", selectorKindCount(rules) { it.excludeMatches.size }, t.chartRamp[2]),
            Triple("规则组 groups", rules.sumOf { it.groups.size }, t.chartRamp[3]),
        )
            // 条数为 0 的类型整行隐藏（锚点 s10-legend 边界：不出现 0 行）
            .filter { it.second > 0 }
            .map { LegendItem(it.first, it.second.toString(), it.third) }
    }
    // 全部类型都为 0（空规则库）时整块图例不渲染
    if (legendItems.isEmpty()) return
    Legend(items = legendItems, modifier = modifier)
}
