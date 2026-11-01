package com.bsfeng.monitor.record

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// 从 record/LocationCollector.kt 拆出：定位规则、采样节奏与模式判定、落库节流、主动定位编排。
// 都是纯逻辑（不碰 Android API），因此能在纯 JVM 单测里逐条验证。

/**
 * 定位采集的**全部规则**：过滤 / 挑选 / 量化。纯逻辑，可单测。
 *
 * 精度分级见 [LocationQuality]（GPS 高/低置信、NETWORK 低置信、超容差丢弃）。
 *
 * 坐标有两档精度，用途不同，**不要混用**：
 * - [quantize] 落库/画轨迹用 6 位小数（≈0.11m）：3 位会把几十米的真实移动压成同一格，
 *   轨迹看起来是一级一级的阶梯；
 * - [quantizeCoarse] 对外（逆地理编码请求与缓存键）用 3 位小数（≈110m），隐私口径不变。
 */
object LocationPolicy {

    /** 超过 30 分钟的位置点视为 stale（进程被冻结/无新定位时不要拿旧点冒充当前位置）。 */
    const val MAX_AGE_MS = 30 * 60 * 1000L

    /**
     * 高置信精度门槛（100m）：保留旧常量名以兼容既有调用点与测试。
     *
     * 注意：这个值不再是「一刀切丢弃线」——分级见 [LocationQuality]（GPS ≤100m 高置信、
     * ≤500m 低置信；NETWORK ≤2000m 低置信）。[isAccurate] 语义即「高置信」。
     */
    const val MAX_ACCURACY_METERS = LocationQuality.HIGH_ACCURACY_METERS

    /** 本地落库坐标保留 6 位小数（≈0.11m）：轨迹要能看出几十米的移动。 */
    const val COORDINATE_DECIMALS = 6

    /** 对外（逆地理编码请求 / 缓存键）坐标保留 3 位小数（≈110m），隐私口径与旧版一致。 */
    const val COARSE_COORDINATE_DECIMALS = 3

    /**
     * provider 优先级：PASSIVE（系统缓存 / 别的 App 刚定位过）→ NETWORK（室内可用）→ GPS（室外兜底）。
     * 与 [LocationManager] 的常量值一致，避免在这里引入 Android 依赖做单测。
     */
    val PROVIDER_PRIORITY = listOf("passive", "network", "gps")

    /**
     * **主动定位**的 provider 顺序：network 先（室内/模拟器更快出点）→ gps。
     * PASSIVE 不参与：它只产出别人的缓存点，不会主动去测。
     */
    val ACTIVE_PROVIDERS = listOf("network", "gps")

    /** 主动定位的单 provider 短预算：5 秒拿不到点就放弃（不猜位置、不落库）。 */
    const val ACTIVE_FIX_BUDGET_MS = 5_000L

    /**
     * GPS 首点的独立预算：15 秒。
     *
     * 旧实现把 [ACTIVE_FIX_BUDGET_MS] 按 provider 数均分（5000/2 = 2500ms），GPS 冷启动
     * 要 10–30 秒才出首点，必然 timeout —— 实测日志反复出现「主动定位失败 provider=gps
     * 原因=timeout(2500ms)」，这就是「掉线」的第一个根因。
     * network 靠基站 / WiFi 秒级出点，5 秒足够；两者时间尺度差一个数量级，**不能均分**。
     */
    const val GPS_FIRST_FIX_BUDGET_MS = 15_000L

    /** 单个 provider 的主动定位预算：GPS 给足首点时间，其余走快失败快回落。 */
    fun activeFixTimeoutMs(provider: String): Long =
        if (provider == "gps") GPS_FIRST_FIX_BUDGET_MS else ACTIVE_FIX_BUDGET_MS

    /**
     * 覆盖级（探测）network 预算：4 秒。
     *
     * 只给门禁探测用（PROBING 低频探测 / SLEEPING 30 分钟兜底）。network 靠基站与 WiFi 秒级出点，
     * 4 秒已是常态上限；等满 5 秒换不来更准的点，只换来更长的射频开启。
     */
    const val PROBE_FIX_BUDGET_MS = 4_000L

    /**
     * 覆盖级（探测）gps 预算：8 秒。
     *
     * 探测的 gps 通常是热启动（几分钟前刚定位过），8 秒足够出点；按满预算等 15 秒，
     * 每轮要多烧 7 秒射频，而门禁探测只关心「现在大概在哪」。
     */
    const val PROBE_GPS_FIX_BUDGET_MS = 8_000L

    /**
     * 探测允许用系统缓存顶替主动定位的年龄上限：90 秒。
     *
     * 真机基线（8 天 2176 个点：passive 2134 / network 38 / gps 4）说明系统缓存几乎总是新鲜的。
     * 90 秒内的缓存点与「刚探测出来的点」在位移判定上等价（探测周期本身就是 180 秒起），
     * 超过 90 秒说明最近没有别的定位，缓存不能代表「现在在哪」，必须主动定位。
     */
    const val PROBE_CACHE_MAX_AGE_MS = 90_000L

    /** 一次主动定位的预算摘要（日志用：实际是按 provider 逐个计时，不是总和）。 */
    val ACTIVE_FIX_BUDGET_SUMMARY: String
        get() = budgetSummaryOf(FixBudget.FULL)

    /**
     * 按预算档位取单 provider 超时。
     *
     * 两档必须分开：满预算那几条路径（MOVING 补采 / 断档自愈 / 首点兜底）砍预算就是重演
     * 「GPS 必然 timeout」的掉线；探测那几条砍预算则是把「什么都没发生」的射频代价直接降四成。
     */
    fun activeFixTimeoutMs(provider: String, budget: FixBudget): Long = when (budget) {
        FixBudget.FULL -> activeFixTimeoutMs(provider)
        FixBudget.PROBE -> if (provider == "gps") PROBE_GPS_FIX_BUDGET_MS else PROBE_FIX_BUDGET_MS
    }

    /** 某档预算的摘要（日志用，便于对照 timeout 证据）。 */
    fun budgetSummaryOf(budget: FixBudget): String =
        ACTIVE_PROVIDERS.joinToString(" / ") { it + " " + activeFixTimeoutMs(it, budget) + "ms" }

    /** 时间新鲜度；时钟回拨（now < time）不算 stale。 */
    fun isFresh(timeMs: Long, nowMs: Long): Boolean = nowMs - timeMs <= MAX_AGE_MS

    /** 高置信精度判定；`accuracy <= 0` 表示系统未给出精度，按不可用处理。 */
    fun isAccurate(accuracyMeters: Float): Boolean =
        accuracyMeters > 0f && accuracyMeters <= MAX_ACCURACY_METERS

    /** 该点是否值得落库（分级后仍可用的最低要求，见 [LocationQuality.confidenceOf]）。 */
    fun isUsable(sample: LocationSample, nowMs: Long): Boolean =
        isFresh(sample.timeMs, nowMs) && LocationQuality.isAcceptable(sample.provider, sample.accuracyMeters)

    /**
     * 按 [PROVIDER_PRIORITY] 取第一个可用点；全都不可用返回 null（不猜位置）。
     *
     * 「可用」按分级判定，不再是 100m 一刀切：室内 300m 的 NETWORK 点也能返回，
     * 但它会是 [FixConfidence.LOW]，能否覆盖更优位置由 [LocationQuality.shouldAccept] 决定。
     */
    fun pick(candidates: List<LocationSample>, nowMs: Long): LocationSample? =
        PROVIDER_PRIORITY.firstNotNullOfOrNull { provider ->
            candidates.firstOrNull { it.provider == provider && isUsable(it, nowMs) }
        }

    /** 坐标量化成 [COORDINATE_DECIMALS] 位小数字符串；等于零归一成 "0"（避免 -0.000000 与补尾零）。 */
    fun quantize(value: Double): String = quantizeTo(value, COORDINATE_DECIMALS)

    /** 对外量化成 [COARSE_COORDINATE_DECIMALS] 位小数（隐私口径：≈110m 一格）。 */
    fun quantizeCoarse(value: Double): String = quantizeTo(value, COARSE_COORDINATE_DECIMALS)

    /**
     * 按指定位数四舍五入（HALF_UP）后取 plain 字符串。
     *
     * 用 [BigDecimal] 而不是 `Math.round(v * factor) / factor` + `String.format`：
     * 后者在 6 位小数时会带出 1e-11 级的二进制误差（0.1 + 0.2 那类），
     * 同一地点可能写出两种字符串，地图上表现为点位轻微抖动。
     */
    private fun quantizeTo(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val rounded = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP)
        return if (rounded.signum() == 0) "0" else rounded.toPlainString()
    }
}

/** 采样模式：移动（约 30 秒一个点）/ 静止（约 3 分钟一个点）。 */
enum class SampleMode {
    MOVING,
    STILL,
}

/**
 * 采样节奏与模式判定（纯函数 + 无副作用的小状态机，可单测）。
 *
 * 判定口径（用户可见行为，改动前先读）：
 * - 静止 → 移动：相邻两点距离 > 100m 且时间差 ≤ 5 分钟；或精度明显改善（≥30m）且位移 > 50m；
 * - **噪声地板（真机标定）**：以上任何一种「判移动」都还要求位移 > 两点 accuracy 之和。
 *   室内 passive 点 26–47m 误差时，相距 40m 的两个点其实是同一处的抖动，不设这道门会
 *   让 STILL↔MOVING 每十几秒来回切，每次切换都重建订阅（释放 + 重注册 3 个 listener）；
 * - 移动 → 静止：连续 3 次采样位移都 < 50m；
 * - 跨天 / 长断档（> 5 分钟）不做「移动」判定：隔夜的两个点不是「刚走了一步」；
 * - 重启回 [SampleMode.STILL]：最多 3 分钟就能重新识别出移动（静止间隔就是 3 分钟）。
 */
object LocationPace {

    /**
     * 移动时的采样间隔：30 秒。
     *
     * 曾经是 10 秒。真机实测 10 秒节奏下定位耗电明显，而轨迹精度并不会因为点更密而
     * 线性变好（室内 fused 本身就 175m 误差）。30 秒在「能画出连续轨迹」与「续航」之间
     * 是更合理的折中，与用户要求一致。
     */
    const val MOVING_INTERVAL_MS = 30_000L

    /** 静止时的采样间隔：3 分钟（省电优先；1–3 分钟区间取上限）。 */
    const val STILL_INTERVAL_MS = 3 * 60_000L

    /** 移动时系统缓存不超过 30 秒就直接用：不每次都为 30 秒节奏去主动定位（省电）。 */
    const val CACHE_FRESH_MS = 30_000L

    /** 静止 → 移动：位移阈值（米）。 */
    const val MOVE_DISTANCE_M = 100.0

    /** 静止 → 移动的观察窗口：超过这个时间差说明中间断档，不当作「刚移动」。 */
    const val MOVE_WINDOW_MS = 5 * 60_000L

    /** 精度改善阈值（米）：新点比旧点准这么多，才把「小位移」也算成移动。 */
    const val ACCURACY_GAIN_M = 30f

    /** 精度改善判定的位移门槛（米）。 */
    const val ACCURACY_MOVE_DISTANCE_M = 50.0

    /** 移动 → 静止：位移足够小（米）。 */
    const val STILL_DISTANCE_M = 50.0

    /** 移动 → 静止：需要连续多少次「原地」采样。 */
    const val STILL_STREAK = 3

    /**
     * 静止模式的**探测**间隔（30 秒），**不等于**落库间隔 [STILL_INTERVAL_MS]（180 秒）。
     *
     * 两个间隔必须分开：落库要省电（3 分钟一条足够画静止段），但模式判定需要更密的信号 ——
     * 订阅只在落库时喂给状态机时，用户走 100m 后要等最多 180 秒才切到移动，
     * 用户感知就是「轨迹断断续续」。探测点只喂状态机、不落库，代价是静止时定位唤醒 ×6。
     * 觉到耗电偏高时先回调这个常量（或让省电模式回退到 [STILL_INTERVAL_MS]）。
     */
    const val STILL_PROBE_INTERVAL_MS = 30_000L

    /**
     * 手持状态的探测间隔：180 秒。
     *
     * 用户拿起手机玩时加速度计一样会抖，但**这不等于在移动**（没有真实位移）。
     * 这一档只做低频探测：既能在坐车/匀速行驶这类「传感器平稳但人在位移」的场景下
     * 用位移发现异常，又不会为玩手机持续烧定位。
     *
     * 这是退避阶梯的**起点**：连续 3 次 / 6 次拿不到位移证据会拉到 300 / 600 秒
     * （见 [probeIntervalMsFor]），任何运动证据立刻回到这个值。
     */
    const val HANDHELD_PROBE_INTERVAL_MS = 180_000L

    /** 探测退避中档：连续 3~5 次拿不到位移证据后，间隔拉到 300 秒。 */
    const val HANDHELD_PROBE_INTERVAL_MID_MS = 300_000L

    /** 探测退避封顶：连续 ≥6 次拿不到位移证据后，间隔拉到 600 秒。 */
    const val HANDHELD_PROBE_INTERVAL_MAX_MS = 600_000L

    /** 退避档位：连续无位移证据的探测次数达到它，间隔升到 [HANDHELD_PROBE_INTERVAL_MID_MS]。 */
    const val PROBE_BACKOFF_MID_STREAK = 3

    /** 退避封顶档位：连续无位移证据的探测次数达到它，间隔升到 [HANDHELD_PROBE_INTERVAL_MAX_MS]。 */
    const val PROBE_BACKOFF_MAX_STREAK = 6

    /**
     * 探测间隔阶梯（纯函数）：0~2 次 180s / 3~5 次 300s / ≥6 次 600s。
     *
     * 为什么按「连续无位移证据的探测次数」退避：探测存在的唯一意义是抓「传感器平稳但人在位移」
     * （坐车 / 骑行）。连续几轮都拿不到位移证据，说明大概率没在远行，而此时门禁还停在 PROBING
     * （有抖动 / 步态一类弱证据在手），会按 180 秒一直烧到 5 分钟无证据降级为止。
     * 退避把「什么都没发生」这一段的一小时探测次数从 20 次压到个位数。
     *
     * 任何运动证据（步数增量 / 步态 / 抖动 / 位移过噪声地板）立刻回 180 秒：
     * 人真的走起来时探测必须恢复灵敏，否则「坐车 3 分钟升级」会变成 10 分钟。
     */
    fun probeIntervalMsFor(quietProbeStreak: Int): Long = when {
        quietProbeStreak >= PROBE_BACKOFF_MAX_STREAK -> HANDHELD_PROBE_INTERVAL_MAX_MS
        quietProbeStreak >= PROBE_BACKOFF_MID_STREAK -> HANDHELD_PROBE_INTERVAL_MID_MS
        else -> HANDHELD_PROBE_INTERVAL_MS
    }

    /**
     * 首次成功前的主动定位重试间隔（30 秒）：**固定值，不做指数退避**。
     *
     * 指数退避会把「冷启动首次出点」越推越远（前几次 GPS 冷启动超时很常见），
     * 固定 30 秒能保证首次成功最多晚 30 秒回来。
     */
    const val INITIAL_ACTIVE_FIX_RETRY_MS = 30_000L

    /**
     * 静止 → 移动的**速度**阈值（m/s，0.45）。
     *
     * 取 0.45 是为了「步行也算移动」：步行 1.4 m/s、跑步 3 m/s、骑车 5 m/s 都在线上，
     * 而原地 GPS 抖动即便抖出 30 米也只对应 0.1 m/s 级，被下面的最小位移挡住。
     */
    const val MOVE_SPEED_MPS = 0.45

    /** 速度判定要求的最小位移（米）：30 米以下的位移一律不算移动（挡住抖动）。 */
    const val MOVE_SPEED_MIN_DISTANCE_M = 30.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 模式对应的采样间隔。 */
    fun intervalOf(mode: SampleMode): Long =
        if (mode == SampleMode.MOVING) MOVING_INTERVAL_MS else STILL_INTERVAL_MS

    /**
     * 位移是否够「在移动」：既要超过最小位移，又要速度过线。
     *
     * 位移门槛先挡抖动（原地 GPS 也能抖出十几米），速度门槛再挡「慢速漂移」：
     * 30 米 / 60 秒 = 0.5 m/s 刚好过线，30 米 / 180 秒 = 0.17 m/s 不算移动。
     */
    fun isMovingBySpeed(distanceMeters: Double, gapMs: Long): Boolean {
        if (gapMs <= 0L) return false
        if (distanceMeters < MOVE_SPEED_MIN_DISTANCE_M) return false
        return distanceMeters / (gapMs / 1000.0) >= MOVE_SPEED_MPS
    }

    /** 两点间大圆距离（米）。 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }
}

/**
 * 采样模式状态机（进程内存，不落库）。
 *
 * 只吃「上一次采样」与「本次采样」，所以能在纯 JVM 单测里把边界钉死：
 * 零位移、一次大跳变、时钟回拨 / 跨天断档、来回抖动（抖动由 3 次连续静止吸收）。
 */
class LocationModeTracker(private val initialMode: SampleMode = SampleMode.STILL) {

    /** 当前模式；重启（新实例）回 [initialMode]。 */
    var mode: SampleMode = initialMode
        private set

    private var last: LocationSample? = null
    private var stillStreak: Int = 0

    /** 喂一次采样，返回更新后的模式。 */
    fun onSample(sample: LocationSample): SampleMode {
        val previous = last
        // 同一个点重复喂（订阅探测点先喂一次、落库时又喂一次）不重复计数：
        // 否则 MOVING 下会多记一次「原地」，3 次静止判定会提前到来。
        if (previous != null && previous.timeMs == sample.timeMs) return mode
        last = sample
        if (previous == null) return mode

        val gap = sample.timeMs - previous.timeMs
        val distance = LocationPace.distanceMeters(
            previous.latitude, previous.longitude, sample.latitude, sample.longitude,
        )
        // 时钟回拨（gap < 0）按「刚采样过」处理；跨天 / 长断档（gap > 5 分钟）不判移动
        val withinWindow = gap <= LocationPace.MOVE_WINDOW_MS
        // 噪声地板：位移必须大于两点精度之和，才算「真的走了」。
        // 真机实测（室内 passive 26–47m）：相距 40m 的两点会同时满足「位移 ≥30m」与
        // 「速度 ≥0.45m/s」，把静止判成移动 —— 随后每次模式切换都要重建订阅。
        // 5m 精度的 GPS 点地板只有 10m，实际门槛仍是 isMovingBySpeed 的 30m，步行/跑步不受影响。
        val noiseFloor = (previous.accuracyMeters + sample.accuracyMeters).toDouble()
        val beyondNoise = distance > noiseFloor

        when (mode) {
            SampleMode.STILL -> {
                val movedFar = beyondNoise && distance > LocationPace.MOVE_DISTANCE_M && withinWindow
                val accuracyGain = previous.accuracyMeters - sample.accuracyMeters
                // 精度大幅改善时噪声地板只按**新点**精度（旧点不可信，新点才代表现在）：
                // 80m→20m 且位移 60m 是真实移动，用两端之和（100m）会把它判成静止。
                val movedWithBetterFix = withinWindow &&
                    distance > LocationPace.ACCURACY_MOVE_DISTANCE_M &&
                    distance > sample.accuracyMeters &&
                    accuracyGain >= LocationPace.ACCURACY_GAIN_M
                // 速度判定只在「两个点都高置信」时生效：低置信点（室内 NETWORK 几百米误差）
                // 的位移本身就不可信，让它参与速度判定会把静止判成移动（漂移刷轨迹）。
                val movedBySpeed = beyondNoise && withinWindow &&
                    isHighConfidence(previous) && isHighConfidence(sample) &&
                    LocationPace.isMovingBySpeed(distance, gap)
                if (movedFar || movedWithBetterFix || movedBySpeed) {
                    mode = SampleMode.MOVING
                    stillStreak = 0
                }
            }
            SampleMode.MOVING -> {
                if (distance < LocationPace.STILL_DISTANCE_M) {
                    stillStreak++
                    if (stillStreak >= LocationPace.STILL_STREAK) {
                        mode = SampleMode.STILL
                        stillStreak = 0
                    }
                } else {
                    stillStreak = 0
                }
            }
        }
        return mode
    }

    private fun isHighConfidence(sample: LocationSample): Boolean =
        LocationQuality.confidenceOf(sample.provider, sample.accuracyMeters) == FixConfidence.HIGH

    /** 恢复到初始状态（单测 / 用户关闭再开启位置采集时调用）。 */
    fun reset() {
        last = null
        stillStreak = 0
        mode = initialMode
    }
}

/** 定位权限四态判定（纯函数，设置页与单测共用）。 */
object LocationPermissionRules {
    /**
     * minSdk 29 起，Android 10+ 在后台读位置必须拿到 `ACCESS_BACKGROUND_LOCATION`，
     * 只有前台授权时后台取不到点 —— 这正是「已开定位但没有记录」的常见原因。
     *
     * 为什么要单列 `coarseGranted`：Android 12+ 用户可只授予 `ACCESS_COARSE_LOCATION`，
     * 此时系统只派基站级点（500m+），过不了精度闸就全被丢弃。旧实现把「有 COARSE」也算作
     * 「精确位置已授权」，于是界面显示绿标「已就绪」而行程页永远空白 —— P0 缺陷的根因。
     *
     * 优先级：NOT_GRANTED > COARSE_ONLY > MISSING_BACKGROUND > GRANTED。
     * 只有大致位置时后台权限再全也没用（点本身不可用），所以 COARSE_ONLY 压过 MISSING_BACKGROUND。
     */
    fun resolveStatus(
        fineGranted: Boolean,
        coarseGranted: Boolean,
        backgroundGranted: Boolean,
    ): LocationPermissionStatus = when {
        !fineGranted && !coarseGranted -> LocationPermissionStatus.NOT_GRANTED
        !fineGranted -> LocationPermissionStatus.COARSE_ONLY
        !backgroundGranted -> LocationPermissionStatus.MISSING_BACKGROUND
        else -> LocationPermissionStatus.GRANTED
    }
}
