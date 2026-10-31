package com.bsfeng.monitor.record

import kotlin.math.sqrt

/**
 * 运动识别：用加速度计区分「静止 / 手持操作 / 真实步态」，决定要不要定位、要不要加密。
 *
 * 为什么必须分三态：**拿起手机玩、手指在屏幕上划，加速度计一样抖**。只判「动/不动」会把
 * 玩手机当成移动，定位白白恢复并加密 —— 用户实测反馈的正是这一点。真实位移只有走路/跑步
 * 才有**稳定步频**，手持操作是随机抖动，两者可以用周期稳定性分开。
 *
 * 判定口径（纯逻辑，边界在单测里钉死）：
 * - 取加速度模长（含重力）在最近 [WINDOW_MS] 内的标准差；
 * - [State.STILL]：标准差 < [STILL_STDDEV] 持续 [STILL_CONFIRM_MS]（手机放桌上）；
 * - [State.GAIT]：检出**稳定周期**（过零间隔的变异系数 < [GAIT_MAX_CV]）持续
 *   [GAIT_CONFIRM_MS] —— 走路/跑步的垂直加速度是 1.5–2.5Hz 的规律波；
 * - [State.HANDHELD]：有抖动但没有稳定周期（玩手机、拿起来看）**或剧烈但无规律**。
 *
 * **采样率约束（真机实测）**：必须以 ≥16Hz（`SENSOR_DELAY_UI`）注册。走路步频 1.5–2.5Hz、
 * 半周期 200–330ms，5Hz 采样会把间隔测成 200/400 交替，变异系数必然超标、步态永远检不出。
 * 另外传感器必须在 `startForeground` **之后**注册：HyperOS 会在前台之前静默拒绝后台传感器注册。
 *
 * 三态怎么用（见 [com.bsfeng.monitor.service.LocationForegroundService]）：
 * STILL 停定位；HANDHELD 只做低频探测（玩手机不该耗电）；GAIT 才加密。
 * **坐车不靠加速度计**：匀速行驶时手机几乎不抖，必须靠低频定位的**位移**来升级（见采集器）。
 */
class MotionDetector(
    private val windowMs: Long = WINDOW_MS,
) {

    enum class State {
        /** 长时间没有加速度变化：手机放着没动。 */
        STILL,

        /** 有抖动但无稳定周期：手持操作 / 拿起看 / 无规律的晃动。 */
        HANDHELD,

        /** 检出稳定步频：走路 / 跑步。 */
        GAIT,
    }

    private data class Sample(val magnitude: Float, val tsMs: Long)

    private val samples = ArrayDeque<Sample>()

    /** 进入「候选态」的时刻；0 表示不在候选中。 */
    private var stillSinceMs: Long = 0L
    private var gaitSinceMs: Long = 0L

    /**
     * 当前状态：默认 [State.HANDHELD]。
     *
     * 默认不是 MOVING/GAIT —— 没有传感器信号时不该假设用户在移动、然后持续烧电；
     * 也不能假设 STILL 而完全不采（那就漏了）。HANDHELD 走低频探测，是安全的中庸值。
     */
    var state: State = State.HANDHELD
        private set

    /** 最近一次算出的模长标准差（诊断用：判断「没数据」还是「判定不过」）。 */
    var lastStdDev: Double = 0.0
        private set

    /** 当前窗口内的样本数（诊断用）。 */
    val windowSize: Int get() = samples.size

    /** 喂一个加速度计样本，返回更新后的状态。 */
    fun onAccelerometer(x: Float, y: Float, z: Float, tsMs: Long): State {
        val magnitude = sqrt(x * x + y * y + z * z)
        samples.addLast(Sample(magnitude, tsMs))
        while (samples.isNotEmpty() && samples.first().tsMs < tsMs - windowMs) {
            samples.removeFirst()
        }
        if (samples.size < MIN_SAMPLES) return state
        val stdDev = stdDevOf(samples)
        lastStdDev = stdDev
        // 一旦进入「不可能是运动」的区间，就开始累计静止时长（灰色带里继续累计）
        if (stdDev < STILL_RESET_STDDEV && stillSinceMs == 0L) stillSinceMs = tsMs
        when {
            // 明显静止：优先于步态判定（走两步又停下时，安静段应当如实降级）
            stdDev < STILL_STDDEV -> {
                gaitSinceMs = 0L
                if (stillSinceMs == 0L) stillSinceMs = tsMs
                if (tsMs - stillSinceMs >= STILL_CONFIRM_MS) {
                    state = State.STILL
                }
            }
            // 稳定步频：走路 / 跑步（优先于静止累加，避免「走着走着被判静止」）。
            // 注意不清静止计时：真正的运动稍后若持续，会在 else 分支（stdDev >= 重置线）清掉。
            isGaitLike(stdDev) -> {
                if (gaitSinceMs == 0L) gaitSinceMs = tsMs
                if (tsMs - gaitSinceMs >= GAIT_CONFIRM_MS) {
                    state = State.GAIT
                }
            }
            // 灰色带：噪声比「静止」高、又够不上「明显运动」——比如手机在桌上被碰一下、
            // 或者人坐在那里拿在手里看。**不清静止计时**，让 60 秒照常累计，
            // 否则一次 0.6 的轻碰就会把整段静止打回原点，定位永远停不下来。
            stdDev < STILL_RESET_STDDEV -> {
                gaitSinceMs = 0L
                if (state == State.STILL) return state
                if (stillSinceMs > 0L && tsMs - stillSinceMs >= STILL_CONFIRM_MS) {
                    state = State.STILL
                }
            }

            // 刚从步态来、此刻抖动却算不出周期（等红灯时窗口混入静止样本）：
            // 保持在 GAIT，宁可多采一会儿，也不要在路口把定位关掉。
            state == State.GAIT -> {
                gaitSinceMs = 0L
            }
            // 有抖动但无周期：手持操作
            else -> {
                stillSinceMs = 0L
                gaitSinceMs = 0L
                state = State.HANDHELD
            }
        }
        return state
    }

    /** 恢复到初始状态（服务重建 / 用户重新开启定位时调用）。 */
    fun reset() {
        samples.clear()
        stillSinceMs = 0L
        gaitSinceMs = 0L
        state = State.HANDHELD
    }

    /**
     * 步态检测：模长减去窗口均值后**过零**，看过零间隔是否稳定。
     *
     * 走路 1.5–2.5Hz → 过零半周期约 200–330ms 且间隔稳定；手持划屏是随机抖动，
     * 过零间隔的变异系数远大于阈值。这是区分「走路」和「玩手机」的唯一可靠信号。
     */
    private fun isGaitLike(stdDev: Double): Boolean {
        // 幅度门槛：手持划屏的随机抖动幅度本来就小（0.2 量级），步态起伏通常在 0.5 以上
        if (stdDev < GAIT_MIN_STDDEV) return false
        if (samples.size < MIN_GAIT_SAMPLES) return false
        val mean = samples.sumOf { it.magnitude.toDouble() } / samples.size
        val crossings = ArrayList<Long>(samples.size)
        var previous = Double.NaN
        for (sample in samples) {
            val centered = sample.magnitude.toDouble() - mean
            if (!previous.isNaN() && previous < 0.0 && centered >= 0.0) crossings.add(sample.tsMs)
            previous = centered
        }
        if (crossings.size < MIN_CROSSINGS) return false
        val intervals = crossings.zipWithNext { a, b -> (b - a).toDouble() }
        val average = intervals.average()
        if (average < MIN_HALF_PERIOD_MS || average > MAX_HALF_PERIOD_MS) return false
        val variance = intervals.sumOf { (it - average) * (it - average) } / intervals.size
        val cv = sqrt(variance) / average
        return cv < GAIT_MAX_CV
    }

    private fun stdDevOf(window: Collection<Sample>): Double {
        var sum = 0.0
        var sumSq = 0.0
        for (sample in window) {
            val v = sample.magnitude.toDouble()
            sum += v
            sumSq += v * v
        }
        val n = window.size.toDouble()
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        return sqrt(variance)
    }

    private companion object {
        /** 计算标准差的滑动窗口：3 秒足够覆盖一次抬手/起身，又不会被更早的动作污染。 */
        const val WINDOW_MS = 3_000L

        /** 窗口内至少这么多个样本才做判定（约 0.4 秒的 SENSOR_DELAY_NORMAL 数据）。 */
        const val MIN_SAMPLES = 8

        /**
         * 静止判定：模长标准差低于此值视为「没动」。
         *
         * 真机实测（HyperOS / 手机平放桌面）：传感器自身噪声在 0.05–0.15 之间波动，
         * 取 0.12 时会每隔二三十秒被一次 0.15 的噪声打断，`连续静止 60 秒` 永远攒不满，
         * 状态卡在 HANDHELD、定位订阅永不释放 —— 用户看到的就是「明明没动，定位没停过」。
         * 0.3 能吃掉这层噪声，又低于「拿起手机看屏幕」的抖动（0.3–1.0）。
         */
        const val STILL_STDDEV = 0.3

        /** 确认静止需要的持续时间：60 秒，避免等红灯 / 地铁停站把定位关掉。 */
        const val STILL_CONFIRM_MS = 60_000L

        /**
         * 静止计时的重置线：标准差超过它才算「明显在动」并清空静止计时。
         *
         * 真机实测：手机平放在桌上也会每几十秒出现一次 0.5–0.7 的尖峰（被碰、桌面震动）。
         * 若用 [STILL_STDDEV] 作重置线，一次轻碰就把 60 秒累计清零，状态永远回不到 STILL。
         */
        const val STILL_RESET_STDDEV = 1.5

        /**
         * 步态判定至少需要的过零次数：5 次（2Hz 步频约 2.5 秒）。
         *
         * 取 3 次时随机抖动偶尔也能凑出「看起来规律」的三个间隔 —— 实测会把玩手机误判成走路；
         * 取 6 次又要求窗口里刚好有 3 个完整周期，边界太紧（2Hz 下正好卡在 3 秒窗口边缘）。
         */
        const val MIN_CROSSINGS = 5

        /** 步态判定至少需要的样本数（约 2 秒的 5Hz 数据）。 */
        const val MIN_GAIT_SAMPLES = 10

        /** 步态判定要求的起伏幅度：手持随机抖动约 0.2，走路起伏通常 >0.5。 */
        const val GAIT_MIN_STDDEV = 0.3

        /** 过零半周期的合理范围（毫秒）：对应步频 0.8–4Hz。 */
        const val MIN_HALF_PERIOD_MS = 200.0
        const val MAX_HALF_PERIOD_MS = 1_200.0

        /** 过零间隔变异系数上限：走路 ≈0.1–0.25，手持随机抖动通常 >0.5。 */
        const val GAIT_MAX_CV = 0.35

        /** 确认步态需要的持续时间：走 5 秒才加密，避免偶尔甩一下手机就恢复高频定位。 */
        const val GAIT_CONFIRM_MS = 5_000L
    }
}
