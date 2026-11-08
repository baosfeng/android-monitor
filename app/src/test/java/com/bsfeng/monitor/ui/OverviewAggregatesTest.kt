package com.bsfeng.monitor.ui

import android.app.Application
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.record.RecordStore
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.HOUR_MS
import com.bsfeng.monitor.ui.live.recordTypeLabel
import com.bsfeng.monitor.ui.live.rollingHourIntensityOf
import com.bsfeng.monitor.ui.live.startOfDay
import com.bsfeng.monitor.ui.live.startOfDaysAgo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone
import java.util.UUID

/**
 * 概览 SQL 聚合与旧实现逐字段对拍（A13）。
 *
 * 旧实现每次刷新（5 秒）用 queryByDate 把「今日 / 滚动 24h / 昨日 / 近 7 天 / 近 30 天」
 * 全量拉回并反序列化 detail；新实现只整段取「今日」（行程/热力/最长事件/最近三条要用），
 * 其余窗口一律 SQL 聚合。本用例用同一批样本分别走两套实现，断言每个数字一致。
 *
 * 时区固定 Asia/Shanghai（无 DST），与线上默认一致；DST 的日界口径由
 * LiveModelsZoneTest 单独钉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverviewAggregatesTest {

    private lateinit var context: Application
    private lateinit var store: RecordStore
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val now: Long = java.time.ZonedDateTime.of(
        2026, 6, 15, 12, 0, 0, 0, java.time.ZoneId.of("Asia/Shanghai"),
    ).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        context = RuntimeEnvironment.getApplication()
        val helper = DatabaseHelper.getInstance(context)
        helper.writableDatabase.execSQL("DELETE FROM records")
        store = RecordStore(helper)
        seed()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    private fun startOf(daysAgo: Int): Long = startOfDaysAgo(daysAgo, now)

    private fun at(daysAgo: Int, hour: Int, minute: Int = 0): Long =
        startOf(daysAgo) + hour * HOUR_MS + minute * 60_000L

    private fun insert(timestamp: Long, type: RecordType, action: String, label: String = "", duration: Long? = null) {
        store.insert(
            Record(
                id = UUID.randomUUID().toString(),
                type = type,
                action = action,
                timestamp = timestamp,
                duration = duration,
                label = label,
            ),
        )
    }

    private fun seed() {
        // 今日：两条 APP_USAGE 都 < 1 分钟口径的分段（验证「逐条截断分钟」）
        insert(at(0, 10), RecordType.APP_USAGE, "USAGE", "微信", 90_000L)
        insert(at(0, 11), RecordType.APP_USAGE, "USAGE", "微信", 45_000L)
        insert(at(0, 9, 30), RecordType.SCREEN, "AD_SKIPPED", "抖音")
        insert(at(0, 9, 40), RecordType.SCREEN, "AD_SKIPPED", "抖音")
        insert(at(0, 9, 50), RecordType.SCREEN, "AD_SKIPPED", "")
        insert(at(0, 9), RecordType.WIFI, "CONNECTED", "WiFi")
        insert(now - 30 * 60_000L, RecordType.APP_USAGE, "USAGE", "支付宝", 30_000L)

        // 昨日：一条在滚动 24h 内、一条在 24h 外
        insert(at(1, 20), RecordType.SCREEN, "AD_SKIPPED", "淘宝")
        insert(at(1, 20, 30), RecordType.APP_USAGE, "USAGE", "微信", 120_000L)
        insert(at(1, 13), RecordType.APP_USAGE, "USAGE", "微信", 60_000L)
        insert(at(1, 19), RecordType.WIFI, "CONNECTED", "WiFi")

        // 近 7 天内（2..6 天前）：每天一条使用 + 一条跳过，覆盖 daily 7 桶
        for (daysAgo in 2..6) {
            insert(at(daysAgo, 8), RecordType.APP_USAGE, "USAGE", "微信", 61_000L)
            insert(at(daysAgo, 9), RecordType.SCREEN, "AD_SKIPPED", "抖音")
        }
        // 7..13 天前：上周窗口
        for (daysAgo in 7..13) {
            insert(at(daysAgo, 10), RecordType.WIFI, "CONNECTED", "WiFi")
        }
        insert(at(8, 9), RecordType.SCREEN, "AD_SKIPPED", "抖音")
        // 14..29 天前：本月窗口
        for (daysAgo in 14..29) {
            insert(at(daysAgo, 11), RecordType.WIFI, "CONNECTED", "WiFi")
        }
        // 30..59 天前：上月窗口
        for (daysAgo in 30..59) {
            insert(at(daysAgo, 12), RecordType.WIFI, "CONNECTED", "WiFi")
        }
        insert(at(40, 9), RecordType.SCREEN, "AD_SKIPPED", "抖音")
    }

    // ───────────── 旧实现口径（复制自 A13 之前的 OverviewViewModel） ─────────────

    private fun usageMinutes(records: List<Record>): Long =
        records.filter { it.type == RecordType.APP_USAGE }.sumOf { it.duration ?: 0L } / 60_000L

    private fun dailyBuckets(records: List<Record>, rangeStart: Long, days: Int): List<Float> {
        val buckets = FloatArray(days)
        records.forEach { record ->
            val index = ((record.timestamp - rangeStart) / DAY_MS).toInt()
            if (index in 0 until days) buckets[index] += 1f
        }
        return buckets.toList()
    }

    private fun dailyUsageMinutes(records: List<Record>, rangeStart: Long, days: Int): List<Float> {
        val buckets = FloatArray(days)
        records.filter { it.type == RecordType.APP_USAGE }.forEach { record ->
            val index = ((record.timestamp - rangeStart) / DAY_MS).toInt()
            if (index in 0 until days) buckets[index] += ((record.duration ?: 0L) / 60_000L).toFloat()
        }
        return buckets.toList()
    }

    private fun slices(records: List<Record>): List<TypeSlice> =
        records.groupingBy { it.type }.eachCount()
            .entries.sortedByDescending { it.value }.take(7)
            .map { TypeSlice(recordTypeLabel(it.key), it.value) }

    private fun topSkipped(records: List<Record>): List<TypeSlice> =
        records.filter { it.action == "AD_SKIPPED" }
            .groupingBy { it.label.ifBlank { "未知应用" } }.eachCount()
            .entries.sortedByDescending { it.value }.take(3)
            .map { TypeSlice(it.key, it.value) }

    @Test
    fun `SQL 聚合结果与旧实现逐字段一致`() {
        val todayStart = startOfDay(now)
        val weekStart = startOfDaysAgo(6, now)
        val monthStart = startOfDaysAgo(29, now)

        val oldToday = store.queryByDate(todayStart, now)
        val oldLast24h = store.queryByDate(now - DAY_MS, now)
        val oldYesterday = store.queryByDate(startOfDaysAgo(1, now), todayStart - 1)
        val oldWeek = store.queryByDate(weekStart, now)
        val oldMonth = store.queryByDate(monthStart, now)
        val oldWeekSkips = oldWeek.filter { it.action == "AD_SKIPPED" }

        val vm = OverviewViewModel(RuntimeEnvironment.getApplication())
        val state = runBlocking { vm.buildSnapshot(now) }

        assertFalse(state.isLoading)
        assertEquals(now, state.lastUpdatedAt)
        // 今日
        assertEquals(oldToday.size, state.todayEventCount)
        assertEquals(oldToday.count { it.action == "AD_SKIPPED" }, state.todaySkipCount)
        assertEquals(usageMinutes(oldToday), state.todayActiveMinutes)
        assertEquals(slices(oldToday), state.typeSlicesToday)
        assertEquals(oldToday.sortedByDescending { it.timestamp }.take(3).map { it.id }, state.recentEvents.map { it.id })
        assertEquals(oldToday.maxByOrNull { it.label.length }?.id, state.longestEvent?.id)
        // 昨日
        assertEquals(oldYesterday.size, state.yesterdayEventCount)
        assertEquals(oldYesterday.count { it.action == "AD_SKIPPED" }, state.yesterdaySkipCount)
        assertEquals(usageMinutes(oldYesterday), state.yesterdayActiveMinutes)
        // 滚动 24h
        assertEquals(rollingHourIntensityOf(oldLast24h, now), state.last24hIntensity)
        assertEquals(usageMinutes(oldLast24h), state.last24hActiveMinutes)
        // 近 7 天
        assertEquals(oldWeek.size, state.weekEventCount)
        assertEquals(oldWeekSkips.size, state.weekSkipCount)
        assertEquals(usageMinutes(oldWeek), state.weekActiveMinutes)
        assertEquals(dailyBuckets(oldWeek, weekStart, 7), state.dailyEventCounts7)
        assertEquals(dailyBuckets(oldWeekSkips, weekStart, 7), state.dailySkipCounts7)
        assertEquals(dailyUsageMinutes(oldWeek, weekStart, 7), state.dailyActiveMinutes7)
        assertEquals(topSkipped(oldWeek), state.topSkippedApps)
        // 近 30 天
        assertEquals(oldMonth.size, state.monthEventCount)
        assertEquals(oldMonth.count { it.action == "AD_SKIPPED" }, state.monthSkipCount)
        assertEquals(usageMinutes(oldMonth), state.monthActiveMinutes)
        assertEquals(dailyUsageMinutes(oldMonth, monthStart, 30), state.dailyActiveMinutes30)
        assertEquals(slices(oldMonth), state.typeSlices30)
        // 上周 / 上月（紧邻窗口）
        val weekPrevStart = weekStart - 7 * DAY_MS
        assertEquals(store.countByDate(weekPrevStart, weekStart - 1), state.prevWeekEventCount)
        assertEquals(
            store.countByTypeAndAction(RecordType.SCREEN, "AD_SKIPPED", weekPrevStart, weekStart - 1),
            state.prevWeekSkipCount,
        )
        val monthPrevStart = monthStart - 30 * DAY_MS
        assertEquals(store.countByDate(monthPrevStart, monthStart - 1), state.prevMonthEventCount)
        assertEquals(
            store.countByTypeAndAction(RecordType.SCREEN, "AD_SKIPPED", monthPrevStart, monthStart - 1),
            state.prevMonthSkipCount,
        )
    }
}
