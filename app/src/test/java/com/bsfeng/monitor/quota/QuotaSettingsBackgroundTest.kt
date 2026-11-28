package com.bsfeng.monitor.quota

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 后台刷新「小时 → 分钟」迁移与息屏策略的单测。
 *
 * 迁移写错不会崩，只会让老用户升级后从「每 6 小时」变成「每小时」或反过来；
 * 这里把旧键的 0/1/3/6 与「新键优先」逐条钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaSettingsBackgroundTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("quota_settings", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun default_is_fifteen_minutes() {
        assertEquals(15, QuotaSettings(context).backgroundRefreshMinutes)
        // 旧名是同一条偏好的委托（ViewModel 未改）
        assertEquals(15, QuotaSettings(context).refreshIntervalHours)
    }

    @Test
    fun legacy_hours_migrate_to_minutes_and_zero_stays_off() {
        prefs.edit().putInt("refresh_interval_hours", 1).commit()
        assertEquals(60, QuotaSettings(context).backgroundRefreshMinutes)
        prefs.edit().putInt("refresh_interval_hours", 3).commit()
        assertEquals(60, QuotaSettings(context).backgroundRefreshMinutes)
        prefs.edit().putInt("refresh_interval_hours", 6).commit()
        assertEquals(60, QuotaSettings(context).backgroundRefreshMinutes)
        prefs.edit().putInt("refresh_interval_hours", 0).commit()
        assertEquals(0, QuotaSettings(context).backgroundRefreshMinutes)
    }

    @Test
    fun new_key_wins_over_legacy_value() {
        prefs.edit().putInt("refresh_interval_hours", 6).putInt("refresh_interval_minutes", 30).commit()
        assertEquals(30, QuotaSettings(context).backgroundRefreshMinutes)
    }

    @Test
    fun setting_persists_and_clamps_to_range() {
        val settings = QuotaSettings(context)
        settings.backgroundRefreshMinutes = 15
        assertEquals(15, QuotaSettings(context).backgroundRefreshMinutes)
        settings.backgroundRefreshMinutes = 10_000
        assertEquals(60, QuotaSettings(context).backgroundRefreshMinutes)
        settings.backgroundRefreshMinutes = 0
        assertEquals(0, QuotaSettings(context).backgroundRefreshMinutes)
    }

    @Test
    fun skip_when_screen_off_defaults_true_and_persists() {
        val settings = QuotaSettings(context)
        assertTrue(settings.skipRefreshWhenScreenOff)
        settings.skipRefreshWhenScreenOff = false
        assertFalse(QuotaSettings(context).skipRefreshWhenScreenOff)
    }

    @Test
    fun option_order_and_labels_follow_prototype() {
        assertEquals(listOf(5, 15, 30, 60, 0), QuotaSettings.REFRESH_OPTIONS)
        assertEquals("5 分钟", QuotaSettings.chipLabelOf(5))
        assertEquals("1 小时", QuotaSettings.chipLabelOf(60))
        assertEquals("关闭", QuotaSettings.chipLabelOf(0))
        assertEquals("每 15 分钟", QuotaSettings.labelOf(15))
    }

    @Test
    fun screen_off_skip_is_fail_open() {
        assertTrue(shouldSkipBackgroundRefresh(skipWhenScreenOff = true, screenInteractive = false))
        assertFalse(shouldSkipBackgroundRefresh(skipWhenScreenOff = true, screenInteractive = true))
        assertFalse(shouldSkipBackgroundRefresh(skipWhenScreenOff = false, screenInteractive = false))
    }
}
