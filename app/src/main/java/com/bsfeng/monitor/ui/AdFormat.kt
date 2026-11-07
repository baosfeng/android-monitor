package com.bsfeng.monitor.ui

// 从 ui/AdScreen.kt 拆出：广告跳过页的文案格式化 —— 跳过方式说明（含耗时）、节省时长、近 7 天峰值说明。
// 负责「这条记录怎么读成人话」，不含任何 UI。

import com.bsfeng.monitor.ui.live.PLACEHOLDER
import java.util.Locale

internal fun methodSubtitle(method: SkipMethod, costMs: Long?): String {
    val head = when (method) {
        SkipMethod.COORD -> "坐标点击兜底"
        SkipMethod.A11Y -> "无障碍事件兜底"
        else -> "规则匹配"
    }
    return head + " · " + (costMs?.let { it.toString() + " ms" } ?: PLACEHOLDER)
}

internal fun formatSaved(ms: Long): String {
    val totalSec = ms / 1000
    return (totalSec / 60).toString() + "m" + String.format(Locale.US, "%02d", totalSec % 60) + "s"
}

/**
 * 趋势卡尾注：把「已确认 / 均耗 / 节省」三项回顾性数据拼成一行。
 *
 * 任一项无数据就不出现该项；三项都无数据时返回空串，调用方整行不渲染 ——
 * 不允许出现「均耗 —」这类占位（真机上空格子只占宽、不承载信息）。
 */
internal fun reviewExtras(confirmedCount: Int, avgCost: Long?, savedTotal: Long?): String {
    val parts = buildList {
        if (confirmedCount > 0) add("已确认 " + confirmedCount + " 次")
        if (avgCost != null) add("均耗 " + avgCost + " ms")
        if (savedTotal != null) add("共省 " + formatSaved(savedTotal.coerceAtLeast(0L)))
    }
    return parts.joinToString(" · ")
}

internal fun peakNote(values: List<Float>): String {
    var idx = 0
    var max = 0f
    values.forEachIndexed { i, v ->
        if (v > max) {
            max = v
            idx = i
        }
    }
    if (max <= 0f) return "近 7 天暂无跳过记录"
    return "峰值 " + (idx + 1) + " 日 · " + max.toInt() + " 次"
}
