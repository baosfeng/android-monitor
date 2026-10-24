package com.bsfeng.monitor.ui.components

import kotlin.math.roundToInt

/**
 * 开屏时间轴（ms）—— 开屏时序的唯一数值来源，纯数据、无 Compose 依赖，可 JVM 单测。
 *
 * 元素、缓动、关键帧全部沿用原型 §8（`.open-design/08-prototype.html` CSS 1599-1631 +
 * JS 6028-6037），只是整条时间轴按 [SPEED] 等比加速：原型的 delay 与时长同乘一个系数，所以每个
 * 元素的「起点占整条的比例」与「自身快慢」都不变 —— 错落入场的先后与手感与原型一致，不会压成
 * 同时入场，也不会一闪而过。唯一的例外是收尾（见 [HIDDEN_AT]）。
 *
 * **为什么压**：原型总长 1900ms，最后 500ms 画面完全静止（元素 1400ms 就全部落位，淡出 1600ms
 * 才起，移除却拖到 1900ms，而淡出 1840ms 就结束了）。现在总长 [HIDDEN_AT] ≈ 930ms，静止尾巴
 * 一起消失，用户少等约 1 秒。
 *
 * 减弱动效分支 [REDUCED_DONE_AT] / [REDUCED_HIDDEN_AT] 本来只有 700ms，**不参与缩放**。
 */
internal object SplashTimeline {

    /** 整体倍率 —— 本次冷启动优化点。改这一处即可再调速，各元素按比例自动跟随。 */
    const val SPEED = 0.48f

    /** 淡出结束后留给「最后一帧真正归零」的缓冲（3 帧 @60Hz）。 */
    const val FADE_TAIL_MS = 48

    /** 原型值 → 现在的时间轴值（四舍五入到 ms）；原型值原样留在下面，便于对照设计规范。 */
    fun sped(protoMs: Int): Int = (protoMs * SPEED).roundToInt()

    // 原型出处：CSS 1599-1631（各元素 od-* 动画）+ JS 6028-6037（1600ms 加 .is-done、1900ms 移除）。
    val RING_DELAY = sped(60) // .bm-ring od-draw 720ms --ease-out
    val RING_MS = sped(720)
    val BAR_DELAY = sped(300) // .splash-bar od-splash-bar 1100ms --ease-standard
    val BAR_MS = sped(1100)
    val BEAD_DELAY = sped(420) // .bm-dot od-pop 420ms --ease-spring
    val BEAD_MS = sped(420)
    val WORD_DELAY = sped(520) // .splash-name od-rise 420ms --ease-out
    val TAG_DELAY = sped(620) // .splash-tag 同上
    val RISE_MS = sped(420)
    val DONE_AT = sped(1600) // .is-done 淡出起点
    val DONE_FADE_MS = sped(240) // transition opacity 240ms --ease-in

    /**
     * 移除 + 回调 `onFinished` = 淡出结束 + [FADE_TAIL_MS]。
     *
     * 原型在淡出结束（1840ms）后还空等 60ms 才移除，那段已不可见、纯浪费，故不保留；但也不能与
     * 淡出结束同帧摘掉 —— 最后一帧 alpha 可能还差一点没归零，会看出硬切。3 帧兼作首帧抖动余量。
     */
    val HIDDEN_AT = DONE_AT + DONE_FADE_MS + FADE_TAIL_MS

    /** 原型 JS 的 reduce 分支：400ms 收起、700ms 移除。已经够快，不参与缩放。 */
    const val REDUCED_DONE_AT = 400
    const val REDUCED_HIDDEN_AT = 700

    /** 强度柱：品牌资产 `.open-design/brand/splash-motion.html:33,39-41`（.sm-b2/.sm-b1/.sm-b3，380ms）。 */
    val GROW_MS = sped(380)
    val GROW_DELAYS = intArrayOf(sped(200), sped(100), sped(280))

    /** 最新落位的元素（进度条）：它的结束时刻决定「画面什么时候静止」。 */
    val LAST_ELEMENT_END_MS = BAR_DELAY + BAR_MS
}
