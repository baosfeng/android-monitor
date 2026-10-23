package com.bsfeng.monitor.domain.trip

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TripAggregator 的判定口径测试：停留 / 移动 / 无定位退化 / Gap / 空输入 / 跨界裁剪。
 *
 * 时间锚点固定为 [DAY_START]（2023-11-14 22:13:20 UTC 附近），避免随 wall clock 漂移；
 * 坐标取杭州附近（纬度 30°，经度 120°），0.001° 纬度 ≈ 111m。
 */
class TripAggregatorTest {

    private val aggregator = TripAggregator(DAY_START)

    // ───────────────────────────── 停留识别 ─────────────────────────────

    @Test
    fun `连续同址超过十分钟识别为停留`() {
        val records = buildList {
            // 家：00:00 – 01:00，每 5 分钟一个点
            for (m in 0..60 step 5) add(loc(at(m), 30.0000, 120.0000))
            // 期间用过微信 30 分钟（段末 00:30）
            add(usage(at(30), "微信", 30 * MIN_MS))
        }

        val timeline = aggregator.aggregate(records)

        assertEquals(Basis.GPS, timeline.basis)
        assertEquals(1, timeline.stays.size)
        val stay = timeline.stays.single()
        assertEquals("停留起点应为第一个同址点", at(0), stay.start)
        assertEquals("停留终点应为最后一个同址点", at(60), stay.end)
        assertEquals(60 * MIN_MS, stay.durationMs)
        assertEquals("微信", stay.topApps.firstOrNull()?.app)
        assertNotNull(stay.place.lat)
        // 01:00 之后没有数据 → 剩余时间全部是未记录，不补编
        assertEquals(DAY_START + TRIP_DAY_MS, timeline.gaps.single().end)
        assertEquals(1, timeline.gaps.size)
    }

    @Test
    fun `不足十分钟的同址点不算停留`() {
        val records = buildList {
            add(loc(at(0), 30.0000, 120.0000))
            // 120 分钟的采样断档会把「同一格」切成两段：各 5 分钟，都不足 10 分钟
            add(loc(at(120), 30.0000, 120.0000))
            add(loc(at(125), 30.0000, 120.0000))
        }

        val timeline = aggregator.aggregate(records)

        assertEquals("两段各 5 分钟都不到 10 分钟 → 没有停留", 0, timeline.stays.size)
        assertTrue("没有停留即整日未记录", timeline.gaps.single().durationMs == TRIP_DAY_MS)
    }

    // ───────────────────────────── 移动识别 ─────────────────────────────

    @Test
    fun `两处停留之间净位移超过三百米识别为移动`() {
        val records = buildList {
            for (m in 0..60 step 5) add(loc(at(m), 30.0000, 120.0000, "家"))       // 家
            add(loc(at(65), 30.0040, 120.0000))                                  // 路上 ≈ 444m
            add(loc(at(75), 30.0080, 120.0000))                                  // 路上 ≈ 888m
            for (m in 85..115 step 5) add(loc(at(m), 30.0100, 120.0000, "某某咖啡")) // 咖啡 ≈ 1111m
        }

        val timeline = aggregator.aggregate(records)

        assertEquals("停留 / 移动 / 停留 / 未记录", 4, timeline.segments.size)
        assertTrue(timeline.segments[0] is Stay)
        val move = timeline.segments[1]
        assertTrue("中间段应为移动", move is Move)
        move as Move
        assertEquals(at(60), move.start)
        assertEquals(at(85), move.end)
        assertTrue("累加距离应在 900–1400m 之间，实际 ${move.distanceMeters}", move.distanceMeters in 900.0..1400.0)
        assertEquals("家", move.from?.label ?: "")
        assertEquals("某某咖啡", move.to?.label ?: "")
        assertTrue(timeline.segments[2] is Stay)
        assertTrue("末段没有数据 → 未记录", timeline.segments[3] is Gap)
    }

    @Test
    fun `定位断档超过一小时不判移动只留未记录`() {
        val records = buildList {
            for (m in 0..60 step 5) add(loc(at(m), 30.0000, 120.0000))   // 家
            add(loc(at(300), 30.0200, 120.0000))                         // 4 小时后一个孤立点
            for (m in 360..420 step 5) add(loc(at(m), 30.0500, 120.0000)) // 咖啡（3.3km 外）
        }

        val timeline = aggregator.aggregate(records)

        assertEquals(2, timeline.stays.size)
        assertEquals("断档区间只产出未记录，不判移动", 0, timeline.moves.size)
        val between = timeline.segments[1]
        assertTrue("定位覆盖 <60% 时不得判移动", between is Gap)
        assertEquals(at(60), between.start)
        assertEquals(at(360), between.end)
    }

    @Test
    fun `两处停留净位移不足三百米不判移动`() {
        val records = buildList {
            for (m in 0..60 step 5) add(loc(at(m), 30.0000, 120.0000))
            add(loc(at(65), 30.0015, 120.0000))   // 166m
            for (m in 70..130 step 5) add(loc(at(m), 30.0020, 120.0000))  // 222m
        }

        val timeline = aggregator.aggregate(records)

        assertEquals(2, timeline.stays.size)
        assertEquals("位移 ≤300m 不判移动", 0, timeline.moves.size)
        assertTrue(timeline.segments[1] is Gap)
    }

    // ──────────────────── P0：主地点归并与在外不重复计入 ────────────────────

    /** 家 → 公司 → 家：同一地点两段停留必须共用一个 place.id。 */
    private fun twoVisitsToHome(): List<Record> = buildList {
        for (m in 0..120 step 10) add(loc(at(m), 30.0000, 120.0000, "家"))
        // 移动点只给两个（5 分钟）：3 个点就满 10 分钟，会被判成途中的一段停留
        for (m in listOf(125, 130)) add(loc(at(m), 30.0040, 120.0000))
        for (m in 140..200 step 10) add(loc(at(m), 30.0100, 120.0000, "公司"))
        for (m in listOf(205, 210)) add(loc(at(m), 30.0040, 120.0000))
        for (m in 220..280 step 10) add(loc(at(m), 30.0000, 120.0000, "家"))
    }

    @Test
    fun `同一地点两段停留共用一个地点标识`() {
        val timeline = aggregator.aggregate(twoVisitsToHome())
        val stays = timeline.stays

        assertEquals(3, stays.size)
        assertEquals("家两段 + 公司一段 = 两个地点", 2, stays.map { it.place.id }.distinct().size)
        assertEquals("家两段必须是同一个 id", stays[0].place.id, stays[2].place.id)
        assertTrue("id 不再是序号", stays[0].place.id.startsWith("geo:"))
        assertEquals(
            "主地点时长 = 两段之和（2h + 1h）",
            3 * 60 * MIN_MS.toLong(),
            timeline.summary.topPlaceMs,
        )
        assertEquals("家", timeline.summary.topPlaceLabel)
    }

    @Test
    fun `在外与主停留不重复计入`() {
        val timeline = aggregator.aggregate(twoVisitsToHome())
        val summary = timeline.summary

        // away 含「移动 + 非主地点停留」，因此恒等式以「已判定时长」为基准
        assertEquals(
            "away + topPlace 必须等于 停留 + 移动（不能把非主停留算两遍）",
            summary.activeMs,
            summary.awayMs + summary.topPlaceMs,
        )
        assertEquals(
            "在外 = 移动 40 分 + 非主地点停留 1h = 1h40m；加上主地点 3h 正好等于已判定 4h40m",
            100 * MIN_MS,
            summary.awayMs,
        )
        assertTrue("在外不得等于全部停留", summary.awayMs != summary.stayMs)
        assertTrue("在外必须小于当天时长", summary.awayMs < TRIP_DAY_MS)
    }

    @Test
    fun `停留移动未记录三段互斥合计等于当天窗口`() {
        val timeline = aggregator.aggregate(twoVisitsToHome())
        val summary = timeline.summary

        assertEquals(
            "三段互斥：停留 + 移动 + 未记录 = 窗口长度",
            TRIP_DAY_MS,
            summary.stayMs + summary.moveMs + summary.gapMs,
        )
        assertEquals(
            "summary 的分项与 segments 求和不一致",
            timeline.segments.sumOf { it.durationMs },
            summary.stayMs + summary.moveMs + summary.gapMs,
        )
        assertTrue(
            "主要停留 + 在外 + 未记录 = 当天窗口（指标三格自洽）",
            summary.topPlaceMs + summary.awayMs + summary.gapMs == TRIP_DAY_MS,
        )
    }

    /**
     * 复刻 debug `seed day` 的场景比例（家 0–30% / 移动 30–50% / 公司 50–78% /
     * 移动 78–88% / 家 88–100%），窗口取 21h47 —— 与模拟器实测同一组数字。
     */
    private fun seedDayLike(spanMin: Int): List<Record> {
        val home = 39.909 to 116.398
        val office = 39.985 to 116.318
        val out = ArrayList<Record>()
        fun stayTo(fromPct: Int, toPct: Int) {
            var m = spanMin * fromPct / 100
            val limit = spanMin * toPct / 100
            while (m <= limit) {
                out += loc(at(m), home.first, home.second, "家")
                m += 10
            }
        }
        fun moveTo(fromPct: Int, toPct: Int) {
            val fromM = spanMin * fromPct / 100
            val toM = spanMin * toPct / 100
            var m = fromM
            while (m < toM) {
                val t = (m - fromM).toDouble() / (toM - fromM).toDouble()
                out += loc(
                    at(m),
                    home.first + (office.first - home.first) * t,
                    home.second + (office.second - home.second) * t,
                )
                m += 5
            }
        }
        // 家（上午）
        stayTo(0, 30)
        // 家 → 公司
        moveTo(30, 50)
        // 公司
        var m = spanMin * 50 / 100
        while (m <= spanMin * 78 / 100) {
            out += loc(at(m), office.first, office.second, "公司")
            m += 10
        }
        // 公司 → 家
        val backFrom = spanMin * 78 / 100
        val backTo = spanMin * 88 / 100
        var bt = backFrom
        while (bt < backTo) {
            val t = (bt - backFrom).toDouble() / (backTo - backFrom).toDouble()
            out += loc(
                at(bt),
                office.first + (home.first - office.first) * t,
                office.second + (home.second - office.second) * t,
            )
            bt += 5
        }
        // 家（晚上）
        stayTo(88, 100)
        return out
    }

    @Test
    fun `seed day 场景主地点归并后在外不再重复计入`() {
        // 21h47 = 1307 分钟，对应实测「停留 15 小时 15 分」；窗口右端取同一时刻（等价于 now）
        val windowEnd = DAY_START + 1307 * MIN_MS
        val timeline = TripAggregator(DAY_START, windowEnd).aggregate(seedDayLike(1307))
        val summary = timeline.summary

        assertEquals("家两段 + 公司一段", 3, timeline.stays.size)
        assertEquals("家两段必须共用一个地点", timeline.stays[0].place.id, timeline.stays[2].place.id)

        val homeStays = timeline.stays.filter { it.place.label == "家" }
        assertEquals(2, homeStays.size)
        // P0-1：主地点时长 = 同一地点两段之和（旧实现只取最长单段）
        assertEquals(homeStays.sumOf { it.durationMs }, summary.topPlaceMs)
        // P0-2：away + topPlace == 已判定时长（停留 + 移动），不再重复计入主停留
        assertEquals(summary.activeMs, summary.awayMs + summary.topPlaceMs)
        assertEquals(
            "away = 移动 + 非主地点停留",
            summary.moveMs + (summary.stayMs - summary.topPlaceMs),
            summary.awayMs,
        )
        // P0-3：主要停留 + 在外 + 未记录 = 当天窗口（三格自洽，不再出现 30h30）
        assertEquals(windowEnd - DAY_START, summary.topPlaceMs + summary.awayMs + summary.gapMs)
        assertTrue("在外必须小于当天窗口", summary.awayMs < windowEnd - DAY_START)
        assertTrue("在外不得等于全部停留", summary.awayMs != summary.stayMs)

        println(
            "seed-day(1307m): stay=" + summary.stayMs / MIN_MS + "m" +
                " topPlace=" + summary.topPlaceMs / MIN_MS + "m" +
                " away=" + summary.awayMs / MIN_MS + "m" +
                " move=" + summary.moveMs / MIN_MS + "m" +
                " gap=" + summary.gapMs / MIN_MS + "m" +
                " homeSegments=" + homeStays.map { it.durationMs / MIN_MS } +
                " office=" + timeline.stays[1].durationMs / MIN_MS + "m",
        )
    }

    // ─────────────────── P1-2：簇内地名投票（不取首个采样） ───────────────────

    @Test
    fun `地名取簇内众数而不是首个采样`() {
        // 复刻线上形态：移动末点（address=移动中）先落进停留簇，随后才是公司的采样
        val records = listOf(
            loc(at(0), 30.0000, 120.0000, "移动中"),
            loc(at(5), 30.0000, 120.0000, "公司"),
            loc(at(10), 30.0000, 120.0000, "公司"),
            loc(at(15), 30.0000, 120.0000, "公司"),
        )

        val timeline = aggregator.aggregate(records)
        val stay = timeline.stays.single()

        assertEquals("瞬态占位不能顶掉真地名", "公司", stay.place.label)
        assertEquals("同一地点仍共用一个 id", "公司", timeline.summary.topPlaceLabel)
        assertTrue("地名不得是移动中", stay.place.label != "移动中")
    }

    @Test
    fun `地名平票时取精度更好的采样`() {
        val records = listOf(
            loc(at(0), 30.0000, 120.0000, "甲", accuracy = 120.0),
            loc(at(5), 30.0000, 120.0000, "乙", accuracy = 10.0),
            loc(at(10), 30.0000, 120.0000, "甲", accuracy = 100.0),
            loc(at(15), 30.0000, 120.0000, "乙", accuracy = 20.0),
        )

        val stay = aggregator.aggregate(records).stays.single()

        assertEquals("2:2 平票 → 取 accuracy 更小的乙", "乙", stay.place.label)
    }

    @Test
    fun `全是瞬态占位时回落到通用地名`() {
        val records = listOf(
            loc(at(0), 30.0000, 120.0000, "移动中"),
            loc(at(5), 30.0000, 120.0000, "路上"),
            loc(at(10), 30.0000, 120.0000, "移动中"),
        )

        val stay = aggregator.aggregate(records).stays.single()

        assertEquals("没有有效地名时用通用占位，不编造", "地点", stay.place.label)
    }

    // ─────────────────── P2：抽屉里的期间应用不截断 ───────────────────

    @Test
    fun `停留期间的应用列表返回全部应用`() {
        val records = buildList {
            for (m in 0..60 step 10) add(loc(at(m), 30.0000, 120.0000, "家"))
            listOf("微信", "抖音", "哔哩哔哩", "小红书", "飞书").forEachIndexed { index, app ->
                add(usage(at(10 + index * 10), app, 5 * MIN_MS))
            }
        }

        val stay = aggregator.aggregate(records).stays.single()

        assertEquals("模型侧给全部应用（抽屉列前 8 条 + 其他）", 5, stay.topApps.size)
        assertEquals("按时长降序", stay.topApps.first().app, "微信")
    }

    // ─────────────────────────── 无定位退化 ───────────────────────────

    @Test
    fun `无定位时按同一 SSID 连续识别地点`() {
        val records = listOf(
            wifi(at(480), "家庭网络"),
            wifi(at(600), "家庭网络"),
            wifi(at(600), "公司网络"),
            wifi(at(720), "公司网络"),
            wifi(at(720), "家庭网络"),
        )

        val timeline = aggregator.aggregate(records)

        assertEquals(Basis.DEVICE, timeline.basis)
        assertEquals(3, timeline.stays.size)
        assertEquals(listOf("家庭网络", "公司网络", "家庭网络"), timeline.stays.map { it.place.label })
        assertEquals("同一 SSID 两次 = 同一地点", timeline.stays[0].place.id, timeline.stays[2].place.id)
        // 家：08:00–10:00（120 分）+ 12:00–24:00（720 分，最后一段开到窗口尽头）
        assertEquals("主地点时长 = 两次 SSID 驻留之和", 840 * MIN_MS, timeline.summary.topPlaceMs)
        assertNull("DEVICE 基准不编造坐标", timeline.stays.first().place.lat)
        assertEquals(0, timeline.moves.size)
        assertEquals("00:00 到首个网络上线的空档是未记录", at(480), timeline.gaps.first().end)
        assertEquals(at(480) - DAY_START, timeline.gaps.first().durationMs)
    }

    @Test
    fun `无定位时按使用簇识别停留`() {
        val records = listOf(
            usage(at(10), "微信", 10 * MIN_MS),   // 00:00 – 00:10
            usage(at(20), "抖音", 10 * MIN_MS),   // 00:10 – 00:20（与上一段间隔 0）
            usage(at(30), "微信", 10 * MIN_MS),   // 00:20 – 00:30
        )

        val timeline = aggregator.aggregate(records)

        assertEquals(Basis.DEVICE, timeline.basis)
        assertEquals(1, timeline.stays.size)
        val stay = timeline.stays.single()
        assertEquals(DAY_START, stay.start)
        assertEquals(at(30), stay.end)
        assertEquals("使用簇地点名不冒充真实地点", "使用中", stay.place.label)
        assertEquals("微信", stay.topApps.first().app)
    }

    @Test
    fun `使用簇间隔超过五分钟拆成两段中间留未记录`() {
        val records = listOf(
            usage(at(10), "微信", 10 * MIN_MS),    // 00:00 – 00:10
            usage(at(60), "抖音", 10 * MIN_MS),    // 00:50 – 01:00（间隔 40 分钟）
        )

        val timeline = aggregator.aggregate(records)

        assertEquals(2, timeline.stays.size)
        val gap = timeline.gaps.first()
        assertEquals(at(10), gap.start)
        assertEquals(at(50), gap.end)
    }

    // ─────────────────────────── Gap 与空输入 ───────────────────────────

    @Test
    fun `空输入返回空时间轴`() {
        val timeline = aggregator.aggregate(emptyList())

        assertTrue("空输入不得产生任何段", timeline.isEmpty)
        assertEquals(DAY_START, timeline.dayStart)
        assertEquals(0, timeline.coveragePct)
        assertEquals(Basis.DEVICE, timeline.basis)
    }

    @Test
    fun `只有零散定位点时整天未记录不猜内容`() {
        val timeline = aggregator.aggregate(
            listOf(loc(at(0), 30.0, 120.0), loc(at(3), 30.0, 120.0)),
        )

        assertTrue(timeline.isEmpty.not())
        assertEquals(1, timeline.gaps.size)
        assertEquals(TRIP_DAY_MS, timeline.gaps.single().durationMs)
        assertEquals("无法判定时不得编造移动", 0, timeline.moves.size)
    }

    // ─────────────────────────── 跨界与非法入参 ───────────────────────────

    @Test
    fun `跨界记录按窗口裁剪不外溢`() {
        val records = listOf(
            // 前一天 23:30 开始、跨到当天 00:10 的使用段：起点应裁到 dayStart
            usage(at(10), "微信", 40 * MIN_MS),
            usage(at(25), "抖音", 15 * MIN_MS),
            // 恰好落在 dayEnd 上的记录必须被丢弃
            usage(DAY_START + TRIP_DAY_MS, "小红书", 10 * MIN_MS),
        )

        val timeline = aggregator.aggregate(records)

        assertEquals("窗口外记录不参与聚合", 1, timeline.stays.size)
        assertTrue(timeline.segments.all { it.start >= DAY_START && it.end <= DAY_START + TRIP_DAY_MS })
        assertEquals(DAY_START, timeline.stays.single().start)
        assertTrue("裁剪后使用段不早于 dayStart", timeline.stays.single().topApps.all { it.start >= DAY_START })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `窗口非法时抛异常不静默返回空`() {
        TripAggregator(DAY_START, DAY_START)
    }

    // ─────────────────────── 页面轨迹（指纹档）计数 ───────────────────────

    @Test
    fun `页面切换只按 App 计数不带页面文本`() {
        val records = buildList {
            for (m in 0..60 step 5) add(loc(at(m), 30.0000, 120.0000))
            // PAGE 类型由并行任务新增：这里用 detail 里的 page_key 识别，验证聚合层的识别兜底
            add(page(at(10), "微信", "a3f2"))
            add(page(at(20), "微信", "b1c4"))
            add(page(at(30), "抖音", "c81d"))
            add(page(at(35), "小红书", "7e05"))
        }

        val timeline = aggregator.aggregate(records)

        val pages = timeline.stays.single().pages
        // 抽屉要完整列表：不因为摘要只取 Top1 就把后面的 App 丢掉
        assertEquals(3, pages.size)
        assertEquals("微信", pages[0].app)
        assertEquals(2, pages[0].switches)
        assertEquals("抖音", pages[1].app)
        assertEquals(1, pages[1].switches)
    }

    // ────────────────────────────── 构造辅助 ──────────────────────────────

    private fun at(minutes: Int): Long = DAY_START + minutes * MIN_MS

    private fun loc(
        ts: Long,
        lat: Double,
        lng: Double,
        address: String? = null,
        accuracy: Double? = null,
    ): Record = Record(
        id = "loc-$ts",
        type = RecordType.LOCATION,
        action = "COORDINATE",
        timestamp = ts,
        label = address ?: "",
        detail = buildMap {
            put("lat", lat.toString())
            put("lng", lng.toString())
            if (address != null) put("address", address)
            if (accuracy != null) put("accuracy_m", accuracy.toString())
        },
    )

    private fun usage(endTs: Long, app: String, ms: Long): Record = Record(
        id = "usage-$endTs-$app",
        type = RecordType.APP_USAGE,
        action = "USAGE",
        timestamp = endTs,
        duration = ms,
        label = app,
        detail = mapOf("app_name" to app, "duration_ms" to ms.toString()),
    )

    private fun wifi(ts: Long, ssid: String): Record = Record(
        id = "wifi-$ts-$ssid",
        type = RecordType.WIFI,
        action = "CONNECTED",
        timestamp = ts,
        label = "连接 WiFi：$ssid",
        detail = mapOf("ssid" to ssid),
    )

    private fun page(ts: Long, app: String, key: String): Record = Record(
        id = "page-$ts-$key",
        type = RecordType.SCREEN,
        action = "CHANGED",
        timestamp = ts,
        label = "$app › 页面 $key",
        detail = mapOf("app_name" to app, "page_key" to key),
    )

    private companion object {
        const val MIN_MS = 60_000L
        const val DAY_START = 1_700_000_000_000L
    }
}
