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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.ui.theme.IOSNavigationBar
import com.bsfeng.monitor.ui.theme.MonitorSpacing

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
