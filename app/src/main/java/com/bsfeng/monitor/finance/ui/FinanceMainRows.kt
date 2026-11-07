package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt 的明细行；职责：按日分组卡的行外框、单笔交易行、五态块与明细 lazy items。
//
// s04 交互口径要求每行一个锚点（s04-tx-1..8 / s04-edge-tx / s04-edge-refund）与「背景 fg 5% + 缩小」按下反馈，
// 而原实现的行内 clickable 用自建 interaction source 且锚点无处落地，故本文件同时承载 items 构造
// （原 FinanceMainList.kt 的职责迁入，见交接文档「被取代的产物」）。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 交互口径在明细里定义了 8 个行锚点（s04-tx-1..8）；第 9 行起不再带锚点。 */
private const val TX_ANCHOR_COUNT = 8

/**
 * 行按下背景（口径：背景转 fg 5%）。
 *
 * 缩放与透明度由共享基元 [rowPressFeedback] 提供（0.99 + alpha 0.9，与 s01/s03 同口径），
 * 这里只补交互口径要求的背景色；不额外建动画对象，滚动挂载不增开销。
 */
@Composable
private fun pressBackground(interaction: MutableInteractionSource, idle: Color): Color {
    val pressed by interaction.collectIsPressedAsState()
    val t = LocalDesignTokens.current
    return if (pressed) t.fgFade(5) else idle
}

/** 行锚点（锚点 s04-tx-1..8）：按明细可见顺序编号，逐字照抄锚点原文。 */
private fun Modifier.txRowAnchor(index: Int): Modifier = when (index) {
    0 -> testTag("s04-tx-1")
    1 -> testTag("s04-tx-2")
    2 -> testTag("s04-tx-3")
    3 -> testTag("s04-tx-4")
    4 -> testTag("s04-tx-5")
    5 -> testTag("s04-tx-6")
    6 -> testTag("s04-tx-7")
    7 -> testTag("s04-tx-8")
    else -> this
}

/**
 * 按日分组卡（原型 `.group`：surface 底 + 1px border-soft + radius-lg，00-shell.html:255-258）的**单行**。
 *
 * J1 行级虚拟化后，卡容器不再由「一天一个 item」承载，改由每行自绘：首行圆上角、末行圆下角，
 * 其余行是直角，描边只画外缘（内侧边由行间的 HorizontalDivider 承担，等价原型
 * `.group-row:first-child{border-top:0}` + 行间 border-top，00-shell.html:262-268）。
 */
@Composable
internal fun DayGroupRow(
    first: Boolean,
    last: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    val r = t.radiusLg
    val shape = remember(first, last, r) {
        RoundedCornerShape(
            topStart = if (first) r else 0.dp,
            topEnd = if (first) r else 0.dp,
            bottomStart = if (last) r else 0.dp,
            bottomEnd = if (last) r else 0.dp,
        )
    }
    Column(
        modifier = Modifier
            .padding(horizontal = t.space4)
            .fillMaxWidth()
            .background(t.surface, shape)
            // drawWithCache：outline / Stroke / 裁剪边界只在尺寸或 shape 变化时构建，
            // 滚动重绘时只重放 onDrawBehind（不再每帧 createOutline + new Stroke × 可见行数）
            .drawWithCache {
                // 只画外缘描边：把 outline 的上/下 1px 带裁掉（相邻行各自画竖线，接缝处仍是 1px）
                val w = 1.dp.toPx()
                val outline = shape.createOutline(size, layoutDirection, this)
                val stroke = Stroke(w)
                val clipTop = if (first) 0f else w
                val clipBottom = if (last) size.height else size.height - w
                onDrawBehind {
                    clipRect(
                        left = 0f,
                        top = clipTop,
                        right = size.width,
                        bottom = clipBottom,
                    ) {
                        drawOutline(outline, color = t.borderSoft, style = stroke)
                    }
                }
            }
            .padding(t.space1),
        content = content,
    )
}

/** 单笔交易行（分类图标 + 标题/副标题 + 金额）。 */
@Composable
internal fun TransactionRow(
    transaction: Transaction,
    color: Color,
    /** 焦点落点（删除后回位用），由调用方决定挂在哪一行；必须排在 `clickable` 之前才落到同一节点 */
    modifier: Modifier = Modifier,
    /** 自动记账刚落库：整行淡 accent 底点亮，1.6s 后淡出（其余行恒为透明） */
    highlighted: Boolean = false,
    /** >= 0 走错落入场；-1 表示离屏行不做入场动画（每行一个 Animatable 会拖帧） */
    enterIndex: Int,
    /** 锚点序号（0 起，对应 s04-tx-1..8）；-1 = 不带锚点 */
    anchorIndex: Int = -1,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val amountIsPositive = !transaction.isExpense || transaction.isRefundEntry
    val isTransfer = transaction.isTransfer
    // 来源徽标：自动记账 / 带 sourceId 的导入行才判 true，手写记录恒 false（见 TransactionSourceBadge）
    val isAutoBooked = TransactionSourceBadge.isAutoBooked(transaction.source, transaction.sourceId)
    val enterModifier = if (enterIndex >= 0) Modifier.staggerEnterAt(enterIndex) else Modifier
    val rowShape = remember(t.radiusMd) { RoundedCornerShape(t.radiusMd) }
    // 按下反馈：interaction 必须与 clickable 共用同一实例，否则收不到 Press 事件
    val interaction = remember { MutableInteractionSource() }
    // 沿用原本的「背景 + clip」口径；只有高亮行才组合动画对象（非高亮行走 Color.Transparent 的零对象路径）
    val highlightColor = if (highlighted) autoBookHighlightColor() else Color.Transparent
    val rowBackground = pressBackground(interaction, highlightColor)
    val anchorModifier = if (anchorIndex >= 0) Modifier.txRowAnchor(anchorIndex) else Modifier
    // 行内金额的 String.format 只随金额 / 收支方向变化重算，滚动挂载不重复格式化
    val amountText = remember(transaction.amount, isTransfer, amountIsPositive) {
        if (isTransfer) "¥" + transaction.displayAmount
        else (if (amountIsPositive) "+" else "-") + "¥" + transaction.displayAmount
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(anchorModifier)
            // focusRequester 要在 clickable 之前：clickable 自带 focusable，请求器先于它才挂到同一节点
            .then(modifier)
            .then(enterModifier)
            .rowPressFeedback(interaction)
            .clip(rowShape)
            .background(rowBackground)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            // 行高下限 tap-row 口径（row-min-h 52dp，DesignTokenSpecs6:150-151）
            .heightIn(min = t.rowMinH)
            .padding(horizontal = t.space3, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 列表行展示分类线描图标（与分类选择格同一套配色），不再只用色点
        CategoryIcon(name = transaction.categoryName, color = color, size = 34.dp)
        Spacer(Modifier.width(t.space3))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 转账没有分类：标题显示「转出账户 → 转入账户」，否则这一行会是空的
                    if (isTransfer) RecordForm.transferTitle(transaction) else transaction.categoryName,
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("row.tx"),
                )
                if (isAutoBooked) {
                    // 原型 s04：标题右侧、金额左边的小号「自动」徽标（浅底圆角、token 着色）
                    Spacer(Modifier.width(t.space1))
                    Badge(TransactionSourceBadge.AUTO_LABEL, tone = BadgeTone.Accent)
                }
            }
            // 副标题：优先用户手写的「说明」，为空时回退自动采集的商户 · 付款方式
            // （再退回商品说明），见 RecordForm.subtitle
            val sub = RecordForm.subtitle(transaction)
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(t.space2))
        Text(
            // 转账既不是花掉也不是赚到：不带正负号、用中性色（账户余额仍按双向真实增减）
            text = amountText,
            // 原型 .list-row .row-tail = 14px/600（同行 .row-title 17px/500）：金额不得抢过标题
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = when {
                isTransfer -> t.muted
                amountIsPositive -> t.incomeText
                else -> t.expenseText
            },
        )
    }
}

/**
 * 自动记账新行的高亮底色。
 *
 * 只有 [highlighted] 为 true 的行才会组合到这里：非高亮行不建 Animatable / 动画状态对象，
 * 滚动挂载 8–12 行时省下 8–12 套对象。挂载即满亮（新行插入时原先就是直接满高亮），
 * 1.6s 后 240ms 淡出；外层计时同为「1.6s + 淡出」，淡出跑完才卸载这一层。
 */
@Composable
private fun autoBookHighlightColor(): Color {
    val t = LocalDesignTokens.current
    val alpha = remember { Animatable(HIGHLIGHT_ALPHA) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(HIGHLIGHT_DURATION_MS)
        alpha.animateTo(0f, tween(HIGHLIGHT_FADE_MS))
    }
    return t.accent.copy(alpha = alpha.value)
}

/**
 * 边界演示行（锚点 s04-edge-tx / s04-edge-refund）。
 *
 * 与普通行同一套按下反馈（fg 5% + 共享 rowPressFeedback）、同一套截断口径（标题 / 副标题各单行省略、
 * 金额不换行），但**不接编辑跳转**：这两行是占位数据，没有真实主键，跳编辑会把占位金额写进库。
 */
@Composable
private fun S04EdgeRow(
    categoryName: String,
    iconColor: Color,
    title: String,
    subtitle: String,
    amountText: String,
    amountColor: Color,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val rowShape = remember(t.radiusMd) { RoundedCornerShape(t.radiusMd) }
    val rowBackground = pressBackground(interaction, Color.Transparent)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .rowPressFeedback(interaction)
            .clip(rowShape)
            .background(rowBackground)
            .testTag("row.edge")
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { }
            .heightIn(min = t.rowMinH)
            .padding(horizontal = t.space3, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(name = categoryName, color = iconColor, size = 34.dp)
        Spacer(Modifier.width(t.space3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(t.space1))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(t.space2))
        Text(
            amountText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
            maxLines = 1,
        )
    }
}

/** 加载态（锚点 s04-loading）：只有「加载中且无数据」才出骨架；骨架不接收点击、不参与位移。 */
@Composable
internal fun S04LoadingBlock() {
    val t = LocalDesignTokens.current
    StateSkeleton(
        modifier = Modifier
            .testTag("s04-loading")
            .padding(horizontal = t.space4, vertical = t.space2),
        rows = 3,
    )
}

/** 空态（锚点 s04-empty / s04-empty-add）：文案逐字照抄，CTA 未接线时不渲染。 */
@Composable
internal fun S04EmptyBlock(onAdd: () -> Unit) {
    val t = LocalDesignTokens.current
    EmptyState(
        text = "点右下角「+」记下第一笔，或从熊猫记账导入历史账目",
        modifier = Modifier
            .testTag("s04-empty")
            .padding(horizontal = t.space4),
        title = "本月还没有交易",
        icon = Icons.Filled.Download,
        actionLabel = "新增一笔",
        onAction = onAdd,
        actionModifier = Modifier.testTag("s04-empty-add"),
    )
}

/** 错态（锚点 s04-error / s04-retry）：只替换明细区，页头 / 月份条 / KPI / 双入口仍在。 */
@Composable
internal fun S04ErrorBlock(onRetry: () -> Unit) {
    val t = LocalDesignTokens.current
    ErrorState(
        text = "本地数据库读取超时，没能取到交易记录",
        modifier = Modifier
            .testTag("s04-error")
            .padding(horizontal = t.space4),
        title = "账目加载失败",
        onRetry = onRetry,
        actionLabel = "重新加载",
        actionModifier = Modifier.testTag("s04-retry"),
    )
}

/**
 * 边界态（锚点 s04-edge / s04-edge-tx / s04-edge-refund）。
 *
 * 演示数据一律为占位值，不读库、不写库；只在 DEBUG 包由五态切换条唤出。
 */
@Composable
internal fun S04EdgeBlock() {
    val t = LocalDesignTokens.current
    Column(Modifier.padding(horizontal = t.space4)) {
        EdgeState(
            text = "单笔最大 ¥1,234,567.89 · 累计结余 ¥9,876,543.21",
            modifier = Modifier.testTag("s04-edge"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "极限数据 · 单月 10,240 笔",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.meta,
                    modifier = Modifier.weight(1f),
                )
                Badge("10240", tone = BadgeTone.Accent)
            }
            Spacer(Modifier.height(t.space2))
            Text(
                "¥12,345,678.90",
                style = MaterialTheme.typography.titleMedium,
                color = t.fg,
                fontWeight = FontWeight.SemiBold,
                // 口径：超过 13 位省略号截断、不换行不撑破卡片
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(t.space3))
        Text(
            "超长分类名与超长备注（单行截断）",
            style = MaterialTheme.typography.labelMedium,
            color = t.muted,
            modifier = Modifier
                .padding(horizontal = t.space1)
                .padding(bottom = t.space1),
        )
        DayGroupRow(first = true, last = true) {
            S04EdgeRow(
                categoryName = "电商",
                iconColor = categoryColorOf("电商"),
                title = "购物 · 双十一预售定金与尾款合并自动分期",
                subtitle = "备注相当长：用于验证单行截断与省略号在超长文本下不撑破布局",
                amountText = "-¥1,234,567.89",
                amountColor = t.expenseText,
                modifier = Modifier.testTag("s04-edge-tx"),
            )
            HorizontalDivider(
                thickness = DividerDefaults.Thickness,
                color = t.borderSoft,
                modifier = Modifier.padding(start = t.space8),
            )
            S04EdgeRow(
                categoryName = "购物退款",
                // 口径：分类缺失时图标回退中性灰
                iconColor = t.muted,
                title = "购物退款",
                subtitle = "订单 202610140938 原路退回",
                amountText = "+¥58.00",
                // 口径：退款金额按收入色显示
                amountColor = t.incomeText,
                modifier = Modifier.testTag("s04-edge-refund"),
            )
        }
        Spacer(Modifier.height(t.space2))
        Text(
            "退款按收入色显示；缺失分类时回退为中性灰。",
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
    }
}

/**
 * 明细区 lazy items。
 *
 * J1：整月明细做成**行级** lazy items —— 日期头一个 item、每笔交易一个 item，
 * 只有可见行会组合；入场动画只给首屏可见的若干行。
 * 锚点（s04-tx-1..8）按明细可见顺序分配：序号在 items 构建期一次算定，不依赖组合时机。
 */
internal fun LazyListScope.financeMainItems(
    state: FiveState,
    grouped: Map<String, List<Transaction>>,
    highlightId: Long,
    tokens: DesignTokens,
    onRowClick: (Transaction) -> Unit,
    onAddClick: () -> Unit,
    onRetry: () -> Unit,
    /** 删除后要接住焦点的那一行（锚点 s05-del-confirm · 移除后）；null = 不挂焦点落点。 */
    focusRowId: Long? = null,
    focusRequester: FocusRequester? = null,
) {
    when (state) {
        FiveState.POPULATED -> {
            var rowIndex = 0
            grouped.forEach { (date, txs) ->
                item(key = "day-" + date) {
                    // 日期解析 + LocalDate.now() 只在挂载时算一次；
                    // 「今天/昨天」跨零点不会自动刷新 —— 本屏每次进入 Tab / 回前台都会重新 loadMonth 并重组，
                    // 可接受；若要求跨零点实时，应换成「每天变一次」的日期状态源。
                    val label = remember(date) { groupLabel(date) }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.muted,
                        modifier = Modifier
                            .padding(horizontal = tokens.space4)
                            .padding(top = tokens.space2, start = tokens.space2),
                    )
                }
                txs.forEachIndexed { index, tx ->
                    // 锚点序号在构建期递增（不在 item 内容里自增），保证与可见顺序一致
                    val anchor = if (rowIndex < TX_ANCHOR_COUNT) rowIndex else -1
                    val enterIndex = if (rowIndex < STAGGER_MAX_STEPS) rowIndex else -1
                    rowIndex++
                    // key 只用主键：finance_transactions.id 是 INTEGER PRIMARY KEY AUTOINCREMENT，
                    // toContentValues 不写 id（导入同样走自增），同一查询内 id 必然唯一。
                    // 带组内下标会让「插入/删除一笔」把该日后续行的 key 全改掉 → 整组丢状态、重播入场动画。
                    item(key = "tx-" + tx.id) {
                        DayGroupRow(
                            first = index == 0,
                            last = index == txs.lastIndex,
                        ) {
                            TransactionRow(
                                transaction = tx,
                                color = categoryColorOf(tx.categoryName),
                                highlighted = tx.id == highlightId,
                                enterIndex = enterIndex,
                                anchorIndex = anchor,
                                // 只有「删除后的目标行」带焦点落点，其余行不多挂一个请求器
                                modifier = if (focusRowId == tx.id && focusRequester != null) {
                                    Modifier.focusRequester(focusRequester)
                                } else {
                                    Modifier
                                },
                                onClick = { onRowClick(tx) },
                            )
                            if (index != txs.lastIndex) {
                                HorizontalDivider(
                                    thickness = DividerDefaults.Thickness,
                                    color = tokens.borderSoft,
                                    modifier = Modifier.padding(start = tokens.space8),
                                )
                            }
                        }
                    }
                }
            }
        }

        FiveState.LOADING -> item(key = "loading") { S04LoadingBlock() }
        FiveState.EMPTY -> item(key = "empty") { S04EmptyBlock(onAdd = onAddClick) }
        FiveState.ERROR -> item(key = "error") { S04ErrorBlock(onRetry = onRetry) }
        FiveState.EDGE -> item(key = "edge") { S04EdgeBlock() }
    }
}

/** 明细区之前固定有 5 个页头 item（head / month / kpis / entries / listhead）。加了新 item 必须同步这里。 */
internal const val FINANCE_MAIN_HEADER_ITEMS = 5

/**
 * 删除后返回列表的一次性焦点复位请求（锚点 s05-del-confirm · 移除后）。
 *
 * @param deletedId 刚被删掉的那笔。返回列表后要等它从明细里消失（重载落地）再定位，
 *   否则明细还是旧的一份、位次差一行，焦点会落到再下一行。
 * @param targetId 目标行：被删行的下一行；末行时是上一行；整月只有这一笔时为 null（焦点回明细区标题）。
 */
data class RowFocusAfterDelete(
    val deletedId: Long,
    val targetId: Long?,
)

/**
 * 删除某笔交易后，焦点该落到哪一行（纯函数，可 JVM 单测）。
 *
 * 锚点 s05-del-confirm · 移除后：「焦点落在被删行的下一行，若为末行则落在上一行」。
 * [displayOrder] 必须是明细的**真实展示顺序**：FinanceViewModel.getTransactionsGroupedByDate 用
 * groupBy 按日期分组，保留首次出现次序，因此月度列表顺序 == 明细的平铺顺序。
 * 返回 null 表示没有相邻行（整月只有这一笔），调用方把焦点落到明细区标题。
 */
internal fun neighborRowIdAfterDelete(displayOrder: List<Long>, deletedId: Long): Long? {
    val index = displayOrder.indexOf(deletedId)
    if (index < 0) return null
    return displayOrder.getOrNull(index + 1) ?: displayOrder.getOrNull(index - 1)
}

/**
 * 明细里某一笔对应的 LazyColumn **item 下标**（页头 item 数按 [headerItems] 计入）。
 *
 * 懒加载列表只能滚到「项下标」：删除后要先把目标行滚进组合窗口，才请求得到焦点。
 * 这里的项顺序必须与 [financeMainItems] 的构建顺序逐条一致（日期头 1 项 + 该日每笔 1 项）；
 * 找不到该笔返回 null，调用方兜底把焦点落到明细区标题。
 */
internal fun financeMainItemIndexOf(
    grouped: Map<String, List<Transaction>>,
    txId: Long,
    headerItems: Int = FINANCE_MAIN_HEADER_ITEMS,
): Int? {
    var index = headerItems
    grouped.forEach { (_, txs) ->
        index++ // 日期分组头
        txs.forEach { tx ->
            if (tx.id == txId) return index
            index++
        }
    }
    return null
}
