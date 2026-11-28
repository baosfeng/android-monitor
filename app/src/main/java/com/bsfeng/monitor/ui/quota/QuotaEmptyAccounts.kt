package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 一个 Key 都没配时的空态（首装必经；原型没有这一屏）。
 *
 * 最轻的一档：一句标题 + 一句说明 + 一个入口，放在列表位置、占位高度与一张账号卡接近，
 * 所以页头 / 结论条不动、也没有新的空卡样式。
 *
 * 为什么**不打 testTag**：原型 s18 没有这个锚点，打上就是「代码自造锚点」（check-anchors
 * 判自增）。这条路由的验收靠文案断言（见 S18S19QuotaParityTest 的空态用例）。
 */
@Composable
internal fun QuotaEmptyAccounts(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "还没有配置额度账号",
            style = MaterialTheme.typography.titleMedium,
            color = t.fg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(t.space1))
        Text(
            "填一个 API Key 或登录令牌，就能在这里看到剩余额度与重置时刻。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(t.space4))
        Box(
            Modifier
                .height(t.tapMin)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(t.accent)
                .pressFeedback(interaction)
                .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                    haptic(HapticTone.LIGHT)
                    onOpenSettings()
                }
                .padding(horizontal = t.space5),
            contentAlignment = Alignment.Center,
        ) {
            Text("去额度设置添加", style = MaterialTheme.typography.bodyMedium, color = t.accentOn)
        }
    }
}
