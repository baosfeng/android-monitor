package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceUIKit.kt 的屏幕基元；职责：屏幕头、圆形图标按钮、月份切换条、图例色点。

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.withTimeout

/**
 * 屏幕大标题（左对齐 h1 + 副标题 meta + 右侧动作）。
 *
 * 长按标题可唤出 DEBUG 五态开关（见 [FinanceStateBar]）——正式包不生效。
 *
 * 长按阈值默认沿用系统 `longPressTimeout`（≈500ms）；口径写死数字的页面（如 s04 的 600ms）
 * 传 [longPressTimeoutMillis]，否则同一手势在「按设计稿」和「按系统」之间对不上。
 */
@Composable
internal fun FinanceScreenHead(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    consumeTopInset: Boolean = false,
    /** 长按判定阈值（ms）；null = 系统 `longPressTimeout`（Compose 默认，现值不变）。 */
    longPressTimeoutMillis: Long? = null,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (consumeTopInset) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    when {
                        onLongPress == null -> Modifier
                        longPressTimeoutMillis == null -> Modifier.pointerInput(onLongPress) {
                            detectTapGestures(onLongPress = { onLongPress() })
                        }
                        else -> Modifier.longPressWithTimeout(longPressTimeoutMillis, onLongPress)
                    },
                ),
        ) {
            // 原型 .screen-head .h1 = 28px/700/-0.42px → headlineMedium（设计规范 §3.1 映射表）
            Text(title, style = MaterialTheme.typography.headlineMedium, color = t.fg)
            if (subtitle != null) {
                Spacer(Modifier.height(t.space1))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(t.space2))
            trailing()
        }
    }
}

/**
 * 指定阈值的长按检测（口径写死 600ms 这类非系统值时用）。
 *
 * 与 [detectTapGestures] 只差阈值来源：系统值走 `viewConfiguration.longPressTimeoutMillis`，
 * 口径数字必须能自己给，否则同一手势在实机与设计稿之间判定不一致。
 * 阈值内抬起 / 手势被取消都不算长按；长按命中后吃掉这一次抬起，避免上层再当成一次点击。
 */
private fun Modifier.longPressWithTimeout(timeoutMillis: Long, onLongPress: () -> Unit): Modifier =
    pointerInput(timeoutMillis, onLongPress) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val longPressed = try {
                withTimeout(timeoutMillis) { waitForUpOrCancellation() }
                false
            } catch (_: PointerEventTimeoutCancellationException) {
                true
            }
            if (longPressed) {
                onLongPress()
                waitForUpOrCancellation()
            }
        }
    }

/**
 * 圆形图标按钮（屏幕头右侧动作 / 月份切换）。
 *
 * 造型对齐原型 `.icon-btn`（00-shell:1117-1126）：--icon-size 44×44、radius-pill、
 * 底 fg 5%、描边 --border-soft。月份条内的同类按钮原型显式去掉底与边框
 * （`.s04-month .icon-btn{border-color:transparent;background:transparent}`，00-shell:1929），
 * 由 [chrome] 关闭，避免与月份胶囊底叠成两层。
 *
 * 反馈按口径走共享基元：[pressFeedback] 按下 0.96 + 点击时 [HapticTone.LIGHT]
 * （「可点元素必须有按下态」——此前本控件完全无按下反馈）。
 *
 * 可读名只有一处：[contentDescription] 由内容 lambda 交给内部图标（`contentDescription = label`）。
 * 不在这里给 Box 补 semantics —— 父子都有名字时读屏会把同一句话念两遍。
 */
@Composable
internal fun FinanceIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    /** 是否画 `.icon-btn` 的浅灰圆底 + 描边；月份条内传 false（原型是透明态）。 */
    chrome: Boolean = true,
    /** 内容 lambda 收到可读名：图标要把它落到 `contentDescription`，读屏才念得出按钮用途。 */
    content: @Composable (String?) -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = modifier
            .size(t.tapMin)
            .pressFeedback(interaction)
            .clip(shape)
            .then(
                if (chrome) {
                    Modifier
                        .background(t.fgFade(5))
                        .border(1.dp, t.borderSoft, shape)
                } else {
                    Modifier
                },
            )
            .testTag("button.icon")
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { content(contentDescription) }
}

/**
 * 月份切换条：整宽胶囊（浅灰底 + 1px 描边）内「左右 chevron + 中间月份标签」，
 * 切换后标签做一次 --ease-spring 弹一下。
 *
 * 造型对齐原型 `.s04-month` / `.s06-month` / `.s07-month`（00-shell:1928/1997/2036）：
 * padding 3px、radius-pill、底 fg 4%、描边 --border-soft；高度 52px 由
 * 内部 44px 图标按钮 + 上下 4dp 内边距自然撑出（原型的 3px 内边距 ≈ space-1）。
 *
 * 箭头留 [previousModifier] / [nextModifier] 两个槽：页面用它们注 `testTag`（锚点必须落在
 * 实际命中元素上）或补额外反馈；默认空修饰，既有调用点行为不变。
 */
@Composable
internal fun FinanceMonthBar(
    monthText: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /** 左侧箭头（上一月）追加的修饰，默认空。 */
    previousModifier: Modifier = Modifier,
    /** 右侧箭头（下一月）追加的修饰，默认空。 */
    nextModifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val bump = remember { Animatable(1f) }
    LaunchedEffect(monthText) {
        bump.snapTo(1.08f)
        bump.animateTo(1f, t.springOf(MotionToken.Press))
    }
    val shape = RoundedCornerShape(t.radiusPill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            .clip(shape)
            .background(t.fgFade(4))
            .border(1.dp, t.borderSoft, shape)
            .padding(t.space1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FinanceIconButton(
            onClick = onPrevious,
            modifier = previousModifier,
            contentDescription = "上个月",
            chrome = false,
        ) { label ->
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = label,
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
        Text(
            monthText,
            style = MaterialTheme.typography.titleLarge,
            color = t.fg,
            modifier = Modifier.graphicsLayer {
                scaleX = bump.value
                scaleY = bump.value
            },
        )
        FinanceIconButton(
            onClick = onNext,
            modifier = nextModifier,
            contentDescription = "下个月",
            chrome = false,
        ) { label ->
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = label,
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
    }
}

/**
 * 分类图例色点：颜色一律由 [categoryColorOf] 决定（s04/s05/s07 同一个口径）。
 *
 * 圆角固定 3dp —— 原型 `.list-row .legend-dot{width:10px;height:10px;border-radius:3px}`
 * 是圆角小方块（不是圆点），与统计页图例 [com.bsfeng.monitor.ui.components.Legend] 的 3dp 口径一致。
 */
@Composable
internal fun LegendDot(color: Color, modifier: Modifier = Modifier, size: Dp? = null) {
    val t = LocalDesignTokens.current
    Box(
        modifier
            .size(size ?: t.dotSize)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}

/**
 * 错误态该画成「整块错态卡」还是「列表首位的一条错误条」（锚点 s06-error / s07-error · 边界，纯函数可单测）。
 *
 * 只有「真的在错误态 **且** 没有任何缓存可显示」时，才允许整块替换列表区（不显示任何数字）；
 * 有缓存时错误只贴一条，下面的数值与图表照常渲染 —— 一次读库抖动不该把用户已经看到的数字清空。
 */
internal fun financeErrorAsCard(state: FiveState, hasCache: Boolean): Boolean =
    state == FiveState.ERROR && !hasCache

/**
 * 错误条（锚点 s06-error / s07-error · 边界「有缓存时错误条贴在列表首位、下方数值可读」）。
 *
 * 与共享 `ErrorState` 的差别只有一条：**不占列表区**。
 * - 无缓存（什么都读不到）：仍然用整块错态卡，列表区只留这一块，不显示任何数字；
 * - 有缓存：只贴这一条在列表首位，下面的数值 / 图表照常渲染 —— 读库抖一次不该把用户
 *   已经看到的数字整块清空（返工把真实失败接上之后，这条路是日常可达的）。
 *
 * 锚点仍落在整条上（`s06-error` / `s07-error`）：两种形态互斥、同一时刻只有一个挂载，
 * 锚点对账不受影响。重试按钮的锚点与焦点落点由 [actionModifier] 注入。
 */
@Composable
internal fun FinanceInlineErrorBar(
    title: String,
    text: String,
    actionLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusMd)
    AppCard(modifier = modifier, padding = t.space3) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = t.dangerText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(t.space1))
                Text(text, style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
            Spacer(Modifier.width(t.space2))
            Box(
                modifier = actionModifier
                    .height(t.tapMin)
                    .pressFeedback(interaction)
                    .clip(shape)
                    .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                        haptic(HapticTone.LIGHT)
                        onRetry()
                    }
                    .padding(horizontal = t.space3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = t.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("button.retry"),
                )
            }
        }
    }
}
