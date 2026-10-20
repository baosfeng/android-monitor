package com.bsfeng.monitor.finance

import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.ui.TransactionSourceBadge
import com.bsfeng.monitor.finance.ui.recognizedTopName
import com.bsfeng.monitor.autobook.AutoBookDebugDraft
import com.bsfeng.monitor.autobook.AutoBookDraft
import com.bsfeng.monitor.autobook.AutoBookRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动记账对齐原型的三处新逻辑（纯逻辑，JVM 单测）：
 * 1. 「自动」徽标的来源判定（只认自动记账 / 导入行）；
 * 2. 浮窗识别项自动展开：识别名 → 它的一级（识别项所在面板展开）；
 * 3. debug 草稿构造（模拟器验证浮窗用）。
 */
class AutoBookPrototypeAlignmentTest {

    // ── 1. 「自动」徽标 ───────────────────────────────────────────────

    @Test
    fun `自动记账来源显示徽标`() {
        assertTrue(TransactionSourceBadge.isAutoBooked("auto_alipay", "CFP0001"))
        assertTrue(TransactionSourceBadge.isAutoBooked("auto_wechat", "4200"))
    }

    @Test
    fun `导入行按 sourceId 判定为自动`() {
        assertTrue(TransactionSourceBadge.isAutoBooked("panda_import", "panda-42"))
    }

    @Test
    fun `手写记录不显示徽标`() {
        assertFalse(TransactionSourceBadge.isAutoBooked("manual", ""))
        // 边界：手动来源即使带了偶发 sourceId 也不算自动记账
        assertFalse(TransactionSourceBadge.isAutoBooked("manual", "x"))
        assertFalse(TransactionSourceBadge.isAutoBooked("", ""))
    }

    @Test
    fun `徽标文案与来源前缀固定`() {
        assertEquals("自动", TransactionSourceBadge.AUTO_LABEL)
        assertEquals("auto_", TransactionSourceBadge.AUTO_SOURCE_PREFIX)
    }

    // ── 2. 识别项自动展开（CategoryPicker 的一级定位） ────────────────

    private val tree = CategoryTree(
        listOf(
            Category(id = 1, name = "餐饮", parentId = -1, type = 0, sortOrder = 1),
            Category(id = 2, name = "外卖", parentId = 1, type = 0, sortOrder = 1),
            Category(id = 3, name = "早餐", parentId = 1, type = 0, sortOrder = 2),
            Category(id = 4, name = "居住", parentId = -1, type = 0, sortOrder = 2),
            Category(id = 5, name = "家政", parentId = 4, type = 0, sortOrder = 1),
            Category(id = 6, name = "工资", parentId = -1, type = 1, sortOrder = 1),
        )
    )

    @Test
    fun `识别二级名定位到它的一级`() {
        assertEquals("居住", recognizedTopName(tree, 0, "家政"))
    }

    @Test
    fun `识别一级名定位到自身`() {
        assertEquals("居住", recognizedTopName(tree, 0, "居住"))
    }

    @Test
    fun `没有识别或名字不在树里时折叠`() {
        assertEquals("", recognizedTopName(tree, 0, ""))
        assertEquals("", recognizedTopName(tree, 0, "不存在的分类"))
    }

    @Test
    fun `识别项不属于当前类型时折叠`() {
        // 「家政」是支出分类，切到收入（type=1）后不该自动展开任何面板
        assertEquals("", recognizedTopName(tree, 1, "家政"))
        assertEquals("工资", recognizedTopName(tree, 1, "工资"))
    }

    // ── 3. debug 草稿（模拟器验证浮窗） ──────────────────────────────

    @Test
    fun `调试草稿按原型示例构造`() {
        val draft: AutoBookDraft = AutoBookDebugDraft.create(nowMs = 1_700_000_000_000L)
        assertEquals(AutoBookDebugDraft.MERCHANT, draft.merchant)
        assertEquals("好慷在家", draft.merchant)
        assertEquals(128.00, draft.amount, 0.001)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
        assertEquals("家政", draft.categoryName)
        assertEquals("居住", tree.topLevelNameOf(draft.categoryName))
        assertEquals("", draft.remark())
        assertEquals(AutoBookRules.CHANNEL_ALIPAY, draft.channel)
        assertEquals("支付宝", draft.channelLabel)
        assertTrue(draft.timeText.isNotBlank())
    }

    @Test
    fun `调试草稿每次 sourceId 不同且非空`() {
        val a = AutoBookDebugDraft.create(nowMs = 1L)
        val b = AutoBookDebugDraft.create(nowMs = 2L)
        assertTrue(a.sourceId.isNotBlank())
        assertTrue(a.sourceId != b.sourceId)
        // 去重键留空：调试浮窗不写短期去重表，连续点两次仍能复现
        assertTrue(a.dedupKeys.isEmpty())
    }
}
