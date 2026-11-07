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
            "从支付宝 / 微信导出的账单，或「熊猫记账」导出的数据库导入历史账目。" +
                "同一笔按交易单号识别：已存在的只补全空白字段，不覆盖你改过的分类。",
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
        Spacer(Modifier.height(t.space3))
        listOf(
            "选择支付宝 / 微信账单（.csv · .xlsx · .zip）或熊猫记账 .db",
            "解析并自动推断分类：平台分类 → 商品说明 → 交易对方",
            "写入账本：已存在的补全空白，不存在的新增",
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
