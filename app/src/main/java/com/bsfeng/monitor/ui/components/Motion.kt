package com.bsfeng.monitor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.FULL_STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.scaleDuration
import com.bsfeng.monitor.ui.foundation.staggerStepsFor
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 动效基元（公共封装，业务屏只调用、不再裸写 animate*AsState / spring(...) / 手算 delay）。
 *
 * 三条基元与各自消费的 token：
 *  1. **数字滚动** [AnimatedNumber] / [animatedCount] / [animatedAmount]
 *     —— 曲线 --ease-out、时长 --dur-chart(600ms)，对齐原型 App.count() 的 1-(1-p)^3 + dur:600。
 *  2. **spring 配方** [springOf] / [springSpecOf]
 *     —— 过冲量由 --ease-spring 的 cubic-bezier 控制点数值推导，刚度由时长 token 推导（首峰时刻 = 时长），
 *        调用点不再手写 spring(dampingRatio = 0.6f, stiffness = 500f) 这类魔数。
 *  3. **列表错落入场** [rememberStaggerProgress] / [Modifier.staggerEnter]
 *     —— 逐项 delay = index × --stagger(24ms)，单条 --dur-page + --ease-out + 位移 10px，
 *        对齐原型 @keyframes od-rise / .rise / .s04-in。
 *
 * 契约 §7 的 reduced-motion 兜底由 [rememberMotionTier] 统一提供：档位（[MotionTier]）为
 * [MotionTier.REDUCED]，或系统「动画时长缩放」为 0 时（系统优先级最高，强制按 REDUCED 生效），
 * 三条基元全部直接落到终态；[MotionTier.NORMAL] 则保留时长、去掉 stagger 与 spring 过冲。
 */

/** 动效时长 token 名（与 DesignTokenTable 的 CSS 变量一一对应）。 */
enum class MotionToken { Press, State, OverlayIn, OverlayOut, Page, Chart, ChartFast }

/** --ease-spring：位置/缩放/旋转类 spring 观感的近似曲线（契约 §7）。 */
const val EASE_SPRING_TOKEN: String = "ease-spring"

/** 错峰步数上限：长列表末尾不至于等太久（24ms × 12 = 288ms）；唯一口径在 [FULL_STAGGER_MAX_STEPS]。 */
const val STAGGER_MAX_STEPS: Int = FULL_STAGGER_MAX_STEPS

/** 列表项入场位移起点：原型 @keyframes od-rise 的 translate3d(0,10px,0)。 */
val STAGGER_RISE: Dp = 10.dp

/** 按 token 取时长（ms）。 */
fun DesignTokens.durationMsOf(token: MotionToken): Int = when (token) {
    MotionToken.Press -> durPress
    MotionToken.State -> durState
    MotionToken.OverlayIn -> durOverlayIn
    MotionToken.OverlayOut -> durOverlayOut
    MotionToken.Page -> durPage
    MotionToken.Chart -> durChart
    MotionToken.ChartFast -> durChartFast
}

/** 通用 tween：颜色/透明度/位移一律 --ease-out（契约 §7）。 */
fun <T> DesignTokens.tweenOf(token: MotionToken = MotionToken.Press): TweenSpec<T> =
    tween(durationMillis = durationMsOf(token), easing = easeOut)

// ─────────────────────────── 1 · 数字滚动 ───────────────────────────

/**
 * 当前生效的动效档位（[MotionTier]），点击一次订阅、档位变化立即重组。
 *
 * 系统「动画时长缩放 = 0」时始终返回 [MotionTier.REDUCED]（系统优先级最高，忽略用户选择）；
 * 首次读系统设置有进程级缓存（ContentResolver 读是 binder IPC），缓存装在
 * [MotionConfig.systemAnimationsAllowed] 里，与基础层同源。
 */
@Composable
fun rememberMotionTier(): MotionTier {
    val context = LocalContext.current
    val config = remember(context) { MotionConfig.get(context) }
    val tier by config.tier.collectAsState()
    return tier
}

/**
 * 当前是否允许播动画（Boolean 兼容层，语义 = 档位 != [MotionTier.REDUCED]）。
 *
 * 它写在 [staggerEnterAt] / [rememberStaggerProgress] 等基元的 `animated:` 默认参数上，
 * 列表每行挂载都会求值一次 —— 内部只读 [MotionConfig] 的内存态，零 IPC、零磁盘。
 * 需要区分三档时用 [rememberMotionTier]。
 */
@Composable
fun rememberMotionEnabled(): Boolean = rememberMotionTier() != MotionTier.REDUCED

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

// ─────────────────────────── 2 · spring 配方 ───────────────────────────

/** cubic-bezier 串的数字提取正则：一次性构造（原先每次调用都新建 Regex）。 */
private val CUBIC_BEZIER_NUM_RE = Regex("-?[0-9]+(?:\\.[0-9]+)?")

/**
 * 从 cubic-bezier(a, b, c, d) 串求最大过冲（超出 1 的部分），如
 * cubic-bezier(0.34, 1.56, 0.64, 1) → 约 0.096。非四参数串返回 0。
 */
fun cubicBezierOvershoot(spec: String): Float {
    val p = CUBIC_BEZIER_NUM_RE.findAll(spec).map { it.value.toFloat() }.toList()
    if (p.size != 4) return 0f
    val y1 = p[1]
    val y2 = p[3]
    var max = 0f
    val steps = 512
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val u = 1f - t
        val y = 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t
        if (y > max) max = y
    }
    return (max - 1f).coerceAtLeast(0f)
}

/** 过冲 → 等价阻尼比：ζ = -ln(o) / sqrt(π² + ln²o)；无过冲即临界阻尼 1.0。 */
fun dampingRatioForOvershoot(overshoot: Float): Float {
    if (overshoot <= 0.0001f) return 1f
    val l = ln(overshoot.toDouble())
    return (-l / sqrt(PI * PI + l * l)).toFloat().coerceIn(0.05f, 1f)
}

/**
 * 时长 → 刚度：令弹簧首峰时刻 = 该 token 时长 T，
 * ωd = π/T，ω0 = ωd/sqrt(1-ζ²)，k = ω0²（Compose 弹簧单位质量）。
 */
fun stiffnessForDuration(durationMs: Int, dampingRatio: Float): Float {
    val tp = durationMs.coerceAtLeast(16) / 1000.0
    val wd = PI / tp
    val w0 = wd / sqrt((1.0 - dampingRatio * dampingRatio).coerceAtLeast(0.001))
    return (w0 * w0).toFloat().coerceIn(1f, 20000f)
}

/**
 * 纯函数配方：按「时长 + --ease-spring 原文」返回 spring 规格。
 * 不依赖 Compose 运行时，可直接单测（见 MotionPrimitivesTest）。
 */
fun <T> springSpecOf(durationMs: Int, easeSpringSpec: String): SpringSpec<T> {
    val z = dampingRatioForOvershoot(cubicBezierOvershoot(easeSpringSpec))
    return spring(dampingRatio = z, stiffness = stiffnessForDuration(durationMs, z))
}

/** spring 规格缓存：themeId → (档位,token → SpringSpec)。SpringSpec 与泛型 T 无关（同一实现类），按 * 缓存后回投。 */
private val springCache = ConcurrentHashMap<String, HashMap<Pair<MotionTier, MotionToken>, SpringSpec<*>>>()

/** 无过冲 spring：临界阻尼（dampingRatio = 1），时长语义与 [springOf] 的 FULL 档一致。 */
fun <T> flatSpringOf(durationMs: Int): SpringSpec<T> =
    spring(dampingRatio = 1f, stiffness = stiffnessForDuration(durationMs, 1f))

/** 生成 [springOf] / [flatSpringOf] 结果：FULL = 有过冲配方，其余档位 = 临界阻尼无过冲。 */
private fun <T> springFor(tier: MotionTier, durationMs: Int, easeSpringSpec: String): SpringSpec<T> =
    if (tier == MotionTier.FULL) springSpecOf(durationMs, easeSpringSpec) else flatSpringOf(durationMs)

/**
 * 按 token 取 spring 规格：时长取 [token]，过冲取当前主题的 --ease-spring；
 * [MotionTier.NORMAL] / [MotionTier.REDUCED] 档返回临界阻尼（无过冲）规格。
 *
 * 结果按 (themeId, 档位, token) 缓存，同一组合只做一次「正则 + 513 次采样」推导
 * （底栏 5 个 tab 每帧各调一次也不重复算）。
 *
 * 档位从 [MotionConfig] 内存态同步读（非 composable 签名保留，避免改 12 处调用点）；
 * 档位变化后第一次调用会取到新档位的规格，旧规格留在缓存里不影响正确性。
 *
 * ```
 * val scale by animateFloatAsState(pressed, animationSpec = t.springOf(MotionToken.Press))
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun <T> DesignTokens.springOf(token: MotionToken = MotionToken.State): SpringSpec<T> {
    val tier = MotionConfig.peek()?.current() ?: MotionTier.FULL
    val key = tier to token
    val cache = springCache[themeId] ?: HashMap<Pair<MotionTier, MotionToken>, SpringSpec<*>>()
        .also { springCache[themeId] = it }
    val hit = cache[key] as SpringSpec<T>?
    if (hit != null) return hit
    val spec = springFor<T>(tier, durationMsOf(token), ThemeRepository.rawToken(themeId, EASE_SPRING_TOKEN))
    cache[key] = spec
    return spec
}

/**
 * 按 token 取**任意**动画规格，供「REDUCED 档直接落终态」的调用点一步替换：
 * FULL → spring（有过冲）；NORMAL → 等时长 tween（0.75 倍时长、--ease-out、无过冲）；
 * REDUCED → `snap()`（不播）。
 *
 * 与 [springOf] 的区别：调用点接受 `AnimationSpec<T>` 而非 `SpringSpec<T>`，且能表达「不播」。
 */
fun <T> DesignTokens.motionSpecOf(token: MotionToken = MotionToken.State): AnimationSpec<T> =
    when (val tier = MotionConfig.peek()?.current() ?: MotionTier.FULL) {
        MotionTier.FULL -> springOf(token)
        MotionTier.NORMAL -> tween(durationMillis = tier.scaleDuration(durationMsOf(token)), easing = easeOut)
        MotionTier.REDUCED -> snap()
    }

// ─────────────────────────── 3 · 列表错落入场 ───────────────────────────

/** 第 [index] 项的延迟（ms）= index × stagger，封顶 [maxSteps] 步。 */
fun staggerDelayMs(index: Int, staggerMs: Int, maxSteps: Int = STAGGER_MAX_STEPS): Int =
    index.coerceAtLeast(0).coerceAtMost(maxSteps) * staggerMs.coerceAtLeast(0)

/**
 * 列表项入场进度 0→1：delay = index × --stagger，单条 [durationToken] + --ease-out。
 * 值层，可自行用于 alpha / scale / 自定义位移。
 *
 * [index] 传 `-1` = **不做入场动画**，直接落终态（J5：滚动中新挂载的项不该再起动画）。
 * [maxSteps] 默认取当前档位的 stagger 步数（[staggerStepsFor]）：只有 FULL 逐项延迟，
 * NORMAL / REDUCED 下 all-at-once（步数为 0 = 全部同时入场）。
 */
@Composable
fun rememberStaggerProgress(
    index: Int,
    animated: Boolean = rememberMotionEnabled(),
    durationToken: MotionToken = MotionToken.Page,
    maxSteps: Int = staggerStepsFor(rememberMotionTier()),
): Float {
    val t = LocalDesignTokens.current
    if (index < 0) return 1f
    val delayMs = staggerDelayMs(index, t.stagger, maxSteps)
    val anim = remember(index) { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(index, animated) {
        if (!animated) {
            anim.snapTo(1f)
        } else {
            anim.snapTo(0f)
            if (delayMs > 0) delay(delayMs.toLong())
            anim.animateTo(1f, tween(t.durationMsOf(durationToken), easing = t.easeOut))
        }
    }
    return anim.value
}

/** 把入场进度落到透明度 + 上浮位移（原型 od-rise：opacity 0→1、translateY 10px→0）。 */
fun Modifier.staggerEnter(progress: Float, riseFrom: Dp = STAGGER_RISE): Modifier = graphicsLayer {
    val p = progress.coerceIn(0f, 1f)
    alpha = p
    translationY = (1f - p) * riseFrom.toPx()
}

/**
 * 一步到位：`Modifier.staggerEnterAt(index)` 直接由 index 得到入场效果，内部已含 reduced-motion 兜底。
 * [index] 传 `-1` = 该项不做入场动画（直接可见），长列表滚动挂载新项时用。
 */
@Composable
fun Modifier.staggerEnterAt(
    index: Int,
    animated: Boolean = rememberMotionEnabled(),
    durationToken: MotionToken = MotionToken.Page,
    maxSteps: Int = staggerStepsFor(rememberMotionTier()),
    riseFrom: Dp = STAGGER_RISE,
): Modifier = this.staggerEnter(
    progress = rememberStaggerProgress(index, animated, durationToken, maxSteps),
    riseFrom = riseFrom,
)
