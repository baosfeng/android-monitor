package com.bsfeng.monitor.finance.ui

import com.bsfeng.monitor.ui.components.FiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 回归（审计 #62 / #63 / #64 / #67）：保存校验的分流、按钮可用态、边界演示态样本。
 *
 * 三条口径锁在同一处：
 * - [RecordForm.saveCheck]：正常 null / 边界金额 0 与空 / 异常转账同账户与缺分类；
 * - [RecordForm.saveButtonEnabled]：金额无效时按钮仍可点，结构性缺失与提交中才禁用；
 * - [EDGE_REMARK_SAMPLE]：边界演示的备注正好顶满 200 字上限。
 */
class SaveValidationFeedbackTest {

    private fun check(
        type: Int = RecordForm.TYPE_EXPENSE,
        amount: String,
        category: String = "餐饮",
        account: String = "微信支付",
        toAccount: String = "",
    ): SaveBlock? = RecordForm.saveCheck(type, amount, category, account, toAccount)

    // ===== saveCheck：三条路径 =====

    @Test
    fun `合法表单没有阻塞原因`() {
        assertNull(check(amount = "23.9"))
        assertNull(check(RecordForm.TYPE_INCOME, amount = "8800", category = "薪资"))
        assertNull(check(RecordForm.TYPE_TRANSFER, amount = "500", category = "", toAccount = "现金"))
    }

    @Test
    fun `金额为空为 0 或非数字都归到金额一类`() {
        assertEquals(SaveBlock.AMOUNT, check(amount = ""))
        assertEquals(SaveBlock.AMOUNT, check(amount = "0"))
        assertEquals(SaveBlock.AMOUNT, check(amount = "0.00"))
        assertEquals("非数字按 0 处理", SaveBlock.AMOUNT, check(amount = "abc"))
        // 转账也先拦金额
        assertEquals(SaveBlock.AMOUNT, check(RecordForm.TYPE_TRANSFER, amount = "0", toAccount = "现金"))
    }

    @Test
    fun `分类未选归到分类一类`() {
        assertEquals(SaveBlock.CATEGORY, check(amount = "23.9", category = ""))
        assertEquals(SaveBlock.CATEGORY, check(amount = "23.9", category = "   "))
    }

    @Test
    fun `转账缺账户或同账户归到账户一类`() {
        assertEquals(
            SaveBlock.TRANSFER_ACCOUNTS,
            check(RecordForm.TYPE_TRANSFER, amount = "500", toAccount = ""),
        )
        assertEquals(
            "转出转入同一账户不算转账",
            SaveBlock.TRANSFER_ACCOUNTS,
            check(RecordForm.TYPE_TRANSFER, amount = "500", toAccount = "微信支付"),
        )
    }

    @Test
    fun `校验顺序先金额后结构`() {
        // 金额与分类同时不合法时先报金额（原型点保存先拦金额）
        assertEquals(SaveBlock.AMOUNT, check(amount = "0", category = ""))
        assertEquals(SaveBlock.AMOUNT, check(RecordForm.TYPE_TRANSFER, amount = "", toAccount = ""))
    }

    // ===== canSave 与 saveCheck 同一口径 =====

    @Test
    fun `canSave 恒等于 saveCheck 通过`() {
        val cases = listOf(
            Triple(RecordForm.TYPE_EXPENSE, "23.9", "餐饮"),
            Triple(RecordForm.TYPE_EXPENSE, "0", "餐饮"),
            Triple(RecordForm.TYPE_EXPENSE, "23.9", ""),
            Triple(RecordForm.TYPE_EXPENSE, "", ""),
            Triple(RecordForm.TYPE_INCOME, "8800", "薪资"),
            Triple(RecordForm.TYPE_TRANSFER, "500", ""),
        )
        cases.forEach { (type, amount, category) ->
            val block = RecordForm.saveCheck(type, amount, category, "微信支付", "现金")
            assertEquals(
                "canSave 与 saveCheck 必须同一口径：$type/$amount/$category",
                block == null,
                RecordForm.canSave(type, amount, category, "微信支付", "现金"),
            )
        }
    }

    // ===== 按钮可用态：金额无效仍可点 =====

    @Test
    fun `金额无效时保存按钮仍可点`() {
        assertTrue(
            "金额错误要给抖动 + toast，按钮不能先变灰",
            RecordForm.saveButtonEnabled(SaveBlock.AMOUNT, submitting = false),
        )
    }

    @Test
    fun `结构性缺失时按钮禁用`() {
        assertFalse(RecordForm.saveButtonEnabled(SaveBlock.CATEGORY, submitting = false))
        assertFalse(RecordForm.saveButtonEnabled(SaveBlock.TRANSFER_ACCOUNTS, submitting = false))
    }

    @Test
    fun `提交中一律禁用`() {
        assertFalse(RecordForm.saveButtonEnabled(null, submitting = true))
        assertFalse(RecordForm.saveButtonEnabled(SaveBlock.AMOUNT, submitting = true))
        assertFalse(RecordForm.saveButtonEnabled(SaveBlock.CATEGORY, submitting = true))
    }

    @Test
    fun `校验通过且未提交时可点`() {
        assertTrue(RecordForm.saveButtonEnabled(null, submitting = false))
    }

    // ===== 反馈文案与边界演示样本 =====

    @Test
    fun `金额提示逐字对齐原型`() {
        assertEquals("请输入大于 0 的金额", SAVE_AMOUNT_HINT)
    }

    @Test
    fun `边界演示备注正好顶满 200 字上限`() {
        assertEquals(REMARK_MAX_LEN, EDGE_REMARK_SAMPLE.length)
        assertTrue("演示的是超长备注，不是一句话", EDGE_REMARK_SAMPLE.length >= 100)
    }

    @Test
    fun `边界演示样本足够极端`() {
        assertTrue("超长分类名要能验证换行不裁切", EDGE_LONG_CATEGORY.length >= 10)
        assertTrue(EDGE_AMOUNT_SAMPLE.length >= 10)
        assertTrue("说明行要与实现的保存口径一致", EDGE_SAVE_HINT.contains("大于 0"))
    }

    @Test
    fun `派生状态永不产出边界态`() {
        // EDGE 只能由 DEBUG 五态条强制；真实数据路径（加载 / 空 / 错 / 正常）都不产 EDGE
        val states = listOf(
            addTransactionState(loading = true, loadFailed = false, categoryCount = 0, accountCount = 0),
            addTransactionState(loading = false, loadFailed = true, categoryCount = 0, accountCount = 0),
            addTransactionState(loading = false, loadFailed = false, categoryCount = 0, accountCount = 0),
            addTransactionState(loading = false, loadFailed = false, categoryCount = 6, accountCount = 2),
            addTransactionState(loading = true, loadFailed = false, categoryCount = 6, accountCount = 2),
            addTransactionState(loading = false, loadFailed = true, categoryCount = 6, accountCount = 2),
        )
        states.forEach { assertNotEquals(FiveState.EDGE, it) }
    }
}
