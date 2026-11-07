package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的边界数据区；职责：s08-edge 的展示样例（超长文件名 + 超限体积）。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 边界数据区（锚点 s08-edge）：超长文件名 + 128,430 条记录。
 *
 * 口径：文件名单行省略号；右侧角标等宽不换行；副行带「已超过 20 MB 建议上限」警示色；
 * 超 20 MB 仍允许选择、不拦截。纯展示、无 CTA。
 */
@Composable
internal fun ImportEdgeCard(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val longName = "alipay_record_2026-10-14-含转账与退款-完整版-final-final.csv"
    Column(modifier.padding(horizontal = t.space4).testTag("s08-edge")) {
        Spacer(Modifier.height(t.space3))
        EdgeState(text = "边界数据区：超长文件名 / 128,430 条记录 / 超过 20 MB 建议上限")
        Spacer(Modifier.height(t.space3))
        AppCard(padding = t.space4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = t.muted,
                    modifier = Modifier.size(t.iconSize),
                )
                Spacer(Modifier.width(t.space3))
                Text(
                    longName,
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(t.space2))
                Text(
                    "128430",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = t.accent,
                    maxLines = 1,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(t.space1))
            Text(
                "19.8 MB · 128,430 条记录 · 已超过 20 MB 建议上限",
                style = MaterialTheme.typography.labelSmall,
                color = t.warnText,
            )
        }
    }
}
