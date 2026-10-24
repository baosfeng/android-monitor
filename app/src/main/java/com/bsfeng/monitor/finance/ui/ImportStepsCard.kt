package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的 steps item；职责：导入页顶部说明与三步引导。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 导入说明 + 三步引导（与原型三段提示一致）。 */
@Composable
internal fun ImportStepsCard(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space3))
        Text(
            "从熊猫记账导出的数据文件导入历史账目。导入前会先预检：重复记录自动跳过，" +
                "无法识别的分类落到「未分类」。",
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
        Spacer(Modifier.height(t.space3))
        listOf(
            "选择熊猫记账导出的数据文件",
            "预检并预览：导入 / 跳过 / 失败 三类记录",
            "写入本地数据库并重建预算与统计",
        ).forEachIndexed { index, step ->
            Row(
                modifier = Modifier.padding(vertical = t.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(t.space6)
                        .clip(CircleShape)
                        .background(t.surfaceWarm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (index + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = t.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(t.space2))
                Text(
                    step,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.fg2,
                )
            }
        }
    }
}
