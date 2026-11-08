package com.bsfeng.monitor.totp

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 复制即焚 —— 复制验证码后 N 秒（默认 30s）自动清空剪贴板。
 *
 * 安全口径：清空前必须确认剪贴板里仍是自己写进去的那串，
 * 用户后来复制了别的内容（甚至另一条验证码）就不能误清；读不到当前内容（App 在后台，
 * Android 10+ 限制读剪贴板）时同样不清理——宁可留一份验证码，也不清用户的别的内容。
 *
 * 调度：主线程调 copy，自动清理跑在内部作用域；页面退出时调 cancelPendingClear。
 */
class TotpClipboard(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clearDelayMillis: Long = TotpSettings.CLIPBOARD_CLEAR_MILLIS,
) {

    private val appContext = context.applicationContext
    private val clipboard: ClipboardManager? =
        appContext.getSystemService(ClipboardManager::class.java)

    private var pendingClear: Job? = null

    /** 复制验证码；返回是否写入成功（系统剪贴板不可用时 false）。 */
    fun copy(text: String): Boolean {
        val manager = clipboard ?: return false
        val wrote = runCatching {
            manager.setPrimaryClip(sensitiveClip(text))
        }.isSuccess
        if (!wrote) return false
        cancelPendingClear()
        if (clearDelayMillis <= 0) return true
        pendingClear = scope.launch {
            delay(clearDelayMillis)
            clearIfUnchanged(text)
        }
        return true
    }

    /** 取消尚未触发的自动清理（页面关闭、关闭「复制即焚」开关时）。 */
    fun cancelPendingClear() {
        pendingClear?.cancel()
        pendingClear = null
    }

    /**
     * 仅当剪贴板内容仍是 [expected] 时清空；返回是否真的清了。
     *
     * 公开给测试与「立即清空」入口复用，不吞异常。
     */
    fun clearIfUnchanged(expected: String): Boolean {
        val manager = clipboard ?: return false
        val current = runCatching { readText(manager) }.getOrNull()
        if (current != expected) {
            if (current != null) Log.d(TAG, "剪贴板已被用户改写，跳过清理")
            return false
        }
        return runCatching {
            manager.clearPrimaryClip()
            true
        }.getOrDefault(false)
    }

    private fun readText(manager: ClipboardManager): String? {
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(appContext)?.toString()
    }

    internal companion object {
        const val TAG = "TotpClipboard"

        /** 剪贴板标签：部分 ROM 的剪贴板历史会显示它，所以只写「验证码」，不带账号 / 发行方。 */
        const val CLIP_LABEL = "验证码"

        /**
         * 组装「敏感剪贴板条目」：Android 13+ 读 [ClipDescription.EXTRA_IS_SENSITIVE]，
         * 系统剪贴板预览（含「已复制到剪贴板」浮层）会遮成「敏感内容」，不再直出 6 位验证码。
         *
         * 低版本不认识这个 extra，写入只是无害的附加键 —— 不必按 SDK 分支。
         * 必须写进 [ClipData.getDescription] 的 extras（类型是 [PersistableBundle]）：
         * 挂在 ClipData.Item 上的 extras 系统不读。
         *
         * 常量标了 InlinedApi：「低版本不必分支」成立的前提正是它被内联成字面量写进 class，
         * 老系统上只是多一个无人识别的键，不会 NoSuchFieldError。
         */
        @SuppressLint("InlinedApi")
        fun sensitiveClip(text: String): ClipData {
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            val extras = clip.description.extras ?: PersistableBundle()
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
            return clip
        }
    }
}
