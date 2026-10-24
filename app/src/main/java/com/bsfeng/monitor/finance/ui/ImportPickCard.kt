package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的 pick item；职责：选择数据文件的入口卡与读取错误提示。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentSoftFill

/** 选择数据文件入口（点击触发系统文件选择器）。 */
@Composable
internal fun ImportPickCard(
    readError: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space3))
        AppCard(
            onClick = onPick,
            padding = t.space6,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(t.tapMin)
                        .clip(CircleShape)
                        .background(t.accentSoftFill()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Upload,
                        contentDescription = null,
                        tint = t.accent,
                        modifier = Modifier.size(t.iconSize),
                    )
                }
                Spacer(Modifier.height(t.space3))
                Text(
                    "选择数据文件",
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(t.space1))
                Text(
                    "熊猫记账导出的 .db 文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.muted,
                )
            }
        }
        if (readError.isNotEmpty()) {
            Spacer(Modifier.height(t.space2))
            Text(
                readError,
                style = MaterialTheme.typography.labelSmall,
                color = t.dangerText,
            )
        }
    }
}
