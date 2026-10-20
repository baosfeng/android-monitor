package com.bsfeng.monitor.autobook

/**
 * 页面变化检测（纯逻辑，可 JVM 单测）。
 *
 * 取代固定时间窗的「事件节流」：**页面文本一变就处理**。
 *
 * 真机问题（2026-10-20）：支付页从过渡态（只有「返回」）到渲染完成，两次事件间隔约 940ms；
 * 旧实现用 800ms（后来 300/150ms）时间窗，渲染完成的那次事件总要多等一个窗口，用户看到
 * 浮窗慢半拍。缩短窗口只是减小等待，没有治本 —— 判断依据应该是「页面内容有没有变」，
 * 而不是「距离上次处理过了多久」。
 *
 * 规则：
 * - `WINDOW_STATE_CHANGED` 永远处理（页面切换，低频且关键），并刷新指纹；
 * - `CONTENT_CHANGED` 只有文本指纹变化才处理（渲染完成/页面内容更新 → 立即解析）；
 * - [minIntervalMs] 只挡同一帧内的连续变化（例如逐行渲染），不是「等页面稳定」的窗口。
 */
internal class AutoBookPageChangeDetector(private val minIntervalMs: Long = MIN_INTERVAL_MS) {

    private var lastFingerprint: String? = null
    private var lastRunAtMs: Long = 0L

    /**
     * @param isWindowStateChange 是否页面切换事件
     * @param fingerprint 当前页面文本指纹（调用方用整页行文本计算）
     * @param nowMs 当前时间
     * @return true = 交给解析器
     */
    fun shouldProcess(isWindowStateChange: Boolean, fingerprint: String, nowMs: Long): Boolean {
        if (isWindowStateChange) {
            lastFingerprint = fingerprint
            lastRunAtMs = nowMs
            return true
        }
        if (fingerprint == lastFingerprint) return false
        // 内容变了才处理；最小间隔只挡同一帧内的连续渲染，不做「等稳定」的延迟
        if (lastFingerprint != null && nowMs - lastRunAtMs < minIntervalMs) return false
        lastFingerprint = fingerprint
        lastRunAtMs = nowMs
        return true
    }

    /** 诊断/单测用：最近一次处理的指纹 */
    fun currentFingerprint(): String? = lastFingerprint

    companion object {
        /** 同一帧内的连续内容变化合并；不是页面稳定窗口 */
        const val MIN_INTERVAL_MS = 50L
    }
}
