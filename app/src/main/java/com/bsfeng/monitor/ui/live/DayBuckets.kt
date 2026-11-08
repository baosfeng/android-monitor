package com.bsfeng.monitor.ui.live

/**
 * 近 N 天日桶切分：对一组时间戳（ms）按 rangeStart 起算 dayIndex 计数，
 * 索引越界丢弃。Overview / Ad / 跳过命中三处原样可能各自写一份，收口到此。
 */
fun bucketizeDaily(timestamps: Iterable<Long>, rangeStart: Long, dayCount: Int): List<Float> {
    val buckets = FloatArray(dayCount.coerceAtLeast(0))
    if (dayCount <= 0) return buckets.toList()
    timestamps.forEach { ts ->
        val idx = ((ts - rangeStart) / DAY_MS).toInt()
        if (idx in 0 until dayCount) buckets[idx] += 1f
    }
    return buckets.toList()
}
