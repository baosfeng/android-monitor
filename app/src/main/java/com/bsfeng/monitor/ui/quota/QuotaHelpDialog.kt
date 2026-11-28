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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 「这张表怎么读」帮助弹窗（原型 s18-help-scrim / s18-help-dialog / s18-help-close）。
 *
 * 走全项目统一的 [AppDialog]（窗口级遮罩 + 返回键关闭），不引 Material3 AlertDialog；
 * 文案与原型逐段一致，其中「≤20% 才叫快见底」是本轮实现口径（原型示例的 34% 不再出现）。
 */
@Composable
internal fun QuotaHelpDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppDialog(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier.testTag("s18-help-scrim"),
    ) {
        Column(Modifier.fillMaxWidth().testTag("s18-help-dialog")) {
            Text("这张表怎么读", style = MaterialTheme.typography.titleMedium, color = t.fg)
            Spacer(Modifier.height(t.space2))
            HelpParagraph("一行 = 一个账号，右侧最多两个数位列，每列都写着窗口名（5 小时 / 今日 / 本周 / 本月 / 周期）与重置时刻。")
            HelpParagraph("数字 = 剩余百分比，下面的横条同源；红色 = 已耗尽，黄色 = 快见底（≤20%），绿色 = 本轮已恢复。")
            HelpParagraph("顶部结论条永远显示最低的那一个；下面「额度提醒」按已恢复 / 快见底 / 已耗尽列最近发生的事，点一行直接定位到对应账号并展开。")
            HelpParagraph("点整行展开：该账号的全部窗口 + 24 小时 / 7 天 / 30 天用量柱图（默认 7 天，带 Y 轴）。重置时刻要每行都显示还是只显示需处理的行，在额度设置里改。")
            Spacer(Modifier.height(t.space2))
            CloseButton(onDismiss)
        }
    }
}

@Composable
private fun HelpParagraph(text: String) {
    val t = LocalDesignTokens.current
    Text(text, style = MaterialTheme.typography.bodySmall, color = t.fg2)
    Spacer(Modifier.height(t.space2))
}

/** 原型 .dialog .btn：通栏 44dp、accent 底、accentOn 字。 */
@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        Modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(t.accent)
            .pressFeedback(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onDismiss()
            }
            .testTag("s18-help-close"),
        contentAlignment = Alignment.Center,
    ) {
        Text("知道了", style = MaterialTheme.typography.bodyMedium, color = t.accentOn)
    }
}
