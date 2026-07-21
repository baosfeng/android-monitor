package com.bsfeng.monitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.skip.SkipRule

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

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部导航栏
        TopAppBar(
            title = { Text("开屏广告跳过", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Text("← 返回")
                }
            },
            actions = {
                TextButton(onClick = { viewModel.resetToBuiltIn() }) {
                    Text("重置")
                }
            }
        )

        // 状态摘要
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("已启用规则", fontWeight = FontWeight.Bold)
                    Text(
                        "${state.enabledCount} / ${state.rules.size}",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "自动跳过开屏广告",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 规则列表
        if (state.rules.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无规则", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.rules.size, key = { index -> state.rules[index].id }) { index ->
                    val rule = state.rules[index]
                    RuleItem(
                        rule = rule,
                        onToggle = { viewModel.toggleRule(rule.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleItem(
    rule: SkipRule,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = rule.app,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = "${rule.groups.size} 组规则 · ${rule.groups.sumOf { it.rules.size }} 条匹配",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}
