package com.bsfeng.monitor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开屏（品牌 C2「时间切片」动效，参照 .open-design/brand/splash-motion.html）。
 *
 * 时序（照搬品牌资产，只把黑场换成 token 的 --splash-bg / --splash-ink 浅色形态）：
 *   三根强度柱错落生长 380ms（延 200/100/280ms）→ 环浮现 300ms（延 400ms）
 *   → 珠子落位 440ms 回弹（延 620ms）→ 脉冲扩散 720ms（延 860ms）→ 字标上浮 340ms（延 860ms）
 *   → 1.22s 起淡出，1.8s 结束并回调 onFinished。
 * 几何与 brand-mark.svg 一致（256 viewBox：环 r100/stroke18、柱 stroke15 等价宽度 15、
 * 柱底 y=183.5、此刻珠 (199,57) r17）。
 */
@Composable
fun BrandSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    word: String = "行为记录",
    tag: String = "记录自己的行为 · 去掉干扰",
    markSize: Dp = 140.dp,
) {
    val t = LocalDesignTokens.current
    val easeGrow = CubicBezierEasing(0.22f, 0.85f, 0.3f, 1f)
    val easeBead = CubicBezierEasing(0.2f, 1.3f, 0.4f, 1f)
    val bar1 = remember { Animatable(0f) }
    val bar2 = remember { Animatable(0f) }
    val bar3 = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val bead = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    val wordP = remember { Animatable(0f) }
    val veil = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch { delay(200); bar1.animateTo(1f, tween(380, easing = easeGrow)) }
        launch { delay(100); bar2.animateTo(1f, tween(380, easing = easeGrow)) }
        launch { delay(280); bar3.animateTo(1f, tween(380, easing = easeGrow)) }
        launch { delay(400); ring.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
        launch { delay(620); bead.animateTo(1f, tween(440, easing = easeBead)) }
        launch { delay(860); pulse.animateTo(1f, tween(720, easing = LinearEasing)) }
        launch { delay(860); wordP.animateTo(1f, tween(340, easing = easeGrow)) }
        launch { delay(1220); veil.animateTo(0f, tween(580, easing = LinearEasing)) }
    }
    LaunchedEffect(Unit) {
        delay(1800)
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(t.splashBg)
            .alpha(veil.value),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(markSize)) {
                val s = size.minDimension / 256f
                val cx = 128f * s
                val cy = 128f * s
                val ink = t.splashInk
                val beadColor = t.accent
                val bars = listOf(
                    Triple(76.5f, 96.5f, 87f) to bar1.value,
                    Triple(120.5f, 56.5f, 127f) to bar2.value,
                    Triple(164.5f, 112.5f, 71f) to bar3.value,
                )
                bars.forEach { entry ->
                    val geom = entry.first
                    val p = entry.second
                    if (p > 0.001f) {
                        val x = geom.first
                        val y = geom.second
                        val h = geom.third
                        scale(
                            scaleX = 1f,
                            scaleY = p,
                            pivot = Offset((x + 7.5f) * s, 183.5f * s),
                        ) {
                            drawRoundRect(
                                color = ink,
                                topLeft = Offset(x * s, y * s),
                                size = Size(15f * s, h * s),
                                cornerRadius = CornerRadius(7.5f * s, 7.5f * s),
                            )
                        }
                    }
                }
                if (ring.value > 0f) {
                    drawCircle(
                        color = ink.copy(alpha = ring.value),
                        radius = 100f * s * (0.84f + 0.16f * ring.value),
                        center = Offset(cx, cy),
                        style = Stroke(width = 18f * s),
                    )
                }
                if (bead.value > 0f) {
                    drawCircle(
                        color = beadColor.copy(alpha = bead.value.coerceIn(0f, 1f)),
                        radius = 17f * s * (0.45f + 0.55f * bead.value),
                        center = Offset(199f * s, (57f - 22f * (1f - bead.value)) * s),
                    )
                }
                if (pulse.value > 0f) {
                    drawCircle(
                        color = beadColor.copy(alpha = (0.9f * (1f - pulse.value)).coerceAtLeast(0f)),
                        radius = 17f * s * (0.55f + 1.95f * pulse.value),
                        center = Offset(199f * s, 57f * s),
                        style = Stroke(width = 6f * s, cap = StrokeCap.Round),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = word,
                style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.12.sp),
                color = t.splashInk,
                modifier = Modifier.alpha(wordP.value.coerceIn(0f, 1f)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = tag,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.alpha(wordP.value.coerceIn(0f, 1f)),
            )
        }
    }
}
