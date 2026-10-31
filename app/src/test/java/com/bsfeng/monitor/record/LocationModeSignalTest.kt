package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4：模式判定的「订阅派点间隔 + 高置信速度」信号。
 *
 * 旧实现只看位移：STILL 间隔 180 秒，用户走起来要等最多 3 分钟才切到 MOVING，
 * 用户感知为「轨迹断断续续」。这里把两件事拆开：**派点**更密（静止 30s），
 * **落库**仍省电（静止 180s）；再用速度门槛让「走 30 秒」就能一次判定。
 */
class LocationModeSignalTest {

    private val now = 1_700_000_000_000L

    private fun point(
        ts: Long,
        meters: Double = 0.0,
        provider: String = "network",
        accuracy: Float = 30f,
    ) = LocationSample(provider, 31.2304 + meters / 111_000.0, 121.4737, accuracy, ts)

    // ===== 派点间隔与落库间隔拆开 =====

    @Test
    fun `静止探测间隔是30秒 落库间隔仍是180秒`() {
        assertEquals(30_000L, LocationPace.STILL_PROBE_INTERVAL_MS)
        assertEquals(180_000L, LocationPace.STILL_INTERVAL_MS)
        assertEquals("探测必须比落库密", true, LocationPace.STILL_PROBE_INTERVAL_MS < LocationPace.STILL_INTERVAL_MS)
    }

    @Test
    fun `订阅按探测间隔派点 不按落库间隔`() {
        assertEquals(30_000L, LocationSubscriptionPlan.probeIntervalMsOf(SampleMode.STILL))
        assertEquals(30_000L, LocationSubscriptionPlan.probeIntervalMsOf(SampleMode.MOVING))
        assertEquals("落库间隔口径不变", 180_000L, LocationSubscriptionPlan.intervalMsOf(SampleMode.STILL))
    }

    @Test
    fun `省电模式下静止回退到180秒派点`() {
        assertEquals(
            "省电模式优先续航，模式判定变钝是可接受的降级",
            180_000L,
            LocationSubscriptionPlan.probeIntervalMsOf(SampleMode.STILL, powerSave = true),
        )
        assertEquals(30_000L, LocationSubscriptionPlan.probeIntervalMsOf(SampleMode.STILL, powerSave = false))
        assertEquals(
            "移动模式本来就要密，省电也不降级",
            30_000L,
            LocationSubscriptionPlan.probeIntervalMsOf(SampleMode.MOVING, powerSave = true),
        )
    }

    @Test
    fun `静默阈值按探测间隔算`() {
        // 静止 30s x 3 + 15s = 105s（旧口径 555s 会把空窗拖到 9 分钟）
        assertEquals(105_000L, LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL))
        assertEquals(105_000L, LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.MOVING))
    }

    // ===== 速度判定：步行 / 跑步 / 骑车 =====

    @Test
    fun `步行 30秒走42米 判为移动`() {
        // 1.4 m/s x 30s = 42m：静止探测间隔下用户走走就能切到 MOVING，不用等 3 分钟
        val tracker = LocationModeTracker()
        val highAccuracy = 8f
        tracker.onSample(point(now, provider = "gps", accuracy = highAccuracy))
        val moved = tracker.onSample(
            point(now + 30_000L, meters = 42.0, provider = "gps", accuracy = highAccuracy),
        )
        assertEquals("步行必须能判定为移动", SampleMode.MOVING, moved)
    }

    @Test
    fun `跑步 10秒走30米 判为移动`() {
        assertEquals(3.0, 30.0 / 10.0, 1e-9)
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, provider = "gps", accuracy = 6f))
        assertEquals(
            SampleMode.MOVING,
            tracker.onSample(point(now + 10_000L, meters = 30.0, provider = "gps", accuracy = 6f)),
        )
    }

    @Test
    fun `骑车 20秒走100米 判为移动`() {
        assertEquals(5.0, 100.0 / 20.0, 1e-9)
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, provider = "gps", accuracy = 5f))
        assertEquals(
            SampleMode.MOVING,
            tracker.onSample(point(now + 20_000L, meters = 100.0, provider = "gps", accuracy = 5f)),
        )
    }

    @Test
    fun `静止 30秒漂移10米 不判移动`() {
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, provider = "gps", accuracy = 8f))
        assertEquals(
            "10 米是 GPS 抖动，不是移动",
            SampleMode.STILL,
            tracker.onSample(point(now + 30_000L, meters = 10.0, provider = "gps", accuracy = 8f)),
        )
    }

    @Test
    fun `速度不够也不算移动 30秒走20米`() {
        // 0.67 m/s 其实过线了，但位移没过 30 米门槛：先挡抖动，再挡慢速漂移
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, provider = "gps", accuracy = 8f))
        assertEquals(
            SampleMode.STILL,
            tracker.onSample(point(now + 30_000L, meters = 20.0, provider = "gps", accuracy = 8f)),
        )
    }

    @Test
    fun `步行 180秒走252米 仍走旧的位移口径也能判移动`() {
        // 降级路径（省电模式 180s 派点）下不靠速度判定，靠原有的 >100m 位移
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, provider = "gps", accuracy = 8f))
        assertEquals(
            SampleMode.MOVING,
            tracker.onSample(point(now + 180_000L, meters = 252.0, provider = "gps", accuracy = 8f)),
        )
    }

    // ===== 反例：室内高误差点不许误判 =====

    // ===== passive 路径（task-5 回归锚点）=====

    @Test
    fun `两个passive高置信点 40米5秒 必须切移动`() {
        // 原缺陷：passive 永远判 LOW -> 速度判定要求「双高置信」永远不满足 -> 模式永远 STILL
        // -> 采样间隔永远 180s -> 轨迹稀疏漂移（用户说的「地图不准」）。
        // 而 PROVIDER_PRIORITY 把 passive 排第一，系统缓存里几乎总有 passive 点，实际必踩。
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, meters = 0.0, provider = "passive", accuracy = 5f))
        val moved = tracker.onSample(point(now + 5_000L, meters = 40.0, provider = "passive", accuracy = 5f))
        assertEquals("5 米的 passive 点就是高置信，40m/5s = 8m/s 必须切 MOVING", SampleMode.MOVING, moved)
    }

    @Test
    fun `两个passive点 120米5秒 连续注入时切移动`() {
        // 复刻 lead 的实测注入节奏：每 5 秒约 120m，连续 12 个点（共 60 秒）
        val tracker = LocationModeTracker()
        var cursor = 0.0
        tracker.onSample(point(now, meters = cursor, provider = "passive", accuracy = 5f))
        repeat(12) { i ->
            cursor += 120.0
            tracker.onSample(point(now + (i + 1) * 5_000L, meters = cursor, provider = "passive", accuracy = 5f))
        }
        assertEquals("持续移动 60 秒后必须已在 MOVING", SampleMode.MOVING, tracker.mode)
    }

    @Test
    fun `两个300米误差的passive点位移40米 不得判移动`() {
        // 反向保护：低置信 passive 点（300m 误差）不参与速度判定，否则误差圈内的漂移会刷成移动
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, meters = 0.0, provider = "passive", accuracy = 300f))
        assertEquals(
            SampleMode.STILL,
            tracker.onSample(point(now + 5_000L, meters = 40.0, provider = "passive", accuracy = 300f)),
        )
    }

    @Test
    fun `室内两个300米误差的network点位移40米 不得判移动`() {
        // 反例：低置信点的位移本身就不可信，让它参与速度判定会把静止判成移动（漂移刷轨迹）
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, meters = 0.0, provider = "network", accuracy = 300f))
        assertEquals(
            SampleMode.STILL,
            tracker.onSample(point(now + 10_000L, meters = 40.0, provider = "network", accuracy = 300f)),
        )
    }

    // ===== 真机标定：噪声地板（位移必须超过两端精度之和）=====

    @Test
    fun `室内30米精度的点 相距40米抖动不判移动`() {
        // 真机实测：室内 passive 点精度 26–47m，10 秒间隔下相邻点相距 30–50m 全是定位抖动。
        // 旧口径同时满足「位移≥30m」与「速度≥0.45m/s」，把坐着不动判成 MOVING；
        // 每次模式切换都要重建订阅（释放 + 重注册 3 个 listener），实测 90 秒重建 5 次。
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, meters = 0.0, provider = "passive", accuracy = 30f))
        assertEquals(
            "30m 精度两点的噪声地板是 60m，40m 抖动不算移动",
            SampleMode.STILL,
            tracker.onSample(point(now + 10_000L, meters = 40.0, provider = "passive", accuracy = 30f)),
        )
    }

    @Test
    fun `室内47米精度的点 相距50米抖动不判移动`() {
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, meters = 0.0, provider = "passive", accuracy = 47f))
        assertEquals(
            SampleMode.STILL,
            tracker.onSample(point(now + 10_000L, meters = 50.0, provider = "passive", accuracy = 47f)),
        )
    }

    @Test
    fun `室内30米精度但位移超过精度之和 仍判移动`() {
        // 反向保护：不能因为加了噪声地板就永远判不出移动（真的走远了必须能切）
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, meters = 0.0, provider = "passive", accuracy = 30f))
        assertEquals(
            SampleMode.MOVING,
            tracker.onSample(point(now + 10_000L, meters = 80.0, provider = "passive", accuracy = 30f)),
        )
    }

    @Test
    fun `高精度行走不受噪声地板影响 仍是30米门槛`() {
        // 5m 精度两点的地板只有 10m，实际门槛仍是 isMovingBySpeed 的 30m
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, provider = "gps", accuracy = 5f))
        assertEquals(
            SampleMode.MOVING,
            tracker.onSample(point(now + 10_000L, meters = 35.0, provider = "gps", accuracy = 5f)),
        )
    }

    @Test
    fun `一高一低两个点也不用速度判定`() {
        // 只要有一个点不是高置信，速度判定就不参与（距离差 40m、10 秒 = 4 m/s 极具诱惑）
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, provider = "gps", accuracy = 8f))
        assertEquals(
            SampleMode.STILL,
            tracker.onSample(point(now + 10_000L, meters = 40.0, provider = "network", accuracy = 300f)),
        )
    }

    // ===== 纯函数口径 =====

    @Test
    fun `速度门槛是045米每秒`() {
        assertEquals(0.45, LocationPace.MOVE_SPEED_MPS, 1e-9)
        assertEquals(30.0, LocationPace.MOVE_SPEED_MIN_DISTANCE_M, 1e-9)
        assertTrue("步行 1.4m/s 过线", LocationPace.isMovingBySpeed(42.0, 30_000L))
        assertFalse("位移不够 30 米一律不算", LocationPace.isMovingBySpeed(29.9, 10_000L))
        assertFalse("时间差为 0 不算", LocationPace.isMovingBySpeed(100.0, 0L))
        assertFalse("时钟回拨（负间隔）不算", LocationPace.isMovingBySpeed(100.0, -5_000L))
    }

    @Test
    fun `同一个探测点重复喂只算一次`() {
        // 订阅探测点先喂状态机、落库时又喂一次：不去重会把 MOVING 的静止计数提前凑满
        val tracker = LocationModeTracker(SampleMode.MOVING)
        tracker.onSample(point(now, provider = "gps", accuracy = 8f))
        tracker.onSample(point(now + 10_000L, provider = "gps", accuracy = 8f))
        tracker.onSample(point(now + 20_000L, provider = "gps", accuracy = 8f))
        // 同一个时间戳重复喂两次，不能把静止计数顶到 3
        tracker.onSample(point(now + 20_000L, provider = "gps", accuracy = 8f))
        assertEquals("重复点不该触发静止判定", SampleMode.MOVING, tracker.mode)
        assertEquals(SampleMode.STILL, tracker.onSample(point(now + 30_000L, provider = "gps", accuracy = 8f)))
    }
}
