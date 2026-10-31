package com.bsfeng.monitor.ui

// 从 ui/SkipRuleScreen.kt 拆出：一条规则的两层 —— RuleRow（标题/计数/启用徽标/展开箭头，可折叠）与 RuleDetail（逐组逐选择器明细）。负责「一条规则长什么样」。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberMotionTier
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.expandEnter
import com.bsfeng.monitor.ui.foundation.expandExit
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 规则行：标题 / 计数 + 启用徽标 + 可折叠的选择器明细。
 *
 * 交互口径（契约 s10-rules）：
 *  - 主区按下透明度 0.62（120ms、--ease-out）、触感 LIGHT，不叠 ripple（原型 .rule-main:active）；
 *  - 展开 / 收起：箭头 120ms 转 90 度、明细 220ms 上浮淡入（expandEnter）；
 *  - 键盘：行主区 2dp 焦点环，Enter 与 Space 等价；开关独立取环、Space 只切开关（由 AppSwitch 承担）；
 *  - 选择器为 0 的行不可展开，箭头替换为「—」。
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
    onCopySelector: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(t.durState, easing = t.easeStandard),
        label = "ruleChevron",
    )
    val interaction = remember { MutableInteractionSource() }
    // 选择器为 0 时没有可展开的内容：整行不可展开、箭头换成「—」（契约 s10-rules 边界）。
    val expandable = selectorCountOf(rule) > 0
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .ruleMainPress(interaction, enabled = expandable)
                .then(
                    if (expandable) {
                        Modifier.keyboardFocusRing(RoundedCornerShape(t.radiusSm))
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    enabled = expandable,
                    interactionSource = interaction,
                    // 原型 .rule-main:active 只有透明度变化，不叠 ripple
                    indication = null,
                    onClickLabel = if (expanded) "收起选择器明细" else "展开选择器明细",
                ) {
                    haptic(HapticTone.LIGHT)
                    onToggleExpand()
                }
                .onPreviewKeyEvent { event ->
                    // clickable 只映射 Enter / 方向中键；契约要求 Space 与 Enter 等价。
                    // KeyDown 也要吞掉：否则 LazyColumn 会把 Space 当翻页键再滚一屏。
                    if (expandable && event.key == Key.Spacebar) {
                        if (event.type == KeyEventType.KeyUp) {
                            haptic(HapticTone.LIGHT)
                            onToggleExpand()
                        }
                        true
                    } else {
                        false
                    }
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
            if (expandable) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = t.muted,
                    modifier = Modifier.width(t.space5).rotate(chevron),
                )
            } else {
                Text(
                    "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.meta,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(t.space5),
                )
            }
            Spacer(Modifier.width(t.space2))
            // 开关自带 LIGHT 触感（Switches.kt:86），调用方不再补，避免一次操作震两下。
            AppSwitch(
                rule.enabled,
                { onToggleEnabled() },
                contentDescription = (rule.name.ifEmpty { rule.app }) + " 规则",
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandEnter(),
            exit = expandExit(),
        ) {
            RuleDetail(rule, onCopySelector)
        }
        if (!expanded) Spacer(Modifier.height(t.space1))
    }
}

/**
 * 规则行主区按下反馈：透明度降到 [RULE_PRESS_ALPHA]（原型 .rule-main:active 的 opacity .62），
 * 时长取 --dur-press、缓动取 --ease-out；精简档不播。
 *
 * 不复用 RowPress（0.99 + alpha .9）：契约给本行写的是 .62，且 RowPress 在 ui/components 写区外、
 * 无法按行覆盖系数（已记交接文档遗留）。
 */
@Composable
private fun Modifier.ruleMainPress(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    if (rememberMotionTier() == MotionTier.REDUCED) return this
    val pressed by interaction.collectIsPressedAsState()
    val t = LocalDesignTokens.current
    val alpha by animateFloatAsState(
        targetValue = if (pressed) RULE_PRESS_ALPHA else 1f,
        animationSpec = tween(t.durPress, easing = t.easeOut),
        label = "ruleMainPressAlpha",
    )
    return graphicsLayer { this.alpha = alpha }
}

/** 规则明细：每个规则组的选择器逐条列出（排查「跳不过 / 误触」的唯一入口）。 */
@Composable
private fun RuleDetail(rule: SkipRule, onCopySelector: (String) -> Unit) {
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
                        // 单行省略号截断；长按复全文并 toast「已复制选择器」（契约 s10-rules 边界行）。
                        // 用 detectTapGestures 而不是 combinedClickable：明细行不该变成可点节点
                        // （否则多出 ripple 与焦点落点）。长按时长取平台默认（500ms）。
                        Box(
                            Modifier
                                .weight(1f)
                                .pointerInput(line) {
                                    detectTapGestures(onLongPress = { onCopySelector(line) })
                                },
                        ) {
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                color = t.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(t.space2))
        }
    }
}

/** 原型 s10 规则行专用图标尺寸（.screen.s10 .rule-main.has-icon .app-icon = 25px）。 */
private val RULE_ICON_SIZE = 25.dp

/** 规则行主区按下的透明度（原型 .rule-main:active 的 opacity .62）。 */
private const val RULE_PRESS_ALPHA = 0.62f
