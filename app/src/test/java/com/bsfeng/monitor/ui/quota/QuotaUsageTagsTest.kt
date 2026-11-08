package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量卡的「变体 / 范围 → 锚点」映射。
 *
 * 这张表原先写在 @Composable 文件里、被覆盖率排除，改错一个锚点只会让运行时审计
 * 与锚点账本对不上，没有任何用例变红（基建审计 I4）。这里把三套锚点逐字钉死：
 * 锚点是原型与账本的唯一对账键，改字就是改契约。
 */
class QuotaUsageTagsTest {

    private val variants = UsageCardVariant.entries
    private val ranges = QuotaRange.entries

    @Test
    fun `卡片锚点按变体逐字对应账本`() {
        assertEquals("s18-ollama-usage", cardTag(UsageCardVariant.OLLAMA_USD))
        assertEquals("s18-legacy-usage", cardTag(UsageCardVariant.OLLAMA_LEGACY))
        assertEquals("s18-deepseek-usage", cardTag(UsageCardVariant.DEEPSEEK))
    }

    @Test
    fun `范围文案锚点按变体逐字对应账本`() {
        assertEquals("s18-usage-range-label", rangeLabelTag(UsageCardVariant.OLLAMA_USD))
        assertEquals("s18-legacy-range-label", rangeLabelTag(UsageCardVariant.OLLAMA_LEGACY))
        assertEquals("s18-ds-range-label", rangeLabelTag(UsageCardVariant.DEEPSEEK))
    }

    @Test
    fun `范围项锚点覆盖 3 个变体各 3 个范围`() {
        assertEquals("s18-range-24", rangeTag(UsageCardVariant.OLLAMA_USD, QuotaRange.LAST_24_HOURS))
        assertEquals("s18-range-7", rangeTag(UsageCardVariant.OLLAMA_USD, QuotaRange.LAST_7_DAYS))
        assertEquals("s18-range-30", rangeTag(UsageCardVariant.OLLAMA_USD, QuotaRange.LAST_30_DAYS))
        assertEquals("s18-legacy-range-24", rangeTag(UsageCardVariant.OLLAMA_LEGACY, QuotaRange.LAST_24_HOURS))
        assertEquals("s18-legacy-range-7", rangeTag(UsageCardVariant.OLLAMA_LEGACY, QuotaRange.LAST_7_DAYS))
        assertEquals("s18-legacy-range-30", rangeTag(UsageCardVariant.OLLAMA_LEGACY, QuotaRange.LAST_30_DAYS))
        assertEquals("s18-ds-range-24", rangeTag(UsageCardVariant.DEEPSEEK, QuotaRange.LAST_24_HOURS))
        assertEquals("s18-ds-range-7", rangeTag(UsageCardVariant.DEEPSEEK, QuotaRange.LAST_7_DAYS))
        assertEquals("s18-ds-range-30", rangeTag(UsageCardVariant.DEEPSEEK, QuotaRange.LAST_30_DAYS))
    }

    @Test
    fun `按模型锚点覆盖两个有明细的变体`() {
        assertEquals("s18-models-24", modelsTag(UsageCardVariant.OLLAMA_USD, QuotaRange.LAST_24_HOURS))
        assertEquals("s18-models-7", modelsTag(UsageCardVariant.OLLAMA_USD, QuotaRange.LAST_7_DAYS))
        assertEquals("s18-models-30", modelsTag(UsageCardVariant.OLLAMA_USD, QuotaRange.LAST_30_DAYS))
        assertEquals("s18-ds-models-24", modelsTag(UsageCardVariant.DEEPSEEK, QuotaRange.LAST_24_HOURS))
        assertEquals("s18-ds-models-7", modelsTag(UsageCardVariant.DEEPSEEK, QuotaRange.LAST_7_DAYS))
        assertEquals("s18-ds-models-30", modelsTag(UsageCardVariant.DEEPSEEK, QuotaRange.LAST_30_DAYS))
    }

    @Test
    fun `旧版计划没有按模型明细因此不给锚点`() {
        // 给空串会让语义树里多出一个「已登记」的空标识，掩盖「这块本来没有明细」
        ranges.forEach { range ->
            assertNull("旧版计划 $range 不该有按模型锚点", modelsTag(UsageCardVariant.OLLAMA_LEGACY, range))
        }
    }

    @Test
    fun `同一变体下不同范围的锚点互不相同`() {
        // 锚点撞车时账本对账会少一条，运行时审计也分不清是哪一项
        variants.filter { it != UsageCardVariant.OLLAMA_LEGACY }.forEach { variant ->
            val tags = ranges.map { range -> rangeTag(variant, range) }
            assertEquals("$variant 的范围锚点必须唯一：$tags", tags.size, tags.toSet().size)
        }
    }

    @Test
    fun `卡片锚点与范围锚点不撞车`() {
        val cardTags = variants.map { cardTag(it) }
        val rangeTags = variants.flatMap { v -> ranges.map { rangeTag(v, it) } }
        assertTrue("卡片锚点与范围锚点不得重复", cardTags.intersect(rangeTags.toSet()).isEmpty())
    }
}
