package com.bsfeng.monitor.ui

// 从 ui/SkipRuleScreen.kt 拆出：一条规则的两层 —— RuleRow（标题/计数/启用徽标/展开箭头，可折叠）与 RuleDetail（逐组逐选择器明细）。负责「一条规则长什么样」。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.foundation.expandEnter
import com.bsfeng.monitor.ui.foundation.expandExit

/**
 * 规则行：标题 / 计数 + 启用徽标 + 可折叠的选择器明细。
 *
 * 展开态 [expanded] 与入场 modifier 都由调用方（LazyColumn 的 item）注入：item 滚出视口会被
 * 回收，行内 remember 保不住展开状态（见 SkipRuleScreen 的 expandedRuleIds）。
 */
@Composable
internal fun RuleRow(
    rule: SkipRule,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(t.durState, easing = t.easeStandard),
        label = "ruleChevron",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onToggleExpand()
                }
                .padding(vertical = t.space2)
                .heightIn(min = t.rowMinH - t.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 原型 s10 规则行以应用为主体：行首是该应用的图标（08-prototype.html:4610 的
            // .rule-main.has-icon > .app-icon，25px/间距 5px）；取不到真实图标时 AppIcon 走
            // 「品牌色底 + 首字」兜底，全局规则（app = "*"）没有包名，直接兜底。
            AppIcon(
                packageName = rule.app.takeIf { !rule.isGlobal },
                appName = rule.name.ifEmpty { rule.app },
                size = RULE_ICON_SIZE,
            )
            Spacer(Modifier.width(t.space1))
            Column(Modifier.weight(1f)) {
                Text(
                    rule.name.ifEmpty { rule.app },
                    style = MaterialTheme.typography.bodyLarge,
                    color = t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    selectorCountOf(rule).toString() + " 个选择器 · " + rule.groups.size + " 组",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(t.space2))
            Badge(if (rule.enabled) "启用" else "停用", tone = if (rule.enabled) BadgeTone.Success else BadgeTone.Neutral)
            Spacer(Modifier.width(t.space2))
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = t.muted,
                modifier = Modifier.width(t.space5).rotate(chevron),
            )
            Spacer(Modifier.width(t.space2))
            AppSwitch(rule.enabled, { onToggleEnabled() })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandEnter(),
            exit = expandExit(),
        ) {
            RuleDetail(rule)
        }
        if (!expanded) Spacer(Modifier.height(t.space1))
    }
}

/** 规则明细：每个规则组的选择器逐条列出（排查「跳不过 / 误触」的唯一入口）。 */
@Composable
private fun RuleDetail(rule: SkipRule) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth().padding(bottom = t.space3)) {
        rule.groups.sortedBy { it.order }.forEach { group ->
            Text(
                group.name.ifEmpty { "规则组" } + if (group.enabled) "" else "（已停用）",
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
            )
            Text(
                groupScopeText(group),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
            )
            Spacer(Modifier.height(t.space1))
            group.rules.forEach { matchRule ->
                Text(
                    matchRuleText(matchRule),
                    style = MaterialTheme.typography.labelMedium,
                    color = t.fg2,
                    modifier = Modifier.padding(top = t.space1),
                )
                selectorLines(matchRule).forEachIndexed { i, line ->
                    Row(Modifier.fillMaxWidth().padding(top = t.space1)) {
                        Text(
                            (i + 1).toString().padStart(2, '0'),
                            style = MaterialTheme.typography.labelSmall,
                            color = t.meta,
                            modifier = Modifier.width(t.space6),
                        )
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = t.muted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(t.space2))
        }
    }
}

/** 原型 s10 规则行专用图标尺寸（.screen.s10 .rule-main.has-icon .app-icon = 25px）。 */
private val RULE_ICON_SIZE = 25.dp
