package com.bsfeng.monitor.autobook

/**
 * 浮窗关闭来源。
 *
 * **只有 [CONFIRM] 与 [IGNORE] 这两个按钮能让浮窗消失**；点遮罩、切到任何 App（包括本 App）
 * 一律不关闭，只有服务销毁（[SERVICE_RELEASE]）这种系统级生命周期才允许被动卸载。
 *
 * 真机事故（2026-10-20）：用户准备点「记一笔」时先点到了屏幕空白处，浮窗立刻消失；
 * 旧实现把「点遮罩」当成「忽略」并落盘 7 天（[AutoBookIgnoreStore]），同一笔之后再也不弹。
 * 用户明确要求：浮窗弹出后只能点「记一笔」或「忽略」关闭。
 */
enum class AutoBookCloseSource {
    /** 用户点「记一笔」 */
    CONFIRM,

    /** 用户点「忽略」 */
    IGNORE,

    /** 用户点到遮罩（确认卡以外的屏幕） */
    SCRIM_TAP,

    /** 用户切到别的 App / 回到桌面 */
    APP_SWITCH,

    /** 无障碍服务销毁（`release()`） */
    SERVICE_RELEASE,
}

/**
 * 一次「关闭尝试」应产生的副作用（纯数据，便于单测）。
 *
 * 用数据而不是布尔返回值表达：排查「为什么这笔不再弹」时要能区分是写了 ignore、
 * 写了 DISMISSED，还是什么都没写。
 */
data class AutoBookCloseEffect(
    /** 是否卸载浮窗 */
    val closeOverlay: Boolean,
    /** 是否在短期去重表标记「已记账」（[AutoBookDedup.Outcome.CONFIRMED]） */
    val markConfirmed: Boolean = false,
    /** 是否在短期去重表标记「已忽略」（[AutoBookDedup.Outcome.DISMISSED]） */
    val markDismissed: Boolean = false,
    /** 是否落盘 7 天忽略记忆（[AutoBookIgnoreStore]） */
    val persistIgnored: Boolean = false,
    /** 是否给一次轻震动反馈（遮罩点击：告诉用户点到了，但不会关） */
    val hapticFeedback: Boolean = false,
)

/**
 * 浮窗关闭策略：**关闭路径唯一化的唯一裁决点**。
 *
 * 引擎与浮窗 UI 都只问这个对象「这个来源要做什么」，不再各自判断 ——
 * 改造前「遮罩点击」直接调 `onDismiss`，而 `onDismiss` 顺手写了 7 天忽略记忆，
 * 两处耦合导致一次误触等于用户主动忽略。
 */
object AutoBookDismissPolicy {

    /** 这个来源对应的副作用 */
    fun effectOf(source: AutoBookCloseSource): AutoBookCloseEffect = when (source) {
        AutoBookCloseSource.CONFIRM -> AutoBookCloseEffect(
            closeOverlay = true,
            markConfirmed = true,
        )

        AutoBookCloseSource.IGNORE -> AutoBookCloseEffect(
            closeOverlay = true,
            markDismissed = true,
            persistIgnored = true,
        )

        // 遮罩点击 = 吞掉点击 + 轻震动，浮窗继续显示，不写任何去重记忆
        AutoBookCloseSource.SCRIM_TAP -> AutoBookCloseEffect(
            closeOverlay = false,
            hapticFeedback = true,
        )

        // 切 App 不收起：浮窗是全局无障碍浮层，用户切走再切回来仍要看到它
        AutoBookCloseSource.APP_SWITCH -> AutoBookCloseEffect(closeOverlay = false)

        // 服务销毁无法保留窗口，允许卸载；但这不是用户的「忽略」，不能写 7 天记忆
        AutoBookCloseSource.SERVICE_RELEASE -> AutoBookCloseEffect(closeOverlay = true)
    }

    /** 这个来源能不能让浮窗消失（只有按钮与服务销毁为 true） */
    fun closesOverlay(source: AutoBookCloseSource): Boolean = effectOf(source).closeOverlay

    /** 这个来源会不会落盘「忽略」记忆（只有 [AutoBookCloseSource.IGNORE] 为 true） */
    fun writesIgnore(source: AutoBookCloseSource): Boolean = effectOf(source).persistIgnored
}
