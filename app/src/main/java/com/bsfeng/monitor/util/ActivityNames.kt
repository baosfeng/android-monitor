package com.bsfeng.monitor.util

/**
 * Activity 类名判定与「当前 Activity」维护。

 * ## 为什么需要它
 * 无障碍的 `TYPE_WINDOW_STATE_CHANGED` **不只是 Activity 变化**：Toast、弹窗、输入法、
 * 系统对话框都会发同一类事件，且 `event.className` 是 **widget 类名**（`android.widget.TextView`、
 * `android.widget.FrameLayout`…）。
 *
 * 真机取证（小米 14，2026-10-13，30 份页面快照）：12 份把 `className` 记成了
 * `android.widget.TextView` / `android.widget.FrameLayout`，而当时的真实 Activity 是 App 主界面。
 * 直接采信会把「当前 Activity」冲掉，导致两类后果：
 * 1. 规则里写了 `activityIds` 的（前缀匹配）在 Toast 之后**静默不再命中**；
 * 2. 页面快照（写规则用）记下错误的 Activity，作者按错误的界面名写规则。
 *
 * 因此：**widget 类名不能用来更新"当前 Activity"**——同 App 内保留上一个已知 Activity，
 * 跨 App 时（上一个 Activity 已无意义）清空。
 */
object ActivityNames {

    /** 这些前缀下的是 widget / 系统 UI 类，不是 Activity */
    private val NON_ACTIVITY_PREFIXES = listOf(
        "android.widget.",
        "android.view.",
        "android.webkit.",
        "android.app.",
        "android.inputmethodservice.",
        "android.accessibilityservice.",
        "com.android.internal."
    )

    /** 类名是否像 Activity（非空且不属于 widget / 系统 UI 前缀） */
    fun isActivityClass(className: String): Boolean {
        if (className.isEmpty()) return false
        if (className.contains('/')) return false   // 应用详情等界面给的是 "pkg/cls" 形式
        return NON_ACTIVITY_PREFIXES.none { className.startsWith(it) }
    }

    /**
     * 计算新的「当前 Activity」。
     *
     * @param current 已知的当前 Activity（可能为空）
     * @param incoming 本次事件的 `className`（可能是 widget 类名）
     * @param appSwitched 前台 App 是否刚变化：变化时旧的 Activity 名已无意义，必须清空
     * @return 新的「当前 Activity」（可能为空字符串 = 未知）
     */
    fun resolveActivityClass(current: String, incoming: String, appSwitched: Boolean): String = when {
        isActivityClass(incoming) -> incoming
        appSwitched -> ""
        else -> current
    }
}
