package com.bsfeng.monitor.record

// 从 record/LocationCollector.kt 拆出：缓存为空时的主动定位编排。
// 按 provider 逐个要一个点、每段**各自**独立超时；抽成接口让这两条路径能在纯 JVM 单测里跑。

/**
 * 单次主动定位请求的抽象。
 *
 * 抽成接口只为让「缓存为空 → 发起请求 / 超时 → 放弃」这两条路径能在纯 JVM 单测里跑，
 * 生产实现是 [AndroidSingleFixSource]。
 *
 * 口径：`request` 必须**恰好回调一次** `onResult`（成功、失败、超时各一条路径），
 * 并在回调前释放底层请求（`CancellationSignal.cancel()` / `removeUpdates()`）。
 */
interface SingleFixSource {
    fun request(provider: String, timeoutMs: Long, onResult: (LocationSample?) -> Unit)
}

/**
 * 缓存为空时的主动定位编排（纯逻辑）：按 [providers] 顺序逐个要一个点，
 * 每段**按 provider 各自**计时（见 [LocationPolicy.activeFixTimeoutMs]），全部落空返回 null。
 *
 * 预算不再按 provider 数均分：GPS 冷启动要 10–30 秒，均分出来的 2500ms 必然超时，
 * 而 network 秒级出点、越早失败越早回落。两者时间尺度差一个数量级，要用两套预算。
 *
 * 预算还有第二维：**档位**（[FixBudget]）。门禁探测用覆盖级（network 4s / gps 8s），
 * 其余路径用满预算（5s / 15s）。两档的超时来源都在 [LocationPolicy]。
 */
/** 探测档连续全落空多少次后放 gps 兜底一次：3 次 ≈ 9~15 分钟（探测周期 180~300 秒）。 */
private const val PROBE_GPS_FALLBACK_AFTER_MISSES = 3

class ActiveFixResolver(
    private val source: SingleFixSource,
    val providers: List<String> = LocationPolicy.ACTIVE_PROVIDERS,
    /** 满预算（[FixBudget.FULL]）下单 provider 的预算；默认按 provider 名取 [LocationPolicy.activeFixTimeoutMs]。 */
    val timeoutMsOf: (String) -> Long = { LocationPolicy.activeFixTimeoutMs(it) },
    /** 覆盖级（[FixBudget.PROBE]）下单 provider 的预算；门禁探测专用。 */
    val probeTimeoutMsOf: (String) -> Long = { LocationPolicy.activeFixTimeoutMs(it, FixBudget.PROBE) },
    /** 日志出口：纯 JVM 单测里 android.util.Log 未 mock，只能由生产侧注入。 */
    private val log: (String, Boolean) -> Unit = { _, _ -> },
) {
    /** 探测档连续「全队列拿不到点」的次数：达到阈值时放一次 gps 兜底（见 [resolve] 的注释）。 */
    private var probeMissStreak = 0

    /** 日志用：满预算的各 provider 预算摘要。 */
    val budgetSummary: String get() = budgetSummaryOf(FixBudget.FULL)

    /** 日志用：某档预算的摘要。 */
    fun budgetSummaryOf(budget: FixBudget): String =
        providers.joinToString(" / ") { it + " " + timeoutFor(it, budget) + "ms" }

    /** 某个 provider 在某档预算下的超时。 */
    fun timeoutFor(provider: String, budget: FixBudget): Long =
        if (budget == FixBudget.PROBE) probeTimeoutMsOf(provider) else timeoutMsOf(provider)

    /**
     * 依次请求各可用 provider；拿到第一个可用点即回调。
     *
     * @param budget 预算档位；默认满预算（既有调用点的语义不变）
     * @param onResult **恰好回调一次**：任一 provider 出点，或全部落空时回调 null
     */
    fun resolve(
        nowMs: Long,
        isAvailable: (String) -> Boolean,
        budget: FixBudget = FixBudget.FULL,
        onResult: (LocationSample?) -> Unit,
    ) {
        // 探测档只问 network（见 LocationPolicy.PROBE_PROVIDER）：为「大致还在原地」等 gps 8 秒，
        // 换来的点还常被精度闸丢掉。network 不可用时才退回全队列，保证只有 gps 的设备仍能探测。
        val available = providers.filter(isAvailable)
        val probeOnly = available.filter { it == LocationPolicy.PROBE_PROVIDER }
        // 探测档只问 network（见 LocationPolicy.PROBE_PROVIDER）；但 isProviderEnabled 对 network 几乎恒真，
        // 飞行模式 / 室内无基站时它是「可用但永不出点」——连续几次全落空就放一次 gps 兜底，
        // 否则门禁的位移证据链会彻底断掉，探测每轮还白烧 4 秒射频。
        val gpsFallback = budget == FixBudget.PROBE && probeMissStreak >= PROBE_GPS_FALLBACK_AFTER_MISSES
        val queue = if (budget == FixBudget.PROBE && probeOnly.isNotEmpty() && !gpsFallback) probeOnly else available
        if (queue.isEmpty()) {
            log("主动定位跳过：没有可用的 provider（${providers.joinToString()}）", true)
            onResult(null)
            return
        }
        var index = 0
        fun next() {
            if (index >= queue.size) {
                // 探测档全落空：记账，连续几次后放 gps 兜底一次
                if (budget == FixBudget.PROBE) probeMissStreak += 1
                log("主动定位放弃：${queue.joinToString()} 均未拿到可用点", true)
                onResult(null)
                return
            }
            val provider = queue[index++]
            source.request(provider, timeoutFor(provider, budget)) { sample ->
                if (sample != null && LocationPolicy.isUsable(sample, nowMs)) {
                    if (budget == FixBudget.PROBE) probeMissStreak = 0
                    log("主动定位成功 provider=$provider accuracy=${sample.accuracyMeters}", false)
                    onResult(sample)
                } else {
                    next()
                }
            }
        }
        next()
    }
}
