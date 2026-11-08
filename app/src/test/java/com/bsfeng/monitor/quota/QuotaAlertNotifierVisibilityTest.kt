package com.bsfeng.monitor.quota

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * 额度预警通知的**锁屏密级**（安全审计 S-05）。
 *
 * 为什么必须钉住：通知正文写的是「$providerName · 会话额度即将用尽 / 剩余 3%」，
 * 它等于把「用户在用什么 AI 服务、还剩多少额度」直接印在锁屏上 —— 锁屏是最容易被旁人看到的一屏，
 * 与同项目 [com.bsfeng.monitor.service.LocationForegroundService] /
 * [com.bsfeng.monitor.transfer.service.TransferService] 的 VISIBILITY_SECRET 口径也不一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaAlertNotifierVisibilityTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 造一条必然命中 1% 档的额度项。 */
    private fun criticalItems() = listOf(
        QuotaItem(
            id = "session",
            title = "会话",
            kind = QuotaKind.PERCENT,
            remaining = BigDecimal("1"),
            total = BigDecimal("100"),
            unit = QuotaUnit.PERCENT,
            primary = true,
        ),
    )

    @Test
    fun `命中的提醒在锁屏上不可见`() {
        val notifier = QuotaAlertNotifier(context)
        notifier.enabled = true

        val sent = notifier.handle("demo", "示例服务", criticalItems())
        assertTrue("用例前提：必须真的发出提醒，否则断言空转", sent > 0)

        val posted = shadowOf(manager).allNotifications
        assertNotNull("没有发出通知", posted)
        assertTrue("没有发出通知", posted.isNotEmpty())
        val notification = posted.last()
        assertEquals(
            "通知必须 VISIBILITY_SECRET（锁屏不显示内容）",
            Notification.VISIBILITY_SECRET,
            notification.visibility,
        )
    }

    @Test
    fun `通知渠道同样禁止锁屏预览`() {
        QuotaAlertNotifier(context).handle("demo", "示例服务", criticalItems())

        val channel = manager.getNotificationChannel(QuotaAlertNotifier.CHANNEL_ID)
        assertNotNull("渠道没建出来：" + QuotaAlertNotifier.CHANNEL_ID, channel)
        assertEquals(
            "渠道 lockscreenVisibility 必须 SECRET（新建时定死，之后改不了）",
            Notification.VISIBILITY_SECRET,
            channel.lockscreenVisibility,
        )
    }

    @Test
    fun `旧渠道被清掉_避免设置页出现两个同名项`() {
        // 模拟老版本留下的渠道
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                QuotaAlertNotifier.LEGACY_CHANNEL_ID,
                QuotaAlertNotifier.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        assertNotNull("用例前提：旧渠道要先能建出来", manager.getNotificationChannel(QuotaAlertNotifier.LEGACY_CHANNEL_ID))

        QuotaAlertNotifier(context).handle("demo", "示例服务", criticalItems())

        assertEquals(
            "旧渠道应被删除：留着它只会在设置里多出一个能上锁屏的同名渠道",
            null,
            manager.getNotificationChannel(QuotaAlertNotifier.LEGACY_CHANNEL_ID),
        )
    }

    @Test
    fun `通知正文仍保留额度信息_只是不上锁屏`() {
        QuotaAlertNotifier(context).handle("demo", "示例服务", criticalItems())

        val notification = shadowOf(manager).allNotifications.last()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        // 锁屏隐藏 ≠ 内容删减：解锁后仍要能一眼看出是哪家、剩多少
        assertTrue("正文丢了额度信息：" + text, text.isNotEmpty())
    }
}
