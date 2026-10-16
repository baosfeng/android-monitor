package com.bsfeng.monitor.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.flow.filter
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 「记一笔」的四个抽屉（定版原型 s14a：全抽屉方案）。
 *
 * 四个抽屉共用同一套外壳（[FinanceScrim] + [BottomSheet] + 标题），差异只在内容 ——
 * 原型选 A 版的核心理由就是这个：分类 / 账户 / 时间 / 单据**一个交互模型**，
 * 抽屉开合不影响上方表单高度（B 版内联展开会把表单顶出屏幕，实测 850 > 738）。
 *
 * 关闭语义：点遮罩（[FinanceScrim]）= 关闭；分类 / 账户**选中即关**；时间要连着选日期与时刻，
 * 由「确定」关闭（用户明确要求过这一点）。
 */

/** 时间滚轮：一屏 3 格（原型 .s14a-wheel-wrap height:132px / 每格 44px） */
private val WHEEL_H: Dp = 132.dp
private val WHEEL_ITEM_H: Dp = 44.dp

/** 分类网格图标尺寸（原型 .s14a-grid .cat-icon：44×44） */
private val GRID_ICON_SIZE: Dp = 44.dp

/** 分类网格最大高度 = 屏高的 46%（原型 .s14a-grid 的 max-height:46vh） */
private const val GRID_MAX_H_RATIO = 0.46f

/** 日期 chip 最小宽度（原型 .s14a-strip .chip 的 min-width:48px） */
private val CHIP_MIN_W: Dp = 48.dp

/** 选择分类（4 列网格，一屏 20 个 —— 原型 A 版的核心优势） */
@Composable
internal fun BoxScope.CategorySheet(
    visible: Boolean,
    categories: List<Category>,
    selectedName: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    SheetShell(title = "选择分类", onDismiss = onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(RecordForm.CATEGORY_GRID_COLUMNS),
            // 原型是 max-height:46vh —— 按屏高比例算，别写死（小屏会占满、大屏会显小）
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * GRID_MAX_H_RATIO).dp),
            horizontalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space3),
            verticalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space3),
        ) {
            items(categories, key = { it.id }) { category ->
                CategoryGridCell(
                    name = category.name,
                    selected = category.name == selectedName,
                    onClick = { onPick(category.name) },
                )
            }
        }
    }
}

@Composable
private fun CategoryGridCell(name: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    // 原型 .s14a-grid .s14-cat 是 padding:0 —— 格子自己不带内边距，行距只由网格 gap 决定
    Column(
        modifier = Modifier
            .clip(shape)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 选中态 = 图标外描边（原型 .s14a-grid .s14-cat[aria-pressed="true"] .cat-icon）
        Box(
            modifier = Modifier
                .clip(shape)
                .border(2.dp, if (selected) t.accent else Color.Transparent, shape)
                .padding(2.dp),
        ) {
            CategoryIcon(name = name, color = categoryColorOf(name), size = GRID_ICON_SIZE)
        }
        Spacer(Modifier.height(t.space1))
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) t.accentText else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 选择账户（列表带余额：一眼看出选的是哪个钱包） */
@Composable
internal fun BoxScope.AccountSheet(
    visible: Boolean,
    title: String,
    accounts: List<Account>,
    selectedName: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    SheetShell(title = title, onDismiss = onDismiss) {
        // 原型是 .group：整组一个卡片（surface 底 + 1px --border-soft + radius-lg + 左右 --space-4），
        // 不是裸列表 —— 裸列表会和下面的 sheet 底同色，三行看不出是一组
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(t.surface)
                .border(1.dp, t.borderSoft, shape)
                .padding(horizontal = t.space4),
        ) {
            accounts.forEachIndexed { index, account ->
                AccountSheetRow(
                    account = account,
                    selected = account.name == selectedName,
                    onClick = { onPick(account.name) },
                )
                if (index != accounts.lastIndex) {
                    HorizontalDivider(
                        thickness = DividerDefaults.Thickness,
                        color = t.borderSoft,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountSheetRow(account: Account, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .row：min-height --row-min-h(52px) + 上下 padding --space-2。
            // 必须用 defaultMinSize 而不是 heightIn —— CSS 的 min-height **含** padding，
            // Compose 的 heightIn 约束的是 padding **之内**的内容区（会多出一个 padding 的高度）。
            .defaultMinSize(minHeight = t.rowMinH)
            .clip(RoundedCornerShape(t.radiusMd))
            .clickable { onClick() }
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            // 原型 .row-body{gap:2px}：标题与副标题之间只有 2px
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                account.name,
                // 原型 .row-title 用 CSS line-height(l.25) 定行高；Compose 默认 includeFontPadding
                // 会给每个 Text 额外留几 dp，两行叠起来比原型高一截（实测 63dp vs 原型 58px）
                style = MaterialTheme.typography.titleSmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = t.fg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                // 原型 .row-sub：--text-sm(14px) + --muted
                "余额 ¥" + account.displayBalance,
                style = MaterialTheme.typography.bodySmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = t.muted,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
    }
}

/**
 * 选择时间：日期快捷 chips + 时 / 分双滚轮。
 *
 * 解决「想记昨天 10 点吃饭」——原 s05 只有一个「今天」日期列表，选不了时刻。
 * 秒不单独选：新增时沿用当前秒，编辑时沿用原记录的秒（[RecordForm.TimeParts]）。
 */
@Composable
internal fun BoxScope.TimeSheet(
    visible: Boolean,
    parts: RecordForm.TimeParts,
    onDateChange: (String) -> Unit,
    onHourMinuteChange: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    val today = LocalDate.now()
    val shortcuts = RecordForm.dateShortcuts(today)
    SheetShell(title = "选择时间", onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = t.space2),
            verticalAlignment = Alignment.CenterVertically,
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
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(t.space2),
            ) {
                shortcuts.forEach { shortcut ->
                    DateChip(
                        label = shortcut.label,
                        selected = shortcut.date == parts.date,
                        onClick = { onDateChange(shortcut.date) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = t.space4),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(
                values = (0..23).map { twoDigits(it) },
                selectedIndex = parts.hour,
                onSelect = { onHourMinuteChange(it, parts.minute) },
            )
            Text(
                ":",
                // 原型 .s14a-colon：--text-xl(28px)/600/--muted
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = t.textXl),
                color = t.muted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = t.space3),
            )
            WheelColumn(
                values = (0..59).map { twoDigits(it) },
                selectedIndex = parts.minute,
                onSelect = { onHourMinuteChange(parts.hour, it) },
            )
        }
        Text(
            RecordForm.timeRowText(parts, today),
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = t.space3),
        )
        Spacer(Modifier.height(t.space3))
        SheetPrimaryButton(text = "确定", onClick = onDismiss)
    }
}

/** 日期快捷 chip（原型 .chip + .s14a-strip） */
@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    // 原型 .chip：底透明 + 1px --border + --fg-2 字；
    // 选中 .chip.is-on = 底 color-mix(fg 7%) + 边 color-mix(fg 24%) + --fg 字 600（不是黑底白字）
    Box(
        modifier = Modifier
            .widthIn(min = CHIP_MIN_W)
            .heightIn(min = t.tapMin)
            .clip(shape)
            .background(if (selected) t.fgFade(7) else Color.Transparent)
            .border(1.dp, if (selected) t.fgFade(24) else t.border, shape)
            .clickable { onClick() }
            .padding(horizontal = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) t.fg else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 时刻滚轮：滚到哪一格就算选中哪一格（原型 .s14a-wheel）。
 *
 * 两个实现要点来自原型的真机教训：
 * 1. 对齐必须在**可见之后**做 —— 隐藏状态下设滚动位置无效（原型里表现为「选中的 10:30 没在中间」）；
 * 2. 上下各留半屏 padding，否则首尾项滚不到中间（原型用的 44px 上下 padding 是同一原因）。
 */
@Composable
private fun WheelColumn(
    values: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    val listState = rememberLazyListState()
    val pad = (WHEEL_H - WHEEL_ITEM_H) / 2

    LaunchedEffect(selectedIndex) {
        if (values.isNotEmpty()) {
            listState.scrollToItem(selectedIndex.coerceIn(0, values.lastIndex))
        }
    }
    // 滚停后吸附到最近一格，并回写选中值（原型：滚停 80ms 再算）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { inProgress -> !inProgress }
            .collect {
                val raw = listState.firstVisibleItemIndex * WHEEL_ITEM_H.value +
                    listState.firstVisibleItemScrollOffset
                val index = (raw / WHEEL_ITEM_H.value).roundToInt().coerceIn(0, values.lastIndex)
                onSelect(index)
                listState.animateScrollToItem(index)
            }
    }

    Box(
        modifier = Modifier
            .width(WHEEL_WIDTH)
            .height(WHEEL_H)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.surface),
    ) {
        // 中间高亮带（原型 .s14a-wheel-band）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(WHEEL_ITEM_H)
                // 原型 .s14a-wheel-band：left/right 6px、底 color-mix(fg 8%)、圆角 radius-sm
                .padding(horizontal = WHEEL_BAND_INSET)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(t.fgFade(8)),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(WHEEL_H),
            contentPadding = PaddingValues(vertical = pad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(values.size) { index ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier.fillMaxWidth().height(WHEEL_ITEM_H),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        values[index],
                        // 原型 .s14a-wheel button：--text-lg(21px)；选中 --fg/600，未选 --muted/400
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = t.textLg),
                        color = if (selected) t.fg else t.muted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 单据明细（自动记账落库的结构化字段，原型 s14a-receipt-sheet 的 6 行） */
@Composable
internal fun BoxScope.ReceiptSheet(
    visible: Boolean,
    transaction: Transaction?,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    val items = RecordForm.receiptItems(transaction)
    SheetShell(title = "单据明细", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = t.space1),
                    horizontalArrangement = Arrangement.spacedBy(t.space3),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.width(SHEET_LABEL_W),
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.fg,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (items.isEmpty()) {
                Text(
                    "这笔交易没有单据信息（手动录入的交易不带单据）",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
            Spacer(Modifier.height(t.space3))
            // 次级按钮：关闭不是「确认」性质的动作，视觉层级要低于时间抽屉的「确定」
            // （原型 s14a-receipt-close 用的就是 .btn-ghost）
            SheetGhostButton(text = "关闭", onClick = onDismiss)
        }
    }
}

/**
 * 抽屉外壳：底部面板 + 标题（原型 .scrim.is-bottom > .sheet + .h3）。
 *
 * 遮罩不在页面里自绘：[BottomSheet] 走窗口级全屏承载，遮罩与面板一起覆盖整屏（含状态栏与手势条）；
 * 页面内的遮罩只能画到系统栏之内（真机表现为顶部仍亮、不像模态），还会与窗口遮罩叠成两层。
 *
 * 声明成 [BoxScope] 扩展是为了 [BottomSheet] 的底对齐（align 是 Box 的 parentData）。
 */
@Composable
private fun BoxScope.SheetShell(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    BottomSheet(
        visible = true,
        onDismiss = onDismiss,
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space3))
        content()
    }
}

/** 抽屉次级按钮（原型 .btn-ghost：透明底 + 1px --border 描边 + --fg 文字） */
@Composable
private fun SheetGhostButton(text: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    // 原型 .btn{border-radius:var(--radius-md)} = 12dp
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, t.border, shape)
            .clickable { onClick() }
            .padding(vertical = t.space3),   // 原型 .btn 的 min-height:48px 由「上下 --space-3 + 17px 行高」达成
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = t.fg)
    }
}

/** 抽屉主按钮（原型 .btn.btn-block：用于时间抽屉的「确定」） */
@Composable
private fun SheetPrimaryButton(text: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.accent)
            .clickable { onClick() }
            .padding(vertical = t.space3),
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

/** 滚轮宽度（原型 .s14a-wheel-wrap width:104px） */
private val WHEEL_WIDTH: Dp = 104.dp

/** 滚轮高亮带左右内缩（原型 .s14a-wheel-band 的 left/right:6px，非 token 值） */
private val WHEEL_BAND_INSET: Dp = 6.dp

/** 单据标签列宽（与表单标签列同宽，保证两处对齐） */
private val SHEET_LABEL_W: Dp = 64.dp

/** 两位数字：00 / 09 / 23 */
private fun twoDigits(value: Int): String = String.format(java.util.Locale.CHINA, "%02d", value)
