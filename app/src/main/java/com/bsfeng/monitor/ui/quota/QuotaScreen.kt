package com.bsfeng.monitor.ui.quota

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 刷新图标转一圈的时长（原型 .s18-refresh：720ms 一圈，不是设计 token，按原型取值）。 */
private const val REFRESH_SPIN_MS = 720

/** 「刚刚更新」的时间窗：过了这个窗口就退回「HH:mm 更新」。 */
private const val JUST_UPDATED_WINDOW_SECOND = 60L

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
    val listState = rememberLazyListState()
    val info = providers.firstOrNull { it.id == selectedId }
    // 聚合视图的 meta 复用原型的「N 家参与排序 · 更新于 9:41」：
    // 页头已经显示时间，聚合视图再在下面单独排一行同样的时间是重复信息
    val updatedMeta = updatedMetaText(
        aggregateMode = aggregateMode,
        aggregateMeta = aggregateMetaText(snapshot),
        fetchedAtEpochSecond = state.fetchedAtEpochSecond,
    )
    // 切 provider / 进聚合视图时拉一次；repository 自带节流，来回切不会打满限流。
    // 同一次切换里把列表滚回顶部（契约「滚动回顶部」）：换面板后停在上一家的深度位置会看到空白。
    LaunchedEffect(selectedId, aggregateMode) {
        if (aggregateMode) viewModel.onAggregateVisible() else viewModel.onProviderVisible()
        listState.scrollToItem(0)
    }

    // 前台定时刷新：间隔按 provider 在设置里定（默认 1 分钟），repository 内还有一层节流兜底
    val refreshMinutes by viewModel.refreshMinutes.collectAsStateWithLifecycle()
    LaunchedEffect(selectedId, refreshMinutes, aggregateMode) {
        while (true) {
            delay(refreshMinutes.coerceAtLeast(1) * 60_000L)
            viewModel.onTick()
        }
    }

    Box(modifier.fillMaxSize().testTag("screen-s18")) {
        Column(Modifier.fillMaxSize()) {
            QuotaHead(
                updated = updatedMeta,
                refreshing = state.loading,
                onRefresh = viewModel::onRefresh,
                onOpenSettings = onOpenSettings,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("s18-content"),
                contentPadding = PaddingValues(top = t.space2, bottom = t.space12),
                verticalArrangement = Arrangement.spacedBy(t.space3),
            ) {
                item {
                    ViewModeSwitch(
                        aggregateMode = aggregateMode,
                        onSelect = { aggregate ->
                            viewModel.onAggregateModeChange(aggregate)
                            toast.show(
                                if (aggregate) "聚合视图 · 按剩余由低到高" else "单家视图 · 选一家看明细"
                            )
                        },
                    )
                }
                if (aggregateMode) {
                    item {
                        QuotaAggregateBody(
                            snapshot = snapshot,
                            // 下钻 = 切单家并选中该家；toast 报「已切到单家 · X」
                            onOpenProvider = { providerId ->
                                viewModel.onOpenProviderFromAggregate(providerId)
                                val name = providers.firstOrNull { it.id == providerId }?.displayName ?: providerId
                                toast.show("已切到单家 · " + name)
                            },
                            onConfigure = onOpenSettings,
                            // 聚合视图没有「当前选中家」，重试就是刷全部（节流在数据层）
                            onRetry = viewModel::onRefresh,
                        )
                    }
                } else {
                    // 聚合视图不显示 chip：榜单里每行已经是 provider，再摆一排 chip 只是重复同一组家
                    item {
                        QuotaProviderChips(
                            providers = providers,
                            selectedId = selectedId,
                            onSelect = { providerId ->
                                val name = providers.firstOrNull { it.id == providerId }?.displayName ?: providerId
                                viewModel.onSelectProvider(providerId)
                                toast.show(if (providerId == OPEN_DESIGN_ID) name + " · 需浏览器会话令牌" else name + " · 已切换")
                            },
                        )
                    }
                    if (selectedId == OLLAMA_ID) {
                        item {
                            QuotaPlanBar(
                                plan = plan,
                                onSelect = { mode ->
                                    viewModel.onOllamaPlanChange(mode)
                                    toast.show(
                                        when (mode) {
                                            OllamaPlanMode.PERCENT -> "Ollama · 已切换为按百分比展示"
                                            OllamaPlanMode.USD -> "Ollama · 已切换为按金额展示"
                                        }
                                    )
                                },
                            )
                        }
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

/** 时间戳文案：聚合态报「N 家参与排序 · 更新于 HH:mm」，单家态「HH:mm 更新 / 刚刚更新 / 尚未更新」。 */
private fun updatedMetaText(
    aggregateMode: Boolean,
    aggregateMeta: String,
    fetchedAtEpochSecond: Long,
): String = when {
    aggregateMode -> aggregateMeta
    fetchedAtEpochSecond <= 0L -> "尚未更新"
    else -> {
        val age = System.currentTimeMillis() / 1000L - fetchedAtEpochSecond
        if (age in 0L..JUST_UPDATED_WINDOW_SECOND) "刚刚更新" else QuotaFormat.clockOf(fetchedAtEpochSecond) + " 更新"
    }
}

/**
 * 页头：标题 + 更新时间 + 刷新 + 设置，压成一行。
 *
 * 早期版本把「N 个 provider 已连接 · 更新于 HH:mm」单独占一行，
 * 加上下面的计费模式分段，一屏只剩半张额度卡；连接数已由 chip 上的绿点表达。
 * 页头固定不随列表滚动：标题与时间戳是静态文案（点击无按压态、不刷新）。
 */
@Composable
private fun QuotaHead(
    updated: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("s18-head")
            .padding(start = t.space4, end = t.space4, top = t.space3, bottom = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标题固定两字不换行
        Text("额度", style = MaterialTheme.typography.headlineMedium, color = t.fg, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(t.space2))
        Text(
            updated,
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("s18-updated"),
        )
        QuotaHeadIcon(
            icon = Icons.Outlined.Refresh,
            contentDescription = "刷新额度",
            tagModifier = Modifier.testTag("s18-refresh"),
            tone = HapticTone.MEDIUM,
            rotating = refreshing,
            onClick = onRefresh,
        )
        Spacer(Modifier.width(t.space2))
        QuotaHeadIcon(
            icon = Icons.Outlined.Settings,
            contentDescription = "额度设置",
            tagModifier = Modifier.testTag("s18-settings"),
            tone = HapticTone.LIGHT,
            onClick = onOpenSettings,
        )
    }
}

/**
 * 页头图标按钮：44dp 圆底 + 按压 0.96 + 键盘焦点环。
 *
 * 不复用 `ui/live/ScreenHeaders.kt` 的 HeadIconButton：那个只有 clickable，
 * 没有按压反馈与 2dp 焦点环（s18 契约两行都要），且它的 modifier 落在 size 之前，
 * 加不上「先定尺寸再画环」的顺序。
 */
@Composable
private fun QuotaHeadIcon(
    icon: ImageVector,
    contentDescription: String,
    tagModifier: Modifier,
    tone: HapticTone,
    onClick: () -> Unit,
    rotating: Boolean = false,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val motion = rememberMotionEnabled()
    val spin = rememberInfiniteTransition(label = "quotaHeadSpin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(REFRESH_SPIN_MS, easing = LinearEasing)),
        label = "quotaHeadSpinAngle",
    )
    Box(
        modifier = Modifier
            .size(t.tapMin)
            .clip(CircleShape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, CircleShape)
            .pressFeedback(interaction)
            .keyboardFocusRing(CircleShape)
            .then(tagModifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(tone)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = t.fg2,
            modifier = Modifier
                .size(t.iconSize / 2)
                // 只在绘制期读动画值：转圈不触发重组
                .graphicsLayer { rotationZ = if (rotating && motion) angle else 0f },
        )
    }
}

/**
 * Ollama 计费模式分段条（原型 .s18-planbar）：只在「单家 + 选中 Ollama」时存在。
 *
 * 切换只改展示口径，不重拉数据（同一份数据两种排版）；选择落盘，重启后仍生效。
 */
@Composable
private fun QuotaPlanBar(
    plan: OllamaPlanMode,
    onSelect: (OllamaPlanMode) -> Unit,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = t.space4).testTag("s18-planbar"),
    ) {
        QuotaSegmented(
            options = listOf(
                QuotaSegment("按百分比限额", Modifier.testTag("s18-plan-legacy")),
                QuotaSegment("按金额（USD）", Modifier.testTag("s18-plan-usd")),
            ),
            selectedIndex = if (plan == OllamaPlanMode.PERCENT) 0 else 1,
            onSelect = { index -> onSelect(if (index == 0) OllamaPlanMode.PERCENT else OllamaPlanMode.USD) },
            tone = HapticTone.LIGHT,
        )
        Spacer(Modifier.height(t.space1))
        Text(
            "计费模式由你选择，选择会记住；与 Ollama 返回的结构不一致时，卡片里会提示",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
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
            // 只在首次取数（无缓存）时出现；有旧数据时刷新不切骨架
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
                modifier = Modifier.testTag("s18-error"),
                actionModifier = Modifier.testTag("s18-retry"),
            )
            else -> QuotaDataBody(
                info = info,
                state = state,
                plan = plan,
                error = error,
                nowEpochSecond = now,
                onOpenSettings = onOpenSettings,
                onRetry = onRetry,
                onRangeChange = onRangeChange,
            )
        }
    }
}

/**
 * 有数据态：失败条在上、旧卡在下（顺序固定）；成功后本卡原地变新值不重排。
 *
 * 面板容器锚点（s18-panel-*）挂在整块外面：它表达「当前是哪一家的面板」，
 * 而不是某张卡的排版细节 —— 卡片内部的锚点由卡片自己承担。
 */
@Composable
private fun QuotaDataBody(
    info: QuotaProviderInfo,
    state: QuotaRepository.ProviderState,
    plan: OllamaPlanMode,
    error: String?,
    nowEpochSecond: Long,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onRangeChange: (QuotaRange) -> Unit,
) {
    val t = LocalDesignTokens.current
    val hasPercent = state.items.any { it.kind == QuotaKind.PERCENT }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(panelTagOf(info.id, hasPercent)),
        verticalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        if (error != null) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("s18-error"),
                verticalArrangement = Arrangement.spacedBy(t.space3),
            ) {
                QuotaErrorBar(
                    message = error,
                    staleAtEpochSecond = state.fetchedAtEpochSecond,
                    onRetry = onRetry,
                    modifier = Modifier.testTag("s18-errorbar"),
                    retrying = state.loading,
                )
                // 失败但有旧数据：旧卡原地保留（标题换成「上次可用额度」的语义由失败条副行承担）
                if (state.items.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().testTag("s18-stale")) {
                        QuotaCard(
                            providerId = info.id,
                            state = state,
                            plan = plan,
                            nowEpochSecond = nowEpochSecond,
                        )
                    }
                }
            }
        }
        if (error == null && state.items.isNotEmpty()) {
            QuotaCard(
                providerId = info.id,
                state = state,
                plan = plan,
                nowEpochSecond = nowEpochSecond,
                modifier = Modifier.staggerEnterAt(0),
            )
        }
        if (state.usage != null) {
            UsageCard(
                usage = state.usage,
                range = state.range,
                costUnit = costUnitOf(state),
                variant = usageVariantOf(info.id, hasPercent),
                onRangeChange = onRangeChange,
                modifier = Modifier.staggerEnterAt(1),
            )
        } else if (info.supportsUsage) {
            QuotaUsageUnavailableCard(onOpenSettings)
        }
    }
}

/**
 * 面板容器锚点：按 provider（Ollama 再按返回结构分新旧两种面板）逐字映射契约。
 *
 * 分支里写字面量 testTag：锚点对账是 grep 源码的，拼字符串扫不到。
 */
private fun panelTagOf(providerId: String, hasPercent: Boolean): Modifier = when (providerId) {
    OLLAMA_ID -> if (hasPercent) Modifier.testTag("s18-panel-ollama-legacy") else Modifier.testTag("s18-panel-ollama-usd")
    "deepseek" -> Modifier.testTag("s18-panel-deepseek")
    "opencode-go" -> Modifier.testTag("s18-panel-opencode")
    OPEN_DESIGN_ID -> Modifier.testTag("s18-panel-opendesign")
    else -> Modifier
}

/** 用量卡用哪一套锚点（三家的卡面不同，契约各给一组锚点）。 */
private fun usageVariantOf(providerId: String, hasPercent: Boolean): UsageCardVariant = when {
    providerId == "deepseek" -> UsageCardVariant.DEEPSEEK
    // Ollama 旧版计划只返回百分比限额，用量侧也只有请求次数
    providerId == OLLAMA_ID && hasPercent -> UsageCardVariant.OLLAMA_LEGACY
    else -> UsageCardVariant.OLLAMA_USD
}

/** 花费币种：用量本身不带币种，取额度项的主币种（Ollama=USD，DeepSeek=CNY）。 */
private fun costUnitOf(state: QuotaRepository.ProviderState): QuotaUnit {
    // 用量自带币种最可靠（DeepSeek 是 CNY、Ollama 是 USD）；
    // 没有额度项时不能再靠 items 猜，否则 DeepSeek 的花费会被标成 $
    state.usage?.costUnit?.let { if (it == QuotaUnit.CNY || it == QuotaUnit.USD) return it }
    val unit = state.items.firstOrNull()?.unit
    return if (unit == QuotaUnit.CNY || unit == QuotaUnit.USD) unit else QuotaUnit.USD
}

/** open-design 的 provider id（契约里写作 open-design，注册 id 是 opendesign）。 */
private const val OPEN_DESIGN_ID = "opendesign"
