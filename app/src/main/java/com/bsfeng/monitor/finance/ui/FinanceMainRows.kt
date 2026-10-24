package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt 的明细行；职责：按日分组卡的单行外框（圆角/描边）与单笔交易行。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

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
    /** 自动记账刚落库：整行淡 accent 底点亮，1.6s 后淡出（其余行恒为透明） */
    highlighted: Boolean = false,
    /** >= 0 走错落入场；-1 表示离屏行不做入场动画（每行一个 Animatable 会拖帧） */
    enterIndex: Int,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val amountIsPositive = !transaction.isExpense || transaction.isRefundEntry
    val isTransfer = transaction.isTransfer
    // 来源徽标：自动记账 / 带 sourceId 的导入行才判 true，手写记录恒 false（见 TransactionSourceBadge）
    val isAutoBooked = TransactionSourceBadge.isAutoBooked(transaction.source, transaction.sourceId)
    val enterModifier = if (enterIndex >= 0) Modifier.staggerEnterAt(enterIndex) else Modifier
    val rowShape = remember(t.radiusMd) { RoundedCornerShape(t.radiusMd) }
    // 沿用原本的「背景 + clip」口径；只有高亮行才组合动画对象（非高亮行走 Color.Transparent 的零对象路径）
    val highlightColor = if (highlighted) autoBookHighlightColor() else Color.Transparent
    // 行内金额的 String.format 只随金额 / 收支方向变化重算，滚动挂载不重复格式化
    val amountText = remember(transaction.amount, isTransfer, amountIsPositive) {
        if (isTransfer) "¥" + transaction.displayAmount
        else (if (amountIsPositive) "+" else "-") + "¥" + transaction.displayAmount
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(enterModifier)
            .clip(rowShape)
            .background(highlightColor)
            .clickable { onClick() }
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
                    modifier = Modifier.weight(1f, fill = false),
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
                Text(sub, style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(t.space2))
        Text(
            // 转账既不是花掉也不是赚到：不带正负号、用中性色（账户余额仍按双向真实增减）
            text = amountText,
            // 原型 .list-row .row-tail = 14px/600（同行 .row-title 17px/500）：金额不得抢过标题
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
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
