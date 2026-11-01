package com.bsfeng.monitor.record

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 定位功耗门禁：只有「确认真的人移动了很远」才允许建立位置订阅。
 *
 * 为什么要有这一层：[com.bsfeng.monitor.service.LocationForegroundService] 原来在「手持」状态
 * 就建订阅 —— 用户拿起手机玩一小时，定位芯片一小时不休眠，正是用户抱怨的耗电来源。
 * 手持抖动、室内走动、摇晃都**不是**远行证据；只有步数距离、探测点位移、持续移动时长才是。
 *
 * 三态（契约冻结）：
 * - [State.SLEEPING]：没有运动证据。不建订阅、不采点；30 分钟兜底一次单点（兜底在服务层）。
 * - [State.PROBING]：有动作但未确认远行（玩手机 / 室内走动 / 摇晃）。**不建订阅**，
 *   只做低频单点探测（180 秒起；连续拿不到位移证据按 [probeIntervalMs] 退避到 300 / 600 秒），
 *   拿到点即释放。
 * - [State.MOVING]：已确认远行。建订阅，30 秒一次。
 *
 * 升级判据（任一满足即 [State.MOVING]，单向保持到降级）：
 * - 累计步数 × [stepLengthM] ≥ [UPGRADE_STEP_DISTANCE_M]；
 * - 探测点相对锚点的位移 ≥ [UPGRADE_PROBE_DISTANCE_M]；
 * - 持续移动 ≥ [UPGRADE_DURATION_MS] 且位移 ≥ [UPGRADE_DURATION_MIN_DISTANCE_M]。
 *
 * 降级判据（任一满足回 [State.SLEEPING] 并清空行程锚点）：
 * - 加速度明确静止满 [sleepDownMs]；
 * - 无步数且无位移满 [sleepDownMs]。
 *
 * 为什么降级必须清锚点：不清的话「上午走了 500m、下午玩手机」会被旧锚点直接顶回 MOVING，
 * 又变回「定位一直开着」。
 *
 * 软件步数（无硬件步数计时）：只在 [MotionDetector] 判定**稳定步频**的窗口里数加速度峰值。
 * 随机抖动同样会反复越过峰值线，不加步频门控时玩 10 分钟手机能凑出几千「步」，
 * 门禁会被直接顶到 MOVING —— 那比原来的功耗 bug 更糟。
 *
 * 抖动唤醒（[onShake]）：没有显著运动传感器、加速度计只能 5Hz batching 的设备上，
 * [MotionDetector] 判不出步态（步态判定要求 ≥16Hz），软件步数与步态证据全废 ——
 * 门禁会永远停在 SLEEPING。这种设备只能靠「模长标准差持续偏高」当唤醒信号：
 * 唤醒到 PROBING 不建订阅、不烧定位，代价可以忽略，所以判据故意放宽（[shakeStdDev] +
 * [shakeConfirmMs]）。抖动**不是**远行证据，唤醒后仍要靠步数 / 位移升级。
 *
 * 纯逻辑、无 Android 依赖：传感器事件与时钟由服务层注入，全部边界在单测里钉死。
 */
class MotionGate(
    private val stepLengthM: Double = STEP_LENGTH_M,
    private val sleepDownMs: Long = SLEEP_DOWN_MS,
    private val shakeStdDev: Double = SHAKE_STDDEV,
    private val shakeConfirmMs: Long = SHAKE_CONFIRM_MS,
) {

    /** 门禁三态。只有 [State.MOVING] 允许建立位置订阅。 */
    enum class State {
        /** 没有运动证据：不建订阅、不采点。 */
        SLEEPING,

        /** 有动作但未确认远行：不建订阅，只做低频单点探测（间隔见 [probeIntervalMs]）。 */
        PROBING,

        /** 已确认远行：建订阅，30 秒一次。 */
        MOVING,
    }

    /** 当前门禁态。默认 SLEEPING：没有证据就不该耗电。 */
    var state: State = State.SLEEPING
        private set

    /** 最近一次状态迁移的原因（日志与单测断言用）。 */
    var reason: String = "尚无运动证据"
        private set

    /** 本次行程锚点以来的步数（硬件增量或软件峰值计数）。 */
    var stepsSinceAnchor: Long = 0L
        private set

    /** 步数折算的位移证据（米）。 */
    val stepDistanceM: Double get() = stepsSinceAnchor * stepLengthM

    /** 探测点相对锚点的最大位移（米）。 */
    var probeDistanceM: Double = 0.0
        private set

    /** 两类位移证据里的较大值（日志里的「距离=..m」）。 */
    val movementDistanceM: Double get() = max(stepDistanceM, probeDistanceM)

    /**
     * 连续「拿到探测点但没有位移证据」的探测次数（退避阶梯的唯一输入）。
     *
     * 只数**探测点**：主动定位失败（回调 null）不经过这里 —— 没有点就是没有证据，
     * 既不该升档（没有可判定的东西）也不该复位。
     */
    var quietProbeCount: Int = 0
        private set

    /**
     * 下一轮探测的间隔（毫秒）：180 秒起，连续无位移证据的探测满 3 次拉到 300 秒、满 6 次拉到 600 秒。
     *
     * 服务 tick 与 collector 的 probeIntervalOverride 都读这一个值（两处读同一来源，
     * 否则「服务按 600 秒 tick、订阅却按 180 秒派点」这种错位会再出现）。
     * 任何运动证据（步数增量 / 步态 / 抖动 / 位移过噪声地板）都把阶梯打回 180 秒。
     */
    val probeIntervalMs: Long get() = LocationPace.probeIntervalMsFor(quietProbeCount)

    /** 服务据此开关订阅：只有 MOVING 才允许 startSubscription。 */
    val wantsSubscription: Boolean get() = state == State.MOVING

    /** 最近一次「真实活动」证据的时刻（步数 / 探测位移 / 显著运动 / 步态）。 */
    var lastActivityAtMs: Long = 0L
        private set

    /** 最近一次评估的时刻（诊断用）。 */
    var lastEvaluatedAtMs: Long = 0L
        private set

    /** 进入明确静止的证据起点；0 表示当前没有静止证据。 */
    private var stillSinceMs: Long = 0L

    /** 持续抖动计时的起点（[onShake] 的样本时刻基准）；0 表示当前不在累计。 */
    private var shakeSinceSampleMs: Long = 0L

    /** 本轮持续移动的起点（用于「移动 10 分钟 + 300m」判据）。 */
    private var movingSinceMs: Long = 0L

    /** 探测位移锚点：第一次探测到的点，降级时清掉。 */
    private var probeAnchor: LocationSample? = null

    // ===== 硬件步数计 =====

    private var hardwareStepsAvailable = false
    private var hardwareBaselineSet = false
    private var lastHardwareTotal = 0L

    // ===== 软件步数（无硬件步数计时的退化路径）=====

    private val motion = MotionDetector()
    private var gravityBaseline = 9.81
    private var previousCentered = 0.0
    private var lastPeakAtMs = 0L

    /**
     * 标记硬件步数计可用。
     *
     * 有硬件步数时必须关掉软件步数，否则同一次走路会被两份计数各算一遍，
     * 500m 的判据会被腰斩成 250m。
     */
    fun markHardwareStepsAvailable() {
        hardwareStepsAvailable = true
    }

    /**
     * 显著运动传感器触发（硬件 hub，一次性）。
     *
     * 这是 SLEEPING 里唯一能零功耗唤醒的手段：只有它能把门禁从 SLEEPING 拉到 PROBING。
     */
    fun onSignificantMotion(nowMs: Long): State {
        noteActivity(nowMs)
        if (state == State.SLEEPING) transition(State.PROBING, "显著运动触发")
        return evaluate(nowMs)
    }

    /**
     * 服务层加速度判定的结论（静止 / 手持 / 步态）。
     *
     * 只把「静止」当作降级计时起点、把「步态」当作活动证据；手持抖动**不刷新**活动时间 ——
     * 否则玩手机能把「无步数且无位移 5 分钟」的降级条件永远拖住，门禁卡在 PROBING 不走。
     */
    fun onMotionState(motionState: MotionDetector.State, tsMs: Long): State {
        when (motionState) {
            MotionDetector.State.GAIT -> {
                noteActivity(tsMs)
                // 步态是「真的在走」的证据：探测间隔立刻回 180 秒，不能因为前面几次没位移就迟钝
                quietProbeCount = 0
                if (state == State.SLEEPING) transition(State.PROBING, "检出稳定步频")
            }
            MotionDetector.State.STILL -> if (stillSinceMs == 0L) stillSinceMs = tsMs
            MotionDetector.State.HANDHELD -> Unit
        }
        return evaluate(tsMs)
    }

    /**
     * 抖动唤醒：把「加速度模长标准差持续偏高」当作从 SLEEPING 起来的唯一可用信号。
     *
     * 为什么需要：没有显著运动传感器、加速度计只能 5Hz batching 的设备上，
     * [MotionDetector] 判不出步态（KDoc 里写死的 ≥16Hz 约束），软件步数永远是 0，
     * 门禁会一直停在 SLEEPING —— 用户走一整天都不升级。
     *
     * 抖动**不刷新**活动时间、也不算远行证据：唤醒后仍要靠步数/位移升级，
     * 5 分钟内一无所获就如实降回 SLEEPING。已在 PROBING/MOVING 时不重复迁移（不覆盖原因）。
     *
     * @param stdDev 最近窗口的模长标准差（[MotionDetector.lastStdDev]）
     * @param sampleTsMs 样本时刻（单调时钟）。batching 一次投递一批样本时墙钟不变，
     *   必须用样本自身的时间跨度量「持续多久」，不能用调用时刻
     * @param nowMs 墙钟，用于唤醒后从 PROBING 的 5 分钟空闲降级起算
     */
    fun onShake(stdDev: Double, sampleTsMs: Long, nowMs: Long): State {
        if (stdDev < shakeStdDev) {
            shakeSinceSampleMs = 0L
            return state
        }
        // 明显抖动同样是运动证据（它是唯一在 5Hz 设备上可用的运动信号）：探测间隔回 180 秒
        quietProbeCount = 0
        if (shakeSinceSampleMs == 0L) shakeSinceSampleMs = sampleTsMs
        if (state == State.SLEEPING && sampleTsMs - shakeSinceSampleMs >= shakeConfirmMs) {
            noteActivity(nowMs)
            transition(State.PROBING, "明显抖动")
        }
        return evaluate(nowMs)
    }

    /**
     * 加速度样本：无硬件步数计时用峰值检测推软件步数。
     *
     * @return 更新后的门禁态
     */
    fun onAccelerometer(x: Float, y: Float, z: Float, tsMs: Long): State {
        // 有硬件步数计时就不跑软件计数（省电，也避免同一段路被两份计数各算一遍）
        if (!hardwareStepsAvailable) {
            val magnitude = sqrt(x * x + y * y + z * z)
            val motionState = motion.onAccelerometer(x, y, z, tsMs)
            if (detectSoftwareStep(magnitude, tsMs, motionState)) {
                stepsSinceAnchor += 1L
                noteActivity(tsMs)
                quietProbeCount = 0
                // 退化模式（无显著运动传感器）里加速度计是常开的：软件步数本身就能唤醒门禁，
                // 不能只等 30 分钟兜底探点，否则一路走完也停在 SLEEPING
                if (state == State.SLEEPING) transition(State.PROBING, "软件步数增量")
            }
        }
        return evaluate(tsMs)
    }

    /**
     * 硬件步数计的累计总步数（开机以来的绝对值）。
     *
     * 第一次读取只建立基线：步数计是累计值，不建基线会把「今天已经走了 8000 步」
     * 一次性算成 5.6km 的远行证据。
     */
    fun onStepCounter(totalSteps: Long, nowMs: Long): State {
        markHardwareStepsAvailable()
        if (!hardwareBaselineSet) {
            hardwareBaselineSet = true
            lastHardwareTotal = totalSteps
            return evaluate(nowMs)
        }
        val delta = (totalSteps - lastHardwareTotal).coerceAtLeast(0L)
        lastHardwareTotal = max(lastHardwareTotal, totalSteps)
        if (delta > 0L) {
            stepsSinceAnchor += delta
            noteActivity(nowMs)
            // 步数增量是最硬的运动证据：探测间隔立刻回 180 秒
            quietProbeCount = 0
            if (state == State.SLEEPING) transition(State.PROBING, "步数增量 " + delta)
        }
        return evaluate(nowMs)
    }

    /**
     * 喂一个探测点（单点定位结果）。
     *
     * 位移取「相对锚点」而不是「相邻两点」：相邻点会把来回踱步累加成几公里，
     * 相对锚点才能证明「人真的离开了出发地」。
     */
    fun onProbeSample(sample: LocationSample): State {
        val now = sample.timeMs
        val anchor = probeAnchor
        if (anchor == null) {
            probeAnchor = sample
            // 首个点只建锚点，拿不出位移证据：同样算一次「无位移证据的探测」
            quietProbeCount += 1
            return evaluate(now)
        }
        val distance = LocationPace.distanceMeters(
            anchor.latitude, anchor.longitude, sample.latitude, sample.longitude,
        )
        if (distance > probeDistanceM) probeDistanceM = distance
        // 精度之和是噪声地板：室内 100m 误差的两个点相距 80m 不是「挪了 80m」
        val noiseFloor = max(PROBE_NOISE_FLOOR_M, anchor.accuracyMeters + sample.accuracyMeters.toDouble())
        if (distance >= noiseFloor) {
            noteActivity(now)
            // 位移过噪声地板 = 真的挪了地方：探测间隔回 180 秒
            quietProbeCount = 0
            if (state == State.SLEEPING) transition(State.PROBING, "探测位移 " + metersText(distance))
        } else {
            // 没位移证据：下一轮探测按阶梯退避（180 → 300 → 600 秒封顶）
            quietProbeCount += 1
        }
        return evaluate(now)
    }

    /** 时间推进：静止 / 无活动 5 分钟就降级（服务每 30 秒 tick 一次）。 */
    fun onTick(nowMs: Long): State = evaluate(nowMs)

    /** 服务重建 / 用户重新开启定位：证据与状态一起清空。 */
    fun reset() {
        state = State.SLEEPING
        reason = "尚无运动证据"
        stepsSinceAnchor = 0L
        probeAnchor = null
        probeDistanceM = 0.0
        stillSinceMs = 0L
        shakeSinceSampleMs = 0L
        movingSinceMs = 0L
        lastActivityAtMs = 0L
        lastEvaluatedAtMs = 0L
        quietProbeCount = 0
        hardwareBaselineSet = false
        lastHardwareTotal = 0L
        hardwareStepsAvailable = false
        previousCentered = 0.0
        lastPeakAtMs = 0L
        gravityBaseline = 9.81
        motion.reset()
    }

    private fun evaluate(nowMs: Long): State {
        lastEvaluatedAtMs = nowMs
        if (state == State.SLEEPING) return state

        val stillLongEnough = stillSinceMs > 0L && nowMs - stillSinceMs >= sleepDownMs
        val idleTooLong = lastActivityAtMs > 0L && nowMs - lastActivityAtMs >= sleepDownMs
        if (stillLongEnough || idleTooLong) {
            val why = if (stillLongEnough) {
                "静止 " + (sleepDownMs / 60_000L) + " 分钟"
            } else {
                "无步数且无位移 " + (sleepDownMs / 60_000L) + " 分钟"
            }
            degradeToSleeping(why)
            return state
        }

        if (state != State.PROBING) return state
        val bySteps = stepDistanceM >= UPGRADE_STEP_DISTANCE_M
        val byProbe = probeDistanceM >= UPGRADE_PROBE_DISTANCE_M
        val byDuration = movingSinceMs > 0L &&
            nowMs - movingSinceMs >= UPGRADE_DURATION_MS &&
            movementDistanceM >= UPGRADE_DURATION_MIN_DISTANCE_M
        if (bySteps || byProbe || byDuration) {
            val why = when {
                bySteps -> "步数距离 " + metersText(stepDistanceM) +
                    " ≥ " + metersText(UPGRADE_STEP_DISTANCE_M)
                byProbe -> "探测位移 " + metersText(probeDistanceM) +
                    " ≥ " + metersText(UPGRADE_PROBE_DISTANCE_M)
                else -> "持续移动 " + (UPGRADE_DURATION_MS / 60_000L) + " 分钟且位移 " +
                    metersText(movementDistanceM) + " ≥ " + metersText(UPGRADE_DURATION_MIN_DISTANCE_M)
            }
            transition(State.MOVING, why)
        }
        return state
    }

    private fun degradeToSleeping(why: String) {
        transition(State.SLEEPING, why)
        // 不清硬件步数基线：步数计是开机累计值，清掉基线会让下一次增量算成「一整天」
        stepsSinceAnchor = 0L
        probeAnchor = null
        probeDistanceM = 0.0
        stillSinceMs = 0L
        movingSinceMs = 0L
        lastActivityAtMs = 0L
        // 回到 SLEEPING 后探测由 30 分钟兜底接管，退避阶梯重新起算
        quietProbeCount = 0
    }

    private fun noteActivity(tsMs: Long) {
        lastActivityAtMs = tsMs
        stillSinceMs = 0L
        if (movingSinceMs == 0L) movingSinceMs = tsMs
    }

    private fun transition(newState: State, why: String) {
        if (state == newState) return
        state = newState
        reason = why
    }

    /**
     * 软件步数：去重力后的模长第一次上穿 [STEP_PEAK_THRESHOLD] 记一个峰。
     *
     * 只有 [MotionDetector] 判为步态（稳定步频）时才把峰计成一步。真机标定过的步态判据
     * 能挡住玩手机的随机抖动 —— 抖动同样会反复越过峰值线，但间隔没有节律。
     */
    private fun detectSoftwareStep(
        magnitude: Float,
        tsMs: Long,
        motionState: MotionDetector.State,
    ): Boolean {
        gravityBaseline += (magnitude - gravityBaseline) * GRAVITY_BASELINE_ALPHA
        val centered = magnitude - gravityBaseline
        val rising = previousCentered < STEP_PEAK_THRESHOLD && centered >= STEP_PEAK_THRESHOLD
        previousCentered = centered
        if (!rising) return false
        val previousPeak = lastPeakAtMs
        lastPeakAtMs = tsMs
        if (previousPeak == 0L) return false
        val interval = tsMs - previousPeak
        if (interval < MIN_STEP_INTERVAL_MS || interval > MAX_STEP_INTERVAL_MS) return false
        return motionState == MotionDetector.State.GAIT
    }

    private fun metersText(value: Double): String = Math.round(value).toString() + "m"

    private companion object {
        /** 成人步行步幅：契约给定的 0.7m/步。 */
        const val STEP_LENGTH_M = 0.7

        /** 降级：连续无活动（静止，或无步数无位移）5 分钟。 */
        const val SLEEP_DOWN_MS = 5 * 60_000L

        /** 升级：步数折算距离 500m。 */
        const val UPGRADE_STEP_DISTANCE_M = 500.0

        /** 升级：探测点相对锚点位移 500m。 */
        const val UPGRADE_PROBE_DISTANCE_M = 500.0

        /** 升级：持续移动 10 分钟。 */
        const val UPGRADE_DURATION_MS = 10 * 60_000L

        /** 升级：持续移动满 10 分钟时仍要求 300m 位移（原地晃 10 分钟不算远行）。 */
        const val UPGRADE_DURATION_MIN_DISTANCE_M = 300.0

        /** 探测位移噪声地板：小于它的位移视为原地抖动，不刷新活动时间。 */
        const val PROBE_NOISE_FLOOR_M = 30.0

        /** 抖动唤醒阈值：模长标准差持续高于它才唤醒。0.8 高于手持静置噪声（0.15）与灰色带。 */
        const val SHAKE_STDDEV = 0.8

        /** 抖动唤醒需要持续的时间：5 秒（5Hz 采样约 25 个样本，足以排除单次碰撞）。 */
        const val SHAKE_CONFIRM_MS = 5_000L

        /** 软件步数峰值线（m/s²，去重力后）：低于它的起伏属于行走时的正常摆幅以下。 */
        const val STEP_PEAK_THRESHOLD = 0.6

        /** 相邻步的合理间隔（毫秒）：对应 0.5–4Hz，超出就算抖动/停顿。 */
        const val MIN_STEP_INTERVAL_MS = 250L
        const val MAX_STEP_INTERVAL_MS = 2_000L

        /** 重力基线跟随速度（16Hz 下约 3 秒时间常数），只用来去掉直流分量。 */
        const val GRAVITY_BASELINE_ALPHA = 0.02
    }
}
