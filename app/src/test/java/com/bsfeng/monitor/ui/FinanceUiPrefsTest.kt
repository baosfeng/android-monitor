package com.bsfeng.monitor.ui

import com.bsfeng.monitor.ui.foundation.FinanceUiPrefs
import com.bsfeng.monitor.ui.foundation.FinanceUiStore
import com.bsfeng.monitor.ui.foundation.FinanceUiToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记账界面三开关：默认值、缺键回落、写入即生效、重复设置返回 false（设置页只据此决定要不要提示）。 */
class FinanceUiPrefsTest {

    /** 内存实现：Map + 已写集合，等同 SharedPreferences 的「缺键 = null」。 */
    private class FakeStore : FinanceUiStore {
        val written = HashMap<FinanceUiToggle, Boolean>()
        val reads = HashMap<FinanceUiToggle, Boolean?>()

        override fun read(toggle: FinanceUiToggle): Boolean? = reads[toggle]
        override fun write(toggle: FinanceUiToggle, enabled: Boolean) { written[toggle] = enabled }
    }

    @Test
    fun `默认值 = 预算与预警关、首页记账卡与多周期入口开`() {
        val store = FakeStore()
        FinanceUiPrefs.attachStore(store)
        FinanceUiToggle.entries.forEach { toggle ->
            assertEquals(toggle.title, 3, FinanceUiToggle.entries.size)
            FinanceUiPrefs.isEnabled(toggle).let { on ->
                if (toggle == FinanceUiToggle.BUDGET) assertFalse(toggle.storageKey, on)
                else assertTrue(toggle.storageKey, on)
            }
        }
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun `盘上有值时以盘上为准_缺键回落到默认值`() {
        val store = FakeStore().apply {
            reads[FinanceUiToggle.BUDGET] = true
            reads[FinanceUiToggle.HOME_CARD] = false
        }
        FinanceUiPrefs.attachStore(store)
        assertTrue(FinanceUiPrefs.isEnabled(FinanceUiToggle.BUDGET))
        assertFalse(FinanceUiPrefs.isEnabled(FinanceUiToggle.HOME_CARD))
        assertTrue(FinanceUiPrefs.isEnabled(FinanceUiToggle.PERIOD_BAR))
    }

    @Test
    fun `改写即落盘并立即生效_重复设置同一值返回 false`() {
        val store = FakeStore()
        FinanceUiPrefs.attachStore(store)
        assertTrue(FinanceUiPrefs.setEnabled(FinanceUiToggle.HOME_CARD, false))
        assertFalse(FinanceUiPrefs.isEnabled(FinanceUiToggle.HOME_CARD))
        assertEquals(false, store.written[FinanceUiToggle.HOME_CARD])
        assertFalse(FinanceUiPrefs.setEnabled(FinanceUiToggle.HOME_CARD, false))
        assertEquals(1, store.written.size)
    }

    /**
     * 恢复默认只落「真的变了」的那几项（值没变的开关不写盘）——
     * 与 setEnabled 的「重复设置返回 false」同一口径，避免无意义的 SharedPreferences 写入。
     */
    @Test
    fun `resetToDefault 把三个开关一起回默认_只对变化的项写盘`() {
        val store = FakeStore()
        FinanceUiPrefs.attachStore(store)
        FinanceUiPrefs.setEnabled(FinanceUiToggle.BUDGET, true)
        FinanceUiPrefs.setEnabled(FinanceUiToggle.PERIOD_BAR, false)
        FinanceUiPrefs.resetToDefault()
        FinanceUiToggle.entries.forEach { toggle ->
            assertEquals(toggle.storageKey, toggle.defaultOn, FinanceUiPrefs.isEnabled(toggle))
        }
        assertEquals(false, store.written[FinanceUiToggle.BUDGET])
        assertEquals(true, store.written[FinanceUiToggle.PERIOD_BAR])
        assertEquals(null, store.written[FinanceUiToggle.HOME_CARD])
    }

    @Test
    fun `defaults 与枚举默认值一致_供 VM 首帧使用`() {
        val defaults = FinanceUiPrefs.defaults()
        assertEquals(FinanceUiToggle.entries.toSet(), defaults.keys)
        assertFalse(defaults.getValue(FinanceUiToggle.BUDGET))
        assertTrue(defaults.getValue(FinanceUiToggle.HOME_CARD))
        assertTrue(defaults.getValue(FinanceUiToggle.PERIOD_BAR))
    }
}
