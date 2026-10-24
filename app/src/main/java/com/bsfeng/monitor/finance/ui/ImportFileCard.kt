package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的 file item；职责：已选文件卡（文件名 / 就绪状态 / 移除按钮）。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 已选文件卡：展示文件名与就绪状态，右侧可移除。 */
@Composable
internal fun ImportFileCard(
    fileName: String,
    ready: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space3))
        AppCard(padding = t.space1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.space3, vertical = t.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = t.muted,
                    modifier = Modifier.size(t.iconSize),
                )
                Spacer(Modifier.width(t.space3))
                Column(Modifier.weight(1f)) {
                    Text(
                        fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = t.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (ready) "已就绪 · 可开始导入" else "读取失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ready) t.incomeText else t.dangerText,
                    )
                }
                FinanceIconButton(
                    onClick = onRemove,
                    contentDescription = "移除文件",
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = t.muted,
                        modifier = Modifier.size(t.iconSize),
                    )
                }
            }
        }
    }
}

/** 导入进度条 + 阶段文案。 */
@Composable
internal fun ImportProgressCard(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space3))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(t.space2)
                .clip(RoundedCornerShape(t.radiusPill))
                .background(t.borderSoft),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(t.radiusPill))
                    // B1：进度条用 chart-2（不占每屏强调色预算）
                    .background(t.chart2),
            )
        }
        Spacer(Modifier.height(t.space3))
        Text(
            progressLabel(progress),
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
    }
}
