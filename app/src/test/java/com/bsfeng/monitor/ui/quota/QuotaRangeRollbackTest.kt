package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.quota.rangeAfterUsageAttempt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s18 用量范围切换失败后的落点。
 *
 * 实测发现：先改 range 再拉用量，失败只写错误文案不回滚 —— 结果「近 30 天」的分段配着
 * 24 小时的数字。修法是把选中态绑到数据上：拉取失败就退回旧范围。
 */
class QuotaRangeRollbackTest {

    @Test
    fun `切换成功时采用新范围`() {
        assertEquals(
            QuotaRange.LAST_30_DAYS,
            rangeAfterUsageAttempt(
                previous = QuotaRange.LAST_24_HOURS,
                requested = QuotaRange.LAST_30_DAYS,
                succeeded = true,
            ),
        )
    }

    @Test
    fun `切换失败时回滚到旧范围`() {
        assertEquals(
            QuotaRange.LAST_24_HOURS,
            rangeAfterUsageAttempt(
                previous = QuotaRange.LAST_24_HOURS,
                requested = QuotaRange.LAST_30_DAYS,
                succeeded = false,
            ),
        )
    }

    @Test
    fun `从更大范围缩回去失败同样回滚`() {
        assertEquals(
            QuotaRange.LAST_30_DAYS,
            rangeAfterUsageAttempt(
                previous = QuotaRange.LAST_30_DAYS,
                requested = QuotaRange.LAST_7_DAYS,
                succeeded = false,
            ),
        )
    }
}
