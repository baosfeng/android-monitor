package com.bsfeng.monitor.ui.quota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s18 前台定时器只跟当前选中家（第 4 批走查 P1-9）。
 *
 * 走查发现：设置页调别家的刷新间隔会把「当前选中家」的 refreshMinutes 一起改掉，
 * 额度页的前台定时器随即按别家的频率重启。修法是改谁只同步谁。
 */
class QuotaRefreshMinutesTest {

    @Test
    fun `改当前选中家的间隔才同步定时器`() {
        assertTrue(shouldSyncRefreshMinutes(changedProviderId = "ollama", selectedProviderId = "ollama"))
    }

    @Test
    fun `改别家的间隔不动当前家的定时器`() {
        assertFalse(shouldSyncRefreshMinutes(changedProviderId = "deepseek", selectedProviderId = "ollama"))
    }

    @Test
    fun `聚合视图下选中家为空时不误同步`() {
        assertFalse(shouldSyncRefreshMinutes(changedProviderId = "ollama", selectedProviderId = ""))
    }
}
