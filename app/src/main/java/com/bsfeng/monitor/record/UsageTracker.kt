package com.bsfeng.monitor.record

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.util.AppUtils
import java.util.UUID

/**
 * App 使用时长跟踪器（状态驱动）。
 *
 * 由前台轮询每秒调用 [tick]：同一 App 持续累加毫秒数，
 * 切换 App 或回到桌面时 [flush] 写入一条带时长的记录。
 * 不再依赖 OPENED/CLOSED 事件对，从根本上避免配对丢失。
 */
class UsageTracker(
    private val onRecord: (Record) -> Unit = {}
) {
    private companion object {
        /** 同一 App 两次 tick 间隔超过该值即判为断层（息屏 / 进程冻结 / 服务被杀），这段不计入时长 */
        const val GAP_THRESHOLD_MS = 2_000L
    }

    private var currentPkg: String? = null
    private var currentAppName: String? = null
    /** 当前 session 已累计的毫秒数 */
    private var accumulatedMs: Long = 0L
    /** 上一次 tick 的时间戳（用于计算本次增量） */
    private var lastTickTime: Long = 0L

    /** 最后活跃的 App 名称（poller 不可用时供 UI 兜底） */
    var lastActiveLabel: String? = null
        private set

    /** 实际回调（解决循环依赖），设置后取代构造参数 */
    private var recordCallback: ((Record) -> Unit)? = null

    fun setOnRecordCallback(callback: (Record) -> Unit) {
        recordCallback = callback
    }

    private fun emit(record: Record) {
        (recordCallback ?: onRecord)(record)
    }

    /**
     * 每秒由前台轮询调用。
     *
     * @param pkg 当前前台 App 包名，null 表示桌面/系统 UI/息屏
     * @param appName 当前前台 App 显示名，null 时按包名查找
     * @param timestamp 当前毫秒时间戳
     */
    fun tick(pkg: String?, appName: String?, timestamp: Long) {
        // 桌面/系统 UI/息屏 → flush 当前 session，不累加
        if (pkg == null) {
            flush(timestamp)
            return
        }

        if (pkg == currentPkg) {
            // 同一 App → 累加本次间隔
            if (lastTickTime > 0) {
                val delta = timestamp - lastTickTime
                // 中间缺失 >2s 说明有断层（息屏 / 进程冻结 / 服务被杀），这段不是使用时长：
                // 只结算到断层起点，再把这次 tick 当作断层之后新 session 的第一跳。
                if (delta > GAP_THRESHOLD_MS) {
                    flushAt(lastTickTime)
                    startSession(pkg, appName, timestamp)
                    return
                }
                accumulatedMs += delta
            }
            lastTickTime = timestamp
        } else {
            // 切换到不同 App → flush 旧 session，开始新 session
            flush(timestamp)
            startSession(pkg, appName, timestamp)
        }
    }

    /**
     * 启动新 App 的 session。
     */
    private fun startSession(pkg: String?, appName: String?, timestamp: Long) {
        currentPkg = pkg
        currentAppName = appName
        if (appName != null) lastActiveLabel = appName
        accumulatedMs = 0L
        lastTickTime = timestamp
    }

    /**
     * 写入当前 App 的使用时长记录，并清空 session 状态。
     * 如果当前没有活跃 App（currentPkg == null）则不写入。
     *
     * 时间戳取 [timestamp]、时长 = 已累计 + (timestamp - 上一跳)：
     * 息屏 / 暂停前最后一次 tick 到息屏那一刻的间隔同样是真实使用（≤1 秒），要保留。
     * 因此**不要**在 [tick] 的断层分支里调它 —— 那会把整段断层算成使用时长，
     * 断层走 [flushAt] 只结算到断层起点。
     */
    fun flush(timestamp: Long) = flushAt(timestamp)

    /**
     * 按指定时间戳结算当前 session 并清空状态；[cutoffMs] 早于最后一跳时以最后一跳为准。
     *
     * 断层分支用 [lastTickTime] 作 cutoff：时长只到「最后一次确认还在用」的那一跳为止，
     * 之后到本次 tick 之间的空档（息屏 8 小时 / 进程冻结）一律丢弃。
     */
    private fun flushAt(cutoffMs: Long) {
        val pkg = currentPkg ?: return
        val appName = currentAppName ?: return
        val timestamp = if (lastTickTime > 0 && lastTickTime > cutoffMs) lastTickTime else cutoffMs
        val totalDuration = accumulatedMs +
                if (lastTickTime > 0 && lastTickTime < timestamp)
                    timestamp - lastTickTime else 0L

        if (totalDuration > 0) {
            emit(Record(
                id = UUID.randomUUID().toString(),
                type = RecordType.APP_USAGE,
                action = "USAGE",
                timestamp = timestamp,
                duration = totalDuration,
                label = appName,
                detail = mapOf(
                    "app_name" to appName,
                    "package_name" to pkg,
                    "duration_ms" to totalDuration.toString()
                )
            ))
        }
        currentPkg = null
        currentAppName = null
        accumulatedMs = 0L
        lastTickTime = 0L
    }

    /**
     * 丢弃当前 session：清空状态但**不写记录**。
     *
     * 监控暂停时用（见 [MonitorPauseGate]）：不丢的话，恢复后的第一次 [tick] 会走
     * `delta > 2s → flush` 分支，把整段暂停时间算成「这个 App 用了这么久」。
     */
    fun discard() {
        currentPkg = null
        currentAppName = null
        accumulatedMs = 0L
        lastTickTime = 0L
    }

    /**
     * 获取当前活跃 App 的 session 信息，供 UI 层展示"使用中 1分23秒"。
     */
    fun getActiveSession(): Pair<String, Long>? {
        val pkg = currentPkg ?: return null
        val name = currentAppName ?: return null
        val extraMs = if (lastTickTime > 0)
            (System.currentTimeMillis() - lastTickTime).coerceAtLeast(0) else 0L
        return name to (accumulatedMs + extraMs)
    }
}
