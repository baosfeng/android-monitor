package com.bsfeng.monitor.finance.ui.proto

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.theme.fgFade
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * s14a 的四个抽屉（定版原型 `.open-design/26-a-quan-chouti.html` 2488-2663 行）。
 *
 * 四个抽屉同一套外壳：遮罩（点即关）+ 贴底面板（[BottomSheet]）+ 标题；
 * 差异只在内容 —— 分类是 4 列网格、账户是带余额的列表、时间是日期条 + 时/分滚轮、单据是 6 行键值。
 *
 * 关闭语义按原型 JS：分类 / 账户**选中即关**；时间要连着选日期与时刻，由「确定」关；
 * 单据由「关闭」关；四者都可点遮罩关闭。
 */

// ─────────────────────────── 抽屉外壳 ───────────────────────────

/**
 * 遮罩：铺满父容器、点击关闭，锚点挂在它身上（原型 `data-od-id` 就在 `.scrim` 上）。
 *
 * 共享的 `Scrim` 没有 modifier 参数、挂不了 testTag，故在 proto 层按同配方重建。
 */
@Composable
private fun BoxScope.SheetScrim(anchor: String, onDismiss: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(anchor)
            .background(t.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    )
}

// ─────────────────────────── 抽屉 1 · 分类 ───────────────────────────

/** 选择分类：4 列网格，21 个分类全在（原型 A 版相对 B 版的核心优势）。 */
@Composable
internal fun BoxScope.CategorySheet(
    visible: Boolean,
    selectedName: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    SheetScrim(ProtoAnchors.CAT_SHEET, onDismiss)
    BottomSheet(
        visible = true,
        onDismiss = onDismiss,
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        SheetTitle("选择分类")
        Spacer(Modifier.height(t.space3))
        LazyVerticalGrid(
            columns = GridCells.Fixed(CATEGORY_GRID_COLUMNS),
            // 原型 .s14a-grid：max-height 46vh —— 按屏高比例算，小屏不占满、大屏不显小
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProtoAnchors.CAT_GRID)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * GRID_MAX_H_RATIO).dp),
            horizontalArrangement = Arrangement.spacedBy(t.space3),
            verticalArrangement = Arrangement.spacedBy(t.space3),
        ) {
            itemsIndexed(ProtoRecordSample.categories) { index, name ->
                CategoryCell(
                    name = name,
                    slot = index,
                    selected = name == selectedName,
                    onClick = { onPick(name) },
                )
            }
        }
    }
}

/**
 * 分类格（原型 `.s14-cat`：图标 44×44 + 名称 12px；选中 = 图标外描边 2px --accent + --accent-text 文字）。
 *
 * @param slot 分类在网格里的下标，即原型 `.s1 .s2 …` 的取色槽
 */
@Composable
private fun CategoryCell(
    name: String,
    slot: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        modifier = Modifier
            .clip(shape)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 原型 outline:2px --accent + outline-offset:2px：Compose 用「描边 + 内缩 2」等价表达；
        // 未选中时描边透明但**保留占位**，否则选中前后图标会跳 4dp
        Box(
            modifier = Modifier
                .clip(shape)
                .border(2.dp, if (selected) t.accent else Color.Transparent, shape)
                .padding(2.dp),
        ) {
            // 原型 .s14a-grid .cat-icon：44×44（= --tap-min）
            CategoryIcon(name = name, color = t.categoryDot(slot), size = t.tapMin)
        }
        Spacer(Modifier.height(t.space1))
        Text(
            name,
            // 原型 .s14a-grid .s14-cat-name：--text-xs(12)，未选中 --muted、选中 --accent-text
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) t.accentText else t.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────── 抽屉 2 · 账户 ───────────────────────────

/** 选择账户：列表带余额，一眼看出选的是哪个钱包（原型 `.group` 包三行）。 */
@Composable
internal fun BoxScope.AccountSheet(
    visible: Boolean,
    selectedName: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    SheetScrim(ProtoAnchors.ACCT_SHEET, onDismiss)
    BottomSheet(
        visible = true,
        onDismiss = onDismiss,
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        SheetTitle("选择账户")
        Spacer(Modifier.height(t.space3))
        // 原型 .group：整组一张卡（--surface 底 + 1px --border-soft + radius-lg + 左右 --space-4）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProtoAnchors.ACCT_LIST)
                .clip(shape)
                .background(t.surface)
                .border(1.dp, t.borderSoft, shape)
                .padding(horizontal = t.space4),
        ) {
            ProtoRecordSample.accounts.forEachIndexed { index, account ->
                AccountRow(
                    account = account,
                    selected = account.name == selectedName,
                    onClick = { onPick(account.name) },
                )
                if (index != ProtoRecordSample.accounts.lastIndex) {
                    HorizontalDivider(thickness = DividerDefaults.Thickness, color = t.borderSoft)
                }
            }
        }
    }
}

@Composable
private fun AccountRow(account: ProtoAccount, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .row：min-height 52px + 上下 --space-2（52 含 padding，故用 defaultMinSize 而非 heightIn）
            .defaultMinSize(minHeight = t.rowMinH)
            .clip(RoundedCornerShape(t.radiusMd))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            }
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            // 原型 .row-body{gap:2px}
            verticalArrangement = Arrangement.spacedBy(ROW_BODY_GAP),
        ) {
            Text(
                account.name,
                // 关掉 includeFontPadding 才能两行贴合原型的 1.25 / 1.3 行高
                style = MaterialTheme.typography.titleSmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = t.fg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                account.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = t.muted,
                maxLines = 1,
            )
        }
        // 原型 .s05-check：选中才显形（opacity 0 → 1），色取 .row-tail 的 --muted
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = t.muted,
                modifier = Modifier.size(CHECK_ICON_SIZE),
            )
        }
    }
}

// ─────────────────────────── 抽屉 3 · 时间 ───────────────────────────

/**
 * 选择时间：日期快捷条 + 时 / 分双滚轮 + 已选文本 + 确定。
 *
 * 解决「想记昨天 10 点吃饭」——只有滚轮能在一屏内放得下 0-23 / 0-59。
 * 秒不单独选：沿用表单当前的秒（原型滚轮也只有时 / 分两列）。
 */
@Composable
internal fun BoxScope.TimeSheet(
    visible: Boolean,
    today: LocalDate,
    parts: ProtoTimeParts,
    onDateChange: (String) -> Unit,
    onTimeChange: (ProtoTimeParts) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    val view = LocalView.current
    val shortcuts = remember(today) { ProtoRecordLogic.dateShortcuts(today) }
    val hours = remember { (0..23).map { ProtoRecordLogic.twoDigits(it) } }
    val minutes = remember { (0..59).map { ProtoRecordLogic.twoDigits(it) } }

    SheetScrim(ProtoAnchors.TIME_SHEET, onDismiss)
    BottomSheet(
        visible = true,
        onDismiss = onDismiss,
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        SheetTitle("选择时间")
        // 原型 .s14a-line.mt-3：日期键 + 横向滚动的 chip 条（padding --space-2 0）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = t.space3, bottom = t.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.space3),
        ) {
            Text(
                "日期",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.width(t.tapMin),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .testTag(ProtoAnchors.DATE_STRIP)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(t.space2),
            ) {
                shortcuts.forEach { shortcut ->
                    Chip(
                        label = shortcut.label,
                        selected = shortcut.date == parts.date,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            // 原型：换日期后抽屉**保持打开**，等「确定」
                            onDateChange(shortcut.date)
                        },
                        // 原型 .s14a-strip .chip{min-width:48px}（48 = --space-12）
                        modifier = Modifier.defaultMinSize(minWidth = t.space12),
                    )
                }
            }
        }
        // 原型 .s14a-timepick：居中行，顶部 --space-4
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = t.space4)
                .testTag(ProtoAnchors.TIME_PICK),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(
                anchor = ProtoAnchors.WHEEL_HOUR,
                values = hours,
                selectedIndex = parts.hour,
                onSelect = { onTimeChange(parts.copy(hour = it)) },
            )
            Text(
                ":",
                // 原型 .s14a-colon：--text-xl(28) / 600 / --muted
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = t.textXl),
                color = t.muted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = t.space3),
            )
            WheelColumn(
                anchor = ProtoAnchors.WHEEL_MINUTE,
                values = minutes,
                selectedIndex = parts.minute,
                onSelect = { onTimeChange(parts.copy(minute = it)) },
            )
        }
        Text(
            ProtoRecordLogic.timeText(parts, today),
            // 原型 .s14a-picked：居中 / --muted / --text-sm，顶部 --space-3
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = t.space3)
                .testTag(ProtoAnchors.PICKED),
        )
        Spacer(Modifier.height(t.space3))
        SheetPrimaryButton(
            text = "确定",
            tag = ProtoAnchors.TIME_OK,
            onClick = onDismiss,
        )
    }
}

/**
 * 时刻滚轮（原型 `.s14a-wheel`：每格 44px、上下各留半格 padding、滚停即吸附中间高亮带）。
 *
 * 两个来自原型的实现要点：
 * 1. 对齐必须发生在**抽屉可见之后** —— 隐藏状态下设滚动位置无效（原型注释里的真机教训）；
 *    这里靠 `LaunchedEffect` 在进入组合后执行；
 * 2. 上下各留半格 padding，否则首尾项滚不到中间高亮带上。
 */
@Composable
private fun WheelColumn(
    anchor: String,
    values: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    val listState = rememberLazyListState()
    val itemH = t.tapMin
    val pad = (WHEEL_HEIGHT - itemH) / 2

    LaunchedEffect(selectedIndex, values.size) {
        if (values.isNotEmpty()) {
            listState.scrollToItem(selectedIndex.coerceIn(0, values.lastIndex))
        }
    }
    // 滚停后吸附到最近一格并回写选中值（原型：滚停 80ms 再算，不逐像素刷新）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { inProgress ->
                if (inProgress || values.isEmpty()) return@collect
                val raw = listState.firstVisibleItemIndex * itemH.value +
                    listState.firstVisibleItemScrollOffset
                val index = (raw / itemH.value).roundToInt().coerceIn(0, values.lastIndex)
                if (index != selectedIndex) onSelect(index)
                listState.animateScrollToItem(index)
            }
    }

    Box(
        modifier = Modifier
            .width(WHEEL_WIDTH)
            .height(WHEEL_HEIGHT)
            .clip(RoundedCornerShape(t.radiusMd))
            // 原型 .s14a-wheel-wrap：底 color-mix(fg 4%, transparent)
            .background(t.fgFade(4))
            .testTag(anchor),
    ) {
        // 中间高亮带（原型 .s14a-wheel-band：左右内缩 6px、高 44px、底 color-mix(fg 8%)）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemH)
                .padding(horizontal = WHEEL_BAND_INSET)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(t.fgFade(8)),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = pad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(values.size) { index ->
                val isOn = index == selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemH),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        values[index],
                        // 原型 .s14a-wheel button：--text-lg(21)；选中 --fg/600，未选 --muted
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = t.textLg),
                        color = if (isOn) t.fg else t.muted,
                        fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

// ─────────────────────────── 抽屉 4 · 单据明细 ───────────────────────────

/** 单据明细：自动记账落库的 6 个字段（只读，故只有「关闭」）。 */
@Composable
internal fun BoxScope.ReceiptSheet(
    visible: Boolean,
    receipt: ProtoReceipt,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    SheetScrim(ProtoAnchors.RECEIPT_SHEET, onDismiss)
    BottomSheet(
        visible = true,
        onDismiss = onDismiss,
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        SheetTitle("单据明细")
        Spacer(Modifier.height(t.space3))
        Column(Modifier.fillMaxWidth()) {
            receipt.items.forEach { (label, value) ->
                // 原型 .s14a-kv：标签列固定 64px、值列吃掉剩余宽度（64 = --space-12 + --space-4）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = t.space1),
                    horizontalArrangement = Arrangement.spacedBy(t.space3),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.width(t.space12 + t.space4),
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.fg,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(t.space3))
        // 次级按钮：关闭不是「确认」性质的动作，视觉层级低于时间抽屉的「确定」（原型用的是 .btn-ghost）
        SheetGhostButton(
            text = "关闭",
            tag = ProtoAnchors.RECEIPT_CLOSE,
            onClick = onDismiss,
        )
    }
}

// ─────────────────────────── 抽屉内的公共件 ───────────────────────────

/** 抽屉标题（原型 `.h3`：--text-base 17 / 600）。 */
@Composable
private fun SheetTitle(text: String) {
    val t = LocalDesignTokens.current
    Text(text, style = MaterialTheme.typography.titleMedium, color = t.fg)
}

/** 抽屉主按钮（原型 `.btn.btn-block`，用于时间抽屉的「确定」）。 */
@Composable
private fun SheetPrimaryButton(text: String, tag: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(t.space12)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.accent)
            .testTag(tag)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = t.accentOn,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 抽屉次级按钮（原型 `.btn-ghost`：透明底 + 1px --border 描边 + --fg 文字）。 */
@Composable
private fun SheetGhostButton(text: String, tag: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(t.space12)
            .clip(shape)
            .border(1.dp, t.border, shape)
            .testTag(tag)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = t.fg)
    }
}

// ─────────────────────────── 尺寸常量（原型里的非 token 值） ───────────────────────────

/** 分类抽屉的列数（原型 .s14a-grid：repeat(4, minmax(0,1fr))）。 */
private const val CATEGORY_GRID_COLUMNS = 4

/** 分类网格最大高度 = 屏高的 46%（原型 .s14a-grid 的 max-height:46vh）。 */
private const val GRID_MAX_H_RATIO = 0.46f

/** 滚轮可视高度（原型 .s14a-wheel-wrap height:132px = 3 × 44px 格高）。 */
private val WHEEL_HEIGHT: Dp = 132.dp

/** 滚轮宽度（原型 .s14a-wheel-wrap width:104px）。 */
private val WHEEL_WIDTH: Dp = 104.dp

/** 滚轮高亮带左右内缩（原型 .s14a-wheel-band 的 left/right:6px）。 */
private val WHEEL_BAND_INSET: Dp = 6.dp

/** 账户行两行文案的间距（原型 .row-body gap:2px）。 */
private val ROW_BODY_GAP: Dp = 2.dp

/** 选中勾尺寸（原型 .s05-check svg 18px，就近取图标 token）。 */
private val CHECK_ICON_SIZE: Dp = 20.dp
