package com.bsfeng.monitor.quota

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * 额度预警的**按实例**口径：通知 ID 派生与提醒状态隔离。
 *
 * 钉的是两件很容易漏的事：
 * 1. 通知 ID 若固定 7301，第二个账号的提醒会**覆盖**第一条（同 id 只留最新一条），
 *    另一个账号的低额度提醒等于没发；
 * 2. 状态键若按类型 id 拼，第二个账号会读到第一个账号的「已提醒档位」，同样不发生提醒。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaAlertNotifierInstanceTest {

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
    fun `不同账号派生出不同的通知 ID`() {
        val first = QuotaAlertNotifier.notificationIdOf("deepseek")
        val second = QuotaAlertNotifier.notificationIdOf("deepseek#2")

        assertNotEquals("两个账号不能共用一个通知槽位", first, second)
        assertTrue("ID 要在额度预警的区间内", first >= QuotaAlertNotifier.NOTIFICATION_ID)
        assertTrue(second >= QuotaAlertNotifier.NOTIFICATION_ID)
    }

    @Test
    fun `同一账号每次派生出同一个通知 ID`() {
        // 同账号多档提醒必须合并成一条，所以 ID 要稳定
        assertEquals(
            QuotaAlertNotifier.notificationIdOf("ollama#3"),
            QuotaAlertNotifier.notificationIdOf("ollama#3"),
        )
    }

    @Test
    fun `同类型两个账号各自发出一条通知而不是互相覆盖`() {
        val notifier = QuotaAlertNotifier(context)
        notifier.enabled = true

        val firstSent = notifier.handle("ollama", "Ollama · 账号 1", criticalItems())
        val secondSent = notifier.handle("ollama#2", "Ollama · 账号 2", criticalItems())

        assertTrue("第一个账号要发提醒", firstSent > 0)
        assertTrue("第二个账号要发提醒（状态键按实例隔离）", secondSent > 0)

        val posted = shadowOf(manager).allNotifications
        assertEquals("两个账号各占一条通知", 2, posted.size)
        val ids = shadowOf(manager).allNotifications.fold(mutableSetOf<Int>()) { acc, _ -> acc }
        assertTrue("通知必须都挂在额度预警渠道", posted.all { it.channelId == QuotaAlertNotifier.CHANNEL_ID })
        assertTrue("内容不在锁屏显示", posted.all { it.visibility == Notification.VISIBILITY_SECRET })
    }

    @Test
    fun `同一账号重复评估不重复发通知`() {
        val notifier = QuotaAlertNotifier(context)
        notifier.enabled = true

        val before = shadowOf(manager).allNotifications.size
        notifier.handle("ollama", "Ollama", criticalItems())
        val afterFirst = shadowOf(manager).allNotifications.size
        val repeated = notifier.handle("ollama", "Ollama", criticalItems())

        assertEquals("第一次要发出提醒", before + 1, afterFirst)
        assertEquals("同一周期同一档不重复提醒", 0, repeated)
        assertEquals(afterFirst, shadowOf(manager).allNotifications.size)
    }
}
