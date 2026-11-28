package com.bsfeng.monitor.ui.quota

// s19 账号行：行头（头像 + 名称 + 状态角标 + 副行 + chevron）与展开收起。
// 面板本体在 QuotaSettingsAccountPanel.kt；分组容器在 QuotaSettingsAccounts.kt。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 行头头像用的首字母：取类型显示名的首字符（原型 O / S / D 是示例账号的写法）。 */
internal fun accountAvatarText(info: QuotaProviderInfo): String =
    info.typeDisplayName.trim().take(1).uppercase().ifEmpty { "?" }

/**
 * 一行账号：整行可点展开面板；展开时行内出现凭据面板（原型 .arow + .acc）。
 *
 * 口径：
 * - 行高下限 48dp、左右 12、上下 6；
 * - 展开/收起 220/150ms（durOverlayIn/Out），与其余覆盖层一致；
 * - 读屏读名带状态与展开态；角标/副行来自纯函数 [accountStatusText]。
 */
@Composable
internal fun AccountRow(
    info: QuotaProviderInfo,
    suffix: String,
    /** 行顶 1dp 边线（原型 border-top；第一行不画）。 */
    topBorder: Boolean,
    status: AccountStatusText,
    expanded: Boolean,
    configured: Boolean,
    saving: Boolean,
    saveError: String?,
    onToggle: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusMd)
    val stateWord = if (expanded) "已展开，点击收起" else "已收起，点击展开"
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 原型 .arow 实测 49.8（含 1px 行内边线）：取 50，让后续行累计偏差 < 1dp
                .heightIn(min = 50.dp)
                .topHairline(topBorder)
                .keyboardFocusRing(shape)
                .accountRowTagOf(suffix)
                .semantics {
                    role = Role.Button
                    contentDescription = accountRowTitle(info) + "，" + status.badge
                    stateDescription = stateWord
                }
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                ) {
                    haptic(HapticTone.LIGHT)
                    onToggle()
                }
                // 原型 .arow padding 6；Compose 的 CJK 行盒比 CSS 高 ~2.5，纵向收到 5 才回到
                // 原型 49.8/50.8（实测 6 → 52.3，四个账号累计 +7）
                .padding(horizontal = t.space3, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(accountAvatarText(info))
            Spacer(Modifier.width(t.space2))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        accountRowTitle(info),
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontSize = 12.sp, lineHeight = (12 * 1.2f).sp)
                            .quotaTight(),
                        color = t.fg,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(t.space1))
                    Badge(status.badge, tone = status.tone)
                }
                Spacer(Modifier.height(t.space1))
                Text(
                    status.subtitle,
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontSize = 11.sp, lineHeight = (11 * 1.25f).sp)
                        .quotaTight(),
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(t.space2))
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null, // decorative：行读名与 stateDescription 已说明展开态
                tint = t.muted,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(t.durOverlayIn, easing = t.easeOut)) +
                fadeIn(tween(t.durOverlayIn, easing = t.easeOut)),
            exit = shrinkVertically(tween(t.durOverlayOut, easing = t.easeIn)) +
                fadeOut(tween(t.durOverlayOut, easing = t.easeIn)),
        ) {
            CredentialPanel(
                info = info,
                suffix = suffix,
                saving = saving,
                saveError = saveError,
                configured = configured,
                onSave = onSave,
                onClear = onClear,
                onToast = onToast,
            )
        }
    }
}

/** 28dp 圆角方块底色头像（原型 .av）：12sp 700、fg2。 */
@Composable
private fun Avatar(initial: String) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(t.radiusSm))
            .background(t.fgFade(8)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = t.fg2,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 行内分割线（原型 .arow / .srow 的 border-top 1px）。色值走 borderSoft。 */
@Composable
internal fun RowHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 0.dp)
            .background(LocalDesignTokens.current.borderSoft)
            .heightIn(min = 1.dp),
    )
}