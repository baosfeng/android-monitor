package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 运动识别三态：静止 / 手持 / 步态。
 *
 * 真机背景：用户拿起手机玩，加速度计一样会抖 —— 只判「动/不动」会把玩手机当成移动，
 * 定位白恢复并加密。真实位移只有走路/跑步才有**稳定步频**，手持操作是随机抖动。
 */
class MotionDetectorTest {

    private val start = 1_700_000_000_000L

    /** 以 200ms 间隔生成一段加速度序列；[magnitudeOf] 决定该时刻的模长。 */
    private fun feed(
        detector: MotionDetector,
        fromMs: Long,
        durationMs: Long,
        magnitudeOf: (elapsedMs: Long) -> Float,
    ): Long {
        var ts = fromMs
        while (ts <= fromMs + durationMs) {
            detector.onAccelerometer(0f, 0f, magnitudeOf(ts - fromMs), ts)
            ts += 200L
        }
        return ts
    }

    private fun still(): (Long) -> Float = { 9.81f }

    /** 走路：2Hz 稳定步频，模长按正弦起伏（过零半周期约 250ms）。 */
    private fun gait(): (Long) -> Float = { elapsed ->
        (9.81 + sin(2.0 * PI * 2.0 * (elapsed / 1000.0))).toFloat()
    }

    /** 手持划屏：随机抖动，没有稳定周期。 */
    private fun handheld(seed: Int = 7): (Long) -> Float {
        val random = Random(seed)
        return { 9.81f + random.nextFloat() * 0.8f - 0.4f }
    }

    @Test
    fun `默认是手持态 没有信号时走低频探测`() {
        assertEquals(MotionDetector.State.HANDHELD, MotionDetector().state)
    }

    @Test
    fun `静止 60 秒才判静止`() {
        val detector = MotionDetector()
        var ts = feed(detector, start, 59_000L, still())
        assertEquals("59 秒还不能停定位", MotionDetector.State.HANDHELD, detector.state)
        feed(detector, ts, 3_000L, still())
        assertEquals("满 60 秒才切静止", MotionDetector.State.STILL, detector.state)
    }

    @Test
    fun `手机放桌上传感器噪声 0_15 仍判静止`() {
        // 真机实测：平放时模长噪声在 0.05–0.15 之间，旧阈值 0.12 会被每次噪声打断，
        // 「连续静止 60 秒」永远攒不满，定位订阅永不释放。
        val random = Random(11)
        val noisyStill: (Long) -> Float = { 9.81f + random.nextFloat() * 0.3f - 0.15f }
        val detector = MotionDetector()
        feed(detector, start, 70_000L, noisyStill)
        assertEquals("0.15 量级的噪声属于静止", MotionDetector.State.STILL, detector.state)
    }

    @Test
    fun `走路 5 秒检出步态`() {
        val detector = MotionDetector()
        feed(detector, start, 3_000L, gait())
        assertEquals("窗口填充期不立刻加密", MotionDetector.State.HANDHELD, detector.state)
        feed(detector, start + 3_200L, 5_500L, gait())
        assertEquals("稳定步频必须识别为步态", MotionDetector.State.GAIT, detector.state)
    }

    @Test
    fun `玩手机随机抖动不算步态`() {
        // 关键回归：拿起手机玩 60 秒，抖动没有稳定周期，不能当成在走路
        val detector = MotionDetector()
        feed(detector, start, 60_000L, handheld())
        assertEquals(MotionDetector.State.HANDHELD, detector.state)
    }

    @Test
    fun `玩手机 60 秒后放下 才回到静止`() {
        val detector = MotionDetector()
        var ts = feed(detector, start, 30_000L, handheld())
        assertEquals(MotionDetector.State.HANDHELD, detector.state)
        ts = feed(detector, ts, 70_000L, still())
        assertEquals(MotionDetector.State.STILL, detector.state)
    }

    @Test
    fun `步态中停下 60 秒回到静止`() {
        val detector = MotionDetector()
        var ts = feed(detector, start, 8_000L, gait())
        assertEquals(MotionDetector.State.GAIT, detector.state)
        ts = feed(detector, ts, 70_000L, still())
        assertEquals(MotionDetector.State.STILL, detector.state)
    }

    @Test
    fun `等红灯 30 秒不误判`() {
        val detector = MotionDetector()
        var ts = feed(detector, start, 8_000L, gait())
        ts = feed(detector, ts, 30_000L, still())
        assertEquals("静止 30 秒未到 60 秒，保持 GAIT", MotionDetector.State.GAIT, detector.state)
    }

    @Test
    fun `样本太少不判定`() {
        val detector = MotionDetector()
        repeat(5) { detector.onAccelerometer(0f, 0f, 9.81f, start + it * 200L) }
        assertEquals("不到 8 个样本不做结论", MotionDetector.State.HANDHELD, detector.state)
    }

    @Test
    fun `reset 回到手持态`() {
        val detector = MotionDetector()
        feed(detector, start, 70_000L, still())
        assertEquals(MotionDetector.State.STILL, detector.state)
        detector.reset()
        assertEquals(MotionDetector.State.HANDHELD, detector.state)
    }
}
