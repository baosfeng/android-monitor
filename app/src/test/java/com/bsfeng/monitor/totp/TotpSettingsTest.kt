package com.bsfeng.monitor.totp

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [TotpSettings] 单元测试：三项安全开关默认全开、可关闭并持久化、可复位。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpSettingsTest {

    private lateinit var context: Context
    private lateinit var settings: TotpSettings

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TotpSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = TotpSettings(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(TotpSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `默认三项全开`() {
        assertTrue(settings.screenshotProtectionEnabled)
        assertTrue(settings.clipboardAutoClearEnabled)
        assertTrue(settings.backgroundMaskEnabled)
    }

    @Test
    fun `关闭后重建实例仍保持关闭`() {
        settings.screenshotProtectionEnabled = false
        settings.clipboardAutoClearEnabled = false
        settings.backgroundMaskEnabled = false

        val reopened = TotpSettings(context)
        assertFalse(reopened.screenshotProtectionEnabled)
        assertFalse(reopened.clipboardAutoClearEnabled)
        assertFalse(reopened.backgroundMaskEnabled)
    }

    @Test
    fun `复位回默认全开`() {
        settings.backgroundMaskEnabled = false
        settings.resetToDefaults()

        assertTrue(settings.backgroundMaskEnabled)
        assertTrue(settings.screenshotProtectionEnabled)
        assertTrue(settings.clipboardAutoClearEnabled)
    }
}
