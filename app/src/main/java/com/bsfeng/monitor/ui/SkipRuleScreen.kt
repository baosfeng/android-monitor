package com.bsfeng.monitor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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

        // ===== 订阅规则 + 页面快照 =====
        var showUrlDialog by remember { mutableStateOf(false) }
        var urlInput by remember { mutableStateOf(state.subscriptionUrl.orEmpty()) }
        val hapticView = LocalView.current

        InsetGroupedCard(
            modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.xs)
        ) {
            SettingsRow(
                title = "订阅规则",
                subtitle = subscriptionSubtitle(state),
                trailing = {
                    TextButton(onClick = {
                        urlInput = state.subscriptionUrl.orEmpty()
                        showUrlDialog = true
                        hapticView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }) {
                        Text(
                            text = if (state.subscriptionUrl == null) "添加" else "更新",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
            if (state.subscriptionUrl != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsRow(
                    title = "清除订阅",
                    subtitle = "删除已下载的订阅规则（保留内置与自定义规则）",
                    trailing = {
                        TextButton(onClick = {
                            viewModel.clearSubscription()
                            hapticView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }) {
                            Text("清除", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
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
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("GKD 订阅地址") },
                text = {
                    Column {
                        Text(
                            text = "支持 GKD 兼容订阅（JSON5，仅 https）。可填写社区订阅地址，例如 " +
                                "https://fastly.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = MonitorSpacing.sm)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateSubscription(urlInput)
                        showUrlDialog = false
                        hapticView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }) {
                        Text("更新")
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

/** 订阅状态摘要文案 */
private fun subscriptionSubtitle(state: SkipRuleUiState): String = when {
    state.subscriptionUpdating -> "正在更新…"
    state.subscriptionUrl == null -> "未订阅：可添加 GKD 兼容订阅地址获取社区规则"
    else -> state.subscriptionUrl + "（v" + state.subscriptionVersion + " · " + state.subscriptionRuleCount + " 条）"
}

@Composable
private fun RuleItem(
    rule: SkipRule,
    onToggle: () -> Unit,
) {
    val view = LocalView.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                    text = "${rule.groups.size} 组规则 · ${rule.groups.sumOf { it.rules.size }} 条匹配",
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
    }
}
