package com.bsfeng.monitor.widget

import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaPeriod
import com.bsfeng.monitor.domain.quota.QuotaUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 额度组件的内容层（纯 JVM）。
 *
 * 为什么这些必须钉住：组件跑在 RemoteViews 里，桌面上只能靠肉眼看；选行错一格、文案截错、
 * 4 格塞不进箱子这类问题肉眼几乎发现不了，能自动验证的就是这一层。
 */
class QuotaWidgetModelTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-11-28 10:00（周六）：下面每条重置时刻都相对它算。 */
    private val now = LocalDateTime.of(2026, 11, 28, 10, 0).atZone(zone).toEpochSecond()

    private fun percentItem(
        id: String,
        title: String,
        remaining: String,
        resetsAt: Long? = null,
        primary: Boolean = false,
    ) = QuotaItem(
        id = id,
        title = title,
        kind = QuotaKind.PERCENT,
        remaining = BigDecimal(remaining),
        unit = QuotaUnit.PERCENT,
        resetsAtEpochSecond = resetsAt,
        primary = primary,
    )

    private fun balanceItem(id: String, remaining: String, unit: QuotaUnit = QuotaUnit.CNY) = QuotaItem(
        id = id,
        title = "余额",
        kind = QuotaKind.MONEY,
        remaining = BigDecimal(remaining),
        unit = unit,
    )

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toEpochSecond()

    // ===== 正常：一账号一格，按最紧张窗口升序 =====

    @Test
    fun `每个账号取最紧张的窗口作为一格`() {
        val accounts = listOf(
            QuotaWidgetAccount(
                instanceId = "ollama",
                displayName = "Ollama",
                items = listOf(
                    percentItem("h", "5 小时", "46", resetsAt = at(12, 1, 0)),
                    percentItem("w", "本周", "96", resetsAt = at(11, 30, 0)),
                ),
            ),
            QuotaWidgetAccount(
                instanceId = "oc-main",
                displayName = "opencode 主号",
                items = listOf(percentItem("h", "5 小时", "12", resetsAt = at(11, 28, 17))),
            ),
        )

        val cells = quotaWidgetCells(accounts, limit = 4, nowEpochSecond = now, zone = zone)

        assertEquals(listOf("oc-main", "ollama"), cells.map { it.instanceId })
        assertEquals("12%", cells[0].valueText)
        assertEquals("opencode 主号 5 小时 · 今天 17:00", cells[0].captionText)
        assertEquals(0.12f, cells[0].fraction!!, 0.0001f)
        // 同一账号里 46% 与 96% 两个窗口：格子里只能取更紧的 46%
        assertEquals("46%", cells[1].valueText)
        assertEquals("Ollama 5 小时 · 周二 00:00", cells[1].captionText)
    }

    @Test
    fun `比例相同优先 provider 自报的主项`() {
        val account = QuotaWidgetAccount(
            instanceId = "x",
            displayName = "X",
            items = listOf(
                percentItem("a", "本周", "50", primary = false),
                percentItem("b", "本月", "50", primary = true),
            ),
        )
        val cells = quotaWidgetCells(listOf(account), limit = 1, nowEpochSecond = now, zone = zone)
        assertEquals("X 本月", cells.single().captionText)
    }

    @Test
    fun `余额型排在百分比型之后且不画横条`() {
        val accounts = listOf(
            QuotaWidgetAccount("ds", "DeepSeek 账号 2", listOf(balanceItem("bal", "8.60"))),
            QuotaWidgetAccount("oc", "opencode", listOf(percentItem("h", "5 小时", "12"))),
        )
        val cells = quotaWidgetCells(accounts, limit = 4, nowEpochSecond = now, zone = zone)

        assertEquals(listOf("oc", "ds"), cells.map { it.instanceId })
        assertEquals("¥8.60", cells[1].valueText)
        assertEquals("DeepSeek 账号 2 余额", cells[1].captionText)
        assertNull(cells[1].fraction)
    }

    // ===== 空数据 / 过期数据 =====

    @Test
    fun `没有账号时一格都不给`() {
        assertTrue(quotaWidgetCells(emptyList(), limit = 4, nowEpochSecond = now, zone = zone).isEmpty())
        assertEquals("额度 · 尚未更新", quotaHeaderText(fetchedAtText = null, stale = false))
    }

    @Test
    fun `账号没有任何额度项时跳过而不是给空格`() {
        val accounts = listOf(QuotaWidgetAccount("empty", "空账号", emptyList()))
        assertTrue(quotaWidgetCells(accounts, limit = 4, nowEpochSecond = now, zone = zone).isEmpty())
    }

    @Test
    fun `数据过期时顶栏明说`() {
        assertEquals("额度 · 数据过期 · 更新于 3 小时前", quotaHeaderText("更新于 3 小时前", stale = true))
        assertEquals("额度 · 更新于 4 分钟前", quotaHeaderText("更新于 4 分钟前", stale = false))
    }

    // ===== 只有一格 =====

    @Test
    fun `限一格时只留最紧张的那个账号`() {
        val accounts = listOf(
            QuotaWidgetAccount("a", "A", listOf(percentItem("h", "5 小时", "70"))),
            QuotaWidgetAccount("b", "B", listOf(percentItem("h", "5 小时", "9"))),
        )
        val cells = quotaWidgetCells(accounts, limit = 1, nowEpochSecond = now, zone = zone)
        assertEquals(1, cells.size)
        assertEquals("9%", cells.single().valueText)
        // limit <= 0 是脏参数（格子数为 0 时不该画出任何内容）
        assertTrue(quotaWidgetCells(accounts, limit = 0, nowEpochSecond = now, zone = zone).isEmpty())
    }

    // ===== 全余额型 =====

    @Test
    fun `全余额型时每格给金额按实例键兜底排序`() {
        val accounts = listOf(
            QuotaWidgetAccount("ds-2", "DeepSeek 账号 2", listOf(balanceItem("b", "8.60"))),
            QuotaWidgetAccount("ds-1", "DeepSeek 账号 1", listOf(balanceItem("b", "120"))),
        )
        val cells = quotaWidgetCells(accounts, limit = 4, nowEpochSecond = now, zone = zone)
        assertEquals(listOf("ds-1", "ds-2"), cells.map { it.instanceId })
        assertEquals(listOf("¥120.00", "¥8.60"), cells.map { it.valueText })
        assertTrue(cells.all { it.fraction == null })
    }

    @Test
    fun `金额按单位给符号_隐藏时与记账组件同一占位`() {
        assertEquals("¥8.60", quotaAmountText(BigDecimal("8.6"), QuotaUnit.CNY))
        assertEquals("$12.00", quotaAmountText(BigDecimal("12"), QuotaUnit.USD))
        assertEquals("1,234 次", quotaAmountText(BigDecimal("1234"), QuotaUnit.COUNT))
        assertEquals("¥••••", quotaAmountText(BigDecimal("8.6"), QuotaUnit.CNY, hidden = true))
        assertEquals("¥••••", quotaAmountText(BigDecimal("12"), QuotaUnit.USD, hidden = true))
    }

    // ===== 重置时刻 =====

    @Test
    fun `重置时刻按远近分四档`() {
        assertEquals("今天 17:00", quotaResetText(now, at(11, 28, 17), zone))
        assertEquals("明天 09:00", quotaResetText(now, at(11, 29, 9), zone))
        assertEquals("周二 00:00", quotaResetText(now, at(12, 1, 0), zone))
        assertEquals("12-05", quotaResetText(now, at(12, 5, 0), zone))
    }

    @Test
    fun `没有重置时刻时说明行不留孤立分隔符`() {
        assertEquals("", quotaResetText(now, resetsAtEpochSecond = 0L, zone = zone))
        val account = QuotaWidgetAccount("oc", "opencode", listOf(percentItem("h", "5 小时", "12")))
        val cell = quotaWidgetCells(listOf(account), limit = 1, nowEpochSecond = now, zone = zone).single()
        assertEquals("opencode 5 小时", cell.captionText)
    }

    @Test
    fun `窄格放不下时整段省掉重置时刻_不留半截分隔符`() {
        // 250×110 的 4×2：一格 109dp < 120dp 阈值 → 只留「账号 窗口」
        val narrow = quotaCaptionText("open-design", "本月", "周二 00:00", availableWidthDp = 109f)
        assertEquals("open-design 本月", narrow)
        // 宽格（4×2 真机 157dp）保留完整写法
        assertEquals(
            "open-design 本月 · 周二 00:00",
            quotaCaptionText("open-design", "本月", "周二 00:00", availableWidthDp = 157f),
        )
        // 阈值边界：120dp 给完整写法，119.9dp 整段省略
        assertTrue(quotaCaptionText("A", "本周", "周一 00:00", availableWidthDp = 120f).contains("·"))
        assertFalse(quotaCaptionText("A", "本周", "周一 00:00", availableWidthDp = 119.9f).contains("·"))
        // 没有重置时刻时不论宽窄都只有「账号 窗口」
        assertEquals("A 本周", quotaCaptionText("A", "本周", "", availableWidthDp = 157f))
    }

    @Test
    fun `窄格里的格子不带重置时刻_宽格带`() {
        val accounts = listOf(
            QuotaWidgetAccount("od", "open-design", listOf(percentItem("m", "本月", "34", resetsAt = at(12, 1, 0)))),
        )
        val narrow = quotaWidgetCells(accounts, limit = 4, nowEpochSecond = now, captionWidthDp = 109f, zone = zone)
        assertEquals("open-design 本月", narrow.single().captionText)
        val wide = quotaWidgetCells(accounts, limit = 4, nowEpochSecond = now, captionWidthDp = 157f, zone = zone)
        assertEquals("open-design 本月 · 周二 00:00", wide.single().captionText)
    }

    @Test
    fun `已过重置时刻只报钟点`() {
        // 快照比重置时刻还旧：报「昨天 00:00」会让人以为那是日程，这里只给钟点
        assertEquals("00:00", quotaResetText(now, at(11, 27, 0), zone))
    }

    @Test
    fun `周期对象也能当重置时刻`() {
        val item = percentItem("p", "本月", "34").copy(
            period = QuotaPeriod(startEpochSecond = at(11, 1, 0), endEpochSecond = at(12, 1, 0)),
        )
        val account = QuotaWidgetAccount("od", "open-design", listOf(item))
        val cell = quotaWidgetCells(listOf(account), limit = 1, nowEpochSecond = now, zone = zone).single()
        assertEquals("open-design 本月 · 周二 00:00", cell.captionText)
    }

    // ===== 网格降档 =====

    @Test
    fun `4×2 最小箱子也是四格`() {
        val grid = quotaWidgetGrid(widthDp = 250f, heightDp = 110f)
        assertEquals(2, grid.columns)
        assertEquals(4, grid.cells)
        // 110dp 放不下顶栏：先砍装饰保数据（实测见交接文档）
        assertEquals(false, grid.showHeader)
    }

    @Test
    fun `真机 4×2 高度补回顶栏`() {
        val grid = quotaWidgetGrid(widthDp = 350f, heightDp = 170f)
        assertEquals(4, grid.cells)
        assertTrue(grid.showHeader)
    }

    @Test
    fun `4×1 降成两格`() {
        val grid = quotaWidgetGrid(widthDp = 350f, heightDp = 72f)
        assertEquals(2, grid.columns)
        assertEquals(2, grid.cells)
        assertEquals(false, grid.showHeader)
    }

    @Test
    fun `矮箱子用紧凑字阶_高箱子回到原型字阶`() {
        // 250×110（4×2 声明最小）：15/10 + 10dp 内边距，实测两行格底 95.3dp
        assertEquals(15, QuotaWidgetMetrics.of(110f).valueSizeSp)
        assertEquals(10, QuotaWidgetMetrics.of(110f).captionSizeSp)
        // 真机 4×2（170dp）：回到原型 .wr b 16px / .wr span 11px / .wtop 13px
        assertEquals(16, QuotaWidgetMetrics.of(170f).valueSizeSp)
        assertEquals(11, QuotaWidgetMetrics.of(170f).captionSizeSp)
        assertEquals(13, QuotaWidgetMetrics.of(170f).headerSizeSp)
        // 4dp 细横条两档都不变
        assertEquals(4, QuotaWidgetMetrics.of(110f).barHeight)
        assertEquals(4, QuotaWidgetMetrics.of(170f).barHeight)
    }

    @Test
    fun `2 格宽改成上下两格_1 格宽只留一格`() {
        assertEquals(1, quotaWidgetGrid(170f, 170f).columns)
        assertEquals(2, quotaWidgetGrid(170f, 170f).cells)
        assertEquals(1, quotaWidgetGrid(170f, 72f).cells)
        assertEquals(1, quotaWidgetGrid(110f, 110f).cells)
    }
}