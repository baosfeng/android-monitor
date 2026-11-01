package com.bsfeng.monitor.totp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
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
            manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
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

    private companion object {
        const val TAG = "TotpClipboard"
        const val CLIP_LABEL = "验证码"
    }
}
