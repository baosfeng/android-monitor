package com.bsfeng.monitor.finance.ui

import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.theme.chartRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类色映射（纯 JVM）：[categoryColorOf] = [categorySlotOf] → [categoryDot]。
 *
 * [categoryColorOf] 是 `@Composable` 的取色包装，本项目没有 Compose 测试依赖（零第三方业务依赖），
 * 单测覆盖它背后的两层纯函数：内置分类固定槽位、其余名称稳定哈希、颜色按槽位取 chart 色阶。
 */
class FinanceCategorySlotTest {

    private val tokens = DesignTokens("apple")

    @Test
    fun `原型六个内置分类固定槽位`() {
        assertEquals(0, categorySlotOf("餐饮"))
        assertEquals(1, categorySlotOf("交通"))
        assertEquals(2, categorySlotOf("购物"))
        assertEquals(3, categorySlotOf("娱乐"))
        assertEquals(4, categorySlotOf("居住"))
        assertEquals(5, categorySlotOf("医疗"))
    }

    @Test
    fun `同一分类名多次取槽位与颜色恒定`() {
        listOf("餐饮", "宠物", "还款", "自定义分类").forEach { name ->
            assertEquals("$name 槽位应恒定", categorySlotOf(name), categorySlotOf(name))
            assertEquals(
                "$name 颜色应恒定",
                tokens.categoryDot(categorySlotOf(name)),
                tokens.categoryDot(categorySlotOf(name)),
            )
        }
    }

    @Test
    fun `未内置分类名按稳定哈希落在 0 到 7 且互相有区分度`() {
        val names = listOf("宠物", "装修", "健身", "订阅", "旅行", "数码", "育儿", "捐赠")
        val slots = names.map { categorySlotOf(it) }

        assertTrue("槽位应落在 0..7: $slots", slots.all { it in 0..7 })
        assertTrue("不同分类名应有区分度: $slots", slots.toSet().size >= 4)
    }

    @Test
    fun `空串与单字符分类名不抛异常且落在合法槽位`() {
        assertEquals("空串哈希为 0，落到第一槽", 0, categorySlotOf(""))
        listOf(" ", "a", "🎁").forEach { name ->
            assertTrue("非法名 [$name]", categorySlotOf(name) in 0..7)
        }
    }

    @Test
    fun `颜色按槽位取 chart 色阶且八个槽位颜色互不相同`() {
        assertEquals(8, tokens.chartRamp.size)
        (0..7).forEach { slot -> assertEquals(tokens.chartRamp[slot], tokens.categoryDot(slot)) }
        assertEquals("8 槽颜色应互不相同", 8, tokens.chartRamp.toSet().size)
    }

    @Test
    fun `槽位越界按色阶长度循环`() {
        assertEquals(tokens.categoryDot(0), tokens.categoryDot(8))
        assertEquals(tokens.categoryDot(1), tokens.categoryDot(9))
    }
}
