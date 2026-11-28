package com.bsfeng.monitor.widget

// 额度组件的排版档位（纯逻辑）：占格 → 几列几格 / 要不要顶栏 / 用哪一档字阶。
//
// 为什么与 QuotaWidgetModel.kt 分开：内容层回答「显示什么账号、什么文案」，
// 排版层回答「这个箱子能放几格、字多大」；两者改的理由不同（产品口径 vs 桌面占格），
// 合在一个文件里已经顶到 300 行上限。

/**
 * 网格档位：几列、几格、要不要顶栏（「额度 · 更新于 4 分钟前」）。
 *
 * @param columns 1 = 整宽单列（2×2 / 2×1），2 = 4 格的两列网格
 * @param cells 最多渲染几格
 * @param showHeader 顶部「额度 · 更新于 …」是否显示（矮箱子先砍装饰、保数据）
 */
data class QuotaWidgetGrid(
    val columns: Int,
    val cells: Int,
    val showHeader: Boolean,
)

/**
 * 一档字阶 + 间距：组件在矮箱子与高箱子上用的两套排版数字。
 *
 * @param padding 卡片内边距
 * @param valueSizeSp 数值字号（等宽）
 * @param captionSizeSp 说明字号
 * @param shortReset 说明行里的重置时刻是否只给钟点（紧凑档一格约 109dp，带「今天/周一」前缀会吃掉时刻）
 * @param barHeight 细横条高度
 */
data class QuotaWidgetScale(
    val padding: Int,
    val headerSizeSp: Int,
    val headerBadge: Int,
    /** 顶栏图标块与文字的间距（原型 .wtop gap:5px，取 6dp 与组件公共间距族对齐）。 */
    val headerBadgeGap: Int,
    val headerGap: Int,
    val valueSizeSp: Int,
    val captionSizeSp: Int,
    val captionGap: Int,
    val barHeight: Int,
    val barGap: Int,
    val rowGap: Int,
    val columnGap: Int,
)

/**
 * 组件几何与字阶 —— Glance 实现与截图镜像共用同一份数字。
 *
 * 为什么两档：Glance 的 TextStyle 没有 lineHeight（1.2.0 的 javap 核对过），行高只能由字号 +
 * 系统字体 metric 决定；字号一改，「4 格塞不塞得进 110dp」就变。所以矮箱子用紧凑档（15/10），
 * 真机 4×2（170dp）回到原型字阶（16/11/13）并把行距放大，别在底部留一大块空。
 */
object QuotaWidgetMetrics {

    /** 紧凑档：250×110 声明最小箱。实测两行格底 95.3dp / 箱子 110dp（余量 14.7dp）。 */
    val COMPACT = QuotaWidgetScale(
        padding = 10,
        headerSizeSp = 12,
        headerBadge = 16,
        headerBadgeGap = 6,
        headerGap = 6,
        valueSizeSp = 15,
        captionSizeSp = 10,
        captionGap = 1,
        barHeight = 4,
        barGap = 2,
        rowGap = 6,
        columnGap = 12,
    )

    /** 宽松档：真机 4×2（≈350×170）用原型 .wr b 16px / .wr span 11px / .wtop 13px。 */
    val COMFORT = QuotaWidgetScale(
        padding = 12,
        headerSizeSp = 13,
        headerBadge = 16,
        headerBadgeGap = 6,
        headerGap = 8,
        valueSizeSp = 16,
        captionSizeSp = 11,
        captionGap = 1,
        barHeight = 4,
        barGap = 2,
        rowGap = 8,
        columnGap = 12,
    )

    /** 按高度选档：宽松档要 156dp 才能放下「顶栏 + 两行格子」（截图实测 149dp + 余量）。 */
    fun of(heightDp: Float): QuotaWidgetScale = if (heightDp >= COMFORT_MIN_HEIGHT_DP) COMFORT else COMPACT
}

/** 两列需要的宽度：一列 ~100dp（值 + 说明 + 横条），加 12dp 列距与 2×10dp 内边距。 */
private const val COMFORT_MIN_HEIGHT_DP = 156f

private const val TWO_COLUMN_MIN_WIDTH_DP = 240f

/**
 * 4 格（2 行 × 2 列）需要的最小高度。
 *
 * 250×110 是 4×2 的**声明最小值**（70×2−30），真机 Pixel 报 170dp；
 * 取实测的单格高度 × 2 + 行距 + 上下内边距，再加 2dp 余量（数字见交接文档的实测表）。
 */
private const val TWO_ROW_MIN_HEIGHT_DP = 104f

/** 2 格（1 行）的下限：单格「值 + 说明 + 横条」再矮就会裁掉横条。 */
private const val ONE_ROW_MIN_HEIGHT_DP = 62f

/** 顶栏要额外吃掉一行字号的高度，低于这个高度先砍顶栏、保住 4 格数据。 */
private const val HEADER_MIN_HEIGHT_DP = 128f

/**
 * 按占格决定网格档位（宽度定列数、高度定格数）。
 *
 * 两列的下限为什么取 240dp 而不是 [tierOf] 的 280dp：组件里一列只有「值 + 一行说明 + 横条」，
 * 两列各 ~100dp 就够；而 70×4−30 = 250dp 正是 4×2 的**声明最小宽度**，按 280 判会让 4×2
 * 在最小箱子下退成单列一格 —— 规格里写死「4×2 四格」就不成立了。
 *
 * 为什么 4×1 不会误判成 4 格：4×1 在真机上只有 ~72dp 高（见 WidgetLayoutPolicy 的
 * MIN_VIABLE_HEIGHT_DP 注释），够不到 [TWO_ROW_MIN_HEIGHT_DP]；同宽 4×2 声明最小 110dp、真机 170dp。
 */
fun quotaWidgetGrid(widthDp: Float, heightDp: Float): QuotaWidgetGrid {
    val tier = tierOf(widthDp, heightDp)
    if (tier == WidgetTier.TINY) return QuotaWidgetGrid(columns = 1, cells = 1, showHeader = false)
    val twoColumns = tier == WidgetTier.WIDE || widthDp >= TWO_COLUMN_MIN_WIDTH_DP
    val header = heightDp >= HEADER_MIN_HEIGHT_DP
    val twoRows = heightDp >= TWO_ROW_MIN_HEIGHT_DP
    if (!twoColumns) {
        // 2 格宽放不下两列（每列 ~70dp，说明会截成「opencode 5…」）：改成上下两格
        return if (twoRows) {
            QuotaWidgetGrid(columns = 1, cells = 2, showHeader = header)
        } else {
            QuotaWidgetGrid(columns = 1, cells = 1, showHeader = false)
        }
    }
    return when {
        twoRows -> QuotaWidgetGrid(columns = 2, cells = 4, showHeader = header)
        heightDp >= ONE_ROW_MIN_HEIGHT_DP -> QuotaWidgetGrid(columns = 2, cells = 2, showHeader = header)
        else -> QuotaWidgetGrid(columns = 1, cells = 1, showHeader = false)
    }
}

/**
 * 一格可用宽度（dp）：箱子宽 − 2×内边距 − (列数−1)×列距，再按列数均分。
 *
 * 说明行要不要带重置时刻按这个宽度判（见 [quotaCaptionText]），不是按箱子宽：
 * 250dp 的 4×2 分两列后每格只剩 109dp，这个数才是决定「放不放得下」的那个。
 */
fun quotaCellWidthDp(boxWidthDp: Float, columns: Int, scale: QuotaWidgetScale): Float {
    if (columns <= 0) return 0f
    val usable = boxWidthDp - 2 * scale.padding - (columns - 1) * scale.columnGap
    return (usable / columns).coerceAtLeast(0f)
}
