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
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.live.formatDayLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 错态「重试」转骨架的展示窗口。
 *
 * AdViewModel.loadData() 是 fire-and-forget，没有暴露「这次重查结束」的信号；先按一个短窗口
 * 展示骨架（按钮已卸载、不可连点），窗口结束回到真实态。补完成回调后可删掉这个常量。
 */
private const val RETRY_SKELETON_MS = 800L

/**
 * 记录行时间列宽。
 *
 * 本页展示到秒（8 位 HH:mm:ss），14sp 等宽字 8 位约 67dp；原先复用 space12（48dp）放不下，
 * 真机上折成「17:34:0 / 4」。这里按内容给足 68dp —— 有布局语义的非常规尺寸，不随主题间距档位
 * 变化（主题把间距调大也不该把时间列挤回两行）。
 */
private val TIME_COLUMN_W = 68.dp

/**
 * s03 广告跳过记录。
 *
 * 结构：固定页头 → 内容区（统计卡 → 近 7 天趋势 → 方式筛选 chip → 倒序记录列表）→
 * 记录详情底部抽屉。锚点逐个落在对应节点 `testTag` 上（见锚点账本）。
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
    val windowStart by adViewModel.windowStart.collectAsState()

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
        loadError -> FiveState.ERROR
        records.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = FiveStateDemo.effective(realState)

    // 横轴与范围标题的窗口起点必须与 ViewModel 的查询窗口同源。
    // 原先 remember {} 无 key：跨零点后 VM 已按新的 7 天窗口出数，横轴和「–」范围标题仍停在旧窗口。
    // 改读 windowStart（VM 每轮刷新现算、仅跨零点变化）：既不冻结，也不会每 3s 重算一次横轴。
    val dayStart = windowStart
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
                        // 文案取自唯一常量（VM 只给标记、不给原文）：锚点 s03-error 逐字不变。
                        text = AD_LOAD_ERROR_DESC,
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

            // 原型控制条（debug 专用，release 不渲染）：保留以便逐态验收本页锚点。
            // 放在 Column 里占位而不是 Box 里 align(BottomCenter) 浮着 —— 重排后记录进了首屏，
            // 浮层会压住最后 1~2 行记录；改为参与布局后无法遮住任何一行，关调试态时它不 emit，零影响。
            FiveStateDemoBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = t.space2),
            )
        }

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
                            // 口径：选择器为空时不写剪贴板。
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

/**
 * 内容区（单列纵向滚动，页头固定）：摘要条 → 记录区块（头 + chip + 列表）→ 趋势卡。
 *
 * 排序依据：本页主任务是「看刚才那次跳过」，记录必须在首屏；摘要压成单行条、趋势属回顾性信息
 * 下移到列表之后。原型口径见 .open-design/src/screens/s03-广告跳过记录.html。
 */
@Composable
private fun AdRecordList(
    visible: List<SkipRecord>,
    methodIndex: Int,
    chipItems: List<String>,
    totalCount: Int,
    confirmedCount: Int,
    avgCost: Long?,
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
        // 底部留出「趋势卡整卡进可视区」的余量：最后一项滚到底时其下沿不被底栏吃掉。
        contentPadding = PaddingValues(
            start = t.space4,
            end = t.space4,
            // 尾部余量 = 一屏高的一小段（对应原型列表到底后趋势卡完整露出的观感）
            bottom = t.space12 + t.space6,
        ),
        verticalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        item(key = "stat") {
            AdSummaryStrip(
                totalCount = totalCount,
                replayTick = replayTick,
                onReplay = onReplay,
                onOpenRules = onOpenRules,
                modifier = Modifier
                    .testTag("s03-stat")
                    .staggerEnterAt(0),
            )
        }

        item(key = "records-head") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("s03-records")
                    .padding(top = t.space1),
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
            // 四个带计数的选项在 390dp 下必然超宽：用 FlowRow 自适应折行，
            // 能一行就一行、放不下换行，绝不裁切或要求横滑才发现。
            AdMethodChips(
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
                // key 用「时间戳 + 选择器 + 包名」：SkipRecord 没有稳定 id，但这三者组合能区分
                // 同毫秒同 App 的两次命中；**不带下标** —— 带下标时列表重排会把后续行的 key 全改掉，
                // Compose 视为全新项、整段重播入场动画（同一口径见 finance/ui/FinanceMainRows.kt）。
                key = { _, record ->
                    record.timestampMs.toString() + "|" + record.selector + "|" + record.packageName
                },
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

        item(key = "trend") {
            AdTrendBlock(
                dailyCounts7 = dailyCounts7,
                labels = labels,
                rangeLabel = rangeLabel,
                // 回顾性补充数据：任一项缺失即整项不出现，三项全空则整行不渲染。
                extras = reviewExtras(confirmedCount, avgCost, savedTotal),
                modifier = Modifier
                    .testTag("s03-bars")
                    .staggerEnterAt(1),
            )
        }
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
            .testTag("row.ad")
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                onClick()
            }
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 时间列：本页精确到秒（8 位 HH:mm:ss），等宽字体 + tabular-nums 保证不抖动。
        // 宽度按内容给足 68dp —— 14sp 等宽字 8 位约 67dp，原先复用 space12(48dp) 会折成
        // 「17:34:0 / 4」。maxLines=1 + softWrap=false 双保险（只关软换行会中间截断成「17:34:…」，
        // 所以宽度必须真够，见 docs/UI设计/审查清单.md「有布局意义的非常规尺寸用注释说明」）。
        Text(
            text = formatClockSec(record.timestampMs),
            style = monitorMonoMeta,
            color = t.muted,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(TIME_COLUMN_W),
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
