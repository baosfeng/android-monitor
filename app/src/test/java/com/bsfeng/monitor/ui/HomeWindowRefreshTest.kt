package com.bsfeng.monitor.ui

import com.bsfeng.monitor.ui.live.startOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「跨零点后页头 / 横轴窗口跟着数据一起翻篇」（架构审计 A8 / A9）。
 *
 * 两个缺陷同源：屏内用**无 key** 的 `remember { System.currentTimeMillis() }` 取时刻，
 * 组合生命周期内再不重算。App 是常驻前台的场景，用户开着页面过零点后，
 * ViewModel 每轮刷新已按新的 startOfDay 重算，页头日期与 7 天横轴却还停在进屏那一刻。
 *
 * 修法是让屏内时刻**从数据快照派生**（[overviewHeaderNow] / [adWindowStart]），
 * 这里钉住派生规则本身；Compose 侧的 key 只是把这条规则接到 lastUpdatedAt / windowStart 上。
 *
 * 「今天」一律按系统时区推算，不写死 Asia/Shanghai。
 */
class HomeWindowRefreshTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun atLocal(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        LocalDate.of(year, month, day)
            .atTime(hour, minute, second)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun localDateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    // ── overviewHeaderNow：页头时刻跟随数据快照 ─────────────────────────────

    @Test
    fun `有数据快照时用快照时刻而不是进屏那一刻`() {
        val entered = atLocal(2026, 10, 27, 23, 59, 50)
        val refreshed = atLocal(2026, 10, 28, 0, 0, 5)

        val now = overviewHeaderNow(lastUpdatedAt = refreshed, wallClock = entered)

        assertEquals("页头必须用快照时刻", refreshed, now)
        assertEquals("跨零点后页头日期翻到新的一天", LocalDate.of(2026, 10, 28), localDateOf(now))
    }

    @Test
    fun `快照时刻与面板同源时页头日期与今日窗口一致`() {
        val refreshed = atLocal(2026, 10, 28, 0, 0, 5)
        val now = overviewHeaderNow(lastUpdatedAt = refreshed, wallClock = refreshed)

        // 「今日」面板的窗口起点由同一个 now 推出：页头说哪天，面板就统计哪天
        assertEquals(
            "页头日期必须与今日面板的 startOfDay 同一天",
            localDateOf(startOfDay(now)),
            localDateOf(now),
        )
    }

    @Test
    fun `还没读到数据时回落到墙钟`() {
        val wall = atLocal(2026, 10, 28, 9, 30)

        assertEquals("lastUpdatedAt = 0 时首帧用墙钟", wall, overviewHeaderNow(lastUpdatedAt = 0L, wallClock = wall))
        assertEquals("负值同样回落", wall, overviewHeaderNow(lastUpdatedAt = -1L, wallClock = wall))
    }

    // ── adWindowStart：7 天横轴窗口起点跟随数据快照 ─────────────────────────

    @Test
    fun `窗口起点是当地零点再往前 6 天`() {
        val now = atLocal(2026, 10, 28, 15, 40)
        val start = adWindowStart(now)

        assertEquals(
            "起点必须是 6 天前的本地 00:00",
            startOfDay(atLocal(2026, 10, 22, 13, 0)),
            start,
        )
        assertEquals("窗口末日本地零点 + 6 天 = 今天零点", startOfDay(now), start + 6 * 86_400_000L)
    }

    @Test
    fun `跨零点后窗口整体前移一天`() {
        val beforeMidnight = atLocal(2026, 10, 27, 23, 59, 59)
        val afterMidnight = atLocal(2026, 10, 28, 0, 0, 1)

        val shift = adWindowStart(afterMidnight) - adWindowStart(beforeMidnight)

        // 夏令时地区一天可能是 23/25 小时，只要「确实前进了一天」即可
        assertTrue("跨零点后窗口不得停在旧起点：shift=$shift", shift > 0L)
        assertEquals(
            "窗口末柱跟着翻到新的一天",
            LocalDate.of(2026, 10, 28),
            localDateOf(adWindowStart(afterMidnight) + 6 * 86_400_000L),
        )
    }

    @Test
    fun `同一时刻反复求值结果稳定`() {
        val now = atLocal(2026, 10, 28, 8, 0)

        // 屏内 deriv 会被重组触发；纯函数必须幂等，否则每次重组横轴都会跳
        assertEquals(adWindowStart(now), adWindowStart(now))
        assertEquals(overviewHeaderNow(now, now), overviewHeaderNow(now, now))
    }

    // ── dayLabels：横轴标签跟随数据快照的日界 ──────────────────────────────

    @Test
    fun `日标签末日就是快照当天`() {
        val snapshot = atLocal(2026, 10, 28, 0, 0, 5)

        val labels7 = dayLabels(7, snapshot)
        val labels30 = dayLabels(30, snapshot)

        assertEquals(7, labels7.size)
        assertEquals(30, labels30.size)
        // 末位 = 今天（MM-dd，与 formatDayLabel 同口径）
        assertEquals("10-28", labels7.last())
        assertEquals("10-28", labels30.last())
        assertEquals("10-22", labels7.first())
        assertEquals("09-29", labels30.first())
    }

    @Test
    fun `日标签按快照而非墙钟翻篇`() {
        // 进屏那一刻是 27 号，数据已按 28 号重算：标签必须用快照
        val labels = dayLabels(7, atLocal(2026, 10, 28, 0, 0, 5))

        assertEquals("跨零点后横轴末日跟着翻篇", "10-28", labels.last())
    }

    // ── 错误文案：异常原文只进日志、不上屏 ────────────────────────────────

    @Test
    fun `三个屏的错误文案都是不含异常原文的常量`() {
        val copies = listOf(
            AD_LOAD_ERROR_DESC,
            SKIP_RULE_LOAD_ERROR_DESC,
            SKIP_SUBSCRIPTION_ERROR_DESC,
            com.bsfeng.monitor.ui.trip.TRIP_LOAD_ERROR_DESC,
        )
        copies.forEach { text ->
            assertTrue("文案不能为空：$text", text.isNotBlank())
            listOf("Exception", "SQLiteException", "null", ".kt", "/data/").forEach { leak ->
                assertTrue("上屏文案不得携带异常原文痕迹 $leak：$text", !text.contains(leak))
            }
        }
        // 锚点 s03-error 逐字（本次只改来源、不改文案）
        assertEquals(
            "跳过事件表暂时被占用（SQLITE_BUSY）。已跳过的广告不会重复出现。",
            AD_LOAD_ERROR_DESC,
        )
    }
}
