package com.bsfeng.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.rememberStaggerEnter
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.DetailRow
import com.bsfeng.monitor.ui.live.FiveStateDemo
import com.bsfeng.monitor.ui.live.FiveStateDemoBar
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.OverlayScrim
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.ScreenHead
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.live.StatBox
import com.bsfeng.monitor.ui.live.TypeIconDot
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.live.formatDayLabel
import com.bsfeng.monitor.ui.live.startOfDay
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.util.Locale

/**
 * s03 广告跳过记录（原型 .open-design/08-prototype.html → parts/s03-adskip.html）。
 *
 * 结构：页头 → 统计卡（累计数字滚动 + 3 个小统计 + 入口按钮）→ 近 7 天柱状趋势
 *      → 跳过记录（方式筛选 chip + 倒序列表）→ 记录详情底部抽屉。
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

    var methodIndex by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<SkipRecord?>(null) }

    LaunchedEffect(Unit) { adViewModel.loadData() }
    BackHandler(enabled = selected != null) { selected = null }

    val method = SkipMethod.entries[methodIndex]
    val visible = remember(records, methodIndex) {
        if (method == SkipMethod.ALL) records else records.filter { it.method == method }
    }
    val realState = when {
        isLoading && records.isEmpty() -> FiveState.LOADING
        loadError != null -> FiveState.ERROR
        records.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val labels = remember {
        val start = startOfDay(System.currentTimeMillis()) - 6 * DAY_MS
        (0 until 7).map { formatDayLabel(start + it * DAY_MS) }
    }
    fun countOf(m: SkipMethod): Int =
        if (m == SkipMethod.ALL) records.size else records.count { it.method == m }

    // 性能：派生值只与 records 有关，缓存后列表重组不再重复全量扫描（chip 文案原先每次
    // 重组都要 4 次全表扫描 + 每条记录重新分类）。
    val avgCost = remember(records) {
        records.mapNotNull { it.costMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    }
    val savedTotal = remember(records) {
        records.mapNotNull { it.savedMs }.takeIf { it.isNotEmpty() }?.sum()
    }
    val ruleHit = remember(records) { records.count { it.method == SkipMethod.RULE } }
    val chipItems = remember(records) { SkipMethod.entries.map { it.label + " " + countOf(it) } }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "广告跳过记录",
                meta = "今日 " + todayCount + " 次 · 最近 " +
                    (if (lastSkipAt > 0L) formatClockSec(lastSkipAt) else PLACEHOLDER),
                actionIcon = Icons.Outlined.Refresh,
                actionDesc = "刷新记录",
                onAction = {
                    adViewModel.loadData()
                    toast.show("已刷新跳过记录")
                },
            )

            StateContainer(
                state = FiveStateDemo.effective(realState),
                modifier = Modifier.weight(1f),
                emptyText = "在「跳过规则」里为常用应用开启规则后，命中记录会按时间倒序出现在这里。",
                errorText = loadError ?: "跳过事件表暂时被占用（SQLITE_BUSY）。已跳过的广告不会重复出现。",
                edgeText = "哔哩哔哩（tv.danmaku.bili）· 开屏与贴片广告 · 累计跳过 3,128 次",
                onRetry = { adViewModel.loadData() },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底部 = 底栏（Scaffold paddingValues 已经扣掉）之外再留一屏尾余量：
                    // 内容高度略超视口时，最后一条记录才能整行滚进可视区（否则被停在底栏上沿截断）。
                    contentPadding = PaddingValues(
                        start = t.space4,
                        end = t.space4,
                        bottom = t.space12 + t.space6,
                    ),
                    verticalArrangement = Arrangement.spacedBy(t.space3),
                ) {
                    item(key = "stat") {
                        AppCard(modifier = Modifier.then(rememberStaggerEnter(0))) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                MetaText("近 7 天累计跳过")
                                MetaText(
                                    "日均 " + String.format(Locale.US, "%.1f", totalCount / 7.0) + " 次",
                                )
                            }
                            Row(verticalAlignment = Alignment.Bottom) {
                                AnimatedNumber(
                                    value = totalCount,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = t.fg,
                                )
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
                                    value = if (savedTotal != null) formatSaved(savedTotal) else PLACEHOLDER,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(t.space3))
                            Button(
                                onClick = {
                                    if (onOpenRules != null) onOpenRules() else toast.show("跳过规则入口：设置 → 跳过规则")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(t.radiusPill),
                            ) {
                                Text("查看跳过规则", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    item(key = "trend") {
                        AppCard(modifier = Modifier.then(rememberStaggerEnter(1))) {
                            CardHead("近 7 天跳过趋势", labels.first() + " – " + labels.last())
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

                    item(key = "records-head") {
                        Row(
                            Modifier
                                .fillMaxWidth()
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
                            onSelect = { methodIndex = it },
                        )
                    }

                    if (visible.isEmpty()) {
                        item(key = "records-empty") {
                            MetaText("该筛选方式下暂无记录")
                        }
                    } else {
                        itemsIndexed(
                            items = visible,
                            // key 必须唯一：timestampMs + selector 在同一毫秒内同 App 两次命中时会撞
                            // （Lazy 抛 "Key was already used"）；下标参与保证唯一且同排序下稳定。
                            key = { index, record -> record.timestampMs.toString() + "#" + index },
                        ) { index, record ->
                            // 性能：staggerEnter 会给每个行附加一个 graphicsLayer（实测每行一层的
                            // 合成/矩阵成本在冷启动窗口被放大 4~6 倍）。入场动画只对首屏可见项有意义，
                            // 超出的行直接不加 modifier，滚动中不再为它们付 layer 成本。
                            val enterMod = if (index < STAGGER_MAX_STEPS) {
                                rememberStaggerEnter(index)
                            } else {
                                Modifier
                            }
                            StackRow(
                                first = index == 0,
                                last = index == visible.lastIndex,
                                modifier = Modifier.then(enterMod),
                            ) {
                                SkipRecordRow(record = record, onClick = { selected = record })
                            }
                        }
                    }

                    item(key = "clear") {
                        Spacer(Modifier.height(t.space2))
                        OutlinedButton(
                            onClick = {
                                adViewModel.clearAll()
                                toast.show("已清空跳过记录")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(t.radiusPill),
                        ) {
                            Text("清空记录", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        // 原型控制条（debug 专用）改为浮层：不再参与 Column 高度分配，避免挤掉列表尾部视口
        FiveStateDemoBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = t.space4),
        )

        selected?.let { record ->
            Box(Modifier.fillMaxSize()) {
                OverlayScrim(onDismiss = { selected = null })
                BottomSheet(
                    visible = true,
                    onDismiss = { selected = null },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = record.displayName + " · 跳过成功",
                            style = MaterialTheme.typography.titleSmall,
                            color = t.fg,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Badge(record.method.label + "匹配")
                    }
                    Spacer(Modifier.height(t.space3))
                    DetailRow("发生时间", formatClockSec(record.timestampMs), first = true)
                    DetailRow("命中规则", record.selector.ifBlank { PLACEHOLDER })
                    DetailRow("响应耗时", record.costMs?.let { it.toString() + " ms" } ?: PLACEHOLDER)
                    DetailRow(
                        "结果",
                        when (record.confirmed) {
                            true -> "已进入正片 · 未误触"
                            false -> "点击后广告仍在（未确认）"
                            null -> "未复核"
                        },
                    )
                    DetailRow("包名", record.packageName.ifBlank { PLACEHOLDER })
                    Spacer(Modifier.height(t.space4))
                    Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                        OutlinedButton(
                            onClick = { selected = null },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(t.radiusPill),
                        ) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(record.selector))
                                toast.show("已复制选择器")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(t.radiusPill),
                        ) { Text("复制选择器", style = MaterialTheme.typography.labelLarge) }
                    }
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

private fun methodSubtitle(method: SkipMethod, costMs: Long?): String {
    val head = when (method) {
        SkipMethod.COORD -> "坐标点击兜底"
        SkipMethod.A11Y -> "无障碍事件兜底"
        else -> "规则匹配"
    }
    return head + " · " + (costMs?.let { it.toString() + " ms" } ?: PLACEHOLDER)
}

private fun formatSaved(ms: Long): String {
    val totalSec = ms / 1000
    return (totalSec / 60).toString() + "m" + String.format(Locale.US, "%02d", totalSec % 60) + "s"
}

private fun peakNote(values: List<Float>): String {
    var idx = 0
    var max = 0f
    values.forEachIndexed { i, v ->
        if (v > max) {
            max = v
            idx = i
        }
    }
    if (max <= 0f) return "近 7 天暂无跳过记录"
    return "峰值 " + (idx + 1) + " 日 · " + max.toInt() + " 次"
}

@Composable
private fun SkipRecordRow(record: SkipRecord, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = t.rowMinH)
            .clickable { onClick() }
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
        TypeIconDot(RecordType.SCREEN)
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
