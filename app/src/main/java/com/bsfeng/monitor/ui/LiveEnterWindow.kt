package com.bsfeng.monitor.ui

// 从 ui/MainScreen.kt 拆出：实时页的入场窗口 —— ENTER_WINDOW_MS 与 EnterWindow 开关。
// 负责「进屏那一屏才播入场动画」的瞬时判定，Time 线（LiveEventTimeline）按它决定每行/每组的 stagger 下标。

/**
 * 进屏后的入场窗口（ms）：进屏 / 切筛选后这段时间内首次挂载的行才播入场动画。
 * `dur-page(340) + stagger(24) × STAGGER_MAX_STEPS(12) = 628ms` 是首屏整段的动画时长，
 * 窗口取 600ms 保证「进屏那一屏」能播完整套错峰，又不让后续滚动继续起动画。
 */
internal const val ENTER_WINDOW_MS = 600L

/**
 * 入场窗口开关：进屏 / 切筛选后重新打开，窗口内首次挂载的组头与行才播入场动画。
 *
 * 有意**不用** Compose 状态：只有「之后挂载」的行需要读它，屏上已挂载的行不该因为
 * 窗口关闭而多重组一次（窗口开关与 UI 无关，只是组合期的瞬时判定）。
 */
internal class EnterWindow {
    var open: Boolean = true
        private set

    fun reopen() {
        open = true
    }

    fun close() {
        open = false
    }
}
