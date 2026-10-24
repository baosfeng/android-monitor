package com.bsfeng.monitor.ui

// 从 ui/SkipRuleScreen.kt 拆出：规则列表与图例 —— 每行一个 RuleRow（首尾圆角 + 错落入场）、
// 选择器种类图例（4 个计数在规则变化时才重算）。负责「规则一条条排下来」。

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.chartRamp

/**
 * 规则列表（LazyColumn 的 DSL 片段）：更新中骨架 → 「规则列表」小节头 → 规则行 → 选择器图例。
 *
 * 外层调用方负责 LazyColumn 本身与上面两张卡；这里只追加列表尾部三块。
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.skipRuleListSection(
    rules: List<SkipRule>,
    updating: Boolean,
    expandedRuleIds: Map<String, Boolean>,
    onToggleExpand: (SkipRule) -> Unit,
    onToggleEnabled: (SkipRule) -> Unit,
) {
    // 更新中的加载指示（P1）：原型 s10 的加载态就是一叠 .sk 骨架行
    // （08-prototype.html:4695-4704 的 .st-loading），Compose 侧对应 StateSkeleton，
    // 用法与既有调用点一致（ComponentGalleryScreen.kt:475 的
    // FiveStateSlot：AppCard { StateSkeleton(rows = 4) }）。
    if (updating) {
        item(key = "rules-loading") {
            SkipRuleLoadingCard()
        }
    }

    // ── 规则列表 ──
    item(key = "rules-head") {
        SkipRuleListHead()
    }

    // 规则行 = 一卡一行（不是一卡一规则卡）：StackRow 首行圆上角 / 末行圆下角、
    // 行间 0 间距，视觉上仍是改动前那一张连续卡片（相邻边框相接即原 TokenDivider）。
    itemsIndexed(
        items = rules,
        // 用 itemsIndexed 而非 items 是为了拿到 index：首尾圆角与错落入场都依赖它；
        // key 仍以 rule.id 为主体。**但 id 不能单独作 key**：rule.id 只在单个来源内
        // 唯一（GKD 订阅的 apps[] 用包名、globalGroups[] 用 gkd_global_N、内置规则用
        // 自定义短名，多订阅源/自定义规则之间可以撞），而 getAllRules() 只是
        // customRules + subscriptionRules + builtInRules 的拼接、不去重 —— key 撞车会
        // 直接抛 "Key was already used"。故与 AdScreen.kt:269 同一兜底：id + 下标。
        key = { index, rule -> rule.id + "#" + index },
    ) { index, rule ->
        SkipRuleListRow(
            rule = rule,
            index = index,
            isLast = index == rules.lastIndex,
            expanded = expandedRuleIds[rule.id] == true,
            onToggleExpand = { onToggleExpand(rule) },
            onToggleEnabled = { onToggleEnabled(rule) },
        )
    }

    // ── 选择器种类图例 ──
    item(key = "legend") {
        SkipRuleLegend(rules)
    }
}

@Composable
private fun SkipRuleLoadingCard() {
    val t = LocalDesignTokens.current
    AppCard(Modifier.padding(start = t.space4, end = t.space4, bottom = t.space4)) {
        Text(
            "正在拉取订阅源并合并规则…（下方仍为本地现有规则）",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space3))
        StateSkeleton(rows = 3)
    }
}

@Composable
private fun SkipRuleListHead() {
    val t = LocalDesignTokens.current
    SecHead("规则列表", Modifier.padding(bottom = t.space2), right = "点击行展开选择器明细")
}

@Composable
private fun SkipRuleListRow(
    rule: SkipRule,
    index: Int,
    isLast: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    val t = LocalDesignTokens.current
    // 入场动画只给首屏可见项：staggerEnter 会给每行挂一个 graphicsLayer，
    // 长列表滚动时合成成本被放大（AdScreen.kt:271-278 同一处理）。
    val enterMod = if (index < STAGGER_MAX_STEPS) Modifier.staggerEnterAt(index) else Modifier
    StackRow(
        first = index == 0,
        last = isLast,
        modifier = Modifier.padding(horizontal = t.space4).then(enterMod),
    ) {
        RuleRow(
            rule = rule,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            onToggleEnabled = onToggleEnabled,
        )
    }
}

@Composable
private fun SkipRuleLegend(rules: List<SkipRule>) {
    val t = LocalDesignTokens.current
    // 图例计数缓存进 remember(rules)：旧实现每次重组都对全量规则做 4 轮
    // 嵌套求和，620 条规则下是纯主线程开销（与 AdScreen.kt:122-129 派生值缓存同一手法）。
    val legendItems = remember(rules) {
        listOf(
            LegendItem(
                "全部匹配 matches",
                selectorKindCount(rules) { it.matches.size }.toString(),
                t.chartRamp[0],
            ),
            LegendItem(
                "任选其一 anyMatches",
                selectorKindCount(rules) { it.anyMatches.size }.toString(),
                t.chartRamp[1],
            ),
            LegendItem(
                "排除 excludeMatches",
                selectorKindCount(rules) { it.excludeMatches.size }.toString(),
                t.chartRamp[2],
            ),
            LegendItem("规则组 groups", rules.sumOf { it.groups.size }.toString(), t.chartRamp[3]),
        )
    }
    Legend(
        items = legendItems,
        modifier = Modifier.padding(start = t.space4, end = t.space4, top = t.space4),
    )
}
