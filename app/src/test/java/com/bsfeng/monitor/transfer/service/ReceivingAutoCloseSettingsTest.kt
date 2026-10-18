package com.bsfeng.monitor.transfer.service

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「自动关闭接收」设置的持久化测试（Robolectric，走真实 SharedPreferences）。
 *
 * 口径：只存**时长**，默认关闭；进程重启（新建实例）后时长保留——计时起点不落盘，
 * 这一点由 [ReceivingAutoCloseTest] 的「进程重启后不恢复计时」钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceivingAutoCloseSettingsTest {

    @Before
    fun clearPrefs() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(TransferSettings.PREFS_NAME, 0)
            .edit()
            .clear()
            .commit()
    }

    private fun settings(): TransferSettings = TransferSettings(RuntimeEnvironment.getApplication())

    @Test
    fun `设置项默认关闭`() {
        val settings = settings()
        assertEquals(ReceivingAutoClose.OFF, settings.autoCloseMinutes)
        assertEquals(0, settings.autoCloseMinutes)
    }

    @Test
    fun `时长持久化并在新实例中保留`() {
        settings().autoCloseMinutes = 30
        // 新建实例 = 进程重启后重新读取设置：值保留
        assertEquals(30, settings().autoCloseMinutes)
    }

    @Test
    fun `非法时长回落为关闭`() {
        settings().autoCloseMinutes = 7
        assertEquals(ReceivingAutoClose.OFF, settings().autoCloseMinutes)
        settings().autoCloseMinutes = -1
        assertEquals(ReceivingAutoClose.OFF, settings().autoCloseMinutes)
    }
}
