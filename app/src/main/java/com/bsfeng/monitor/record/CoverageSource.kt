package com.bsfeng.monitor.record

// Android 侧聚合：从 RecordStore 读 LOCATION 记录 → CoverageWindow。
// 分段算法全在 [CoverageTimeline]（纯逻辑、有单测），这里只负责「取数 + 缓存 + 失败兜底」。
//
// 口径与 TripViewModel 的 TripCache 一致：**进程内 LRU + 分钟粒度键**。
// 进程退出即丢弃，不落盘、不建表 —— 断档时间线是观测面，不是新的数据源。

import android.util.Log
import com.bsfeng.monitor.domain.model.RecordType
import java.util.LinkedHashMap

/**
 * 采集覆盖的一次查询结果（UI 直接消费）。
 *
 * [window] 可能为空窗口：[window.isEmpty] 为 true 表示这段时间一条 LOCATION 都没有
 * （UI 走空态，不画空条）。[mode] 是算分段时用的采样模式，展示侧不用，排查时要能核对。
 */
data class CoverageSnapshot(
    val window: CoverageWindow,
    val gaps: List<CoverageGap> = emptyList(),
    val mode: SampleMode = SampleMode.STILL,
    /** 读库失败的可读原因；非 null 时 [window] 一定为空窗口。 */
    val error: String? = null,
) {
    val isEmpty: Boolean get() = window.isEmpty
}

/**
 * 采集覆盖数据源。
 *
 * 三条边界：
 * 1. **不补点**：只用已落库的 LOCATION 时间戳，缺就是缺；
 * 2. **不猜原因**：断档原因来自 [CoverageFailureLog]（进程内），查不到就留空给 UI 写「未知」；
 * 3. **读库失败不抛**：落到 [CoverageSnapshot.error]，页面照常渲染（设置页不能因为一次读失败就白屏）。
 */
interface CoverageReader {

    /** 最近 24 小时的覆盖（设置页卡用这个窗口）。 */
    fun read(nowMs: Long = System.currentTimeMillis()): CoverageSnapshot

    /** 任意窗口的覆盖（行程页按「当天到此刻」取）。 */
    fun readWindow(startMs: Long, endMs: Long, mode: SampleMode? = null): CoverageSnapshot
}

/**
 * 生产实现：RecordStore + 进程内失败日志。
 *
 * @param store 记录库；只读 type = LOCATION 的采样时间戳
 * @param failures 失败日志（与采集器共享同一个实例，见 [CoverageFailureLog.shared]）
 * @param modeOf 当前采样模式；模式变了容忍阈值也变，要参与缓存键
 * @param statusOf 当前采集状态；每次读之前取一次，用于给**正在发生的**断档归因
 */
class StoreCoverageReader(
    private val store: RecordStore,
    private val failures: CoverageFailureLog = CoverageFailureLog.shared,
    private val modeOf: () -> SampleMode = { SampleMode.STILL },
    private val statusOf: () -> LocationStatus? = { null },
) : CoverageReader {

    override fun read(nowMs: Long): CoverageSnapshot =
        readWindow(nowMs - WINDOW_MS, nowMs)

    override fun readWindow(startMs: Long, endMs: Long, mode: SampleMode?): CoverageSnapshot {
        if (endMs <= startMs) {
            return CoverageSnapshot(window = CoverageWindow(startMs, endMs, emptyList()))
        }
        val resolved = mode ?: modeOf()
        return CoverageCache.obtain(startMs, endMs, resolved) {
            runCatching { compute(startMs, endMs, resolved) }
                .getOrElse { error ->
                    Log.w(TAG, "读取采集覆盖失败", error)
                    CoverageSnapshot(
                        window = CoverageWindow(startMs, endMs, emptyList()),
                        mode = resolved,
                        error = error.message ?: "读取定位记录失败（本地数据库暂时不可用）",
                    )
                }
        }
    }

    private fun compute(startMs: Long, endMs: Long, mode: SampleMode): CoverageSnapshot {
        // 失败日志先按窗口裁剪：窗口滚动后，窗口外的旧失败不该再被安到新断档上
        failures.pruneBefore(startMs)
        // 正在发生的断档：采集器此刻的失败原因是唯一能拿到的一手信息，读之前记一笔
        // note 而不是 record：每分钟读一次，同一原因重复观测只记一条（见 CoverageFailureLog.note）
        statusOf()?.let { failures.note(endMs, it.lastFailure) }
        val stamps = store.queryByType(RecordType.LOCATION, startMs, endMs).map { it.timestamp }
        val window = CoverageTimeline.build(stamps, startMs, endMs, mode)
        return CoverageSnapshot(
            window = window,
            gaps = window.gaps { segment -> failures.reasonFor(segment.startMs, segment.endMs) },
            mode = mode,
        )
    }

    companion object {
        private const val TAG = "CoverageSource"

        /** 设置页卡的窗口长度：最近 24 小时（与副标题「过去 24 小时」逐字对应）。 */
        const val WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * 进程内 LRU（最多 [MAX_ENTRIES] 个键，访问顺序淘汰）—— 与 TripCache 同口径。
 *
 * 键含左右两个边界各自的分钟数与采样模式：一分钟内多次重组（滚动 / 悬停 / 主题切换）
 * 只读一次库；跨分钟即失效，新落库的记录至多 1 分钟后出现在覆盖条上。
 */
internal object CoverageCache {

    private const val MAX_ENTRIES = 4

    private const val KEY_SEP = ":"

    private val entries = object : LinkedHashMap<String, CoverageSnapshot>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverageSnapshot>) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun obtain(startMs: Long, endMs: Long, mode: SampleMode, compute: () -> CoverageSnapshot): CoverageSnapshot {
        val key = (startMs / 60_000L).toString() + KEY_SEP +
            (endMs / 60_000L).toString() + KEY_SEP + mode.name
        entries[key]?.let { return it }
        val snapshot = compute()
        entries[key] = snapshot
        return snapshot
    }

    @Synchronized
    fun clear() = entries.clear()

    /**
     * 让缓存整体失效（**不**按分钟键细算）。
     *
     * 用途：失败日志新增一条后，当前分钟内的旧快照里「原因」已经不准了；
     * 直接清空比给每个键打补丁简单，代价只是下一次读库（一分钟最多几次）。
     */
    fun invalidate() = clear()
}
