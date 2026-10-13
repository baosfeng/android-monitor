package com.bsfeng.monitor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.ui.theme.InsetGroupedCard
import com.bsfeng.monitor.ui.theme.IOSNavigationBar
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.SettingsRow

/**
 * 开屏广告跳过规则管理页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkipRuleScreen(
    onBack: () -> Unit,
    viewModel: SkipRuleViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        IOSNavigationBar(
            title = "开屏广告跳过",
            onBack = onBack,
            actions = {
                TextButton(onClick = { viewModel.resetToBuiltIn() }) {
                    Text("重置", color = MaterialTheme.colorScheme.primary)
                }
            },
        )

        // ===== 订阅规则（支持多个订阅源）+ 页面快照 =====
        var showUrlDialog by remember { mutableStateOf(false) }
        var urlInput by remember { mutableStateOf("") }
        // 非空 = 正在更新该订阅源；null = 新增订阅
        var updateTarget by remember { mutableStateOf<String?>(null) }
        val hapticView = LocalView.current
        val defaultUrl = "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5"

        InsetGroupedCard(
            modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.xs)
        ) {
            state.subscriptions.forEach { subscription ->
                SettingsRow(
                    title = displayUrl(subscription.url),
                    subtitle = "v" + subscription.version + " · " + subscription.ruleCount + " 条规则" +
                        if (state.updatingUrl == subscription.url) " · 更新中…" else "",
                    trailing = {
                        TextButton(onClick = {
                            updateTarget = subscription.url
                            urlInput = subscription.url
                            showUrlDialog = true
                            hapticView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }) {
                            Text("更新", color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = {
                            viewModel.removeSubscription(subscription.url)
                            hapticView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }) {
                            Text("删除", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            SettingsRow(
                title = "添加订阅",
                subtitle = if (state.subscriptions.isEmpty())
                    "未订阅：可添加 GKD 兼容订阅地址获取社区规则（支持多个源）"
                else "可继续添加其它 GKD 兼容订阅地址",
                trailing = {
                    TextButton(onClick = {
                        updateTarget = null
                        urlInput = defaultUrl
                        showUrlDialog = true
                        hapticView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }) {
                        Text("添加", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SettingsRow(
                title = "页面快照",
                subtitle = state.snapshotPath ?: "导出当前前台页面的无障碍树，便于编写精确规则",
                trailing = {
                    TextButton(onClick = {
                        viewModel.requestSnapshot()
                        hapticView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }) {
                        Text("导出", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }

        state.message?.let { message ->
            LaunchedEffect(message) {
                delay(4_000)
                viewModel.consumeMessage()
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.xxs)
            )
        }

        if (showUrlDialog) {
            val target = updateTarget
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text(if (target == null) "添加订阅" else "更新订阅") },
                text = {
                    Column {
                        if (target == null) {
                            Text(
                                text = "支持 GKD 兼容订阅（JSON5，仅 https），可添加多个订阅源。推荐地址：" +
                                    "registry.npmmirror.com/@gkd-kit/subscription（jsdelivr 在真机上下载不稳定）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                singleLine = true,
                                // URL 输入必须用 Uri 键盘：默认文本键盘会被中文输入法接管，
                                // 网址里的字母会被当成拼音转成汉字
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = MonitorSpacing.sm)
                            )
                        } else {
                            Text(
                                text = "重新拉取该订阅源的最新规则：" + target,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateSubscription(target ?: urlInput)
                        showUrlDialog = false
                        hapticView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }) {
                        Text(if (target == null) "添加" else "更新")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlDialog = false }) { Text("取消") }
                }
            )
        }

        // 状态摘要
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.xs),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MonitorSpacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "已启用规则",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${state.enabledCount} / ${state.rules.size}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "自动跳过开屏广告",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(Modifier.height(MonitorSpacing.xs))

        // 规则列表
        if (state.rules.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "暂无规则",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = MonitorSpacing.lg,
                    vertical = MonitorSpacing.xs,
                ),
                verticalArrangement = Arrangement.spacedBy(MonitorSpacing.xs),
            ) {
                items(state.rules.size, key = { index -> state.rules[index].id }) { index ->
                    val rule = state.rules[index]
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 6 },
                    ) {
                        RuleItem(
                            rule = rule,
                            onToggle = { viewModel.toggleRule(rule.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 订阅地址展示文案：去掉协议前缀并截断，避免一行放不下 */
private fun displayUrl(url: String): String {
    val text = url.removePrefix("https://")
    return if (text.length <= 42) text else text.take(42) + "…"
}

@Composable
private fun RuleItem(
    rule: SkipRule,
    onToggle: () -> Unit,
) {
    val view = LocalView.current
    // 展开后显示每条匹配规则的选择器：排查"为什么没跳/为什么误点"的唯一入口
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                    .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = rule.app,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MonitorSpacing.xxs),
                    )
                    Text(
                        text = "${rule.groups.size} 组规则 · ${rule.groups.sumOf { it.rules.size }} 条匹配 · " +
                            if (expanded) "收起 ▴" else "展开 ▾",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MonitorSpacing.xxs),
                    )
                }
                Spacer(Modifier.width(MonitorSpacing.sm))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = {
                        onToggle()
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    },
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(
                        start = MonitorSpacing.lg,
                        end = MonitorSpacing.lg,
                        bottom = MonitorSpacing.md,
                    )
                ) {
                    rule.groups.sortedBy { it.order }.forEach { group ->
                        RuleGroupDetail(group)
                    }
                }
            }
        }
    }
}

/**
 * 规则组明细：闸门参数 + 每条匹配规则的选择器。
 *
 * 排查用：真机上「跳不过 / 误触」时先看这里——
 * 时间闸门（matchTime）是否留足、activityIds 是否限定了界面、选择器到底匹配什么。
 */
@Composable
private fun RuleGroupDetail(group: RuleGroup) {
    Column(modifier = Modifier.padding(bottom = MonitorSpacing.sm)) {
        Text(
            text = group.name.ifEmpty { "规则组" } + (if (group.enabled) "" else "（已停用）"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = groupScopeText(group),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MonitorSpacing.xxs),
        )
        group.rules.forEach { matchRule ->
            Text(
                text = matchRuleText(matchRule),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = MonitorSpacing.sm),
            )
            selectorLines(matchRule).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = MonitorSpacing.sm, top = MonitorSpacing.xxs),
                )
            }
        }
    }
}

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
