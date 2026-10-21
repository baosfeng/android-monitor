package com.bsfeng.monitor.autobook

import android.content.Context
import androidx.core.content.edit
import android.content.SharedPreferences

/**
 * 用户点过「忽略」的这笔账单：**落盘记住，之后不再弹**。
 *
 * 真机反馈（2026-10-17）：点了忽略之后，页面继续抛事件、用户再划一下列表，浮窗又弹一次。
 * 内存去重表（[AutoBookDedup]）只能管进程内 10 分钟，退出微信 / 服务重启就忘了。
 * 这里按**落库键**（[AutoBookParser.buildSourceId]）持久化，同一笔在 [DEFAULT_TTL_MS] 内不再弹。
 *
 * 为什么用落库键而不是弹窗指纹：
 * - 落库键包含渠道 / 方向 / 金额 / 商户 / 5 分钟时间桶（有订单号或交易时间时用它们），
 *   比「渠道 + 方向 + 金额」的模糊指纹更能区分「同一笔」与「另一笔同金额」；
 * - 与 [AutoBookEngine.persist] 的幂等键同源，忽略与已记账在同一维度上生效。
 */
class AutoBookIgnoreStore(
    private val prefs: SharedPreferences,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis
) {

    /** 记住这笔被用户忽略了 */
    fun markIgnored(sourceId: String) {
        if (sourceId.isBlank()) return
        val at = now()
        prefs.edit { putLong(KEY_PREFIX + sourceId, at) }
        prune(at)
    }

    /** 这笔是否被忽略过（过期条目顺手清掉） */
    fun isIgnored(sourceId: String): Boolean {
        if (sourceId.isBlank()) return false
        val at = prefs.getLong(KEY_PREFIX + sourceId, 0L)
        if (at <= 0L) return false
        if (now() - at > ttlMs) {
            prefs.edit { remove(KEY_PREFIX + sourceId) }
            return false
        }
        return true
    }

    /** 清理过期条目，避免偏好文件无限增长 */
    private fun prune(nowMs: Long) {
        prefs.edit {
            for ((key, value) in prefs.all) {
                if (key.startsWith(KEY_PREFIX) && value is Long && nowMs - value > ttlMs) {
                    remove(key)
                }
            }
        }
    }

    /**
     * 清空全部忽略记忆，返回清掉的条数（诊断 / 迁移用；一次性标记保留）。
     */
    fun clearAll(): Int {
        var removed = 0
        prefs.edit {
            for (key in prefs.all.keys) {
                if (key.startsWith(KEY_PREFIX)) {
                    remove(key)
                    removed++
                }
            }
        }
        return removed
    }

    /**
     * 旧版本升级后的一次性清理。
     *
     * 2026-10-20 之前，浮窗把「点遮罩」也当成忽略，用户误触一次就落盘 7 天；
     * 新版本只有「忽略」按钮会写记忆。旧记录无法区分误触与主动忽略，所以升级后整体清一次，
     * 让被误伤的账单恢复弹窗；之后用户新点的「忽略」不再被清理。
     */
    fun migrateLegacyOnce() {
        if (prefs.getBoolean(KEY_LEGACY_CLEARED, false)) return
        val removed = clearAll()
        prefs.edit { putBoolean(KEY_LEGACY_CLEARED, true) }
        if (removed > 0) {
            android.util.Log.i(
                "AutoBook",
                "清空旧版忽略记忆 $removed 条（旧版遮罩误触会写忽略，升级后一次性重置）"
            )
        }
    }

    companion object {
        /** 忽略记忆保留 7 天：足够覆盖「同一笔反复被识别」，又不会永久占空间 */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000

        private const val PREFS_NAME = "autobook_ignored"
        private const val KEY_PREFIX = "ignored_"

        /** 旧版忽略记忆清理标记：升级后只清一次，之后用户主动忽略的记录不受影响 */
        private const val KEY_LEGACY_CLEARED = "legacy_cleared_v1"

        fun getInstance(context: Context): AutoBookIgnoreStore =
            AutoBookIgnoreStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
