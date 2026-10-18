package com.bsfeng.monitor.domain.transfer

import java.util.Locale

/**
 * 传输相关的展示文案（纯 Kotlin，可纯 JVM 单测）。
 *
 * 统一口径：
 * - 体积按 1024 进制、保留 1 位小数（`1.2 MB`），不足 1 KB 用整数 `B`；
 * - 「已传 / 总量」两个数字共用**总量**的单位，避免 `980 KB / 1.0 MB` 这类跳单位；
 * - 速度 ≤ 0 返回空串，由调用方决定是否省略这一段；
 * - 免配对窗口剩余时间用 `mm:ss`（最长 30 分钟，不出现小时位）。
 */
object TransferFormat {

    private val UNITS = arrayOf("KB", "MB", "GB", "TB")

    /** 体积文案：`0 B` / `512 B` / `1.2 MB` / `4.1 MB`。 */
    fun fileSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        if (safe < 1024L) return "$safe B"
        val (value, unit) = scale(safe)
        return String.format(Locale.US, "%.1f %s", value, unit)
    }

    /** 进度文案：`1.2 / 4.1 MB`（两数共用总量单位）；总量为 0 时 `0 B`。 */
    fun progressPair(received: Long, total: Long): String {
        val safeTotal = total.coerceAtLeast(0L)
        val safeReceived = received.coerceAtLeast(0L).coerceAtMost(safeTotal)
        if (safeTotal < 1024L) return "$safeReceived / $safeTotal B"
        val (totalValue, unit) = scale(safeTotal)
        val divisor = divisorOf(unit)
        val receivedValue = safeReceived.toDouble() / divisor
        return String.format(Locale.US, "%.1f / %.1f %s", receivedValue, totalValue, unit)
    }

    /** 速度文案：`8.4 MB/s`；≤ 0 返回空串。 */
    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return ""
        return fileSize(bytesPerSecond) + "/s"
    }

    /** 免配对窗口剩余时间：`08:32`；已过期/未开启返回 `00:00`。 */
    fun windowRemaining(remainingMs: Long): String {
        val seconds = (remainingMs.coerceAtLeast(0L) / 1000L)
        val minutes = seconds / 60L
        val rest = seconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, rest)
    }

    /** 阈值文案：`0` → `关闭`，其余 `10 MB`。 */
    fun threshold(mb: Int): String = if (mb <= 0) "关闭" else "$mb MB"

    private fun scale(bytes: Long): Pair<Double, String> {
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024.0 && unit < UNITS.lastIndex) {
            value /= 1024.0
            unit++
        }
        return value to UNITS[unit.coerceAtLeast(0)]
    }

    private fun divisorOf(unit: String): Double {
        var divisor = 1.0
        for (index in 0..UNITS.indexOf(unit)) divisor *= 1024.0
        return divisor
    }
}
