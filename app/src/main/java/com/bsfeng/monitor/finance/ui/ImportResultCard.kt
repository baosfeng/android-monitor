package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的结果卡；职责：导入结果统计（成功/跳过/失败 + 分类/账户/预算计数）与失败明细。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.finance.PandaImporter
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 失败明细最多展示的条数（契约 s08-failrows）。 */
internal const val FAIL_ROWS_LIMIT = 50

/**
 * 导入结果概览卡（锚点 s08-result）。
 *
 * 契约：卡上无点击响应、不提供撤销或删除入口；三格数字 620ms 滚动到终值；卡可聚焦且整体一个焦点组
 * （三格不拆成三个 Tab 停位）；读屏一次读完「导入完成，共 1,039 条记录，成功 1,024，跳过 12，失败 3」，
 * 失败数为 0 时不朗读失败项。
 *
 * @param modifier 调用方注入锚点 / 焦点修饰（卡节点）。
 */
@Composable
internal fun ImportResultSummaryCard(
    result: PandaImporter.ImportResult,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val failCount = result.errors.size
    val title = if (result.hasErrors) "导入完成（有错误）" else "导入完成"
    val spoken = buildString {
        append(title)
        append("，共 ")
        append(formatCount(result.totalTransactions))
        append(" 条记录，成功 ")
        append(formatCount(result.importedTransactions))
        append("，跳过 ")
        append(formatCount(result.skippedTransactions))
        if (failCount > 0) {
            append("，失败 ")
            append(formatCount(failCount))
        }
    }
    Column(Modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space4))
        GlassCard(
            modifier = modifier
                .testTag("s08-result")
                .focusable()
                .keyboardFocusRing(shape)
                .semantics(mergeDescendants = true) { contentDescription = spoken },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = t.fg,
                )
                Badge(
                    text = formatCount(result.totalTransactions),
                    tone = if (result.hasErrors) BadgeTone.Warn else BadgeTone.Success,
                )
            }
            Spacer(Modifier.height(t.space3))
            Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                ResultTile(
                    label = "成功",
                    value = formatCount(animatedCount(result.importedTransactions)),
                    color = t.incomeText,
                    modifier = Modifier.weight(1f),
                )
                ResultTile(
                    label = "跳过",
                    value = formatCount(animatedCount(result.skippedTransactions)),
                    color = t.fg,
                    modifier = Modifier.weight(1f),
                )
                ResultTile(
                    label = "失败",
                    value = formatCount(animatedCount(result.errors.size)),
                    color = if (result.hasErrors) t.expenseText else t.fg,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(t.space3))
            Text(
                "共 " + formatCount(result.totalTransactions) + " 条记录 · 新增 " +
                    result.importedCategories + " 个分类 · " +
                    result.importedAccounts + " 个账户 · " +
                    result.importedBudgets + " 条预算",
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
    }
}

/**
 * 失败明细列表（锚点 s08-failrows）。
 *
 * 契约：行按下底变 fg 5%（--dur-state）、只展示原因不改数据；每行标题单行、原因两行内截断，
 * 第二行固定「该条已跳过，可点「重试失败项」再次导入」；超过 50 条只列前 50 条并追加提示；
 * 整块一个焦点组（Tab 进组一次），方向键在行间移动、逐行朗读。
 */
@Composable
internal fun ImportFailureListCard(
    errors: List<String>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shown = errors.take(FAIL_ROWS_LIMIT)
    val hidden = (errors.size - shown.size).coerceAtLeast(0)
    val requesters = remember(errors) { List(shown.size) { FocusRequester() } }
    Column(Modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space3))
        AppCard(
            padding = t.space1,
            modifier = modifier
                .testTag("s08-failrows")
                .focusGroup(),
        ) {
            shown.forEachIndexed { index, error ->
                FailureRow(
                    error = error,
                    requester = requesters[index],
                    onMove = { delta ->
                        val target = (index + delta).coerceIn(0, shown.lastIndex)
                        if (target != index) requesters[target].requestFocus()
                    },
                )
            }
            if (hidden > 0) {
                Text(
                    "还有 " + hidden + " 条未显示，可点「重试失败项」",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.meta,
                    modifier = Modifier.padding(horizontal = t.space3, vertical = t.space2),
                )
            }
        }
    }
}

/** 单条失败明细行。 */
@Composable
private fun FailureRow(error: String, requester: FocusRequester, onMove: (Int) -> Unit) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusMd)
    val bg by animateColorAsState(
        targetValue = if (pressed) lerp(t.surface, t.fg, 0.05f) else t.surface,
        animationSpec = tween(t.durState),
        label = "importFailRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = t.space1)
            .focusRequester(requester)
            .clip(shape)
            .background(bg)
            .keyboardFocusRing(shape)
            .semantics(mergeDescendants = true) {
                contentDescription = error + "，该条已跳过，可点「重试失败项」再次导入"
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionDown -> { onMove(1); true }
                        Key.DirectionUp -> { onMove(-1); true }
                        else -> false
                    }
                }
            }
            .testTag("row.fail")
            .clickable(interactionSource = interaction, indication = null) { /* 只展示原因 */ }
            .padding(horizontal = t.space3, vertical = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = t.warnText,
            modifier = Modifier.size(t.iconSize),
        )
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = t.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "该条已跳过，可点「重试失败项」再次导入",
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
    }
}