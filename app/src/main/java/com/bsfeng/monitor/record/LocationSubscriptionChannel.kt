package com.bsfeng.monitor.record

// 从 LocationCollector 拆出：持续订阅通道的编排（建立 / 释放 / 静默重建 / 首点判定）。
// 纯逻辑（supervisor、日志、provider 来源都是注入的），所以「零投递 → 重建 / 补首点」能在纯 JVM 单测里钉死。

/**
 * 持续订阅通道的编排：建立、释放、静默重建、首点兜底判定。
 *
 * 抽出来有两个原因：LocationCollector 要守 400 行；更重要的是这四件事**共用同一份状态**
 * （supervisor 的活跃/投递计数/建立时刻）—— 散在采集器里就出现过「needsRestart 写好了却没人调」
 * 的事故（实测订阅全程零投递、状态却一直显示「活跃」，静默重建从未触发）。
 */
internal class LocationSubscriptionChannel(
    private val supervisor: LocationSubscriptionSupervisor?,
    private val log: (String, Boolean) -> Unit,
    private val fineGranted: () -> Boolean,
    private val availableProviders: () -> List<String>,
    private val powerSave: () -> Boolean,
    /** 传感器给的派点间隔覆盖（手持 180s / 静止 30s / null = 用默认）；已确认 MOVING 时不生效。 */
    private val probeIntervalOverride: () -> Long? = { null },
    private val modeOf: () -> SampleMode,
    private val onFailure: (LocationFailureReason, String) -> Unit,
    /** 是否已有主动定位在飞：在飞时不重建，避免两套定位互抢 provider。 */
    private val inFlight: () -> Boolean,
    /** 订阅点处理链（质量闸 / 模式探测 / 节流），第二个参数是落库出口。 */
    private val processor: (LocationSample, (LocationSample) -> Unit) -> Unit,
) {

    /** 订阅回调出口：由服务注入（落库），stop 时清空以免持住已销毁的 Service。 */
    @Volatile
    var consumer: ((LocationSample) -> Unit)? = null
        private set

    /** 订阅是否活跃（设置页自检 / 日志用）。 */
    fun isActive(): Boolean = supervisor?.isActive() == true

    /** 只读快照（状态行与静默判定用）；无 supervisor 时为 null。 */
    fun snapshot(nowMs: Long): LocationSubscriptionSupervisor.Snapshot? = supervisor?.snapshot(nowMs)

    /**
     * 建立（或按当前模式调速）持续订阅。幂等：重复调用只在配置变化时重建。
     *
     * @param onSample 订阅回调的出口（落库）
     * @return 订阅最终是否活跃（无 supervisor / 未授权 / 无 provider 时 false）
     */
    fun start(nowMs: Long, onSample: (LocationSample) -> Unit): Boolean {
        val host = supervisor
        if (host == null) {
            onFailure(LocationFailureReason.SOURCE_UNAVAILABLE, "无 LocationManager，订阅不可用")
            return false
        }
        if (!fineGranted()) {
            onFailure(LocationFailureReason.PERMISSION, "未授权，订阅不可用")
            log("位置订阅跳过：未授权", true)
            return false
        }
        consumer = onSample
        val providers = currentProviders()
        val intervalMs = currentIntervalMs()
        val ok = host.ensure(nowMs, providers, intervalMs) { sample -> dispatch(sample) }
        log("位置订阅建立：providers=" + providers.joinToString() + " 间隔=" + (intervalMs / 1000) + "s", false)
        if (!ok) {
            onFailure(
                if (providers.isEmpty()) LocationFailureReason.SOURCE_UNAVAILABLE else LocationFailureReason.EXCEPTION,
                "订阅建立失败 providers=" + providers.joinToString(),
            )
        }
        return ok
    }

    /** 释放订阅。幂等：未建立时是空操作；服务 onDestroy 必须调用（否则 listener 跟着进程泄漏）。 */
    fun stop() {
        consumer = null
        supervisor?.stop()
    }

    /**
     * 深断档后强制重建（30 分钟级）：系统可能已经悄悄摘掉了监听。
     *
     * 与 [restartIfSilent] 的分工：这个只在 [LocationGapHealing.Level.DEEP] 触发；
     * 日常的「活着但不派点」由 [restartIfSilent] 管（阈值只有 105s 量级）。
     */
    fun restart(nowMs: Long): Boolean {
        val host = supervisor ?: return false
        if (inFlight()) {
            log("位置订阅重建推迟：主动定位进行中", false)
            return false
        }
        return host.restart(nowMs, currentProviders(), currentIntervalMs()) { sample -> dispatch(sample) }
    }

    /**
     * 静默重建：订阅「活着但零投递 / 长时间无投递」时重建，并打出可观测日志。
     *
     * @return 是否真的重建了
     */
    fun restartIfSilent(nowMs: Long): Boolean {
        val host = supervisor ?: return false
        if (inFlight()) {
            log("位置订阅静默重建推迟：主动定位进行中", false)
            return false
        }
        return host.restartIfSilent(nowMs, modeOf(), currentProviders(), currentIntervalMs()) { sample ->
            dispatch(sample)
        }
    }

    /** 统一出口：走处理链，落库出口取当前 consumer（stop 后为 null 时丢弃）。 */
    private fun dispatch(sample: LocationSample) {
        val target = consumer ?: return
        processor(sample, target)
    }

    /** 订阅建立了但至今零投递、且已超过静默阈值（见 [LocationSubscriptionPlan.needsInitialFix]）。 */
    fun needsInitialFix(nowMs: Long): Boolean {
        val current = snapshot(nowMs) ?: return false
        return LocationSubscriptionPlan.needsInitialFix(current, modeOf())
    }

    private fun currentProviders(): List<String> = LocationSubscriptionPlan.providersFor(availableProviders())

    private fun currentIntervalMs(): Long =
        LocationSubscriptionPlan.probeIntervalMsOf(modeOf(), powerSave(), probeIntervalOverride())
}
