package com.bsfeng.monitor.record

import android.content.Context
import androidx.core.content.edit
import com.bsfeng.monitor.MonitorApp

/**
 * 「前台应用监控」暂停闸门（契约 s09-monitor）。
 *
 * 契约要求暂停后**停止写新事件、实时流不再增长**，恢复后继续采集。实现取「写入侧短路」：
 * - 不靠停无障碍服务 —— 停服务会摘掉系统授权，还会连带停掉开屏跳过与记账；
 * - 不靠 UI 内存标志 —— 设置页的 ViewModel 与采集侧（Service / Receiver）是两个对象，标志传不过去。
 *
 * 暂停态落盘到 `monitor_prefs`：进程被系统杀掉后重建时暂停仍然成立，
 * 否则会出现「用户暂停过，重启后监控自己又跑起来」。
 * 内存态由设置页与采集侧共用，[setPaused] 一次写内存 + 落盘，两边同帧一致。
 */
object MonitorPauseGate {

    internal const val PREFS_NAME = "monitor_prefs"
    internal const val KEY_PAUSED = "monitor_paused"

    @Volatile
    private var paused: Boolean = false

    /** 首次访问时从磁盘装载（服务 / 接收器可能先于 Activity 就读它）。 */
    @Volatile
    private var loaded: Boolean = false

    /** 当前是否暂停。采集侧在每次写入前读它（热路径，只读内存）。 */
    val isPaused: Boolean
        get() {
            ensureLoaded()
            return paused
        }

    /**
     * 设置暂停态：内存态立即生效，同时落盘。
     *
     * 幂等：值没变时不重复写盘。
     */
    fun setPaused(value: Boolean) {
        ensureLoaded()
        synchronized(this) {
            if (paused == value) return
            paused = value
        }
        prefs()?.edit { putBoolean(KEY_PAUSED, value) }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            paused = prefs()?.getBoolean(KEY_PAUSED, false) ?: false
            loaded = true
        }
    }

    /** 进程单例的偏好存储；单测 / 未初始化时降级为「只留内存态，不落盘」。 */
    private fun prefs() = runCatching {
        MonitorApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()

    /** 单测用：清掉懒装载标记，让下一个用例按自己的前置条件重新开始。 */
    internal fun resetForTesting() {
        synchronized(this) {
            paused = false
            loaded = false
        }
    }
}
