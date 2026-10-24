package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleEngine.kt 的窗口状态与弱网顺延；职责：一次匹配窗口的起止、扫描计数、内容变化节流与顺延判定。

import com.bsfeng.monitor.util.DebugConfig

/**
 * 一次「匹配窗口」的状态与顺延判定。
 *
 * 语义与 [SkipRuleEngine] 类 KDoc 一致：窗口以 App 切换时刻为锚点，到点时若页面仍在渲染就顺延
 * （有次数与硬上限），扫描次数有护栏。把状态集中在这里，是为了让引擎只保留事件编排。
 */
internal class SkipWatchWindow {

    /** 是否处于匹配窗口内 */
    var watching = false
        private set

    /** 本次窗口内的扫描次数 */
    var scanCount = 0
        private set

    /** 本次（或最近一次）窗口的截止时刻（绝对毫秒；顺延会把它往后推） */
    var deadlineMs = 0L
        private set

    /** 本次（或最近一次）窗口已顺延次数 */
    var extensionCount = 0
        private set

    /** 本次（或最近一次）窗口累计顺延时长（毫秒）：同时是规则时间闸门 `matchTime` 的宽限额度 */
    var extensionGrantedMs = 0L
        private set

    /** 进入当前 App 的时刻（`matchTime` 的起算点） */
    var enterTimeMs = 0L
        private set

    /** 最近一次 CONTENT_CHANGED 触发时刻；null = 本窗口内还没有内容变化（弱网顺延的判定依据） */
    private var lastContentTriggerMs: Long? = null

    /** 上一次扫描时的引擎时间（毫秒）：用于确认"引擎一直在连续扫描"（见 [tryExtend]） */
    private var lastScanAtMs: Long? = null

    /** 记录进入 App 的时刻（切 App 时调用） */
    fun markEnter(now: Long) {
        enterTimeMs = now
    }

    /** 开启窗口：重置扫描计数、截止时间与顺延额度 */
    fun start(now: Long, windowMs: Long) {
        watching = true
        scanCount = 0
        deadlineMs = now + windowMs
        extensionCount = 0
        extensionGrantedMs = 0
        lastContentTriggerMs = null
        lastScanAtMs = null
    }

    /** 结束窗口（扫描计数与待复核清理由调用方负责） */
    fun stop() {
        watching = false
        scanCount = 0
    }

    /**
     * 内容变化节流：命中节流间隔返回 null（不该扫描），否则记录触发时刻并返回 true。
     */
    fun noteContentChanged(now: Long, throttleMs: Long): Boolean {
        if (lastContentTriggerMs?.let { now - it < throttleMs } == true) return false
        lastContentTriggerMs = now
        return true
    }

    /**
     * 开始一次扫描：返回「距上次扫描」的间隔（首次为 null），并刷新上次扫描时间。
     *
     * 调用方必须先取间隔再判断到点，才能识别"引擎长时间未运行"的过期窗口。
     */
    fun beginScan(now: Long): Long? {
        val sinceLastScanMs = lastScanAtMs?.let { now - it }
        lastScanAtMs = now
        return sinceLastScanMs
    }

    /** 递增扫描计数（每次真正扫描一个 App 记一次） */
    fun countScan() {
        scanCount++
    }

    /**
     * 弱网顺延（P2-2 增强）：窗口**到点**时判断是否还值得继续扫描。
     *
     * 三个条件同时成立才顺延：①策略启用且顺延次数未用尽；②页面仍在渲染
     * （距最近一次 CONTENT_CHANGED < [WindowExtendPolicy.stillRenderingMs]）；③引擎一直在连续扫描
     * （距上次扫描 ≤ [WindowExtendPolicy.maxScanGapMs]，排除进程被冻结/时间跳变导致的"复活过期窗口"）。
     * 调用方已保证当前**未命中**任何规则、也没有待复核动作。
     *
     * 截止时间取「原截止时间 + stepMs」而不是「now + stepMs」：这样"基础窗口 + 步长×次数"就是
     * **精确**的硬上限（默认 10s + 4s），不会被到点检测延迟（正常 ≤ 一个兜底轮询周期）叠加放大；
     * 到点判定晚于截止时间时，顺延额度照样按原截止时间累加。
     *
     * @return true = 已顺延（窗口继续）；false = 不顺延（调用方结束窗口）
     */
    fun tryExtend(packageName: String, now: Long, sinceLastScanMs: Long?, policy: WindowExtendPolicy): Boolean {
        if (!policy.enabled) return false
        if (extensionCount >= policy.maxExtensions) {
            DebugConfig.log("SkipEngine: 窗口到点不顺延（顺延次数已达上限 " + extensionCount + "/" +
                policy.maxExtensions + "）" + packageName)
            return false
        }
        if (sinceLastScanMs != null && sinceLastScanMs > policy.maxScanGapMs) {
            DebugConfig.log("SkipEngine: 窗口到点不顺延（距上次扫描 " + sinceLastScanMs + "ms > 连续扫描上限 " +
                policy.maxScanGapMs + "ms，引擎长时间未运行/时间跳变，窗口已过期）" + packageName)
            return false
        }
        val last = lastContentTriggerMs
        val idleMs = if (last == null) Long.MAX_VALUE else now - last
        if (idleMs > policy.stillRenderingMs) {
            val idleText = if (last == null) "窗口内无内容变化" else "距上次内容变化 " + idleMs + "ms"
            DebugConfig.log("SkipEngine: 窗口到点不顺延（页面已静止：" + idleText + "）" + packageName)
            return false
        }
        val newDeadline = deadlineMs + policy.stepMs
        extensionCount++
        extensionGrantedMs += policy.stepMs
        deadlineMs = newDeadline
        DebugConfig.log("SkipEngine: 弱网顺延 #" + extensionCount + "/" + policy.maxExtensions +
            " 触发原因=页面仍在渲染（距上次内容变化 " + idleMs + "ms < " + policy.stillRenderingMs + "ms, now=" +
            now + "） → 窗口延长 " + policy.stepMs + "ms, 新截止=" + newDeadline +
            ", 累计顺延=" + extensionGrantedMs + "ms, " + packageName)
        return true
    }
}
