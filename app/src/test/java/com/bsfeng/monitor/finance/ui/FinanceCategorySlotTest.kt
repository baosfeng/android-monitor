package com.bsfeng.monitor.finance.ui

import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.theme.chartRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类色映射（纯 JVM）：[categoryColorOf] = [categorySlotOf] → [categoryDot]。
 *
 * [categoryColorOf] 是 `@Composable` 的取色包装，本项目没有 Compose 测试依赖（零第三方业务依赖），
 * 单测覆盖它背后的两层纯函数。
 *
 * 现行口径（`.open-design/21-app-sync-handover.md` §2.2）：槽位即分类在
 * [Category.defaultExpenseCategories]（0..21）/ [Category.defaultIncomeCategories]（22..33）里的顺序下标，
 * 该顺序与 `finance_categories.sort_order` 一致，因此相邻分类天然不重色；
 * 预设之外的分类再按名称稳定哈希 % 16 兜底。
 * 颜色统一取 [chartRamp]（chart-1..chart-16，跨主题固定）并按槽位长度循环。
 */
class FinanceCategorySlotTest {

    private val tokens = DesignTokens("apple")

    private val expense = Category.defaultExpenseCategories
    private val income = Category.defaultIncomeCategories

    @Test
    fun `二十二个支出分类按预设顺序取到槽位 0 到 21 且互不重复`() {
        assertEquals("预设支出分类应为 22 个", 22, expense.size)

        expense.forEachIndexed { index, name ->
            assertEquals(
                "支出分类 [$name] 应按 defaultExpenseCategories 顺序取第 $index 槽",
                index,
                categorySlotOf(name),
            )
        }

        val slots = expense.map { categorySlotOf(it) }
        assertTrue("支出分类槽位应落在 0..21: $slots", slots.all { it in 0..21 })
        assertEquals("支出分类槽位应恰好是 0..21（顺序取槽，不跳号）", (0..21).toList(), slots)
        assertEquals("22 个支出分类槽位应互不重复: $slots", 22, slots.toSet().size)
    }

    @Test
    fun `十二个收入分类取到槽位 22 到 33 且与支出分类不冲突`() {
        assertEquals("预设收入分类应为 12 个", 12, income.size)

        val base = expense.size
        income.forEachIndexed { index, name ->
            assertEquals(
                "收入分类 [$name] 应接在支出分类之后取第 ${base + index} 槽",
                base + index,
                categorySlotOf(name),
            )
        }

        val incomeSlots = income.map { categorySlotOf(it) }
        assertEquals("收入分类槽位应恰好是 22..33", (22..33).toList(), incomeSlots)

        val expenseSlots = expense.map { categorySlotOf(it) }.toSet()
        val overlap = expenseSlots intersect incomeSlots.toSet()
        assertTrue("收入与支出的槽位不应冲突: $overlap", overlap.isEmpty())
        assertEquals(
            "收支预设分类合计 34 个应占满 0..33 且互不重复",
            34,
            (expenseSlots + incomeSlots.toSet()).size,
        )
    }

    @Test
    fun `相邻槽位取色不重色`() {
        val ramp = tokens.chartRamp
        (0 until ramp.size).forEach { slot ->
            assertNotEquals(
                "第 $slot 槽与第 ${slot + 1} 槽不应重色（末槽与首槽循环相邻）",
                tokens.categoryDot(slot),
                tokens.categoryDot(slot + 1),
            )
        }
    }

    @Test
    fun `跨越色板边界的相邻支出分类不重色`() {
        // 本次改动的直接目的：22 个支出分类里 index 15 与 16 跨过 chart-16 → chart-1 的边界，必须仍不重色。
        val left = expense[15]
        val right = expense[16]
        assertEquals("支出分类第 15 项应为 $left", 15, categorySlotOf(left))
        assertEquals("支出分类第 16 项应为 $right", 16, categorySlotOf(right))
        assertNotEquals(
            "相邻支出分类 [$left] 与 [$right] 不应重色（第 15 槽与第 16 槽正好跨过 chart 色板边界）",
            tokens.categoryDot(categorySlotOf(left)),
            tokens.categoryDot(categorySlotOf(right)),
        )
    }

    @Test
    fun `全部相邻支出分类两两不重色`() {
        expense.zipWithNext().forEach { (a, b) ->
            assertNotEquals(
                "相邻支出分类 [$a] 与 [$b] 不应重色",
                tokens.categoryDot(categorySlotOf(a)),
                tokens.categoryDot(categorySlotOf(b)),
            )
        }
    }

    @Test
    fun `同一分类名多次取槽位与颜色恒定`() {
        listOf("餐饮", "出行", "薪资", "宠物", "自定义分类").forEach { name ->
            assertEquals("$name 槽位应恒定", categorySlotOf(name), categorySlotOf(name))
            assertEquals(
                "$name 颜色应恒定",
                tokens.categoryDot(categorySlotOf(name)),
                tokens.categoryDot(categorySlotOf(name)),
            )
        }
    }

    @Test
    fun `预设之外的分类名按稳定哈希落在 0 到 15 且互相有区分度`() {
        val names = listOf("宠物", "装修", "健身", "订阅", "旅行", "数码", "育儿", "捐赠")
        names.forEach { name ->
            assertTrue("[$name] 不应命中预设分类（否则本条测的不是哈希兜底）", name !in expense && name !in income)
        }

        val slots = names.map { categorySlotOf(it) }
        assertTrue("自定义分类槽位应落在 0..15: $slots", slots.all { it in 0..15 })
        assertTrue("不同分类名应有区分度: $slots", slots.toSet().size >= 4)
    }

    @Test
    fun `空串与单字符分类名不抛异常且落在合法槽位`() {
        assertEquals("空串哈希为 0，落到第一槽", 0, categorySlotOf(""))
        listOf(" ", "a", "🎁").forEach { name ->
            assertTrue("非法名 [$name]", categorySlotOf(name) in 0..15)
        }
    }

    @Test
    fun `颜色按槽位取 chart 色阶且十六个槽位颜色互不相同`() {
        assertEquals("分类色板应为 16 槽（chart-1..chart-16）", 16, tokens.chartRamp.size)
        (0 until tokens.chartRamp.size).forEach { slot ->
            assertEquals("第 $slot 槽应取 chart-${slot + 1}", tokens.chartRamp[slot], tokens.categoryDot(slot))
        }
        assertEquals("16 槽颜色应互不相同", 16, tokens.chartRamp.toSet().size)
    }

    @Test
    fun `分类色板跨主题固定`() {
        val apple = tokens.chartRamp
        listOf("notion", "bento").forEach { themeId ->
            assertEquals("主题 [$themeId] 下分类色板应与 apple 一致", apple, DesignTokens(themeId).chartRamp)
        }
    }

    @Test
    fun `槽位越界按色阶长度循环`() {
        assertEquals(tokens.categoryDot(0), tokens.categoryDot(16))
        assertEquals(tokens.categoryDot(1), tokens.categoryDot(17))
        assertEquals("负槽位也按长度循环", tokens.categoryDot(15), tokens.categoryDot(-1))
    }
}
