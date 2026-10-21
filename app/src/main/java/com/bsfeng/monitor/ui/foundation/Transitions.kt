package com.bsfeng.monitor.ui.foundation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.bsfeng.monitor.ui.components.rememberMotionTier
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlin.math.roundToInt

/**
 * 统一转场基元：整屏覆盖层 / 底部抽屉 / 弹窗 / 折叠展开四类转场，全部取 design token 并响应减弱动效。
 *
 * 分四层，逐层可测：
 *  1. **纯函数** [transitionSpecFor] → [TransitionSpec]：档位是否播动画、时长 / 缓动 / 几何全部落成一个不可变数据类，
 *     不依赖 Compose 运行时，可直接 JVM 单测（见 TransitionsTest）。
 *  2. [TransitionSpec] → `EnterTransition` / `ExitTransition`：[toEnterTransition] / [toExitTransition]。
 *  3. @Composable 一步入口：[slideInFromRight] / [slideOutToRight] / [sheetEnterFromBottom] / [sheetExitToBottom] /
 *     [dialogEnter] / [dialogExit] / [expandEnter] / [expandExit]。
 *  4. [AnimatedScreenOverlay]：整屏覆盖层的一步式载体（`AnimatedVisibility` + `BackHandler`）。
 *
 * 时长 / 缓动 token 映射（不再出现裸 `tween()` 的默认 LinearEasing）：
 *   - 整屏覆盖层 / 抽屉 / 弹窗：进场 `durOverlayIn` + `easeOut`，退场 `durOverlayOut` + `easeIn`
 *   - 折叠展开：进出都取 `durPage`（内容区布局过渡，与 `dur-overlay-*` 的「覆盖层」语义区分）
 *
 * **减弱动效**：动效档为 `MotionTier.REDUCED`（用户选「精简」，或系统「动画时长缩放 = 0」）时，
 * 四个方向一律返回 `EnterTransition.None` / `ExitTransition.None`，内容直接落终态、不播动画
 * （时长仍保留在 spec 里便于调试与单测断言）。判定取 `rememberMotionTier() != MotionTier.REDUCED`，
 * 因此 FULL 与 NORMAL 两档都保留转场 —— NORMAL 只去掉逐个入场，不砍转场。
 *
 * **预测式返回**：`AndroidManifest.xml` 已开 `android:enableOnBackInvokedCallback="true"`，Compose 的 `BackHandler`
 * 在 targetSdk 33+ 会经 `OnBackInvokedDispatcher` 分发，返回键行为与开启前一致（[AnimatedScreenOverlay] 内部统一收口）。
 * 覆盖层跟随手势进度的「可拖拽退出」（SeekableTransitionState / PredictiveBackHandler 驱动进度）暂未接入：
 * `AnimatedVisibility` 走的是 Transition 状态机，与 `SeekableTransitionState` 是两套 API，需要把覆盖层改造成可寻址
 * 的 Transition 才能真正跟随手指；这里先保证 `enableOnBackInvokedCallback` 打开后返回行为不回归。
 */

/** 转场角色：决定取哪一组时长 token 与几何（进入 / 退出各一个，便于纯函数逐项断言）。 */
enum class TransitionRole {
    ScreenEnter,
    ScreenExit,
    SheetEnter,
    SheetExit,
    DialogEnter,
    DialogExit,
    ExpandEnter,
    ExpandExit,
}

/** 位移轴：整屏覆盖层水平、抽屉/折叠垂直、弹窗不位移（只 fade + scale）。 */
enum class SlideAxis { None, Horizontal, Vertical }

/** 缓动族：进场统一 `ease-out`、退场统一 `ease-in`（与原型一致）。 */
enum class TransitionEasing { Out, In }

/**
 * 一个转场的全部可测参数（纯数据，不含 Compose 类型）。
 *
 * [animated] 为 false 时表示减弱动效档位，[toEnterTransition] / [toExitTransition] 返回 `None`。
 * [slideBy] 是相对自身尺寸的比例（1f = 整宽 / 整高），[scaleFrom] 是缩放起点（1f = 不缩放）。
 */
@Immutable
data class TransitionSpec(
    val role: TransitionRole,
    val animated: Boolean,
    val durationMs: Int,
    val axis: SlideAxis,
    val slideBy: Float,
    val scaleFrom: Float,
    val easing: TransitionEasing,
)

/** 弹窗入场缩放的起点（原型 .dialog 的 pop 观感：0.94 → 1）。 */
const val DIALOG_SCALE_FROM: Float = 0.94f

/**
 * 纯函数：按角色 + 动效开关 + 主题 token 生成转场规格。
 *
 * 调用方只管「这是哪个方向」，时长 / 缓动 / 几何由本函数唯一决定；档位关闭时只把 [TransitionSpec.animated] 置 false，
 * 其余字段照常填充（时长保留 token 值，便于设置页展示与单测断言）。
 */
fun transitionSpecFor(
    role: TransitionRole,
    motionEnabled: Boolean,
    tokens: DesignTokens,
): TransitionSpec = when (role) {
    TransitionRole.ScreenEnter -> TransitionSpec(
        role, motionEnabled, tokens.durOverlayIn, SlideAxis.Horizontal, 1f, 1f, TransitionEasing.Out,
    )
    TransitionRole.ScreenExit -> TransitionSpec(
        role, motionEnabled, tokens.durOverlayOut, SlideAxis.Horizontal, 1f, 1f, TransitionEasing.In,
    )
    TransitionRole.SheetEnter -> TransitionSpec(
        role, motionEnabled, tokens.durOverlayIn, SlideAxis.Vertical, 1f, 1f, TransitionEasing.Out,
    )
    TransitionRole.SheetExit -> TransitionSpec(
        role, motionEnabled, tokens.durOverlayOut, SlideAxis.Vertical, 1f, 1f, TransitionEasing.In,
    )
    TransitionRole.DialogEnter -> TransitionSpec(
        role, motionEnabled, tokens.durOverlayIn, SlideAxis.None, 0f, DIALOG_SCALE_FROM, TransitionEasing.Out,
    )
    TransitionRole.DialogExit -> TransitionSpec(
        role, motionEnabled, tokens.durOverlayOut, SlideAxis.None, 0f, DIALOG_SCALE_FROM, TransitionEasing.In,
    )
    TransitionRole.ExpandEnter -> TransitionSpec(
        role, motionEnabled, tokens.durPage, SlideAxis.Vertical, 1f, 1f, TransitionEasing.Out,
    )
    TransitionRole.ExpandExit -> TransitionSpec(
        role, motionEnabled, tokens.durPage, SlideAxis.Vertical, 1f, 1f, TransitionEasing.In,
    )
}

private fun TransitionSpec.easingOf(tokens: DesignTokens): Easing =
    if (easing == TransitionEasing.Out) tokens.easeOut else tokens.easeIn

/** 规格 → 进场动画。减弱动效档位直接返回 [EnterTransition.None]。 */
fun TransitionSpec.toEnterTransition(tokens: DesignTokens): EnterTransition {
    if (!animated) return EnterTransition.None
    val e = easingOf(tokens)
    return when (role) {
        TransitionRole.ScreenEnter -> slideInHorizontally(
            animationSpec = tween<IntOffset>(durationMs, easing = e),
            initialOffsetX = { (it * slideBy).roundToInt() },
        ) + fadeIn(animationSpec = tween<Float>(durationMs, easing = e))

        TransitionRole.SheetEnter -> slideInVertically(
            animationSpec = tween<IntOffset>(durationMs, easing = e),
            initialOffsetY = { (it * slideBy).roundToInt() },
        ) + fadeIn(animationSpec = tween<Float>(durationMs, easing = e))

        TransitionRole.DialogEnter -> fadeIn(animationSpec = tween<Float>(durationMs, easing = e)) +
            scaleIn(initialScale = scaleFrom, animationSpec = tween<Float>(durationMs, easing = e))

        TransitionRole.ExpandEnter -> expandVertically(
            animationSpec = tween<IntSize>(durationMs, easing = e),
        ) + fadeIn(animationSpec = tween<Float>(durationMs, easing = e))

        // 退场角色没有对应的进场动画：返回 None 而不是抛异常（调用方传错角色时不至于崩屏）
        TransitionRole.ScreenExit, TransitionRole.SheetExit,
        TransitionRole.DialogExit, TransitionRole.ExpandExit,
        -> EnterTransition.None
    }
}

/** 规格 → 退场动画。减弱动效档位直接返回 [ExitTransition.None]。 */
fun TransitionSpec.toExitTransition(tokens: DesignTokens): ExitTransition {
    if (!animated) return ExitTransition.None
    val e = easingOf(tokens)
    return when (role) {
        TransitionRole.ScreenExit -> slideOutHorizontally(
            animationSpec = tween<IntOffset>(durationMs, easing = e),
            targetOffsetX = { (it * slideBy).roundToInt() },
        ) + fadeOut(animationSpec = tween<Float>(durationMs, easing = e))

        TransitionRole.SheetExit -> slideOutVertically(
            animationSpec = tween<IntOffset>(durationMs, easing = e),
            targetOffsetY = { (it * slideBy).roundToInt() },
        ) + fadeOut(animationSpec = tween<Float>(durationMs, easing = e))

        TransitionRole.DialogExit -> fadeOut(animationSpec = tween<Float>(durationMs, easing = e)) +
            scaleOut(targetScale = scaleFrom, animationSpec = tween<Float>(durationMs, easing = e))

        TransitionRole.ExpandExit -> shrinkVertically(
            animationSpec = tween<IntSize>(durationMs, easing = e),
        ) + fadeOut(animationSpec = tween<Float>(durationMs, easing = e))

        TransitionRole.ScreenEnter, TransitionRole.SheetEnter,
        TransitionRole.DialogEnter, TransitionRole.ExpandEnter,
        -> ExitTransition.None
    }
}

/** 取当前主题 token + 当前动效档位下的转场规格（组合期唯一入口）。 */
@Composable
fun rememberTransitionSpec(role: TransitionRole): TransitionSpec {
    val tokens = LocalDesignTokens.current
    // 只有 REDUCED（用户选精简 / 系统动画缩放 = 0）不播动画；FULL 与 NORMAL 都走完整转场——
    // 转场是「NORMAL 保留」的三项之一，档位差异体现在时长系数上（见 scaleDuration）。
    val motionEnabled = rememberMotionTier() != MotionTier.REDUCED
    return transitionSpecFor(role, motionEnabled, tokens)
}

/** 进场规格 → EnterTransition（组合期）；与 [rememberTransitionSpec] 共用同一次 token / 档位读取。 */
@Composable
private fun enterOf(role: TransitionRole): EnterTransition {
    val tokens = LocalDesignTokens.current
    return rememberTransitionSpec(role).toEnterTransition(tokens)
}

/** 退场规格 → ExitTransition（组合期）。 */
@Composable
private fun exitOf(role: TransitionRole): ExitTransition {
    val tokens = LocalDesignTokens.current
    return rememberTransitionSpec(role).toExitTransition(tokens)
}

/** 整屏覆盖层进场：水平滑入（整宽）+ 淡入。 */
@Composable
fun slideInFromRight(): EnterTransition = enterOf(TransitionRole.ScreenEnter)

/** 整屏覆盖层退场：向右滑出 + 淡出。 */
@Composable
fun slideOutToRight(): ExitTransition = exitOf(TransitionRole.ScreenExit)

/** 底部抽屉进场：垂直滑入（整高）+ 淡入。 */
@Composable
fun sheetEnterFromBottom(): EnterTransition = enterOf(TransitionRole.SheetEnter)

/** 底部抽屉退场：垂直滑出 + 淡出。 */
@Composable
fun sheetExitToBottom(): ExitTransition = exitOf(TransitionRole.SheetExit)

/** 弹窗进场：淡入 + 缩放 0.94 → 1。 */
@Composable
fun dialogEnter(): EnterTransition = enterOf(TransitionRole.DialogEnter)

/** 弹窗退场：淡出 + 缩放 1 → 0.94。 */
@Composable
fun dialogExit(): ExitTransition = exitOf(TransitionRole.DialogExit)

/** 折叠展开进场：高度展开 + 淡入。 */
@Composable
fun expandEnter(): EnterTransition = enterOf(TransitionRole.ExpandEnter)

/** 折叠展开退场：高度收起 + 淡出。 */
@Composable
fun expandExit(): ExitTransition = exitOf(TransitionRole.ExpandExit)

/**
 * 整屏覆盖层一步式载体：`AnimatedVisibility(enter = slideInFromRight(), exit = slideOutToRight())` + `BackHandler`。
 *
 * 调用方不再各自手写 enter/exit，也不再各自写 `Box(Modifier.fillMaxSize())` 与 `BackHandler`：
 * ```
 * AnimatedScreenOverlay(visible = showX, onBack = { showX = false }) { XScreen(onBack = { showX = false }) }
 * ```
 *
 * [onBack] 只在 [visible] 为 true 时接管返回键（与「页面级 BackHandler 只在该页可见时生效」的既有口径一致）；
 * 退出动画期间返回键不再被本覆盖层拦截，避免连按两次返回时重复触发。
 * [backEnabled] 供「覆盖层可见但暂不接管返回」的场景（如内部还有子页）显式关闭。
 * [onBack] 与调用方 `onBack` 回调重复触发是安全的：两者都是把可见态置 false 的幂等写。
 *
 * 预测式返回的手势进度跟随见文件头 KDoc（暂未接入，等 Compose 侧稳定 API）。
 */
@Composable
fun AnimatedScreenOverlay(
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // BackHandler 放在 AnimatedVisibility 外层：退出动画期间（visible 已为 false、内容仍在组合中）不再拦截返回键
    BackHandler(enabled = backEnabled && visible) { onBack() }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInFromRight(),
        exit = slideOutToRight(),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}
