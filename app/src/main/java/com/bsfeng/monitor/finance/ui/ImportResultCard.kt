package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的结果卡；职责：导入结果统计（成功/跳过/失败 + 分类/账户/预算计数）与失败明细。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.finance.PandaImporter
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 导入结果概览卡（成功 / 跳过 / 失败 + 明细计数）。 */
@Composable
internal fun ImportResultSummaryCard(
    result: PandaImporter.ImportResult,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space4))
        GlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (result.hasErrors) "导入完成（有错误）" else "导入完成",
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

/** 失败明细列表（每条含原因与处理建议）。 */
@Composable
internal fun ImportFailureListCard(
    errors: List<String>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space3))
        AppCard(padding = t.space1) {
            errors.forEach { error ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
        }
    }
}
