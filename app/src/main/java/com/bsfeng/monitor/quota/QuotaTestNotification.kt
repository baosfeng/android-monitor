package com.bsfeng.monitor.quota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * 额度预警通道的「可用性判断」与「测试通知」。
 *
 * 为什么不在 ui/quota 里（基建审计 I4）：这两段是 Android 平台胶水（权限查询 + 通知发布），
 * 与 @Composable 编译进同一个 facade 类，JaCoCo 只能按类排除 → 整段逻辑既不进覆盖率分母、
 * 也无人测。搬到 [QuotaAlertNotifier] 同一个包后，它与渠道常量的归属也对上了。
 */
internal fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** 测试通知用独立 id，不覆盖真实的额度提醒（7301）。 */
private const val QUOTA_TEST_NOTIFICATION_ID = 7302

/**
 * 发送一条测试通知到系统的额度预警渠道（锚点 s19-test）。
 *
 * 只发通知、不写任何 prefs、不动预警计数；通知权限未允许时系统会静默丢弃，
 * 页面仍给出同一句 toast（用户可点「通知权限」行自查）。
 */
internal fun postQuotaTestNotification(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel(
                QuotaAlertNotifier.CHANNEL_ID,
                QuotaAlertNotifier.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = QuotaAlertNotifier.CHANNEL_DESCRIPTION
                // 与真实提醒同口径：测试通知也不许露在锁屏上
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, QuotaAlertNotifier.CHANNEL_ID)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
    }
    builder.setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("额度预警 · 测试通知")
        .setContentText("渠道正常：额度不足时会在这里提醒你。")
        .setAutoCancel(true)
        .setVisibility(Notification.VISIBILITY_SECRET)
    runCatching { manager.notify(QUOTA_TEST_NOTIFICATION_ID, builder.build()) }
}
