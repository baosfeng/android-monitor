package com.bsfeng.monitor.ui.foundation

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.atomic.AtomicReference

/**
 * 语义化触觉档：调用方只说「这是一次什么性质的操作」，不关心系统常量与版本分支。
 *
 * 档位与 `docs/UI设计/设计规范.md` §8 的三档对齐并扩到 6 档（补成功 / 警告 / 拒绝三个语义）：
 *
 * | 档 | 语义 | API 30+ | API 29 |
 * |---|---|---|---|
 * | [HapticTone.LIGHT] | 轻触：开关、Chip 选中、列表点击 | VIRTUAL_KEY | VIRTUAL_KEY |
 * | [HapticTone.MEDIUM] | 中触：Tab 切换、FAB | KEYBOARD_TAP | KEYBOARD_TAP |
 * | [HapticTone.HEAVY] | 重触：长按、拖拽落点 | LONG_PRESS | LONG_PRESS |
 * | [HapticTone.SUCCESS] | 成功：保存/提交完成 | CONFIRM | VIRTUAL_KEY |
 * | [HapticTone.WARNING] | 警告：可继续的提示 | LONG_PRESS | LONG_PRESS |
 * | [HapticTone.REJECT] | 拒绝：校验失败、删除确认 | REJECT | LONG_PRESS |
 */
enum class HapticTone { LIGHT, MEDIUM, HEAVY, SUCCESS, WARNING, REJECT }

/**
 * 触觉强度档（设置页可选）：只抬/降「触感强弱」，不改变语义档。
 *
 * - [STRONG]：LIGHT → MEDIUM、MEDIUM → HEAVY（已是最重的 HEAVY 不再升）；
 * - [NORMAL]（默认）：原样输出，对应用户「性能机不妥协、默认满体验」；
 * - [LIGHT]：HEAVY → MEDIUM、MEDIUM → LIGHT（LIGHT 已是最轻的不再降）。
 *
 * 语义档（SUCCESS / WARNING / REJECT）在任何强度下都不改档 —— 它们承载的是「结果」而非「力度」，
 * 抬高或压低会让用户分不清成功与失败。
 */
enum class HapticStrength {
    LIGHT,
    NORMAL,
    STRONG,
    ;

    /** 强度档 → 实际生效的触觉档（纯函数，可直接 JVM 单测）。 */
    fun toneFor(tone: HapticTone): HapticTone = when (this) {
        NORMAL -> tone
        STRONG -> when (tone) {
            HapticTone.LIGHT -> HapticTone.MEDIUM
            HapticTone.MEDIUM -> HapticTone.HEAVY
            else -> tone
        }
        LIGHT -> when (tone) {
            HapticTone.HEAVY -> HapticTone.MEDIUM
            HapticTone.MEDIUM -> HapticTone.LIGHT
            else -> tone
        }
    }
}

// ─────────────────────────── 1 · 档 → 系统常量 ───────────────────────────

/**
 * API 29 上可用的常量映射。
 *
 * `CONFIRM`(16) / `REJECT`(17) 是 API 30 才有的常量，minSdk 29 直接引用会被 lint 判 `InlinedApi`
 * （旧机型上常量按值内联，表现是「没反馈」而不是崩溃）。
 */
private fun legacyConstantFor(tone: HapticTone): Int = when (tone) {
    HapticTone.LIGHT -> HapticFeedbackConstants.VIRTUAL_KEY
    HapticTone.MEDIUM -> HapticFeedbackConstants.KEYBOARD_TAP
    HapticTone.HEAVY -> HapticFeedbackConstants.LONG_PRESS
    HapticTone.SUCCESS -> HapticFeedbackConstants.VIRTUAL_KEY
    HapticTone.WARNING -> HapticFeedbackConstants.LONG_PRESS
    HapticTone.REJECT -> HapticFeedbackConstants.LONG_PRESS
}

/**
 * API 30+ 独有常量的收口处。
 *
 * 抑制理由：[constantFor] 是按 `sdkInt` **参数**分支的纯函数（为了能在 JVM 单测里覆盖各版本分支），
 * lint 只认方法体内的 `Build.VERSION.SDK_INT` 守卫，看不到参数级守卫。调用链保证只有 30+ 才进来。
 */
@Suppress("InlinedApi")
private fun api30ConstantFor(tone: HapticTone): Int = when (tone) {
    HapticTone.SUCCESS -> HapticFeedbackConstants.CONFIRM
    HapticTone.REJECT -> HapticFeedbackConstants.REJECT
    else -> legacyConstantFor(tone)
}

/**
 * 触觉档 → [HapticFeedbackConstants] 常量（纯函数：把 sdkInt 作为参数，便于单测各版本分支）。
 *
 * @param sdkInt 取 `Build.VERSION.SDK_INT`；测试里直接传 29 / 30 / 37。
 */
fun constantFor(tone: HapticTone, sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) api30ConstantFor(tone) else legacyConstantFor(tone)

// ─────────────────────────── 2 · 三重闸门 ───────────────────────────

/**
 * 三重闸门：系统触觉总开关 / View 自身触觉开关 / 应用内开关，任一为 false 都不震。
 *
 * 抽成纯函数是有意的——「静音或关了触觉的用户不该被震」这条最容易回归，
 * 用单测把 8 种组合钉死，比在设备上手动开关系统设置可靠。
 */
fun shouldPerformHaptic(
    systemHapticsEnabled: Boolean,
    viewHapticsEnabled: Boolean,
    appHapticsEnabled: Boolean,
): Boolean = systemHapticsEnabled && viewHapticsEnabled && appHapticsEnabled

/**
 * 进程级缓存：系统触觉总开关只读一次（每次读都是 ContentResolver → binder IPC）。
 *
 * 进程内不再刷新：本文件没有 Application 级注册点，挂广播/ContentObserver 需要 Application 侧配合，
 * 所以用户在系统设置里改完要**重启 App 才生效**。别在这里补一个没人调的 refresh —— 那只会让注释撒谎。
 */
private val systemHapticsCache = AtomicReference<Boolean?>(null)

/**
 * 系统触觉总开关（`Settings.System.HAPTIC_FEEDBACK_ENABLED`）当前是否开启。
 *
 * 读失败时按开启处理（与「默认满体验」一致：读不到不惩罚用户）。
 */
fun systemHapticEnabled(context: Context): Boolean = systemHapticsCache.get() ?: runCatching {
    Settings.System.getInt(
        context.applicationContext.contentResolver,
        Settings.System.HAPTIC_FEEDBACK_ENABLED,
        1,
    ) != 0
}.getOrDefault(true).also { systemHapticsCache.set(it) }

// ─────────────────────────── 3 · 统一入口 ───────────────────────────

/**
 * 全项目唯一的触觉出口：先过三重闸门，再按强度档换算出常量发一次。
 *
 * ```
 * val view = LocalView.current
 * view.performHaptic(HapticTone.LIGHT)
 * ```
 *
 * 不做的事：转场、滚动、同一操作重复震动（规范 §8）。调用点不要自己判版本/系统开关。
 */
fun View.performHaptic(tone: HapticTone) {
    val ctx = context
    HapticConfig.attach(ctx)
    if (!shouldPerformHaptic(
            systemHapticsEnabled = systemHapticEnabled(ctx),
            viewHapticsEnabled = isHapticFeedbackEnabled,
            appHapticsEnabled = HapticConfig.isEnabled,
        )
    ) {
        return
    }
    performHapticFeedback(constantFor(HapticConfig.currentStrength.toneFor(tone), Build.VERSION.SDK_INT))
}

/** Compose 侧统一入口：内部取 [LocalView]，业务屏不必自己 `LocalView.current`。 */
@Composable
fun rememberHaptic(): (HapticTone) -> Unit {
    val view = LocalView.current
    return remember(view) { { tone: HapticTone -> view.performHaptic(tone) } }
}
