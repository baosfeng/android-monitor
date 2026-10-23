package com.bsfeng.monitor.debug

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import java.util.Locale
import java.util.UUID

/**
 * 模拟数据工厂（纯逻辑，可纯 JVM 单测；只在 debug 变体编译）。
 *
 * 目的：让「今日行程」页在模拟器上**立刻有内容可看**，不用真跑一天。
 *
 * 口径必须与 `TripAggregator` 对齐，否则造出来的数据在页面上是空白：
 * - 停留段 = 相邻定位点间隔 ≤ 60 分钟、同段内偏移 ≤ 80m、段长 ≥ 10 分钟；
 * - 移动段 = 两段停留之间净位移 > 300m 且采样覆盖 ≥ 60%；
 * - 时间落在「今天」本地时区窗口内（`dayStart` 由调用方按系统时区取 00:00）。
 *
 * 数据全部带 `source=debug_seed`，方便和其它来源区分（`clear`/`dump` 也据此核对）。
 */
object DebugSeed {

    /** 供 CLI 与文档复用的来源标记。 */
    const val SOURCE = "debug_seed"

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 86_400_000L

    /** 窗口至少给半小时：刚过零点就 seed 时，停留段会短于 10 分钟（聚合器会判成「未记录」）。 */
    private const val MIN_WINDOW_MS = 30 * MINUTE_MS

    /** 采样步长：停留段 10 分钟、移动段 5 分钟（都远小于 60 分钟的空档阈值）。 */
    private const val STAY_STEP_MS = 10 * MINUTE_MS
    private const val MOVE_STEP_MS = 5 * MINUTE_MS

    /** 端点短停：正好等于聚合器的最短停留（10 分钟），只用它切分移动段，不算「长期停留」。 */
    private const val STOP_MS = STAY_STEP_MS

    /**
     * 家 ↔ 公司直线距离 ≈ 10.8km，按 550m 一步 → 20 步。
     *
     * 为什么不是刚好 300m：落库坐标量化到 3 位小数（≈111m），量化误差最坏能把一步削掉 150m 左右，
     * 400m 一步会掉到 280m（实测低于移动判据）。550m 一步量化后仍稳定 > 300m。
     */
    private const val LEG_METERS = 10_800.0
    private const val MOVE_STEP_METERS = 550.0
    private val STEPS_PER_LEG: Int = Math.ceil(LEG_METERS / MOVE_STEP_METERS).toInt()

    private val HOME = Spot(39.9087, 116.3975, "家")
    private val OFFICE = Spot(39.9847, 116.3180, "公司")

    private class Spot(val lat: Double, val lng: Double, val label: String, val address: String = label)

    /**
     * 生成一套记录（时间升序）。
     *
     * @param dayStart 当天本地 00:00
     * @param now 当前时刻；末点压到 `now - 1 分钟`，因为 `TripAggregator` 的窗口右端就是 now（闭开区间）
     */
    fun build(scenario: SeedScenario, dayStart: Long, now: Long): List<Record> {
        val end = maxOf(dayStart + MIN_WINDOW_MS, now - MINUTE_MS).coerceAtMost(dayStart + DAY_MS - MINUTE_MS)
        val start = dayStart
        return when (scenario) {
            SeedScenario.DAY -> dayScenario(start, end)
            SeedScenario.STAY -> stayScenario(start, end)
            SeedScenario.MOVE -> moveScenario(start, end)
            SeedScenario.GAP -> gapScenario(start, end)
        }.sortedBy { it.timestamp }
    }

    /** 上午在家 → 中午移动 → 下午在公司 → 晚上回家。 */
    private fun dayScenario(start: Long, end: Long): List<Record> {
        val span = end - start
        val moveStart = start + span * 30 / 100
        val officeStart = start + span * 50 / 100
        val officeEnd = start + span * 78 / 100
        val home2Start = start + span * 88 / 100

        val out = ArrayList<Record>()
        out += stayPoints(start, moveStart, HOME)
        out += movePoints(moveStart, officeStart, HOME, OFFICE)
        out += stayPoints(officeStart, officeEnd, OFFICE)
        out += movePoints(officeEnd, home2Start, OFFICE, HOME)
        out += stayPoints(home2Start, end, HOME)

        // 三个停留段各配一段使用记录 + 页面轨迹（聚合器按停留区间挑 topApps / pages）
        out += usage(start + span * 10 / 100, "微信", "com.tencent.mm", 25 * MINUTE_MS)
        out += pages(start + span * 12 / 100, "微信", "com.tencent.mm", listOf("Chatting", "Moments"))
        out += usage(officeStart + span * 8 / 100, "飞书", "com.ss.android.lark", 40 * MINUTE_MS)
        out += pages(officeStart + span * 10 / 100, "飞书", "com.ss.android.lark", listOf("Doc", "Calendar"))
        out += usage(home2Start + span * 3 / 100, "哔哩哔哩", "tv.danmaku.bili", 35 * MINUTE_MS)
        out += pages(home2Start + span * 5 / 100, "哔哩哔哩", "tv.danmaku.bili", listOf("Home", "Player"))
        // 亮屏记录：一对 UNLOCKED/OFF，让概览的亮屏时长不为 0
        out += screenOn(start + span * 9 / 100, 12 * MINUTE_MS)
        out += screenOn(officeStart + span * 7 / 100, 8 * MINUTE_MS)
        return out
    }

    /** 全天一个地点：单个停留段，没有移动。 */
    private fun stayScenario(start: Long, end: Long): List<Record> {
        val span = end - start
        val out = ArrayList<Record>()
        out += stayPoints(start, end, HOME)
        out += usage(start + span * 20 / 100, "微信", "com.tencent.mm", 30 * MINUTE_MS)
        out += pages(start + span * 22 / 100, "微信", "com.tencent.mm", listOf("Chatting"))
        out += screenOn(start + span * 18 / 100, 10 * MINUTE_MS)
        return out
    }

    /**
     * 全天在路上：家 ↔ 公司来回跑，每步 5 分钟走 550m，端点只做 10 分钟短停。
     *
     * 判据对齐 `TripAggregator`：单步位移 550m（量化后仍 > 300m，不会并进停留段）、端点短停 10 分钟 ≥
     * STAY_MIN_MS（聚合器需要「停留」来切分移动段，否则整段会退化成一整块「未记录」）。
     * 预期聚合结果：**0 处长期停留**（每段停留固定 10 分钟）+ **≥2 段移动** + 首尾少量未记录。
     */
    private fun moveScenario(start: Long, end: Long): List<Record> {
        val out = ArrayList<Record>()
        var ts = start
        var from = HOME
        var to = OFFICE
        out += location(ts, from)
        while (ts + STOP_MS < end) {
            ts += STOP_MS
            if (ts >= end) break
            // 与上一采样同坐标 + 相隔 10 分钟 → 恰好一段最短停留
            out += location(ts, from)
            var arrived = false
            for (i in 1..STEPS_PER_LEG) {
                ts += MOVE_STEP_MS
                if (ts >= end) {
                    arrived = true
                    break
                }
                val ratio = i.toDouble() / STEPS_PER_LEG
                out += location(
                    ts,
                    Spot(
                        lat = from.lat + (to.lat - from.lat) * ratio,
                        lng = from.lng + (to.lng - from.lng) * ratio,
                        label = "路上",
                        address = "移动中",
                    ),
                )
            }
            if (arrived) break
            val swap = from
            from = to
            to = swap
        }
        // 路上一直在用导航：让移动段也有 App 使用记录可看
        var usageTs = start + 20 * MINUTE_MS
        while (usageTs < end) {
            out += usage(usageTs, "高德地图", "com.autonavi.minimap", 15 * MINUTE_MS)
            usageTs += 45 * MINUTE_MS
        }
        return out
    }

    /** 两段停留之间留 >1 小时空档：验证「采样空档」不被算成移动。 */
    private fun gapScenario(start: Long, end: Long): List<Record> {
        val span = end - start
        val homeEnd = start + span * 30 / 100
        val gapEnd = start + span * 70 / 100
        val out = ArrayList<Record>()
        out += stayPoints(start, homeEnd, HOME)
        out += stayPoints(gapEnd, end, OFFICE)
        out += usage(start + span * 10 / 100, "微信", "com.tencent.mm", 20 * MINUTE_MS)
        out += usage(gapEnd + span * 5 / 100, "飞书", "com.ss.android.lark", 30 * MINUTE_MS)
        return out
    }

    // ───────────────────────────── 点位 ─────────────────────────────

    /**
     * 停留段：步长 10 分钟，**坐标不带抖动**。
     *
     * 落库坐标量化到 3 位小数（≈111m），抖动哪怕只有 40m 也会在量化后跨格，
     * 让 `TripAggregator` 判成「离开」而把一段停留拆成好几段。演示数据要的是稳定可复现。
     */
    private fun stayPoints(start: Long, end: Long, spot: Spot): List<Record> {
        if (end <= start) return emptyList()
        val out = ArrayList<Record>()
        var ts = start
        while (ts < end) {
            out += location(ts, spot)
            ts += STAY_STEP_MS
        }
        out += location(end, spot)
        return out
    }

    /** 移动段：步长 5 分钟，沿两点线性插值（净位移 ≈ 8km，覆盖 100%）。 */
    private fun movePoints(start: Long, end: Long, from: Spot, to: Spot): List<Record> {
        if (end <= start) return emptyList()
        val out = ArrayList<Record>()
        var ts = start
        while (ts < end) {
            val t = (ts - start).toDouble() / (end - start).toDouble()
            val lat = from.lat + (to.lat - from.lat) * t
            val lng = from.lng + (to.lng - from.lng) * t
            out += location(ts, Spot(lat, lng, "路上", "移动中"))
            ts += MOVE_STEP_MS
        }
        out += location(end, to)
        return out
    }

    private fun location(ts: Long, spot: Spot): Record =
        Record(
            id = UUID.randomUUID().toString(),
            type = RecordType.LOCATION,
            action = "COORDINATE",
            timestamp = ts,
            label = spot.label,
            detail = mapOf(
                "lat" to quantize(spot.lat),
                "lng" to quantize(spot.lng),
                "provider" to "gps",
                "accuracy_m" to "25",
                "address" to spot.address,
                "source" to SOURCE,
            ),
        )

    private fun usage(ts: Long, appName: String, pkg: String, durationMs: Long): Record = Record(
        id = UUID.randomUUID().toString(),
        type = RecordType.APP_USAGE,
        action = "USAGE",
        timestamp = ts,
        duration = durationMs,
        label = appName,
        detail = mapOf("package_name" to pkg, "app_name" to appName, "source" to SOURCE),
    )

    private fun pages(ts: Long, appName: String, pkg: String, keys: List<String>): List<Record> =
        keys.mapIndexed { index, key ->
            Record(
                id = UUID.randomUUID().toString(),
                type = RecordType.PAGE,
                action = if (index == 0) "ENTER" else "CHANGED",
                timestamp = ts + index * MINUTE_MS,
                duration = 45_000L,
                label = "$appName › $key",
                detail = mapOf(
                    "package_name" to pkg,
                    "app_name" to appName,
                    "page_key" to key,
                    "source" to SOURCE,
                    "duration_ms" to "45000",
                ),
            )
        }

    private fun screenOn(ts: Long, durationMs: Long): List<Record> = listOf(
        Record(UUID.randomUUID().toString(), RecordType.SCREEN, "UNLOCKED", ts, null, "亮屏", mapOf("source" to SOURCE)),
        Record(UUID.randomUUID().toString(), RecordType.SCREEN, "OFF", ts + durationMs, durationMs, "熄屏", mapOf("source" to SOURCE)),
    )

    /** 与 `LocationPolicy.quantize` 同口径：3 位小数（≈110m），-0.000 归一成 0.000。 */
    internal fun quantize(value: Double): String {
        val factor = 1_000.0
        val rounded = Math.round(value * factor) / factor
        return if (rounded == 0.0) "0.000" else String.format(Locale.US, "%.3f", rounded)
    }
}
