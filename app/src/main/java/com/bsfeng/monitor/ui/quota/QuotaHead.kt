package com.bsfeng.monitor.ui.quota

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 刷新图标转一圈的时长（原型 720ms 一圈，不是设计 token，按原型取值）。 */
private const val REFRESH_SPIN_MS = 720

/**
 * 页头（原型 s18-head）：标题 20sp + 副行「N 个账号 · M 家 · 更新于 x 分钟前」+ 右三图标。
 *
 * 高度按原型 85dp 复现：原型 h1 带浏览器默认上下外边距（各 13.4），实现用显式间距占位，
 * 否则其后所有锚点会整体上移 21dp（对齐台账的 y 漂移问题）。
 */
@Composable
internal fun QuotaHead(
    accountCount: Int,
    providerCount: Int,
    updatedText: String,
    refreshing: Boolean,
    onHelp: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    refreshFocus: FocusRequester,
    settingsFocus: FocusRequester,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("s18-head")
            .padding(start = t.space4, end = t.space4, top = t.space2, bottom = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Spacer(Modifier.height(13.dp))
            Box(Modifier.height(23.dp)) {
                Text(
                    "额度",
                    style = quotaHeadTitle,
                    color = t.fg,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                )
            }
            Spacer(Modifier.height(15.dp))
            Row(Modifier.height(13.2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    accountCount.toString() + " 个账号 · " + providerCount + " 家 · ",
                    style = quotaTextSmall,
                    color = t.muted,
                    maxLines = 1,
                    modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                )
                Text(
                    updatedText,
                    style = quotaTextSmall,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("s18-updated").wrapContentHeight(Alignment.Top, unbounded = true),
                )
            }
        }
        Spacer(Modifier.width(t.space3))
        Row {
            QuotaHeadIcon(
                icon = Icons.Outlined.HelpOutline,
                contentDescription = "这张表怎么读",
                modifier = Modifier.testTag("s18-help"),
                tone = HapticTone.LIGHT,
                onClick = onHelp,
            )
            Spacer(Modifier.width(t.space2))
            QuotaHeadIcon(
                icon = Icons.Outlined.Refresh,
                contentDescription = "刷新额度",
                modifier = Modifier.testTag("s18-refresh").focusRequester(refreshFocus),
                tone = HapticTone.MEDIUM,
                rotating = refreshing,
                onClick = onRefresh,
            )
            Spacer(Modifier.width(t.space2))
            QuotaHeadIcon(
                icon = Icons.Outlined.Settings,
                contentDescription = "额度设置",
                modifier = Modifier.testTag("s18-settings").focusRequester(settingsFocus),
                tone = HapticTone.LIGHT,
                onClick = onOpenSettings,
            )
        }
    }
}

/**
 * 页头图标按钮：44dp 圆底 + 按压 0.96 + 键盘焦点环。
 *
 * 不复用 HeadIconButton：那个没有按压反馈与 2dp 焦点环（s18 口径两行都要）。
 */
@Composable
private fun QuotaHeadIcon(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier,
    tone: HapticTone,
    onClick: () -> Unit,
    rotating: Boolean = false,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val motion = rememberMotionEnabled()
    val spin = rememberInfiniteTransition(label = "quotaHeadSpin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(REFRESH_SPIN_MS, easing = LinearEasing)),
        label = "quotaHeadSpinAngle",
    )
    Box(
        modifier = Modifier
            .size(t.tapMin)
            .clip(CircleShape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, CircleShape)
            .pressFeedback(interaction)
            .keyboardFocusRing(CircleShape)
            .then(modifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(tone)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = t.fg2,
            modifier = Modifier
                .size(t.iconSize / 2)
                // 只在绘制期读动画值：转圈不触发重组
                .graphicsLayer { rotationZ = if (rotating && motion) angle else 0f }
                .testTag("button.icon"),
        )
    }
}

/**
 * 额度设置退出后「焦点回齿轮」的一次性信号（锚点 s18-settings · 键盘行）。
 *
 * 额度设置是宿主导航用覆盖层打开的（MainOverlays.kt），额度页拿不到「覆盖层已关闭」的回调；
 * 用最小代次信号衔接，不动共享导航件（同 AppearanceScreen 的先例）。
 */
internal object QuotaSettingsFocusReturn {
    private var generationState by mutableIntStateOf(0)
    val generation: Int get() = generationState

    /** 额度设置退页时调用一次（导航栏返回键与系统返回 / 手势共用）。 */
    fun request() {
        generationState += 1
    }
}

/** 代次有变化才需要回焦；同一代次重复重组不抢焦点。 */
internal fun quotaReturnFocusPending(currentGeneration: Int, seenGeneration: Int): Boolean =
    currentGeneration != seenGeneration
