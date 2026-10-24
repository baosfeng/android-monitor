package com.bsfeng.monitor.ui.trip

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.TripAggregator
import com.bsfeng.monitor.ui.live.startOfDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

data class TripState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val timeline: DayTimeline? = null,
    /** 当天定位采样（升序、已去重，最多 500 点）；轨迹卡按画布尺寸再投影。 */
    val trace: List<TraceSample> = emptyList(),
    val dayStart: Long = 0L,
    val dayEnd: Long = 0L,
)

/**
 * s16 每日行程的数据源：打开页面现算，不新建表、不落库。
 *
 * 口径：
 * - 读当天 `[startOfDay(now), now)` 的记录（与概览页同一时间口径），交给 [TripAggregator]；
 * - 结果进**进程内 LRU**（最多 4 天，按分钟切键）：今天的数据每分钟最多重算一次，
 *   历史日期当天内稳定命中；进程退出即丢弃，不产生任何持久化；
 * - 读库失败只落到 error 态，不抛到 UI。
 */
class TripViewModel(application: Application) : AndroidViewModel(application) {

    private val store = MonitorApp.instance.container.recordStore

    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state.asStateFlow()

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            _state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    private fun load(): TripState {
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)
        // 零点整点打开：窗口为 0，聚合器会拒绝非法区间；此时本来就没有行程
        if (now <= dayStart) {
            return TripState(isLoading = false, timeline = null, dayStart = dayStart, dayEnd = now)
        }
        return try {
            val day = TripCache.obtain(dayStart, now) {
                // 同一批 records 同时喂给聚合器与轨迹提取：不二次读库，轨迹与时间轴口径一致
                val records = store.queryByDate(dayStart, now)
                TripDay(
                    timeline = TripAggregator(dayStart, now).aggregate(records),
                    samples = TripTrace.samples(records),
                )
            }
            TripState(
                isLoading = false,
                timeline = day.timeline,
                trace = day.samples,
                dayStart = dayStart,
                dayEnd = now,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TripViewModel", "计算每日行程失败", e)
            TripState(
                isLoading = false,
                error = e.message ?: "行程计算失败（本地数据库暂时不可用）",
                dayStart = dayStart,
                dayEnd = now,
            )
        }
    }
}

/**
 * 进程内 LRU（最多 [MAX_DAYS] 个键，访问顺序淘汰）。
 *
 * 键含「分钟」：今天的时间轴每分钟随新记录变化；历史日期的键在同一天内固定，命中即秒开。
 */
private data class TripDay(val timeline: DayTimeline, val samples: List<TraceSample>)

private object TripCache {
    private const val MAX_DAYS = 4

    private val entries = object : LinkedHashMap<String, TripDay>(MAX_DAYS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TripDay>) =
            size > MAX_DAYS
    }

    @Synchronized
    fun obtain(dayStart: Long, dayEnd: Long, compute: () -> TripDay): TripDay {
        val key = dayStart.toString() + ":" + (dayEnd / 60_000L)
        entries[key]?.let { return it }
        val day = compute()
        entries[key] = day
        return day
    }

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * 清掉「今日行程」缓存，强制下次进入重算。
 *
 * 用途只有一处：debug 调试入口 `open trip`（`app/src/debug/`）在 `seed` 之后立刻打开行程页。
 * `TripCache` 的键含「分钟」，同一分钟内会命中旧结果，页面看不出刚造的数据。
 *
 * 为什么不能放在 debug 源集里：`TripCache` 是本文件的 private object，别的文件引用不到；
 * 而 debug 侧走反射调用在 debug 包的 R8 之后方法名已不可靠（实测 `cacheCleared=false`）。
 * release 里没有任何调用方，R8 会连同本函数一起裁掉，不影响生产包。
 */
internal fun clearTripCache() = TripCache.clear()
