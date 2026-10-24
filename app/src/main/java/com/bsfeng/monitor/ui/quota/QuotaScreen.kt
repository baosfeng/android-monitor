package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.HeadIconButton
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 「额度」Tab（聚合视图 = 原型 s20a，单家视图 = s18）。
 *
 * 信息架构：页头（标题 + 刷新 + 设置）→ 「聚合 / 单家」分段 → 对应视图。
 * 聚合视图自带 provider 结论条与位次列表，不再显示 provider chip（chip 只服务于单家）；
 * 单家视图保持原样：provider chip → Ollama 计费模式分段 → 主面板。
 *
 * 四态由数据层状态推导，不在本地维护一份：
 * - 加载中 / 未配置凭据 / 查询失败 / 有数据；
 * - 查询失败但有旧数据时**保留旧卡**，只在上面补一条「数据停在 HH:mm」（见 [QuotaErrorBar]）。
 *
 * 请求全部经 ViewModel 下发到 `Dispatchers.IO`：组合里只读 StateFlow，不在组合期发请求。
 */
@Composable
fun QuotaScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuotaViewModel = viewModel(factory = QuotaViewModelFactory),
) {
    val t = LocalDesignTokens.current
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedProviderId.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val plan by viewModel.ollamaPlan.collectAsStateWithLifecycle()
    val aggregateMode by viewModel.aggregateMode.collectAsStateWithLifecycle()
    val snapshot by viewModel.aggregateSnapshot.collectAsStateWithLifecycle()
    val toast = rememberToastHostState()
    val info = providers.firstOrNull { it.id == selectedId }
    // 聚合视图的 meta 复用原型的「N 家参与排序 · 更新于 9:41」：
    // 页头已经显示时间，聚合视图再在下面单独排一行同样的时间是重复信息
    val updatedMeta = if (aggregateMode) {
        aggregateMetaText(snapshot)
    } else if (state.fetchedAtEpochSecond > 0L) {
        QuotaFormat.clockOf(state.fetchedAtEpochSecond) + " 更新"
    } else {
        "尚未更新"
    }
    // 切 provider / 进聚合视图时拉一次；repository 自带节流，来回切不会打满限流
    LaunchedEffect(selectedId, aggregateMode) {
        if (aggregateMode) viewModel.onAggregateVisible() else viewModel.onProviderVisible()
    }

    // 前台定时刷新：间隔按 provider 在设置里定（默认 1 分钟），repository 内还有一层节流兜底
    val refreshMinutes by viewModel.refreshMinutes.collectAsStateWithLifecycle()
    LaunchedEffect(selectedId, refreshMinutes, aggregateMode) {
        while (true) {
            delay(refreshMinutes.coerceAtLeast(1) * 60_000L)
            viewModel.onTick()
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            QuotaHead(
                updated = updatedMeta,
                onRefresh = viewModel::onRefresh,
                onOpenSettings = onOpenSettings,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = t.space2, bottom = t.space12),
                verticalArrangement = Arrangement.spacedBy(t.space3),
            ) {
                item {
                    ViewModeSwitch(
                        aggregateMode = aggregateMode,
                        onSelect = viewModel::onAggregateModeChange,
                    )
                }
                if (aggregateMode) {
                    item {
                        QuotaAggregateBody(
                            snapshot = snapshot,
                            onOpenProvider = viewModel::onOpenProviderFromAggregate,
                            onConfigure = onOpenSettings,
                        )
                    }
                } else {
                    // 聚合视图不显示 chip：榜单里每行已经是 provider，再摆一排 chip 只是重复同一组家
                    item {
                        ProviderChipRow(
                            providers = providers,
                            selectedId = selectedId,
                            onSelect = viewModel::onSelectProvider,
                        )
                    }
                    item {
                        QuotaBody(
                            info = info,
                            state = state,
                            plan = plan,
                            onOpenSettings = onOpenSettings,
                            onRetry = viewModel::onRefresh,
                            onSaveCredentials = viewModel::onSaveCredentials,
                            onRangeChange = viewModel::onRangeChange,
                            onSaved = { toast.show("已保存，正在查询") },
                        )
                    }
                }
            }
        }
        AppToast(
            message = toast.message,
            onDismiss = toast::clear,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space8),
        )
    }
}

/**
 * 页头：标题 + 更新时间 + 刷新 + 设置，压成一行。
 *
 * 早期版本把「N 个 provider 已连接 · 更新于 HH:mm」单独占一行，
 * 加上下面的计费模式分段，一屏只剩半张额度卡；连接数已由 chip 上的绿点表达。
 */
@Composable
private fun QuotaHead(
    updated: String,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = t.space4, end = t.space4, top = t.space3, bottom = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("额度", style = MaterialTheme.typography.headlineMedium, color = t.fg)
        Spacer(Modifier.width(t.space2))
        Text(updated, style = MaterialTheme.typography.labelSmall, color = t.meta, modifier = Modifier.weight(1f))
        HeadIconButton(
            icon = Icons.Outlined.Refresh,
            contentDescription = "刷新额度",
            onClick = {
                haptic(HapticTone.MEDIUM)
                onRefresh()
            },
        )
        Spacer(Modifier.width(t.space2))
        HeadIconButton(
            icon = Icons.Outlined.Settings,
            contentDescription = "额度设置",
            onClick = {
                haptic(HapticTone.LIGHT)
                onOpenSettings()
            },
        )
    }
}



/**
 * 主面板四态。
 *
 * 收在一个函数里是因为状态互斥：拆成多个 LazyColumn item 时，状态切换会造成
 * 高度估算跳变（骨架行数 ≠ 卡片高度），滚动位置会抖。
 */
@Composable
private fun QuotaBody(
    info: QuotaProviderInfo?,
    state: QuotaRepository.ProviderState,
    plan: OllamaPlanMode,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onSaveCredentials: (balance: String, usage: String) -> Unit,
    onRangeChange: (QuotaRange) -> Unit,
    onSaved: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val error = state.errorMessage
    val hasData = state.items.isNotEmpty() || state.usage != null
    val now = System.currentTimeMillis() / 1000L
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = t.space4),
        verticalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        when {
            info == null -> QuotaLoading()
            state.loading && !hasData -> QuotaLoading()
            !state.balanceConfigured && !state.usageConfigured -> QuotaConfigCard(
                info = info,
                onSave = { balance, usage ->
                    onSaveCredentials(balance, usage)
                    onSaved()
                },
            )
            error != null && !hasData -> ErrorState(
                text = error,
                title = "查询失败",
                onRetry = onRetry,
            )
            else -> {
                if (error != null) {
                    QuotaErrorBar(error, state.fetchedAtEpochSecond, onRetry = onRetry)
                }
                if (state.items.isNotEmpty()) {
                    QuotaCard(
                        providerId = info.id,
                        state = state,
                        plan = plan,
                        nowEpochSecond = now,
                        modifier = Modifier.staggerEnterAt(0),
                    )
                }
                if (state.usage != null) {
                    UsageCard(
                        usage = state.usage,
                        range = state.range,
                        costUnit = costUnitOf(state),
                        onRangeChange = onRangeChange,
                        modifier = Modifier.staggerEnterAt(1),
                    )
                } else if (info.supportsUsage) {
                    QuotaUsageUnavailableCard(onOpenSettings)
                }
            }
        }
    }
}

/** 花费币种：用量本身不带币种，取额度项的主币种（Ollama=USD，DeepSeek=CNY）。 */
private fun costUnitOf(state: QuotaRepository.ProviderState): QuotaUnit {
    // 用量自带币种最可靠（DeepSeek 是 CNY、Ollama 是 USD）；
    // 没有额度项时不能再靠 items 猜，否则 DeepSeek 的花费会被标成 $
    state.usage?.costUnit?.let { if (it == QuotaUnit.CNY || it == QuotaUnit.USD) return it }
    val unit = state.items.firstOrNull()?.unit
    return if (unit == QuotaUnit.CNY || unit == QuotaUnit.USD) unit else QuotaUnit.USD
}

private const val OLLAMA_ID = "ollama"
