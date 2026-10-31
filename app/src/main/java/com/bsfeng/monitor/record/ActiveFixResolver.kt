package com.bsfeng.monitor.record

// 从 record/LocationCollector.kt 拆出：缓存为空时的主动定位编排。
// 按 provider 逐个要一个点、每段**各自**独立超时；抽成接口让这两条路径能在纯 JVM 单测里跑。

/**
 * 单次主动定位请求的抽象。
 *
 * 抽成接口只为让「缓存为空 → 发起请求 / 超时 → 放弃」这两条路径能在纯 JVM 单测里跑，
 * 生产实现是 [AndroidSingleFixSource]。
 *
 * 契约：`request` 必须**恰好回调一次** `onResult`（成功、失败、超时各一条路径），
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
 */
class ActiveFixResolver(
    private val source: SingleFixSource,
    val providers: List<String> = LocationPolicy.ACTIVE_PROVIDERS,
    /** 单个 provider 的预算；默认按 provider 名取 [LocationPolicy.activeFixTimeoutMs]。 */
    val timeoutMsOf: (String) -> Long = { LocationPolicy.activeFixTimeoutMs(it) },
    /** 日志出口：纯 JVM 单测里 android.util.Log 未 mock，只能由生产侧注入。 */
    private val log: (String, Boolean) -> Unit = { _, _ -> },
) {
    /** 日志用：各 provider 的预算摘要。 */
    val budgetSummary: String
        get() = providers.joinToString(" / ") { it + " " + timeoutMsOf(it) + "ms" }

    fun resolve(
        nowMs: Long,
        isAvailable: (String) -> Boolean,
        onResult: (LocationSample?) -> Unit,
    ) {
        val queue = providers.filter(isAvailable)
        if (queue.isEmpty()) {
            log("主动定位跳过：没有可用的 provider（${providers.joinToString()}）", true)
            onResult(null)
            return
        }
        var index = 0
        fun next() {
            if (index >= queue.size) {
                log("主动定位放弃：${queue.joinToString()} 均未拿到可用点", true)
                onResult(null)
                return
            }
            val provider = queue[index++]
            source.request(provider, timeoutMsOf(provider)) { sample ->
                if (sample != null && LocationPolicy.isUsable(sample, nowMs)) {
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
