package com.bsfeng.monitor.quota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.bsfeng.monitor.domain.quota.QuotaAlertEvaluator
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaUnit
import org.json.JSONObject
import java.math.BigDecimal

/**
 * 额度阈值提醒 —— 把「评估结果」发成系统通知，并把已提醒状态持久化。
 *
 * 阈值 5 / 4 / 3 / 2 / 1 %：每条额度项（内含额度 / 会话 / 每周）独立计数，
 * 同一周期同一档只提醒一次；余额回升或周期重置后可再次提醒。
 * 余额型（金额且无总额）走「低于设定金额提醒一次」的单档。
 */
class QuotaAlertNotifier(
    private val context: Context,
    private val openTabIntent: (() -> Intent)? = null,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 通知总开关（与额度设置的开关联动，关闭时只刷新不评估）。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 低余额阈值（各币种；默认 CNY 50）。 */
    fun lowBalanceThreshold(unit: QuotaUnit): BigDecimal? = when (unit) {
        QuotaUnit.CNY -> BigDecimal(prefs.getString(KEY_LOW_CNY, "50") ?: "50")
        QuotaUnit.USD -> BigDecimal(prefs.getString(KEY_LOW_USD, "5") ?: "5")
        else -> null
    }

    /** 评估一次快照并发送命中的提醒；返回本次发出的条数（供日志与测试断言）。 */
    fun handle(
        providerId: String,
        providerName: String,
        items: List<QuotaItem>,
    ): Int {
        if (!enabled) return 0
        val state = loadState()
        val thresholds = QuotaUnit.entries
            .mapNotNull { unit -> lowBalanceThreshold(unit)?.let { unit to it } }
            .toMap()
        val result = QuotaAlertEvaluator.evaluate(
            providerId = providerId,
            items = items,
            state = state,
            lowBalanceThresholds = thresholds,
        )
        if (result.state != state) saveState(result.state)
        if (result.alerts.isEmpty()) return 0

        // 同一次刷新多条命中合并成一条，避免连发轰炸
        val first = result.alerts.first()
        val unitOf = items.associate { it.id to it.unit }
        val title = if (result.alerts.size == 1) {
            first.title(providerName)
        } else {
            "$providerName · ${result.alerts.size} 项额度偏低"
        }
        val body = result.alerts.joinToString("；") { alert ->
            alert.bodyText(unitOf[alert.key.substringAfterLast('.')] ?: QuotaUnit.PERCENT)
        }
        post(title, body)
        return result.alerts.size
    }

    // MARK: - 通知

    /**
     * 渠道属性（可见性 / 重要级）**创建后不可改**：旧渠道 `quota_alert` 已按默认可见性建过，
     * 只在这条上加 visibility 对老用户不生效。所以换新 id 重建，并显式删掉旧渠道 ——
     * 留着它只会让系统设置里多出一个「能上锁屏」的同名渠道，用户改的其实是无效的那个。
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = CHANNEL_DESCRIPTION
            // 锁屏不显示任何内容：额度名 / 剩余百分比本身就是敏感的用量信息
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
    }

    private fun post(title: String, body: String) {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            // 与 LocationForegroundService / TransferService 同口径：锁屏上不露任何内容
            .setVisibility(Notification.VISIBILITY_SECRET)
        openTabIntent?.invoke()?.let { intent ->
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pending)
        }
        runCatching {
            manager.notify(NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w(TAG, "额度提醒发送失败：${it.javaClass.simpleName}") }
    }

    // MARK: - 状态持久化

    private fun loadState(): Map<String, QuotaAlertEvaluator.AlertState> {
        val raw = prefs.getString(KEY_STATE, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associate { key ->
                val obj = root.optJSONObject(key) ?: JSONObject()
                key to QuotaAlertEvaluator.AlertState(
                    cycleId = if (obj.has("cycle")) obj.optLong("cycle") else null,
                    notifiedThreshold = obj.optInt("threshold", 0),
                    lowBalanceNotified = obj.optBoolean("low", false),
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveState(state: Map<String, QuotaAlertEvaluator.AlertState>) {
        val root = JSONObject()
        state.forEach { (key, value) ->
            root.put(
                key,
                JSONObject().apply {
                    value.cycleId?.let { put("cycle", it) }
                    put("threshold", value.notifiedThreshold)
                    put("low", value.lowBalanceNotified)
                },
            )
        }
        prefs.edit { putString(KEY_STATE, root.toString()) }
    }

    companion object {
        private const val TAG = "QuotaAlertNotifier"
        const val PREFS_NAME = "quota_alerts"
        const val KEY_STATE = "alert_state"
        const val KEY_ENABLED = "alerts_enabled"
        const val KEY_LOW_CNY = "low_balance_cny"
        const val KEY_LOW_USD = "low_balance_usd"

        /** 渠道 id 带版本后缀：渠道属性创建后不可改，改可见性只能换 id 重建（见 [ensureChannel]）。 */
        const val CHANNEL_ID = "quota_alert_v2"

        /** 渠道展示名：设置页的「测试通知」与真实提醒共用同一条渠道。 */
        const val CHANNEL_NAME = "额度预警"

        /** 渠道说明文案。 */
        const val CHANNEL_DESCRIPTION = "额度剩余不足时提醒"

        /** 旧渠道（无 lockscreenVisibility）：建新渠道时一并删除，避免设置页出现两个同名项。 */
        const val LEGACY_CHANNEL_ID = "quota_alert"
        const val NOTIFICATION_ID = 7301
    }
}
