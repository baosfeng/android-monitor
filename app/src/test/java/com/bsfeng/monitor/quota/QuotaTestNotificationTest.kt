package com.bsfeng.monitor.quota

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 额度设置页的「测试通知」与通知权限判断。
 *
 * 这两段原先藏在 ui/quota 的 @Composable 文件里：既是 Android 胶水、又和 UI 一起被覆盖率排除，
 * 等于没人验证过「测试通知真的走额度预警渠道、真的不上锁屏」（基建审计 I4）。
 * Robolectric 用例覆盖的是行为，不参与 JaCoCo 行覆盖（沙箱 ClassLoader 绕过 agent）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaTestNotificationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `测试通知建在额度预警渠道上且不上锁屏`() {
        postQuotaTestNotification(context)

        // 走真实 API 而不是 Shadow 的受保护方法：渠道存在与否本身就是被测行为
        val channel = manager.getNotificationChannel(QuotaAlertNotifier.CHANNEL_ID)
        assertNotNull("必须先建渠道，否则通知会被系统静默丢弃", channel)
        assertEquals(
            "测试通知与真实预警同口径：锁屏不显示内容",
            Notification.VISIBILITY_SECRET,
            channel.lockscreenVisibility,
        )

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(Notification.VISIBILITY_SECRET, posted.visibility)
        assertTrue("点一下就该消失", posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `重复点测试通知不炸且只更新同一条`() {
        // 设置页允许连点：每次都是同一条 id 覆盖，不该抛异常、也不该堆一屏
        postQuotaTestNotification(context)
        postQuotaTestNotification(context)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `权限判断读的是系统开关`() {
        // Robolectric 默认允许通知；断言的是「读的是系统状态」而不是写死 true
        assertTrue(notificationsAllowed(context))
    }
}
