package com.bsfeng.monitor.ui.components

// 从 ui/components/Motion.kt 拆出：spring 配方 —— 由 cubic-bezier 控制点推导过冲/阻尼比、由时长推导刚度，含缓存与 token 入口 springOf / springSpecOf / motionSpecOf。负责「动效曲线参数怎么算」。

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.scaleDuration
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt

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
