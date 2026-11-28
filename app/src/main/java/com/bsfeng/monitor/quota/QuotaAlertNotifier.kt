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
import com.bsfeng.monitor.ui.quota.QuotaAlertTriadStore
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.absoluteValue

/**
 * 额度阈值提醒 —— 把「评估结果」发成系统通知，并把已提醒状态持久化。
 *
 * 阈值 5 / 4 / 3 / 2 / 1 %：每条额度项（内含额度 / 会话 / 每周）独立计数，
 * 同一周期同一档只提醒一次；余额回升或周期重置后可再次提醒。
 * 余额型（金额且无总额）走「低于设定金额提醒一次」的单档。
 *
 * 「额度恢复时提醒」走同一条渠道、另一个通知 ID 槽位：恢复通知不能覆盖掉
 * 另一个窗口的低额度提醒（多窗口时两者可能同时成立）。
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

    /**
     * 「额度恢复时提醒」开关（原型 s19-sw-back）。
     *
     * 只在有真实消费点时才加：关闭后仍记录恢复事件（主屏三态要展示「已恢复」），
     * 只是不投递通知。
     */
    var recoverEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECOVER_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_RECOVER_ENABLED, value) }

    /** 恢复事件仓库：与主屏三态区共用同一份 prefs，见 [QuotaAlertTriadStore]。 */
    private val triadStore = QuotaAlertTriadStore(context)

    /** 低余额阈值（各币种；默认 CNY 50）。 */
    fun lowBalanceThreshold(unit: QuotaUnit): BigDecimal? = when (unit) {
        QuotaUnit.CNY -> BigDecimal(prefs.getString(KEY_LOW_CNY, "50") ?: "50")
        QuotaUnit.USD -> BigDecimal(prefs.getString(KEY_LOW_USD, "5") ?: "5")
        else -> null
    }

    /**
     * 评估一次快照并发送命中的提醒；返回本次发出的条数（供日志与测试断言）。
     *
     * @param instanceId 实例键：状态键与通知 ID 都由它稳定派生，同类型两个账号各占一个槽位
     * @param providerName 通知标题里的账号名（多账号时已带账号名，见 [QuotaInstances.displayName]）
     */
    fun handle(
        instanceId: String,
        providerName: String,
        items: List<QuotaItem>,
    ): Int {
        if (!enabled) return 0
        val state = loadState()
        val thresholds = QuotaUnit.entries
            .mapNotNull { unit -> lowBalanceThreshold(unit)?.let { unit to it } }
            .toMap()
        val result = QuotaAlertEvaluator.evaluate(
            instanceId = instanceId,
            items = items,
            state = state,
            lowBalanceThresholds = thresholds,
        )
        if (result.state != state) saveState(result.state)
        // 恢复事件先落盘：主屏三态区要能展示「已恢复」，这一条与恢复通知开关无关
        triadStore.append(result.recoveries)
        var sentRecoveries = 0
        if (result.recoveries.isNotEmpty() && recoverEnabled) {
            postRecoveries(instanceId, providerName, result.recoveries)
            sentRecoveries = result.recoveries.size
        }
        if (result.alerts.isEmpty()) return sentRecoveries

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
        post(notificationIdOf(instanceId), title, body)
        return sentRecoveries + result.alerts.size
    }

    /** 恢复通知：同一次刷新多条恢复合并成一条，正文逐项写「4% → 96%」。 */
    private fun postRecoveries(
        instanceId: String,
        providerName: String,
        recoveries: List<QuotaAlertEvaluator.Recovery>,
    ) {
        val title = if (recoveries.size == 1) {
            val one = recoveries.first()
            "${providerName} · ${one.itemTitle}额度已恢复"
        } else {
            "${providerName} · ${recoveries.size} 项额度已恢复"
        }
        val body = recoveries.joinToString("；") { recovery ->
            val amount = recovery.remainingAmount
            val unit = recovery.unit
            if (amount != null && unit != null) {
                "余额回升到 ${unit.symbol}${amount.stripTrailingZeros().toPlainString()}"
            } else {
                "${recovery.fromPercent}% → ${recovery.toPercent}%"
            }
        }
        post(recoveryNotificationIdOf(instanceId), title, body)
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

    private fun post(notificationId: Int, title: String, body: String) {
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
            manager.notify(notificationId, builder.build())
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

        /** 「额度恢复时提醒」（原型 s19-sw-back）；与 [com.bsfeng.monitor.ui.quota.QuotaAlertPrefs] 同键。 */
        const val KEY_RECOVER_ENABLED = "recover_alerts_enabled"
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

        /** 第一实例的通知 ID，沿用历史固定值 7301（老用户已有的那条通知不被另起一条）。 */
        const val NOTIFICATION_ID = 7301

        /** 通知 ID 槽位区间大小：7301 起往后 900 个槽位，够所有账号互不覆盖。 */
        private const val NOTIFICATION_SPAN = 900

        /**
         * 通知 ID 由实例键稳定派生。
         *
         * 固定 7301 会让第二个账号的提醒**覆盖**第一条（同一 id 系统只留最新一条），
         * 用户只看到「某个账号低了」，另一个账号的提醒等于没发。
         * 用 instanceId 的稳定散列：同一账号每次落在同一槽位（同账号多档提醒仍合并成一条），
         * 不同账号几乎不会撞；真撞了也只是并入同一条，不会崩。
         */
        fun notificationIdOf(instanceId: String): Int =
            NOTIFICATION_ID + instanceId.hashCode().absoluteValue % NOTIFICATION_SPAN

        /** 恢复通知的第一个槽位：与低额度提醒区间错开 1000，两者同时成立时互不覆盖。 */
        private const val RECOVERY_NOTIFICATION_ID = 8301

        /** 恢复通知 ID：同账号稳定派生，不同账号不互相覆盖。 */
        fun recoveryNotificationIdOf(instanceId: String): Int =
            RECOVERY_NOTIFICATION_ID + instanceId.hashCode().absoluteValue % NOTIFICATION_SPAN
    }
}
