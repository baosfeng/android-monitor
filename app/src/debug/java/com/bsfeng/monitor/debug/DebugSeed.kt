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

    /** 既有场景的坐标位数：3 位小数（≈110m），等价于老数据 —— 演示「历史粗坐标」时正好。 */
    const val COARSE_DECIMALS = 3

    /** 带噪声场景的坐标位数：6 位（与 `LocationPolicy.quantize` 同口径），±30m 抖动才留得住。 */
    const val FINE_DECIMALS = 6

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
            SeedScenario.NOISE -> noiseScenario(start, end)
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

        // 三个停留段各配一段使用记录（聚合器按停留区间挑 topApps）
        out += usage(start + span * 10 / 100, "微信", "com.tencent.mm", 25 * MINUTE_MS)
        out += usage(officeStart + span * 8 / 100, "飞书", "com.ss.android.lark", 40 * MINUTE_MS)
        out += usage(home2Start + span * 3 / 100, "哔哩哔哩", "tv.danmaku.bili", 35 * MINUTE_MS)
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

    // ───────────────────── 带噪声移动（精度表达的对照件）─────────────────────

    /** 步行两端：直线 ≈1.8km，落在与家/公司同一座城市里。 */
    private val WALK_FROM = Spot(39.9500, 116.3500, "家")
    private val WALK_TO = Spot(39.9630, 116.3680, "公司")

    /** 步行采样步长：2 分钟一步、约 120m，远小于聚合器 60 分钟的空档阈值。 */
    private const val NOISE_WALK_STEP_MS = 2 * MINUTE_MS

    /** 步行段时长与端点停留：各 10 分钟以上，聚合器才能切出「停留 → 移动 → 停留」。 */
    private const val NOISE_WALK_MS = 30 * MINUTE_MS
    private const val NOISE_INDOOR_MS = 20 * MINUTE_MS
    private const val NOISE_INDOOR_STEP_MS = 5 * MINUTE_MS

    /** 步行抖动（米）：GPS 在开阔地也有的量级。 */
    private const val WALK_JITTER_METERS = 30.0

    /** 室内抖动（米）：network 点位在同一个格子里来回跳，但精度本身很差。 */
    private const val INDOOR_JITTER_METERS = 20.0

    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * 带噪声移动：停在起点 10 分钟 → 沿直线走到终点（±30m 抖动、accuracy 15~60m）
     * → 室内 20 分钟（network，accuracy 250~450m）。
     *
     * 为什么两条腿都要造：步行段 accuracy 全是高置信，轨迹卡不会出现虚线段；
     * 室内那段 accuracy 差才是「虚线 = 精度 > 100m」的证据。坐标按 6 位小数落库
     * （与 `LocationPolicy.quantize` 同口径），抖动才不会被 3 位小数网格吃掉。
     */
    private fun noiseScenario(start: Long, end: Long): List<Record> {
        val out = ArrayList<Record>()
        val jitter = Jitter(seed = start)
        val walkStart = start + STAY_STEP_MS
        out += stayPoints(start, walkStart, WALK_FROM, decimals = FINE_DECIMALS)

        var ts = walkStart
        val walkEnd = minOf(walkStart + NOISE_WALK_MS, end)
        while (ts < walkEnd) {
            val progress = (ts - walkStart).toDouble() / NOISE_WALK_MS
            out += noisyLocation(
                ts = ts,
                base = interpolate(WALK_FROM, WALK_TO, progress),
                jitterMeters = WALK_JITTER_METERS,
                accuracyMeters = 15 + jitter.unit01() * 45,
                provider = "gps",
                jitter = jitter,
            )
            ts += NOISE_WALK_STEP_MS
        }

        // 室内：坐标只在小范围里跳（聚合器仍判成一段停留），精度却差一个数量级
        val indoorEnd = minOf(ts + NOISE_INDOOR_MS, end)
        while (ts < indoorEnd) {
            out += noisyLocation(
                ts = ts,
                base = WALK_TO,
                jitterMeters = INDOOR_JITTER_METERS,
                accuracyMeters = 250 + jitter.unit01() * 200,
                provider = "network",
                jitter = jitter,
            )
            ts += NOISE_INDOOR_STEP_MS
        }
        // 室内也在用手机：让停留段有 App 使用记录可看
        out += usage(walkStart + NOISE_WALK_MS / 2, "高德地图", "com.autonavi.minimap", 15 * MINUTE_MS)
        out += usage(indoorEnd - 5 * MINUTE_MS, "微信", "com.tencent.mm", 4 * MINUTE_MS)
        return out
    }

    private fun interpolate(from: Spot, to: Spot, ratio: Double): Spot = Spot(
        lat = from.lat + (to.lat - from.lat) * ratio,
        lng = from.lng + (to.lng - from.lng) * ratio,
        label = "路上",
        address = "移动中",
    )

    /** 在 [base] 周围加 ±[jitterMeters] 的抖动；纬度与经度各自换算，经度按 cos 纬度压缩。 */
    private fun noisyLocation(
        ts: Long,
        base: Spot,
        jitterMeters: Double,
        accuracyMeters: Double,
        provider: String,
        jitter: Jitter,
    ): Record {
        val lat = base.lat + jitter.unit() * jitterMeters / METERS_PER_DEGREE
        val lngScale = METERS_PER_DEGREE * Math.cos(Math.toRadians(base.lat))
        val lng = base.lng + jitter.unit() * jitterMeters / lngScale
        return location(
            ts = ts,
            spot = Spot(lat, lng, base.label, base.address),
            accuracy = accuracyMeters.toInt().toString(),
            provider = provider,
            decimals = FINE_DECIMALS,
        )
    }

    /** 固定种子：同一时刻 seed 出来的抖动必须一模一样，测试与截图才可复现。 */
    private class Jitter(seed: Long) {
        private var state = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L

        /** [-1, 1) 的均匀分布。 */
        fun unit(): Double = unit01() * 2.0 - 1.0

        /** [0, 1) 的均匀分布。 */
        fun unit01(): Double {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return (state ushr 11).toDouble() / (1L shl 53).toDouble()
        }
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
    private fun stayPoints(start: Long, end: Long, spot: Spot, decimals: Int = COARSE_DECIMALS): List<Record> {
        if (end <= start) return emptyList()
        val out = ArrayList<Record>()
        var ts = start
        while (ts < end) {
            out += location(ts, spot, decimals = decimals)
            ts += STAY_STEP_MS
        }
        out += location(end, spot, decimals = decimals)
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

    private fun location(
        ts: Long,
        spot: Spot,
        accuracy: String = "25",
        provider: String = "gps",
        decimals: Int = COARSE_DECIMALS,
    ): Record =
        Record(
            id = UUID.randomUUID().toString(),
            type = RecordType.LOCATION,
            action = "COORDINATE",
            timestamp = ts,
            label = spot.label,
            detail = mapOf(
                "lat" to quantize(spot.lat, decimals),
                "lng" to quantize(spot.lng, decimals),
                "provider" to provider,
                "accuracy_m" to accuracy,
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

    private fun screenOn(ts: Long, durationMs: Long): List<Record> = listOf(
        Record(UUID.randomUUID().toString(), RecordType.SCREEN, "UNLOCKED", ts, null, "亮屏", mapOf("source" to SOURCE)),
        Record(UUID.randomUUID().toString(), RecordType.SCREEN, "OFF", ts + durationMs, durationMs, "熄屏", mapOf("source" to SOURCE)),
    )

    /**
     * 量化到 [decimals] 位小数，-0.000 归一成 0.000。
     *
     * 默认 [COARSE_DECIMALS]（3 位 ≈110m）：既有场景靠它演示「历史粗坐标」的阶梯，
     * 与老数据的落库口径一致。需要保留抖动时传 [FINE_DECIMALS]。
     */
    internal fun quantize(value: Double, decimals: Int = COARSE_DECIMALS): String {
        val factor = Math.pow(10.0, decimals.toDouble())
        val rounded = Math.round(value * factor) / factor
        val format = "%." + decimals + "f"
        return if (rounded == 0.0) String.format(Locale.US, format, 0.0) else String.format(Locale.US, format, rounded)
    }
}
