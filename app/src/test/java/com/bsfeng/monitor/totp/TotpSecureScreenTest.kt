package com.bsfeng.monitor.totp

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [TotpSecureScreen] 单元测试：FLAG_SECURE 的加与清。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpSecureScreenTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `开启后设置 FLAG_SECURE 关闭后清除`() {
        TotpSecureScreen.apply(activity, true)
        assertTrue(TotpSecureScreen.isEnabled(activity))

        TotpSecureScreen.apply(activity, false)
        assertFalse(TotpSecureScreen.isEnabled(activity))
    }

    @Test
    fun `重复开启是幂等的`() {
        TotpSecureScreen.apply(activity, true)
        TotpSecureScreen.apply(activity, true)

        assertTrue(TotpSecureScreen.isEnabled(activity))
    }
}
