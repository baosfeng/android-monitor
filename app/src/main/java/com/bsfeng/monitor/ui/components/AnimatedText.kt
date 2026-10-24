package com.bsfeng.monitor.ui.components

// 从 ui/components/Motion.kt 拆出：数字滚动 —— animatedCount / animatedAmount 取值器与 AnimatedNumber 组件。负责「数值变化时滚动到新值」。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 整型数字滚动（值层）。
 * 首次出现按 [animateOnStart] 从 0 滚到 [target]（原型行为）；此后 [target] 变化即滚动过去。
 * 业务屏字符串拼接场景直接用本函数，不再裸写 animateIntAsState。
 */
@Composable
fun animatedCount(
    target: Int,
    animated: Boolean = rememberMotionEnabled(),
    animateOnStart: Boolean = true,
): Int {
    val t = LocalDesignTokens.current
    val anim = remember { Animatable(if (animated && animateOnStart) 0f else target.toFloat()) }
    LaunchedEffect(target, animated) {
        if (animated) {
            anim.animateTo(target.toFloat(), tween(t.durChart, easing = t.easeOut))
        } else {
            anim.snapTo(target.toFloat())
        }
    }
    return anim.value.roundToInt()
}

/** 浮点数字滚动（值层），语义同 [animatedCount]。 */
@Composable
fun animatedAmount(
    target: Float,
    animated: Boolean = rememberMotionEnabled(),
    animateOnStart: Boolean = true,
): Float {
    val t = LocalDesignTokens.current
    val anim = remember { Animatable(if (animated && animateOnStart) 0f else target) }
    LaunchedEffect(target, animated) {
        if (animated) {
            anim.animateTo(target, tween(t.durChart, easing = t.easeOut))
        } else {
            anim.snapTo(target)
        }
    }
    return anim.value
}

/** 整型数字滚动（文本层）：见 [animatedCount]。 */
@Composable
fun AnimatedNumber(
    value: Int,
    modifier: Modifier = Modifier,
    animated: Boolean = rememberMotionEnabled(),
    animateOnStart: Boolean = true,
    prefix: String = "",
    suffix: String = "",
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
) {
    val shown = animatedCount(value, animated, animateOnStart)
    Text(
        text = prefix + shown.toString() + suffix,
        modifier = modifier,
        style = style,
        color = color,
    )
}

/** 浮点数字滚动（文本层）：[decimals] 位小数，默认两位（金额）。 */
@Composable
fun AnimatedNumber(
    value: Float,
    modifier: Modifier = Modifier,
    decimals: Int = 2,
    animated: Boolean = rememberMotionEnabled(),
    animateOnStart: Boolean = true,
    prefix: String = "",
    suffix: String = "",
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
) {
    val shown = animatedAmount(value, animated, animateOnStart)
    Text(
        text = prefix + String.format(Locale.US, "%.${decimals.coerceIn(0, 6)}f", shown) + suffix,
        modifier = modifier,
        style = style,
        color = color,
    )
}
