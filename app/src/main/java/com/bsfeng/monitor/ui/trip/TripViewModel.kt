package com.bsfeng.monitor.ui.trip

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.TripAggregator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.LinkedHashMap

/**
 * s16 错态文案（异常兜底）。
 *
 * 统一口径：**异常原文只进日志、不上屏** —— 上屏用本常量，e.message（SQLite 错误码 /
 * 表名 / 路径）只进 Log.w。与 [com.bsfeng.monitor.ui.AD_LOAD_ERROR_DESC] 同一规则。
 *
 * 措辞逐字等于 TripScreen 的 DEFAULT_ERROR_TEXT：VM 已给文案时页面原样展示，
 * 只有 error 为 null（不该发生）才回落那句；两处必须同字，否则同一次失败会有两种说法。
 */
internal const val TRIP_LOAD_ERROR_DESC = "本地数据库暂时不可用（SQLITE_BUSY）。稍后重试即可，已采集的事件不会丢失。"

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
 * - 读所选日期当天的记录（今天截到此刻，历史日期取整日），交给 [TripAggregator] 与 [TripTrace]；
 * - 结果进**进程内 LRU**（最多 4 天，按分钟切键）：今天的数据每分钟最多重算一次，
 *   历史日期当天内稳定命中；进程退出即丢弃，不产生任何持久化；
 * - 读库失败只落到 error 态，不抛到 UI。
 */
class TripViewModel(application: Application) : AndroidViewModel(application) {

    private val store = MonitorApp.instance.container.recordStore

    private val _state = MutableStateFlow(TripState())
    val state: StateFlow<TripState> = _state.asStateFlow()

    private var loaded = false

    /** 当前选中的一天（当地 00:00 的毫秒）。 */
    private var selectedDay: Long = 0L

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        loadDay(TripDays.today(System.currentTimeMillis()))
    }

    /** 切到指定的一天；超出「最近 30 天 ~ 今天」范围时按边界夹住。 */
    fun selectDay(dayStart: Long) {
        loadDay(TripDays.clamp(dayStart, System.currentTimeMillis()))
    }

    /** 重试当前这一天（错误态按钮用）。 */
    fun refresh() {
        val day = selectedDay.takeIf { it > 0L } ?: TripDays.today(System.currentTimeMillis())
        loadDay(day)
    }

    private fun loadDay(dayStart: Long) {
        selectedDay = dayStart
        viewModelScope.launch {
            // 换天先清空旧数据：否则新日期标题下会短暂留着上一天的行程与轨迹
            _state.value = TripState(isLoading = true, dayStart = dayStart, dayEnd = TripDays.dayEnd(dayStart, System.currentTimeMillis()))
            _state.value = withContext(Dispatchers.IO) { load(dayStart) }
        }
    }

    private fun load(dayStart: Long): TripState {
        val now = System.currentTimeMillis()
        // 历史日期的窗口是 [当天 00:00, 次日 00:00)；今天截到「此刻」
        val dayEnd = TripDays.dayEnd(dayStart, now)
        // 零点整点打开：窗口为 0，聚合器会拒绝非法区间；此时本来就没有行程
        if (dayEnd <= dayStart) {
            return TripState(isLoading = false, timeline = null, dayStart = dayStart, dayEnd = dayEnd)
        }
        return try {
            val contextStart = dayStart - BOUNDARY_CONTEXT_MS
            val contextEnd = dayEnd + BOUNDARY_CONTEXT_MS
            val day = TripCache.obtain(dayStart, dayEnd, contextStart, contextEnd) {
                // 一次读库：窗口内的记录喂聚合器与轨迹提取（口径一致），
                // 窗口外前后各 2 小时只作跨天边界上下文，不产生独立段
                val (inWindow, context) = splitByWindow(store.queryByDate(contextStart, contextEnd), dayStart, dayEnd)
                TripDay(
                    timeline = TripAggregator(dayStart, dayEnd).aggregate(inWindow, context),
                    samples = TripTrace.samples(inWindow),
                )
            }
            TripState(
                isLoading = false,
                timeline = day.timeline,
                trace = day.samples,
                dayStart = dayStart,
                dayEnd = dayEnd,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 异常原文只进日志，上屏用常量（口径见 [TRIP_LOAD_ERROR_DESC]）
            Log.w("TripViewModel", "计算每日行程失败", e)
            TripState(
                isLoading = false,
                error = TRIP_LOAD_ERROR_DESC,
                dayStart = dayStart,
                dayEnd = dayEnd,
            )
        }
    }
}

/**
 * 可切换的日期范围：最近 [WINDOW_DAYS] 天（含今天）到今天。
 *
 * 纯逻辑（不碰 Android / 数据库），按本地时区的「日」做加减：不按固定 86400000 毫秒推算，
 * 避免跨夏令时地区出现「切一天少一小时」。
 */
internal object TripDays {

    /** 可回看的天数（含今天）：30 天窗口。 */
    const val WINDOW_DAYS = 30

    fun today(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long = startOfDayOf(now, zone)

    /** 窗口最早一天（今天往前 29 天）。 */
    fun firstSelectable(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        shift(today(now, zone), -(WINDOW_DAYS - 1).toLong(), zone)

    fun previous(day: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        clamp(shift(day, -1, zone), now, zone)

    fun next(day: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        clamp(shift(day, 1, zone), now, zone)

    fun canPrevious(day: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        day > firstSelectable(now, zone)

    fun canNext(day: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        day < today(now, zone)

    /** 越界回退：早于窗口夹到最早一天，晚于今天夹到今天。 */
    fun clamp(day: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        day.coerceIn(firstSelectable(now, zone), today(now, zone))

    /** 一天窗口的右边界：今天是「此刻」，历史日期是次日 00:00。 */
    fun dayEnd(dayStart: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        if (dayStart >= today(now, zone)) now else shift(dayStart, 1, zone)

    private fun shift(dayStart: Long, days: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate().plusDays(days)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    private fun startOfDayOf(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * 跨天边界上下文窗口：前后各多查 2 小时，只用于判定日首 / 日尾归属。
 * 必须与 domain 的 TripAggregatorRules.BOUNDARY_CONTEXT_MS 同值（跨模块 internal 不可见，无法直接引用）。
 */
private const val BOUNDARY_CONTEXT_MS = 2 * 60 * 60_000L

/**
 * 把「窗口 + 边界上下文」的查询结果切成（窗口内, 窗口外）两份：窗口内按 [dayStart, dayEnd) 半开区间。
 * 窗口外的只作为跨天边界上下文交给聚合器，不参与轨迹与统计。
 */
internal fun splitByWindow(
    records: List<Record>,
    dayStart: Long,
    dayEnd: Long,
): Pair<List<Record>, List<Record>> =
    records.partition { it.timestamp >= dayStart && it.timestamp < dayEnd }

private data class TripDay(val timeline: DayTimeline, val samples: List<TraceSample>)

/**
 * 进程内 LRU（最多 [MAX_DAYS] 个键，访问顺序淘汰）。
 *
 * 键含「分钟」与边界上下文窗口：今天的时间轴每分钟随新记录变化；历史日期的键在同一天内固定，命中即秒开。
 */
private object TripCache {
    // 历史日期可翻 30 天：缓存 8 天，来回翻几天仍能秒开，又不至于把内存堆满
    private const val MAX_DAYS = 8

    private val entries = object : LinkedHashMap<String, TripDay>(MAX_DAYS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TripDay>) =
            size > MAX_DAYS
    }

    @Synchronized
    fun obtain(
        dayStart: Long,
        dayEnd: Long,
        contextStart: Long,
        contextEnd: Long,
        compute: () -> TripDay,
    ): TripDay {
        // 键含边界上下文窗口：今天 dayEnd 与窗口每分钟都在变；历史日两者都固定，命中即秒开
        val key = dayStart.toString() + ":" + (dayEnd / 60_000L) +
            ":" + (contextStart / 60_000L) + ":" + (contextEnd / 60_000L)
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
