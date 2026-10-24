package com.bsfeng.monitor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.delay

/**
 * 开屏（原型 `.open-design/08-prototype.html` §8「开屏」）。
 *
 * **时序**：元素 / 动画 / 缓动沿用原型（CSS 1599-1631、JS 6028-6037），整条时间轴按
 * [SplashTimeline.SPEED] 等比加速（原型 1900ms → 约 930ms），数值与压缩依据见 [SplashTimeline]。
 * 原型最后 500ms 画面完全静止（元素 1400ms 全落位，淡出 1600ms 才起、移除拖到 1900ms），
 * 那段空等被一起压掉。等比而非逐项砍：起点比例与时长比例都不变，所以错落入场的先后与快慢
 * 手感与原型一致，不会压成「同时入场 / 一闪而过」。
 *
 * 减弱动效（原型 `@media (prefers-reduced-motion)` + JS reduce 分支）：元素直接落终态、
 * 400ms 起收起、700ms 结束，不参与缩放。
 *
 * **几何**：`.splash-inner` gap=`--space-3`、padding=`--space-6`；mark 96×96；字标 `--text-xl`/700/
 * `--tracking-display`/`--leading-body`；副标 `--text-sm`/`--muted`；进度条 120×3 + `margin-top:--space-4`，
 * 底 `color-mix(fg 10%,transparent)`、进度色 `--fg-2`、圆角 pill。
 *
 * **mark 内部**：原型 `.brand-mark` 是占位图形（自述见 5538-5541 行：正式 logo 只替换 `<svg>` 内部
 * 路径，外层结构与入场时序不变）。故这里画正式品牌标（环 r100/stroke18 + 三根强度柱 + 珠，取自
 * `.open-design/brand/assets/brand-mark.svg`），占位 mark 的逐元素入场映射到同名元素（环 ← od-draw；
 * 珠 ← 占位「中心点」的 od-pop）。强度柱原型没有，用 `.open-design/brand/splash-motion.html`
 * 冻结的 barGrow 配方；占位专用的软环 / 四向刻度、品牌资产的脉冲圈（会与整层淡出相撞）不移植。
 */
@Composable
fun BrandSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    word: String = "行为记录",
    tag: String = "记录自己的行为 · 去掉干扰",
    markSize: Dp = 96.dp, // 原型 .brand-mark{width:96px;height:96px}
) {
    val t = LocalDesignTokens.current
    val animated = rememberMotionEnabled()
    // 时间轴当前时刻（ms）：整条动效由同一个时钟驱动，各元素再按自己的 delay/时长/缓动取切片，
    // 与 CSS 的 animation-delay + animation-timing-function 一一对应。
    val clock = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (animated) {
            clock.animateTo(TL.HIDDEN_AT.toFloat(), tween(TL.HIDDEN_AT, easing = LinearEasing))
        } else {
            // 减弱动效：先落终态，再按原型 JS 的 reduce 分支 400ms 收起 / 700ms 结束。
            delay(TL.REDUCED_DONE_AT.toLong())
            clock.snapTo(TL.HIDDEN_AT.toFloat())
            delay((TL.REDUCED_HIDDEN_AT - TL.REDUCED_DONE_AT).toLong())
        }
        onFinished()
    }

    val ms = clock.value
    val layerAlpha = segment(ms, 0, t.durOverlayIn, t.easeStandard) *
        (1f - segment(ms, TL.DONE_AT, TL.DONE_FADE_MS, t.easeIn))
    val ringP = segment(ms, TL.RING_DELAY, TL.RING_MS, t.easeOut)
    val beadP = segment(ms, TL.BEAD_DELAY, TL.BEAD_MS, t.easeSpring)
    val wordP = segment(ms, TL.WORD_DELAY, TL.RISE_MS, t.easeOut)
    val tagP = segment(ms, TL.TAG_DELAY, TL.RISE_MS, t.easeOut)
    val barP = segment(ms, TL.BAR_DELAY, TL.BAR_MS, t.easeStandard)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(t.splashBg)
            .alpha(layerAlpha.coerceIn(0f, 1f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(t.space3),
            modifier = Modifier.padding(t.space6),
        ) {
            Canvas(Modifier.size(markSize)) {
                val s = size.minDimension / MARK_VIEWBOX
                val ink = t.splashInk
                // 环：占位 mark 的 .bm-ring od-draw（stroke-dashoffset 1→0）。
                // 等价做法：SVG <circle> 的路径起点在 3 点钟、顺时针推进，与 drawArc 的
                // 0° 起点 / 正角方向完全一致，故用等长描边弧替代 dashoffset。
                if (ringP > 0f) {
                    drawArc(
                        color = ink,
                        startAngle = 0f,
                        sweepAngle = RING_SWEEP * ringP,
                        useCenter = false,
                        topLeft = Offset(RING_LEFT * s, RING_LEFT * s),
                        size = Size(RING_DIAMETER * s, RING_DIAMETER * s),
                        style = Stroke(width = RING_STROKE * s, cap = StrokeCap.Round),
                    )
                }
                // 三根强度柱：品牌资产 barGrow（scaleY 0→1、opacity .35→1，origin center bottom）。
                MARK_BARS.forEachIndexed { index, bar ->
                    val p = segment(ms, TL.GROW_DELAYS[index], TL.GROW_MS, EASE_GROW)
                    if (p > 0.001f) {
                        scale(
                            scaleX = 1f,
                            scaleY = p,
                            pivot = Offset((bar.x + BAR_WIDTH / 2f) * s, BAR_BASE_Y * s),
                        ) {
                            drawRoundRect(
                                color = ink.copy(alpha = BAR_MIN_ALPHA + (1f - BAR_MIN_ALPHA) * p),
                                topLeft = Offset(bar.x * s, bar.y * s),
                                size = Size(BAR_WIDTH * s, bar.height * s),
                                cornerRadius = CornerRadius(BAR_RADIUS * s, BAR_RADIUS * s),
                            )
                        }
                    }
                }
                // 珠：占位 mark「中心点」的 od-pop（opacity 0→1、scale .94→1）。
                // --ease-spring 有约 0.5% 过冲，与原型实测 scale 1.00473 一致。
                if (beadP > 0f) {
                    val beadScale = BEAD_MIN_SCALE + (1f - BEAD_MIN_SCALE) * beadP
                    scale(
                        scaleX = beadScale,
                        scaleY = beadScale,
                        pivot = Offset(BEAD_CX * s, BEAD_CY * s),
                    ) {
                        drawCircle(
                            color = t.accent.copy(alpha = beadP.coerceIn(0f, 1f)),
                            radius = BEAD_R * s,
                            center = Offset(BEAD_CX * s, BEAD_CY * s),
                        )
                    }
                }
            }
            // 字标 / 副标：od-rise（透明度 + 上浮 10px，复用动效基元 Motion.kt 的 staggerEnter）。
            Text(
                text = word,
                style = MaterialTheme.typography.headlineMedium.copy(lineHeight = t.textXl * t.leadingBody),
                color = t.fg,
                modifier = Modifier.staggerEnter(wordP),
            )
            Text(
                text = tag,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = t.textSm * t.leadingBody),
                color = t.muted,
                modifier = Modifier.staggerEnter(tagP),
            )
            SplashProgressBar(
                progress = barP,
                modifier = Modifier.padding(top = t.space4),
            )
        }
    }
}

/** 进度条（原型 `.splash-bar` + `span`）：120×3、pill、底 fg 10%、进度色 --fg-2、scaleX 0→1。 */
@Composable
private fun SplashProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = modifier
            .width(BAR_TRACK_WIDTH)
            .height(BAR_TRACK_HEIGHT)
            .background(t.fgFade(BAR_TRACK_FADE), shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = progress.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(t.fg2, shape),
        )
    }
}

/**
 * 时间轴切片：把时钟 [clockMs] 映射为 [delayMs] 起、[durMs] 长的这段动画的进度。
 * 区间之前返回 0、之后返回 1（对应 CSS 的 `animation-fill-mode: both`），
 * 区间内归一化后交给 [easing] 变换（对应 `animation-timing-function`）。
 */
private fun segment(clockMs: Float, delayMs: Int, durMs: Int, easing: Easing): Float {
    if (clockMs <= delayMs) return 0f
    if (durMs <= 0) return 1f
    return easing.transform(((clockMs - delayMs) / durMs).coerceIn(0f, 1f))
}

// ─── 时序：数值与出处见 SplashTimeline（原型值 × 倍率，纯数据可单测）──────────
/** 别名为读起来短；[SplashTimeline] 是唯一数值来源，这里不重复写数。 */
private val TL = SplashTimeline

/** 品牌资产 barGrow 的缓动 cubic-bezier(.22,.85,.3,1)。 */
private val EASE_GROW = CubicBezierEasing(0.22f, 0.85f, 0.3f, 1f)

// ─── 几何常量（256 viewBox，与 brand/assets/brand-mark.svg 一致）─────────────
private const val MARK_VIEWBOX = 256f
private const val RING_R = 100f
private const val RING_LEFT = 128f - RING_R
private const val RING_DIAMETER = RING_R * 2f
private const val RING_SWEEP = 360f
private const val RING_STROKE = 18f
private const val BAR_WIDTH = 15f
private const val BAR_RADIUS = 7.5f
private const val BAR_BASE_Y = 183.5f
private const val BAR_MIN_ALPHA = 0.35f
private const val BEAD_CX = 199f
private const val BEAD_CY = 57f
private const val BEAD_R = 17f
private const val BEAD_MIN_SCALE = 0.94f

/** 强度柱（x / 顶 y / 高），顺序与 .sm-b1/.sm-b2/.sm-b3 的延迟一一对应。 */
private val MARK_BARS = listOf(
    MarkBar(76.5f, 96.5f, 87f),
    MarkBar(120.5f, 56.5f, 127f),
    MarkBar(164.5f, 112.5f, 71f),
)

/** 强度柱几何（viewBox 单位）。 */
private data class MarkBar(val x: Float, val y: Float, val height: Float)

/** 原型 .splash-bar{width:120px;height:3px}—— 有特定布局意义的尺寸，见设计规范 §4.2 例外。 */
private val BAR_TRACK_WIDTH = 120.dp
private val BAR_TRACK_HEIGHT = 3.dp

/** 原型 .splash-bar 底色的 color-mix 百分比。 */
private const val BAR_TRACK_FADE = 10
