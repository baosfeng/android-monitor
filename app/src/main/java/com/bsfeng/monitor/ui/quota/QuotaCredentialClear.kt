package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens


/**
 * 「清除凭据」入口的显示与可点判定（纯函数，单测在 `QuotaCredentialClearTest`）。
 *
 * 抽出成纯函数的理由与 [providerRowAffordance] / [saveRowState] 相同：
 * 这条判据错了不会崩，只会让用户对着一个点了没用的按钮（未配置的家）或者
 * 在保存/清除途中连点两次（同一操作执行两遍）。
 *
 * @param configured 该 provider 是否还有已保存的凭据（[QuotaProviderInfo.connected]）；
 *   false 时整条清除入口不出现 —— 没有可清的东西，入口本身就是噪音。
 * @param busy 该行有在途操作（保存或清除）；true 时按钮置灰不可点，与保存共用同一把在途锁。
 */
internal data class CredentialClearAction(
    /** 是否渲染「清除凭据」这一行。 */
    val visible: Boolean,
    /** 是否可点（visible 为 false 时无意义，恒 false）。 */
    val enabled: Boolean,
)

/**
 * 清除入口的判据：**已配置才出现，在途一律不可点**。
 *
 * 「在途」包含两种操作：保存与清除。两者都改同一份凭据与同一条状态，
 * 允许交叉连点会出现「保存请求在跑时清除，随后保存成功又把凭据写回」的竞态。
 */
internal fun credentialClearAction(configured: Boolean, busy: Boolean): CredentialClearAction =
    CredentialClearAction(
        visible = configured,
        enabled = configured && !busy,
    )

/** 断开 provider 的二次确认标题（破坏性操作，先讲后果再问）。 */
internal const val CLEAR_CREDENTIALS_TITLE: String = "断开并清除凭据？"

/** 二次确认正文：写清会发生什么、以及之后要做什么才能恢复（口径：破坏性操作不藏后果）。 */
internal const val CLEAR_CREDENTIALS_MESSAGE: String =
    "将删除本机保存的 Key / 令牌与该家最近一次的额度数据，聚合榜与卡片回到「未配置」；" +
        "需要重新填写才能继续查询。刷新间隔与预警阈值不受影响。"

/** 行内「清除凭据」按钮文案。 */
internal const val CLEAR_CREDENTIALS_LABEL: String = "清除凭据"

/** 清除成功后的一次性提示（toast 文案，与触觉同一次操作只发一份）。 */
internal const val CLEAR_CREDENTIALS_DONE: String = "凭据已清除 · 重新填写后才能查询"

/**
 * 「清除凭据」次级操作：文字按钮、danger 语义、默认不抢主按钮的注意力。
 *
 * 为什么不用主色 / 不用实底：这一页的主操作永远是「保存并查询」，清除是低频补偿动作；
 * 做成主色实心会让用户第一眼看到的是「删掉」。在途时置灰（与保存共用同一把锁）。
 */
@Composable
internal fun ClearCredentialButton(enabled: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 触达区 ≥44dp（无障碍红线 3），视觉上只有一行文字
            .heightIn(min = t.tapMin)
            .clip(RoundedCornerShape(t.radiusMd))
            .pressFeedback(interaction, enabled = enabled)
            .testTag("button.inline")
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                // 只是打开确认框，还没真删：用 LIGHT；真正的破坏性反馈在确认键上（REJECT）
                haptic(HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            CLEAR_CREDENTIALS_LABEL,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) t.dangerText else t.muted,
        )
    }
}
/**
 * 断开 provider 的二次确认（破坏性语义）：先讲后果，再问要不要删。
 *
 * 抽出来是为了让「文案 + destructive 语义」与行本体分开演进：
 * 文案改一次只动这里，调用点（设置页 provider 行）不跟着晃。
 */
@Composable
internal fun ClearCredentialsConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        visible = visible,
        title = CLEAR_CREDENTIALS_TITLE,
        message = CLEAR_CREDENTIALS_MESSAGE,
        confirmLabel = CLEAR_CREDENTIALS_LABEL,
        dismissLabel = "取消",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
