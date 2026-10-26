package com.bsfeng.monitor.widget

// 尺寸档判定（纯函数）：桌面给多少 dp，就决定放几行内容。

/** 小组件三档（见 docs/小组件/设计.md 的尺寸档）。 */
enum class WidgetTier {
    /** 1 格宽（≈110dp）：一个数字 + 一行标签。 */
    TINY,

    /** 2 格宽（≈170dp）：两个数字或一个数字 + 进度。 */
    COMPACT,

    /** 4 格宽（≈350dp）：标题 + 多列指标 + 进度。 */
    WIDE,
}

/** 1 格 ≈ 110dp（含系统内边距）、2 格 ≈ 170–180dp、4 格 ≈ 350dp；阈值取在格宽之间。 */
private const val COMPACT_MIN_WIDTH_DP = 140f
private const val WIDE_MIN_WIDTH_DP = 280f

/**
 * 低于这个高度连一行主数字都放不下（横条 / 被压扁），统一按 TINY 渲染。
 *
 * 取 45dp 而不是 90dp：2×1 在真机上只有约 72dp（info 的 minHeight=40dp + launcher 内边距），
 * 90dp 的旧阈值会把所有 2×1 组件误判成 TINY（只显示一行，第二行内容永远出不来）。
 */
private const val MIN_VIABLE_HEIGHT_DP = 45f

/**
 * 按**宽度**判档。
 *
 * 为什么不按短边（T1 初版）：真机 4×2 的短边只有约 170dp，短边判定会把它压成 COMPACT，
 * 「三列指标 + 进度条」的 Wide 形态在真机上永远出不来（实测 2×1≈170×110、4×2≈350×170）。
 * 桌面的占格差异体现在宽度（1/2/4 格），高度只影响要不要省略进度条，所以主判档看宽度，
 * 只有被压扁到 90dp 以下时才一律降成 TINY。
 */
fun tierOf(widthDp: Float, heightDp: Float): WidgetTier {
    if (heightDp < MIN_VIABLE_HEIGHT_DP) return WidgetTier.TINY
    return when {
        widthDp < COMPACT_MIN_WIDTH_DP -> WidgetTier.TINY
        widthDp < WIDE_MIN_WIDTH_DP -> WidgetTier.COMPACT
        else -> WidgetTier.WIDE
    }
}
