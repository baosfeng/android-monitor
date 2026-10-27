package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的按钮；职责：主按钮（含按压缩放、禁用态与连点闸门）与幽灵按钮。
// 本文件两个按钮只服务本屏吸底条（AddTransactionActionBar），故契约锚点 s14a-save / s05-del 直接落在组件内。

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 连点窗口（毫秒）：主按钮在窗口内的补点一律不放行。
 *
 * 契约 s14a-save 边界「连续点只写一次」的硬闸门 —— 屏幕层的提交态要等一帧重组才把按钮置灰，
 * 这中间的补点靠它兜住（保存成功后页面还要 260ms 才返回，窗口比它略长）。
 */
internal const val SUBMIT_DOUBLE_TAP_MS = 300L

/**
 * 连点闸门（纯函数，可 JVM 单测）：距上次放行不足 [window] 毫秒 → 拒绝本次点击。
 *
 * 不依赖重组时序；[lastAcceptedMillis] < 0 表示还没放过行，系统时钟回拨按「允许」处理，
 * 不让一次时间跳变把保存按钮永久锁死。
 */
internal fun acceptSubmitClick(
    nowMillis: Long,
    lastAcceptedMillis: Long,
    window: Long = SUBMIT_DOUBLE_TAP_MS,
): Boolean = lastAcceptedMillis < 0L ||
    nowMillis < lastAcceptedMillis ||
    nowMillis - lastAcceptedMillis >= window

/**
 * 吸底主按钮：按下缩放 + 禁用走中性配方（保证对比度）。
 *
 * 契约 s14a-save：按下缩到 0.97（120ms，SUCCESS 触感）；禁用时点击无反应、不弹错误、不抖动。
 * 提交中由调用方把 [enabled] 置 false（见 AddTransactionScreen 的 submitting）；[acceptSubmitClick]
 * 再兜一道「状态还没重组完」的补点，两层合起来保证 300ms 内连点 N 次只写 1 笔。
 */
@Composable
internal fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    // 上次放行点击的时刻（uptimeMillis）；-1 = 还没放过行
    val lastAccepted = remember { mutableLongStateOf(-1L) }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = t.springOf(MotionToken.Press),
        label = "primaryScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // 原型 .btn{border-radius:var(--radius-md)} = 12dp（不是 radius-lg 的 18dp）
            .clip(RoundedCornerShape(t.radiusMd))
            // 禁用态走中性配方（底 fg 12% + 字 muted）：accent 降饱和得到的 #87BAEE 配白字
            // 只有 2.04:1，低于 WCAG AA；中性配方同时把两态的差别从「深浅」变成「有没有色」。
            .background(if (enabled) t.accent else t.fgFade(12))
            .testTag("s14a-save")
            // 禁用态连 Press 事件都不收：不缩放、不震、不触发点击（契约 s14a-save 边界）
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                val now = SystemClock.uptimeMillis()
                if (acceptSubmitClick(now, lastAccepted.longValue)) {
                    lastAccepted.longValue = now
                    // 主操作确认用 SUCCESS（契约 s14a-save）；禁用态不震
                    haptic(HapticTone.SUCCESS)
                    onClick()
                }
            }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) t.accentOn else t.muted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 幽灵按钮（删除这笔交易等破坏性次级动作）。契约 s05-del：编辑态才出现，按下 120ms 变浅一档。
 *
 * @param focusRequester 焦点落点槽。渲染容器自身带 `clickable`（因此本来就在焦点树里），
 *   请求器必须挂在 `clickable` **之前**才落到同一节点上 —— s05-del-dialog / s05-del-cancel 的
 *   「确认框关闭后焦点回『删除这笔交易』」靠它。默认 null = 不参与焦点复位，既有调用点不变。
 */
@Composable
internal fun GhostButton(
    text: String,
    textColor: Color,
    background: Color,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) background.copy(alpha = (background.alpha + 0.08f).coerceAtMost(1f)) else background,
        animationSpec = tween(t.durState),
        label = "ghostBg",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusMd))
            .background(bg)
            .testTag("s05-del")
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = textColor)
    }
}
