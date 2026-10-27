package com.bsfeng.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.durationMsOf
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.DetailRow
import com.bsfeng.monitor.ui.live.FiveStateDemo
import com.bsfeng.monitor.ui.live.FiveStateDemoBar
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.live.StatBox
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.live.formatDayLabel
import com.bsfeng.monitor.ui.live.startOfDay
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 错态「重试」转骨架的展示窗口。
 *
 * AdViewModel.loadData() 是 fire-and-forget，没有暴露「这次重查结束」的信号；先按一个短窗口
 * 展示骨架（按钮已卸载、不可连点），窗口结束回到真实态。补完成回调后可删掉这个常量。
 */
private const val RETRY_SKELETON_MS = 800L

/**
 * s03 广告跳过记录。
 *
 * 结构：固定页头 → 内容区（统计卡 → 近 7 天趋势 → 方式筛选 chip → 倒序记录列表）→
 * 记录详情底部抽屉。契约锚点逐个落在对应节点 `testTag` 上（见
 * docs/交互设计/s03-广告跳过记录-交互契约.md）。
 */
@Composable
fun AdScreen(
    modifier: Modifier = Modifier,
    adViewModel: AdViewModel = viewModel(),
    onOpenRules: (() -> Unit)? = null,
) {
    val records by adViewModel.skipRecords.collectAsState()
    val totalCount by adViewModel.totalCount.collectAsState()
    val confirmedCount by adViewModel.confirmedCount.collectAsState()
    val todayCount by adViewModel.todayCount.collectAsState()
    val lastSkipAt by adViewModel.lastSkipAt.collectAsState()
    val dailyCounts7 by adViewModel.dailyCounts7.collectAsState()
    val isLoading by adViewModel.isLoading.collectAsState()
    val loadError by adViewModel.loadError.collectAsState()

    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()
    val motionEnabled = rememberMotionEnabled()

    var methodIndex by rememberSaveable { mutableStateOf(0) }
    var selected by remember { mutableStateOf<SkipRecord?>(null) }
    var replayTick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var restoreRowFocus by remember { mutableStateOf(false) }
    var lastRowRequester by remember { mutableStateOf<FocusRequester?>(null) }
    val refreshRotation = remember { Animatable(0f) }

    LaunchedEffect(Unit) { adViewModel.loadData() }
    // 骨架窗口：重试按钮已被骨架替换，窗口内不可连点；窗口结束按真实 loadError / 数据回落。
    LaunchedEffect(retrying) {
        if (retrying) {
            delay(RETRY_SKELETON_MS)
            retrying = false
        }
    }
    // 移除后五问之「焦点」：抽屉关闭后焦点回到打开它的那一行。
    LaunchedEffect(selected) {
        if (selected == null && restoreRowFocus) {
            withFrameNanos { }
            runCatching { lastRowRequester?.requestFocus() }
            restoreRowFocus = false
        }
    }

    fun closeSheet() {
        restoreRowFocus = true
        selected = null
    }

    fun openRules() {
        onOpenRules?.invoke()
    }

    BackHandler(enabled = selected != null) { closeSheet() }

    val method = SkipMethod.entries[methodIndex]
    val visible = remember(records, methodIndex) {
        if (method == SkipMethod.ALL) records else records.filter { it.method == method }
    }
    val realState = when {
        retrying -> FiveState.LOADING
        isLoading && records.isEmpty() -> FiveState.LOADING
        loadError != null -> FiveState.ERROR
        records.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = FiveStateDemo.effective(realState)

    val dayStart = remember { startOfDay(System.currentTimeMillis()) - 6 * DAY_MS }
    val labels = remember(dayStart) { (0 until 7).map { formatDayLabel(dayStart + it * DAY_MS) } }
    val rangeLabel = remember(dayStart) {
        monthDayLabel(dayStart) + " – " + monthDayLabel(dayStart + 6 * DAY_MS)
    }

    fun countOf(m: SkipMethod): Int =
        if (m == SkipMethod.ALL) records.size else records.count { it.method == m }

    // 派生值只与 records 有关，缓存后列表重组不再重复全量扫描。
    val avgCost = remember(records) {
        records.mapNotNull { it.costMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    }
    val savedTotal = remember(records) {
        records.mapNotNull { it.savedMs }.takeIf { it.isNotEmpty() }?.sum()
    }
    val ruleHit = remember(records) { records.count { it.method == SkipMethod.RULE } }
    val chipItems = remember(records) { SkipMethod.entries.map { it.label + " " + countOf(it) } }

    fun refresh() {
        // 刷新中再点不叠加旋转、不追加 toast。
        if (refreshing) return
        refreshing = true
        haptic(HapticTone.LIGHT)
        adViewModel.loadData()
        toast.show("已刷新跳过记录")
        val target = refreshRotation.value + 360f
        if (motionEnabled) {
            scope.launch {
                refreshRotation.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = t.durationMsOf(MotionToken.Chart),
                        easing = t.easeOut,
                    ),
                )
                refreshing = false
            }
        } else {
            scope.launch {
                refreshRotation.snapTo(target)
                refreshing = false
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag("screen-s03"),
    ) {
        Column(Modifier.fillMaxSize()) {
            AdScreenHead(
                todayCount = todayCount,
                lastSkipAt = lastSkipAt,
                rotationZ = { refreshRotation.value },
                onRefresh = { refresh() },
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("s03-content"),
            ) {
                when (state) {
                    // 五态一律走 ui/components 的共享件（骨架也走 StateSkeleton）：
                    // 块级锚点走 modifier、CTA 锚点走 actionModifier，页面不再自持第二份规格。
                    FiveState.LOADING -> StateSkeleton(
                        rows = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = t.space4, vertical = t.space4)
                            .testTag("s03-loading"),
                    )

                    FiveState.EMPTY -> EmptyState(
                        text = "在「跳过规则」里为常用应用开启规则后，命中记录会按时间倒序出现在这里。",
                        title = "还没有跳过记录",
                        icon = Icons.Outlined.Block,
                        actionLabel = "去配置跳过规则",
                        onAction = { openRules() },
                        actionModifier = Modifier.testTag("s03-empty-cta"),
                        modifier = Modifier.testTag("s03-empty"),
                    )

                    FiveState.ERROR -> ErrorState(
                        text = "跳过事件表暂时被占用（SQLITE_BUSY）。已跳过的广告不会重复出现。",
                        title = "跳过记录读取失败",
                        actionLabel = "重试",
                        onRetry = {
                            retrying = true
                            toast.show("正在重新读取跳过记录…")
                            adViewModel.loadData()
                        },
                        actionModifier = Modifier.testTag("s03-error-retry"),
                        modifier = Modifier.testTag("s03-error"),
                    )

                    FiveState.EDGE -> EdgeState(
                        // 该态真实数据不可达（只有调试演示条能强制，batch1.md §5 T2）
                        text = "超长应用名与万级计数用于验证截断；缺失字段以「—」占位。",
                        modifier = Modifier.testTag("s03-edge"),
                    ) {
                        AdEdgeContent()
                    }

                    FiveState.POPULATED -> AdRecordList(
                        visible = visible,
                        methodIndex = methodIndex,
                        chipItems = chipItems,
                        totalCount = totalCount,
                        confirmedCount = confirmedCount,
                        avgCost = avgCost,
                        ruleHit = ruleHit,
                        savedTotal = savedTotal,
                        dailyCounts7 = dailyCounts7,
                        labels = labels,
                        rangeLabel = rangeLabel,
                        replayTick = replayTick,
                        onReplay = {
                            haptic(HapticTone.LIGHT)
                            replayTick++
                        },
                        onSelectMethod = { methodIndex = it },
                        onOpenRules = { haptic(HapticTone.MEDIUM); openRules() },
                        onOpenRecord = { record, requester ->
                            haptic(HapticTone.LIGHT)
                            lastRowRequester = requester
                            selected = record
                        },
                    )
                }
            }
        }

        // 原型控制条（debug 专用，release 不渲染）：保留以便逐态验收本页锚点。
        FiveStateDemoBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = t.space4),
        )

        selected?.let { record ->
            Box(Modifier.fillMaxSize()) {
                BottomSheet(
                    visible = true,
                    onDismiss = { closeSheet() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    AdRecordDetailContent(
                        record = record,
                        onClose = { closeSheet() },
                        onCopySelector = {
                            // 契约：选择器为空时不写剪贴板。
                            if (record.selector.isNotBlank()) {
                                clipboard.setText(AnnotatedString(record.selector))
                            }
                            toast.show("已复制选择器")
                        },
                    )
                }
            }
        }

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 页头：副标题（今日条数 · 最近时间）+ 大标题 + 刷新按钮；固定不随内容滚动。 */
@Composable
private fun AdScreenHead(
    todayCount: Int,
    lastSkipAt: Long,
    rotationZ: () -> Float,
    onRefresh: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("s03-head")
            .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MetaText(
                "今日 " + todayCount + " 次 · 最近 " +
                    (if (lastSkipAt > 0L) formatClockSec(lastSkipAt) else PLACEHOLDER),
            )
            Spacer(Modifier.height(t.space2))
            Text(
                text = "广告跳过记录",
                style = MaterialTheme.typography.headlineMedium,
                color = t.fg,
            )
        }
        Spacer(Modifier.width(t.space3))
        AdRefreshButton(rotationProvider = rotationZ, onClick = onRefresh)
    }
}

/** 页头刷新：44×44 点击区；按下缩 0.96，点击旋转一周（600ms）+ toast。 */
@Composable
private fun AdRefreshButton(
    rotationProvider: () -> Float,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .testTag("s03-refresh")
            .size(t.tapMin)
            .clip(CircleShape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, CircleShape)
            .keyboardFocusRing(CircleShape)
            .pressFeedback(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Refresh,
            contentDescription = "刷新记录",
            tint = t.fg2,
            modifier = Modifier
                .size(t.iconSize / 2)
                .graphicsLayer { rotationZ = rotationProvider() },
        )
    }
}

/** 内容区：统计卡 + 趋势 + 记录区块（整块纵向滚动，页头固定）。 */
@Composable
private fun AdRecordList(
    visible: List<SkipRecord>,
    methodIndex: Int,
    chipItems: List<String>,
    totalCount: Int,
    confirmedCount: Int,
    avgCost: Long?,
    ruleHit: Int,
    savedTotal: Long?,
    dailyCounts7: List<Float>,
    labels: List<String>,
    rangeLabel: String,
    replayTick: Int,
    onReplay: () -> Unit,
    onSelectMethod: (Int) -> Unit,
    onOpenRules: () -> Unit,
    onOpenRecord: (SkipRecord, FocusRequester) -> Unit,
) {
    val t = LocalDesignTokens.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部再留一屏尾余量：最后一条记录能整行滚进可视区、不被底栏截住。
        contentPadding = PaddingValues(
            start = t.space4,
            end = t.space4,
            bottom = t.space12 + t.space6,
        ),
        verticalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        item(key = "stat") {
            AdStatsBlock(
                totalCount = totalCount,
                confirmedCount = confirmedCount,
                avgCost = avgCost,
                ruleHit = ruleHit,
                savedTotal = savedTotal,
                replayTick = replayTick,
                onReplay = onReplay,
                onOpenRules = onOpenRules,
                modifier = Modifier
                    .testTag("s03-stat")
                    .staggerEnterAt(0),
            )
        }

        item(key = "trend") {
            AdTrendBlock(
                dailyCounts7 = dailyCounts7,
                labels = labels,
                rangeLabel = rangeLabel,
                modifier = Modifier
                    .testTag("s03-bars")
                    .staggerEnterAt(1),
            )
        }

        item(key = "records-head") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("s03-records")
                    .padding(top = t.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "跳过记录",
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    modifier = Modifier.weight(1f),
                )
                MetaText("最近 " + visible.size + " 次 · 倒序")
            }
        }

        item(key = "chips") {
            ChipRow(
                items = chipItems,
                selectedIndex = methodIndex,
                onSelect = onSelectMethod,
                modifier = Modifier.testTag("s03-chips"),
            )
        }

        if (visible.isEmpty()) {
            item(key = "records-empty") {
                MetaText("该筛选方式下暂无记录")
            }
        } else {
            itemsIndexed(
                items = visible,
                // key 必须唯一：timestampMs + selector 在同一毫秒内同 App 两次命中时会撞；
                // 下标参与保证唯一且同排序下稳定。
                key = { index, record -> record.timestampMs.toString() + "#" + index },
            ) { index, record ->
                // 入场动画只对首屏可见项有意义，超出部分不再为滚动项付 layer 成本。
                val enterMod = if (index < STAGGER_MAX_STEPS) {
                    Modifier.staggerEnterAt(index)
                } else {
                    Modifier
                }
                val focusRequester = remember { FocusRequester() }
                StackRow(
                    first = index == 0,
                    last = index == visible.lastIndex,
                    modifier = enterMod,
                ) {
                    AdRecordRow(
                        record = record,
                        focusRequester = focusRequester,
                        onClick = { onOpenRecord(record, focusRequester) },
                    )
                }
            }
        }
    }
}

/** 统计卡：大数字点按重播滚动（600ms），其余区域不响应；底部满宽「查看跳过规则」。 */
@Composable
private fun AdStatsBlock(
    totalCount: Int,
    confirmedCount: Int,
    avgCost: Long?,
    ruleHit: Int,
    savedTotal: Long?,
    replayTick: Int,
    onReplay: () -> Unit,
    onOpenRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val numberInteraction = remember { MutableInteractionSource() }
    val rulesInteraction = remember { MutableInteractionSource() }
    val rulesShape = RoundedCornerShape(t.radiusPill)
    AppCard(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetaText("近 7 天累计跳过")
            MetaText("日均 " + String.format(Locale.US, "%.1f", totalCount / 7.0) + " 次")
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = t.tapMin)
                .keyboardFocusRing(RoundedCornerShape(t.radiusSm))
                .clickable(
                    interactionSource = numberInteraction,
                    indication = LocalIndication.current,
                ) { onReplay() }
                .padding(vertical = t.space1),
            verticalAlignment = Alignment.Bottom,
        ) {
            key(replayTick) {
                AnimatedNumber(
                    value = totalCount,
                    style = MaterialTheme.typography.headlineMedium,
                    color = t.fg,
                )
            }
            Spacer(Modifier.width(t.space1))
            Text("次", style = MaterialTheme.typography.labelMedium, color = t.muted)
        }
        if (confirmedCount > 0) {
            Spacer(Modifier.height(t.space1))
            MetaText("其中已确认 " + confirmedCount + " 次")
        }
        Spacer(Modifier.height(t.space3))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            StatBox(
                label = "平均耗时",
                value = if (avgCost != null) avgCost.toString() + " ms" else PLACEHOLDER,
                modifier = Modifier.weight(1f),
            )
            StatBox(
                label = "规则命中",
                value = ruleHit.toString() + " / " + totalCount,
                modifier = Modifier.weight(1f),
            )
            StatBox(
                label = "累计节省",
                // 结余类不出现负值；缺失写「—」。
                value = savedTotal?.let { formatSaved(it.coerceAtLeast(0L)) } ?: PLACEHOLDER,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(t.space3))
        Button(
            onClick = onOpenRules,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = t.tapMin)
                .testTag("s03-rules")
                .keyboardFocusRing(rulesShape)
                .pressFeedback(rulesInteraction),
            shape = rulesShape,
            interactionSource = rulesInteraction,
        ) {
            Text("查看跳过规则", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 趋势卡：卡头区间 + 单色柱 + 轴标签 + 峰值说明；柱子不可点选。 */
@Composable
private fun AdTrendBlock(
    dailyCounts7: List<Float>,
    labels: List<String>,
    rangeLabel: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        CardHead("近 7 天跳过趋势", rangeLabel)
        Spacer(Modifier.height(t.space3))
        BarChart(
            values = dailyCounts7,
            modifier = Modifier
                .fillMaxWidth()
                .height(t.chartH),
        )
        ChartAxisLabels(labels)
        Spacer(Modifier.height(t.space2))
        MetaText(peakNote(dailyCounts7))
    }
}

/** 单行跳过记录：按下缩 0.99 / 透明度 0.9（120ms），抬指打开详情抽屉。 */
@Composable
private fun AdRecordRow(
    record: SkipRecord,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = t.rowMinH)
            .focusRequester(focusRequester)
            .keyboardFocusRing(RoundedCornerShape(t.radiusSm))
            .rowPressFeedback(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                onClick()
            }
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClockSec(record.timestampMs),
            style = MaterialTheme.typography.labelMedium,
            color = t.muted,
            modifier = Modifier.width(t.space12),
        )
        Spacer(Modifier.width(t.space1))
        AppIcon(
            packageName = record.packageName.takeIf { it.isNotBlank() },
            appName = record.displayName,
        )
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = record.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = methodSubtitle(record.method, record.costMs),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 边界态内容（万级计数不换行、超长应用名单行省略、缺失字段写「—」、不出横向滚动）。
 *
 * 仅调试演示条可强制到该态（真实数据不可达，见 batch1.md §5 T2）；产品页不会渲染到这里。
 */
@Composable
private fun AdEdgeContent() {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge("10,204 次")
        MetaText("累计上限")
    }
    Spacer(Modifier.height(t.space2))
    Text(
        text = "哔哩哔哩（tv.danmaku.bili）· 开屏与贴片广告 · 累计跳过 3,128 次",
        style = MaterialTheme.typography.titleSmall,
        color = t.fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(t.space2))
    DetailRow("命中规则", PLACEHOLDER, first = true)
}

/** 卡头区间文案：原型「10 月 8 日 – 10 月 14 日」。 */
private fun monthDayLabel(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.monthValue.toString() + " 月 " + date.dayOfMonth + " 日"
}
