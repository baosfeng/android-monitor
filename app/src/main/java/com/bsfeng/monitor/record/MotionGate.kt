package com.bsfeng.monitor.record

import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 定位功耗门禁：只有「确认真的人移动了很远」才允许建立位置订阅。
 *
 * 为什么要有这一层：[com.bsfeng.monitor.service.LocationForegroundService] 原来在「手持」状态
 * 就建订阅 —— 用户拿起手机玩一小时，定位芯片一小时不休眠，正是用户抱怨的耗电来源。
 * 手持抖动、室内走动、摇晃都**不是**远行证据；只有步数距离、探测点位移、持续移动时长才是。
 *
 * 三态（口径冻结）：
 * - [State.SLEEPING]：没有运动证据。不建订阅、不采点；30 分钟兜底一次单点（网络环境指纹未变时
 *   放宽到 60 分钟，见 [NetworkStillnessTracker]；兜底在服务层）。
 * - [State.PROBING]：有真实运动证据（步态 / 步数 / 位移）但未确认远行。**不建订阅**，
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
 * **为什么显著运动与抖动都不是活动证据**：TYPE_SIGNIFICANT_MOTION 在「拿起手机」时就触发
 * （23127PN0CC 实测：20 分钟内被拿起手机触发 6 次）。旧实现把它当活动证据刷新
 * [lastActivityAtMs]，于是「无步数且无位移满 [sleepDownMs]」的降级永远不到点 —— MOVING 的
 * 30 秒 GPS 订阅跟着「玩手机」挂了 33 分钟（同期 gps provider 以 HIGH_ACCURACY 连续注册），
 * 正是用户抱怨的耗电。现在它只做两件事：记录 [lastSignificantMotionAtMs]（诊断）与
 * [observationHintAtMs]（让服务开 16Hz 观察窗，等 [MotionDetector] 的步态判据说话）。
 * 抖动（[onShake]）同理：模长标准差高只说明「有人在动手机」，走路的判据是**稳定步频**。
 *
 * 唤醒 / 升级只认三样：步态 [MotionDetector.State.GAIT]、步数增量、探测位移过噪声地板。
 * 没有显著运动传感器、加速度计只能 5Hz batching 的设备（判不出步态）也走这条路：
 * 抖动提示 → 服务开 16Hz 观察窗 → 判出 GAIT 才升级。
 *
 * **为什么 TYPE_MOTION_DETECT 也只做提示**：它与显著运动同源，判据是「设备不再静止」，
 * 拿起手机、放进口袋、被人碰一下桌面都会触发（真机 23127PN0CC 实测：22:17–22:46 共触发
 * 9 次，同期用户没离开座位）。旧实现把它当门禁证据直接拉到 PROBING，观察窗一开就是
 * 180 秒一次的探测 —— 用户看到的就是「只是拿手机出来玩一玩，定位就开始了」。
 * 真证的「人在远行」只有步数（硬件计步/步态）与位移两种，见类首段。
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

    /**
     * 最近一次探测相对锚点的位移（米）与推算速度（m/s）。
     *
     * 坐车判据专用：累计位移 [probeDistanceM] 会被「来回踱步 + 偶尔挪一次」凑满，
     * 只有**单次**位移配上算出来的速度才能区分「走路挪了 300m」与「坐车 3 分钟 1 公里」。
     */
    var lastProbeDistanceM: Double = 0.0
        private set

    /** 最近一次探测推算速度（m/s）：位移 ÷ 两次探测的时间差。 */
    var lastProbeSpeedMps: Double = 0.0
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
     * 任何运动证据（步数增量 / 步态 / 位移过噪声地板）都把阶梯打回 180 秒；显著运动与抖动
     * 不是证据，不动阶梯（见类 KDoc）。
     */
    val probeIntervalMs: Long get() = LocationPace.probeIntervalMsFor(quietProbeCount)

    /**
     * 本轮 MOVING 里最近一次「确认还在远行」的时刻（净位移较上次证据再增 ≥ [MOVING_KEEP_DISTANCE_M]）。
     *
     * 为什么需要它：旧的 MOVING 只按「5 分钟无活动」降级，而在办公室来回走动、去倒水、
     * 上厕所都是「有活动」—— 只要每 5 分钟有几步，MOVING 就永不降级，30 秒订阅一挂就是
     * 一整天（真机实测 8 小时订阅、network 1095 次 / fused 505 次 / gps 645 次）。
     * 真正定义「远行」的是位移规模，不是「手在动」。
     */
    private var lastFarMoveAtMs: Long = 0L

    /** 上一次「还在远行」证据时刻的净位移（相对本轮锚点，米）：判据用增量，不用绝对值。 */
    private var lastFarMoveDistanceM: Double = 0.0

    /** 服务据此开关订阅：只有 MOVING 才允许 startSubscription。 */
    val wantsSubscription: Boolean get() = state == State.MOVING

    /** 最近一次「真实活动」证据的时刻（步数 / 探测位移 / 步态）。显著运动与抖动**不算**。 */
    var lastActivityAtMs: Long = 0L
        private set

    /** 最近一次硬件运动检测触发时刻（纯诊断：与显著运动同源，只说明「设备不再静止」）。 */
    var lastMotionDetectAtMs: Long = 0L
        private set

    /** 最近一次显著运动触发时刻（纯诊断：它只说明「手机被拿起来了」）。 */
    var lastSignificantMotionAtMs: Long = 0L
        private set

    /** 最近一次确认抖动（模长标准差持续偏高）的时刻（纯诊断）。 */
    var lastShakeAtMs: Long = 0L
        private set

    /**
     * 最近一次「提示型唤醒」（显著运动 / 抖动）的时刻。
     *
     * 服务读它开 16Hz 观察窗：提示只说明「可能有人在动」，门禁态与活动时间都不受它影响；
     * 观察窗里判出 [MotionDetector.State.GAIT] 才算证据（见类 KDoc）。
     */
    var observationHintAtMs: Long = 0L
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

    /** 睡着时连续步态的起点（0 = 当前不在连续步态；无硬件步数计时设备靠它唤醒）。 */
    private var gaitSinceMs: Long = 0L

    /** 探测位移锚点：第一次探测到的点，降级时清掉。 */
    private var probeAnchor: LocationSample? = null

    // ===== 硬件步数计 =====

    private var hardwareStepsAvailable = false
    private var hardwareBaselineSet = false
    private var lastHardwareTotal = 0L

    /** 最近一次步数增量的时刻（0 = 还没有），用于「连续走」窗口。 */
    private var lastHardwareStepAtMs = 0L

    /** 当前窗口内攒下的步数（睡着时判唤醒来源；断档超过窗口就清零）。 */
    private var wakeStepWindowSteps = 0L

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
     * **不改门禁态、不刷新活动时间**：它只说明「手机被拿起来了」，在真机上「拿起手机」就会触发
     * （见类 KDoc）。这里只记 [lastSignificantMotionAtMs] 与 [observationHintAtMs]；
     * 服务拿提示开 16Hz 观察窗，判出 GAIT / 拿到步数或位移才升级。
     */
    fun onSignificantMotion(nowMs: Long): State {
        lastSignificantMotionAtMs = nowMs
        observationHintAtMs = nowMs
        return evaluate(nowMs)
    }

    /**
     * 硬件运动检测（`TYPE_MOTION_DETECT`，一次性触发）：设备不再静止。
     *
     * **只开观察窗，不改门禁态**：它与显著运动同源（判据是「设备不再静止」），拿起手机、
     * 放进口袋、桌面被碰都会触发（真机 22:17–22:46 触发 9 次，同期人没离开座位）。
     * 旧实现把它当证据直接拉 PROBING，于是「只是拿手机出来玩一玩」就开始 180 秒一轮探测。
     * 它仍然是**唤醒时机**的来源：事件驱动替代了定时轮询（真机 8 小时里 network 1095 次 /
     * gps 645 次几乎全是「没人动也按 180 秒探」），但「是否远行」只由步数与位移说了算。
     */
    fun onMotionDetect(nowMs: Long): State {
        lastMotionDetectAtMs = nowMs
        observationHintAtMs = nowMs
        return evaluate(nowMs)
    }

    /**
     * 硬件静止检测（`TYPE_STATIONARY_DETECT`，一次性触发）：设备已静止。
     *
     * 强静止证据，直接回 SLEEPING（清行程锚点）：PROBING 的定时探测由此停摆，
     * 只剩 30/60 分钟兜底与被动通道 —— 这就是「人没动就不该烧射频」的落点。
     */
    fun onStationaryDetect(nowMs: Long): State {
        if (state != State.SLEEPING) degradeToSleeping("硬件静止检测")
        return state
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
                quietProbeCount = 0
                if (state == State.SLEEPING) {
                    // 无硬件步数计时（或没给运动权限）时的唤醒路径：要求**连续**走够
                    // [WAKE_GAIT_MS] 才算远行开始。判据与步数门槛同义 —— 30 秒步行约 55 步 ≈ 40m，
                    // 而「走去倒水」通常 10 秒就走到了（那种只该开观察窗，不该点亮探测）。
                    if (gaitSinceMs == 0L) gaitSinceMs = tsMs
                    if (tsMs - gaitSinceMs < WAKE_GAIT_MS) return evaluate(tsMs)
                    gaitSinceMs = 0L
                    clearTripAnchors()
                    noteActivity(tsMs)
                    transition(State.PROBING, "连续步态 " + (WAKE_GAIT_MS / 1_000L) + " 秒")
                } else {
                    // 已经在跟行程：步态就是「真的在走」的证据，探测间隔立刻回 180 秒
                    gaitSinceMs = 0L
                    noteActivity(tsMs)
                }
            }
            MotionDetector.State.STILL -> if (stillSinceMs == 0L) stillSinceMs = tsMs
            MotionDetector.State.HANDHELD -> {
                // 步态被随机抖动打断：连续走的计时重来（判据是「连续」，不是「累计」）
                gaitSinceMs = 0L
            }
        }
        return evaluate(tsMs)
    }

    /**
     * 抖动提示：模长标准差持续偏高（[shakeStdDev] 持续 [shakeConfirmMs]）。
     *
     * 抖动**不改门禁态、不刷新活动时间、不动探测阶梯**：玩手机、拿起来看、口袋里晃都会抖，
     * 但它不是远行证据（见类 KDoc）。它唯一的作用是给出 [observationHintAtMs]，
     * 让服务在没有显著运动传感器的设备上开 16Hz 观察窗 —— 观察窗里判出 GAIT 才算证据。
     *
     * @param stdDev 最近窗口的模长标准差（[MotionDetector.lastStdDev]）
     * @param sampleTsMs 样本时刻（单调时钟）。batching 一次投递一批样本时墙钟不变，
     *   必须用样本自身的时间跨度量「持续多久」，不能用调用时刻
     * @param nowMs 墙钟（记录提示时刻）
     */
    fun onShake(stdDev: Double, sampleTsMs: Long, nowMs: Long): State {
        if (stdDev < shakeStdDev) {
            shakeSinceSampleMs = 0L
            return state
        }
        if (shakeSinceSampleMs == 0L) shakeSinceSampleMs = sampleTsMs
        if (sampleTsMs - shakeSinceSampleMs >= shakeConfirmMs) {
            lastShakeAtMs = nowMs
            observationHintAtMs = nowMs
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
                // 与硬件步数走同一条路径：睡着时有同样的唤醒门槛（见 onSteps）
                onSteps(1L, tsMs)
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
        onSteps(delta, nowMs)
        return evaluate(nowMs)
    }

    /**
     * 步数证据的统一入口（硬件步数计与软件峰值计数都走这里）。
     *
     * **睡着（SLEEPING）时的唤醒门槛**：必须在一个短窗口里连续走出 [WAKE_STEP_DISTANCE_M] 才进
     * PROBING。为什么不是「有一步就算」：工位起身、走去倒水、接个电话都会产生十几步，旧口径把这些
     * 全当成「远行已开始」，于是人根本没离开这一片，180 秒一轮的探测就先跑起来了（用户原话：
     * 只是拿手机出来玩一玩，定位就开始了）。为什么也不是「整段累计」：坐一天断续起身攒出的步数
     * 同样能过门槛，那等于把「一整天没出门」判成远行。窗口断档超过 [WAKE_STEP_WINDOW_MS] 就清零重来。
     *
     * 唤醒那一刻要**先作废睡着期间攒下的行程证据**（探测锚点、位移、活动时间），只把这次连续走的
     * 步数带进新行程：不清的话，一天里断续攒的 500m 会在唤醒瞬间直接把门禁顶到 MOVING；
     * 连带全清也不行 —— 这几十步本来就是证据（离升级线还差得远）。
     */
    private fun onSteps(delta: Long, nowMs: Long) {
        if (delta <= 0L) return
        if (state == State.SLEEPING) {
            if (lastHardwareStepAtMs > 0L && nowMs - lastHardwareStepAtMs > WAKE_STEP_WINDOW_MS) {
                wakeStepWindowSteps = 0L
            }
            lastHardwareStepAtMs = nowMs
            wakeStepWindowSteps += delta
            if (wakeStepWindowSteps * stepLengthM < WAKE_STEP_DISTANCE_M) return
            val walkedM = wakeStepWindowSteps * stepLengthM
            val walkedSteps = wakeStepWindowSteps
            wakeStepWindowSteps = 0L
            clearTripAnchors()
            stepsSinceAnchor = walkedSteps
            noteActivity(nowMs)
            quietProbeCount = 0
            transition(
                State.PROBING,
                "连续走了 " + metersText(walkedM) + " ≥ " + metersText(WAKE_STEP_DISTANCE_M),
            )
            return
        }
        // PROBING / MOVING：已经在跟行程，步数增量直接累加（不设门槛）
        stepsSinceAnchor += delta
        noteActivity(nowMs)
        // 步数增量是最硬的运动证据：探测间隔立刻回 180 秒
        quietProbeCount = 0
    }

    /**
     * 喂一个探测点（单点定位结果）。
     *
     * 位移取「相对锚点」而不是「相邻两点」：相邻点会把来回踱步累加成几公里，
     * 相对锚点才能证明「人真的离开了出发地」。
     */
    fun onProbeSample(sample: LocationSample, fromProbe: Boolean = true): State {
        val now = sample.timeMs
        val anchor = probeAnchor
        if (anchor == null) {
            probeAnchor = sample
            // 首个点只建锚点，拿不出位移证据：同样算一次「无位移证据的探测」。
            // 被动点不计数：它的频率由别的 App 决定，15 秒一个点能在 90 秒内把退避顶到 600 秒
            if (fromProbe) quietProbeCount += 1
            return evaluate(now)
        }
        val distance = LocationPace.distanceMeters(
            anchor.latitude, anchor.longitude, sample.latitude, sample.longitude,
        )
        if (distance > probeDistanceM) probeDistanceM = distance
        // 精度之和是噪声地板：室内 100m 误差的两个点相距 80m 不是「挪了 80m」
        val noiseFloor = max(PROBE_NOISE_FLOOR_M, anchor.accuracyMeters + sample.accuracyMeters.toDouble())
        // 坐车靠「单次位移 + 速度」升级：手机放车上时加速度平稳、没有步数，
        // 唯一能证明在移动的就是位置本身（见类 KDoc）。两条保护：
        // ① 样本时刻早于锚点（被动点与探测点不同源，乱序会发生）不产生速度证据 ——
        //    把时间差 coerce 到 1ms 会把速度放大成天文数字，一次乱序就能误升 MOVING；
        // ② 与位移证据共用噪声地板。
        // 不满足时清零：byCar 只反映「最近一次有效样本」，不留过期证据给下一次 evaluate。
        if (sample.timeMs > anchor.timeMs && distance >= noiseFloor) {
            lastProbeDistanceM = distance
            lastProbeSpeedMps = distance * 1000.0 / (sample.timeMs - anchor.timeMs).toDouble()
        } else {
            lastProbeDistanceM = 0.0
            lastProbeSpeedMps = 0.0
        }
        if (distance >= noiseFloor) {
            noteActivity(now)
            // 位移过噪声地板 = 真的挪了地方：探测间隔回 180 秒
            quietProbeCount = 0
            // 「还在远行」的证据只认**净位移增量**（相对锚点再走 300m），不用绝对值：
            // 人在离家 5km 的办公室坐着时绝对值一直是 5km，用它判会把 MOVING 钉死一整天。
            if (distance - lastFarMoveDistanceM >= MOVING_KEEP_DISTANCE_M) {
                lastFarMoveDistanceM = distance
                lastFarMoveAtMs = now
            }
            if (state == State.SLEEPING) transition(State.PROBING, "探测位移 " + metersText(distance))
        } else {
            // 没位移证据：下一轮探测按阶梯退避（180 → 300 → 600 秒封顶）。
            // 只数我们自己的探测点（见类 KDoc 的阶梯口径）
            if (fromProbe) quietProbeCount += 1
        }
        return evaluate(now)
    }

    /**
     * 外部环境变化（换网络 / 断网 / 刚插上充电）：把探测退避阶梯清零。
     *
     * 坐车场景的关键补丁：在家连续几次探测没位移，退避会拉到 600 秒；用户出门上车后
     * 要等最多 10 分钟才探一次，再等一次确认 —— 轨迹起点晚 10~20 分钟。环境变化是
     * 「可能离开原地」的便宜信号，立刻回 180 秒灵敏度，代价只是多一次探测。
     */
    fun onEnvironmentChanged() {
        quietProbeCount = 0
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
        lastProbeDistanceM = 0.0
        lastProbeSpeedMps = 0.0
        stillSinceMs = 0L
        shakeSinceSampleMs = 0L
        movingSinceMs = 0L
        gaitSinceMs = 0L
        lastFarMoveAtMs = 0L
        lastFarMoveDistanceM = 0.0
        lastActivityAtMs = 0L
        lastSignificantMotionAtMs = 0L
        lastMotionDetectAtMs = 0L
        lastShakeAtMs = 0L
        observationHintAtMs = 0L
        lastEvaluatedAtMs = 0L
        quietProbeCount = 0
        hardwareBaselineSet = false
        lastHardwareTotal = 0L
        lastHardwareStepAtMs = 0L
        wakeStepWindowSteps = 0L
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

        // MOVING 的维持条件：窗口内必须出现过一次 ≥300m 的位移。办公室里的来回走动、
        // 拿起手机、去倒水都不产生这种位移 —— 没有它就退回 PROBING（不建订阅、只低频探测）。
        // 放在 5 分钟降级之后：真躺下不动仍然直接回 SLEEPING，不必多绕一圈 PROBING。
        if (state == State.MOVING && lastFarMoveAtMs > 0L && nowMs - lastFarMoveAtMs >= MOVING_KEEP_WINDOW_MS) {
            degradeToProbing("MOVING " + (MOVING_KEEP_WINDOW_MS / 60_000L) + " 分钟无 ≥" + metersText(MOVING_KEEP_DISTANCE_M) + " 位移")
            return state
        }

        if (state != State.PROBING) return state
        val bySteps = stepDistanceM >= UPGRADE_STEP_DISTANCE_M
        val byProbe = probeDistanceM >= UPGRADE_PROBE_DISTANCE_M
        val byDuration = movingSinceMs > 0L &&
            nowMs - movingSinceMs >= UPGRADE_DURATION_MS &&
            movementDistanceM >= UPGRADE_DURATION_MIN_DISTANCE_M
        // 坐车：单次探测就走出 300m 且速度 ≥5 m/s（18 km/h，步行 1.4 m/s 不可能到）
        val byCar = lastProbeDistanceM >= UPGRADE_CAR_DISTANCE_M &&
            lastProbeSpeedMps >= UPGRADE_CAR_SPEED_MPS
        if (bySteps || byProbe || byDuration || byCar) {
            val why = when {
                bySteps -> "步数距离 " + metersText(stepDistanceM) +
                    " ≥ " + metersText(UPGRADE_STEP_DISTANCE_M)
                byProbe -> "探测位移 " + metersText(probeDistanceM) +
                    " ≥ " + metersText(UPGRADE_PROBE_DISTANCE_M)
                byCar -> "坐车 " + metersText(lastProbeDistanceM) +
                    " @ " + String.format(Locale.ROOT, "%.1f", lastProbeSpeedMps) + "m/s"
                else -> "持续移动 " + (UPGRADE_DURATION_MS / 60_000L) + " 分钟且位移 " +
                    metersText(movementDistanceM) + " ≥ " + metersText(UPGRADE_DURATION_MIN_DISTANCE_M)
            }
            // 升级这一刻本身就是「还在远行」的最新证据；不置位会被下一次 evaluate 立刻判成超窗口
            if (lastFarMoveAtMs == 0L) {
                lastFarMoveAtMs = nowMs
                lastFarMoveDistanceM = probeDistanceM
            }
            transition(State.MOVING, why)
        }
        return state
    }

    /**
     * MOVING → PROBING：还在动，但没有远行实质，先收回订阅。
     *
     * 与回 SLEEPING 的区别只是目标态：PROBING 仍按 180 秒起的阶梯探测，人真的上车/走远
     * 下次探测就能重新升级；锚点同样清空（否则「上午从家到公司」的旧锚点会把 PROBING
     * 一路顶回 MOVING，等于没降级）。
     */
    private fun degradeToProbing(why: String) {
        transition(State.PROBING, why)
        clearTripAnchors()
    }

    private fun degradeToSleeping(why: String) {
        transition(State.SLEEPING, why)
        clearTripAnchors()
        // 回到 SLEEPING 后探测由兜底接管，退避阶梯重新起算
        quietProbeCount = 0
    }

    /** 行程锚点整体作废：步数、探测位移、活动时间、远行证据一起清（不清硬件步数基线）。 */
    private fun clearTripAnchors() {
        // 不清硬件步数基线：步数计是开机累计值，清掉基线会让下一次增量算成「一整天」
        stepsSinceAnchor = 0L
        probeAnchor = null
        probeDistanceM = 0.0
        lastProbeDistanceM = 0.0
        lastProbeSpeedMps = 0.0
        lastFarMoveAtMs = 0L
        lastFarMoveDistanceM = 0.0
        stillSinceMs = 0L
        movingSinceMs = 0L
        lastActivityAtMs = 0L
        // 降级后探测阶梯重新起算（PROBING 从 180 秒开始）
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
        /** 成人步行步幅：口径给定的 0.7m/步。 */
        const val STEP_LENGTH_M = 0.7

        /** 降级：连续无活动（静止，或无步数无位移）5 分钟。 */
        const val SLEEP_DOWN_MS = 5 * 60_000L

        /**
         * 唤醒门槛：睡着时累计步数折算距离 ≥ 40m（约 57 步）才进 PROBING。
         *
         * 依据是用户的诉求：**确认真的走出了距离才开始监听**。零步数（拿手机、擦桌子、口袋晃动）
         * 不产生步数增量，本来就不会唤醒；门槛挡的是「起身走两步又坐下」这类室内挪动 ——
         * 它在旧口径里等于「远行已开始」，会白白点亮 180 秒一轮的探测。
         * 40m 的选取：走去倒水 / 上厕所来回约 30~60 步，正常出门至少几百步，
         * 门槛落在这两件事之间。设太大（如 100m）会让「出门走到公交站」的起点晚 1 分钟。
         */
        const val WAKE_STEP_DISTANCE_M = 40.0

        /**
         * 唤醒窗口：连续走这么久才算「一次出门」，断档超过它就把窗口步数清零。
         *
         * 与 [WAKE_STEP_DISTANCE_M] 配对：57 步约 30 秒步行（110 步/分）。窗口取 2 分钟留出一倍余量，
         * 既容得下路口等一次红灯，又挡得住「坐一天、时不时起身」的累计作弊。
         */
        const val WAKE_STEP_WINDOW_MS = 2 * 60_000L

        /**
         * 无硬件步数时的唤醒门槛：连续步态 [WAKE_GAIT_MS]。
         *
         * 30 秒步行 ≈ 55 步 ≈ 40m，与 [WAKE_STEP_DISTANCE_M] 同义（两套传感器走同一口径）。
         * 取「连续」而不是「累计」：见 [onSteps] 的 KDoc，累计口径会把「坐一天、时时起身」判成远行。
         */
        const val WAKE_GAIT_MS = 30_000L

        /** 升级：步数折算距离 500m。 */
        const val UPGRADE_STEP_DISTANCE_M = 500.0

        /** 升级：探测点相对锚点位移 500m。 */
        const val UPGRADE_PROBE_DISTANCE_M = 500.0

        /** 升级：持续移动 10 分钟。 */
        const val UPGRADE_DURATION_MS = 10 * 60_000L

        /** 升级：持续移动满 10 分钟时仍要求 300m 位移（原地晃 10 分钟不算远行）。 */
        const val UPGRADE_DURATION_MIN_DISTANCE_M = 300.0

        /** 坐车升级：单次探测位移 ≥300m。城市道路 3 分钟约 400~600m，一次探测即可命中。 */
        const val UPGRADE_CAR_DISTANCE_M = 300.0

        /** 坐车升级：推算速度 ≥5 m/s（18 km/h）。步行 1.4 m/s、跑步 3 m/s 都到不了。 */
        const val UPGRADE_CAR_SPEED_MPS = 5.0

        /** 探测位移噪声地板：小于它的位移视为原地抖动，不刷新活动时间。 */
        const val PROBE_NOISE_FLOOR_M = 30.0

        /** MOVING 维持判据：窗口内必须出现过一次这个规模的位移，否则退回 PROBING。 */
        const val MOVING_KEEP_DISTANCE_M = 300.0

        /** MOVING 维持窗口：30 分钟。短途车程/通勤远大于它，办公室里待着远小于它。 */
        const val MOVING_KEEP_WINDOW_MS = 30 * 60_000L

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
