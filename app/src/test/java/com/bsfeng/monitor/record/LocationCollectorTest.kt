package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 定位过滤/节流规则 + 「缓存空 → 单次主动定位」编排的用例。
 *
 * 全部纯逻辑：不依赖真实 LocationManager（`LocationCollector.Env` / `SingleFixSource` 都是可注入的）。
 */
class LocationCollectorTest {

    private val now = 1_700_000_000_000L

    private fun sample(
        provider: String = "network",
        accuracy: Float = 30f,
        ageMs: Long = 60_000L,
        lat: Double = 31.2304,
        lng: Double = 121.4737,
    ) = LocationSample(provider, lat, lng, accuracy, now - ageMs)

    // ===== 时间新鲜度 =====

    @Test
    fun `刚拿到的点算新鲜`() {
        assertTrue(LocationPolicy.isFresh(now, now))
        assertTrue(LocationPolicy.isFresh(now - LocationPolicy.MAX_AGE_MS, now))
    }

    @Test
    fun `超过30分钟视为 stale`() {
        assertFalse(LocationPolicy.isFresh(now - LocationPolicy.MAX_AGE_MS - 1, now))
        assertFalse(LocationPolicy.isFresh(now - 31 * 60 * 1000L, now))
    }

    @Test
    fun `时钟回拨不算 stale`() {
        assertTrue(LocationPolicy.isFresh(now + 5 * 60 * 1000L, now))
    }

    // ===== 精度 =====

    @Test
    fun `精度门槛是100米`() {
        assertTrue(LocationPolicy.isAccurate(100f))
        assertTrue(LocationPolicy.isAccurate(5f))
        assertFalse(LocationPolicy.isAccurate(100.1f))
        assertFalse(LocationPolicy.isAccurate(2000f))
    }

    @Test
    fun `精度缺失按不可用处理`() {
        assertFalse(LocationPolicy.isAccurate(0f))
        assertFalse(LocationPolicy.isAccurate(-1f))
    }

    // ===== 挑选 =====

    @Test
    fun `passive 优先 其次 network 最后 gps`() {
        val candidates = listOf(
            sample(provider = "gps", accuracy = 5f),
            sample(provider = "network", accuracy = 50f),
            sample(provider = "passive", accuracy = 80f),
        )
        assertEquals("passive", LocationPolicy.pick(candidates, now)?.provider)
        assertEquals("network", LocationPolicy.pick(candidates.dropLast(1), now)?.provider)
        assertEquals("gps", LocationPolicy.pick(listOf(candidates[0]), now)?.provider)
    }

    @Test
    fun `高优先级不可用时回落到低优先级`() {
        val stalePassive = sample(provider = "passive", ageMs = 31 * 60 * 1000L)
        val coarseNetwork = sample(provider = "network", accuracy = 500f)
        val goodGps = sample(provider = "gps", accuracy = 8f)
        assertEquals("gps", LocationPolicy.pick(listOf(stalePassive, coarseNetwork, goodGps), now)?.provider)
    }

    @Test
    fun `全部不可用时返回 null 不猜位置`() {
        val candidates = listOf(
            sample(provider = "passive", ageMs = 60 * 60 * 1000L),
            sample(provider = "network", accuracy = 800f),
        )
        assertNull(LocationPolicy.pick(candidates, now))
        assertNull(LocationPolicy.pick(emptyList(), now))
    }

    // ===== 坐标量化 =====

    @Test
    fun `落库坐标保留六位小数`() {
        assertEquals("31.230400", LocationPolicy.quantize(31.2304))
        assertEquals("121.473700", LocationPolicy.quantize(121.4737))
        assertEquals("-33.868800", LocationPolicy.quantize(-33.8688))
    }

    @Test
    fun `六位小数能看出几十米的移动 三位不行`() {
        // 原 bug：3 位小数（≈110m 一格）把 40m 的真实移动压成同一个点，轨迹是一级级阶梯
        val from = LocationPolicy.quantize(31.2300)
        val to = LocationPolicy.quantize(31.2304)
        assertEquals("六位小数必须区分 40m 的位移", false, from == to)
        assertEquals("三位小数会把它压成同一个点（这就是旧行为）", true, coarseOf(31.2300) == coarseOf(31.2304))
    }

    @Test
    fun `极小负坐标归一成零避免同一个点两种写法`() {
        assertEquals("0", LocationPolicy.quantize(-0.0000001))
        assertEquals("0", LocationPolicy.quantize(0.0))
    }

    @Test
    fun `对外粗坐标仍是三位小数 隐私口径不变`() {
        assertEquals("31.230", LocationPolicy.quantizeCoarse(31.2304))
        assertEquals("121.474", LocationPolicy.quantizeCoarse(121.4737))
        assertEquals("0", LocationPolicy.quantizeCoarse(-0.0001))
    }

    @Test
    fun `旧三位小数字符串仍能解析回接近的坐标`() {
        // 向后兼容：库里历史记录的 lat/lng 是 3 位小数字符串，读回后必须能 toDouble
        val legacy = "31.230"
        val parsed = legacy.toDouble()
        assertEquals(31.230, parsed, 1e-9)
        assertTrue("量化为零后也必须可解析", LocationPolicy.quantize(0.0).toDouble() == 0.0)
        assertEquals("再把 6 位版量化回去应等于原值", "31.230000", LocationPolicy.quantize(parsed))
    }

    private fun coarseOf(value: Double): String = String.format(Locale.US, "%.3f", value)

    // ===== 节流 =====

    @Test
    fun `节流窗口按传入的模式间隔生效`() {
        val throttle = LocationThrottle()
        assertTrue(throttle.canAcquire(now))
        throttle.markAcquired(now)
        // 移动：30 秒一个点
        assertFalse("移动模式 29 秒还不够", throttle.canAcquire(now + 29_000L, LocationPace.MOVING_INTERVAL_MS))
        assertTrue("移动模式满 30 秒可以再采", throttle.canAcquire(now + 30_000L, LocationPace.MOVING_INTERVAL_MS))
        // 静止：3 分钟一个点
        assertFalse("静止模式 2 分钟内不能再采", throttle.canAcquire(now + 2 * 60_000L, LocationPace.STILL_INTERVAL_MS))
        assertTrue("静止模式满 3 分钟可以再采", throttle.canAcquire(now + 3 * 60_000L, LocationPace.STILL_INTERVAL_MS))
    }

    @Test
    fun `默认间隔是最省电的静止间隔`() {
        val throttle = LocationThrottle()
        throttle.markAcquired(now)
        // 调用方漏传间隔时按静止处理：1 分钟内不会再采
        assertFalse(throttle.canAcquire(now + 60_000L))
        assertTrue(throttle.canAcquire(now + LocationPace.STILL_INTERVAL_MS))
    }

    @Test
    fun `采集失败不占用节流窗口`() {
        val throttle = LocationThrottle()
        // 只看时间窗、不 mark：下一次立刻还能采
        assertTrue(throttle.canAcquire(now))
        assertTrue(throttle.canAcquire(now + 1_000L))
        throttle.markAcquired(now)
        throttle.reset()
        assertTrue(throttle.canAcquire(now + 1_000L))
    }

    // ===== 权限四态 =====

    @Test
    fun `定位权限四态`() {
        assertEquals(
            LocationPermissionStatus.NOT_GRANTED,
            LocationCollector.resolveStatus(fineGranted = false, coarseGranted = false, backgroundGranted = false),
        )
        assertEquals(
            LocationPermissionStatus.COARSE_ONLY,
            LocationCollector.resolveStatus(fineGranted = false, coarseGranted = true, backgroundGranted = false),
        )
        assertEquals(
            LocationPermissionStatus.MISSING_BACKGROUND,
            LocationCollector.resolveStatus(fineGranted = true, coarseGranted = true, backgroundGranted = false),
        )
        assertEquals(
            LocationPermissionStatus.GRANTED,
            LocationCollector.resolveStatus(fineGranted = true, coarseGranted = true, backgroundGranted = true),
        )
    }

    @Test
    fun `只给大致位置时 status 走 COARSE_ONLY 而不是已就绪`() {
        // 缺陷回归锚点：修复前这里是 GRANTED（=已就绪），行程页却永远空白
        val collector = LocationCollector(FakeEnv(fine = false, coarse = true, background = true))
        assertEquals(LocationPermissionStatus.COARSE_ONLY, collector.status())
    }

    @Test
    fun `精确位置加后台时 status 才是已就绪`() {
        val collector = LocationCollector(FakeEnv(fine = true, coarse = true, background = true))
        assertEquals(LocationPermissionStatus.GRANTED, collector.status())
    }

    @Test
    fun `只给大致位置时仍会发起采集而不是误报未授权`() {
        // 采集闸按「至少有一个位置权限」：只给大致位置也要采（点会被精度闸丢），
        // 否则失败原因会误报成 PERMISSION，把用户引到「始终允许」这条错路上。
        val fresh = LocationSample("network", 39.9042, 116.4074, 1200f, now)
        val env = FakeEnv(
            fine = false,
            coarse = true,
            background = true,
            cached = listOf(fresh),
        )
        val collector = LocationCollector(env)
        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertNull("精度不足的点应被丢弃", result)
        assertNotEquals(
            "不该报成未授权",
            LocationFailureReason.PERMISSION,
            collector.health(now).lastFailure,
        )
    }

    @Test
    fun `两种位置权限都没有时不采集`() {
        val env = FakeEnv(fine = false, coarse = false, background = false)
        val collector = LocationCollector(env)
        assertNull(collector.collect(now))
        var called = false
        collector.collectThrottledAsync(now) { called = true; assertNull(it) }
        assertTrue("回调必须恰好一次", called)
        assertEquals(LocationFailureReason.PERMISSION, collector.health(now).lastFailure)
    }

    @Test
    fun `假 Env 不实现 isCoarseGranted 时默认跟随 isFineGranted`() {
        // 默认实现的意义：既有假 Env 只写了 isFineGranted，不应被迫全量实现
        val legacy = object : LocationCollector.Env {
            override fun isFineGranted(): Boolean = true
            override fun isBackgroundGranted(): Boolean = false
            override fun readCached(): List<LocationSample> = emptyList()
            override fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit) = onResult(null)
        }
        assertTrue("精确位置默认蕴含大致位置", legacy.isCoarseGranted())
        assertEquals(LocationPermissionStatus.MISSING_BACKGROUND, LocationCollector(legacy).status())
    }

    // ===== 缓存空 → 单次主动定位（task-9 的核心回路） =====

    /** 注入式的假环境：不碰 LocationManager。 */
    private class FakeEnv(
        var fine: Boolean = true,
        var background: Boolean = true,
        /** 大致位置独立可配：默认跟随 fine（精确位置必然包含大致位置）。 */
        var coarse: Boolean = fine,
        var cached: List<LocationSample> = emptyList(),
        var onActive: (Long, (LocationSample?) -> Unit) -> Unit = { _, callback -> callback(null) },
    ) : LocationCollector.Env {
        var activeRequests = 0
        var lastRequestedAt = 0L

        override fun isFineGranted(): Boolean = fine
        override fun isCoarseGranted(): Boolean = coarse
        override fun isBackgroundGranted(): Boolean = background
        override fun readCached(): List<LocationSample> = cached
        override fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit) {
            activeRequests++
            lastRequestedAt = nowMs
            onActive(nowMs, onResult)
        }
    }

    @Test
    fun `缓存为空时进入单次主动定位且成功即占节流窗口`() {
        val fresh = LocationSample("network", 39.9042, 116.4074, 30f, now)
        val env = FakeEnv(cached = emptyList(), onActive = { _, callback -> callback(fresh) })
        val collector = LocationCollector(env)

        var result: LocationSample? = null
        var callbacks = 0
        collector.collectThrottledAsync(now) {
            result = it
            callbacks++
        }
        assertEquals("缓存空必须发起一次主动定位", 1, env.activeRequests)
        assertEquals("回调必须恰好一次", 1, callbacks)
        assertSame(fresh, result)

        // 成功后 10 分钟内不再采（节流被占用）
        var second: LocationSample? = null
        collector.collectThrottledAsync(now + 1_000L) { second = it }
        assertEquals(1, env.activeRequests)
        assertNull(second)
    }

    @Test
    fun `主动定位超时返回 null 且不占节流窗口`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, callback -> callback(null) })
        val collector = LocationCollector(env)

        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertNull(result)
        assertEquals(1, env.activeRequests)

        // 失败（超时/过滤空）不占用窗口：下一个心跳会重新发起
        collector.collectThrottledAsync(now + 60_000L) { result = it }
        assertNull(result)
        assertEquals("超时后下一次心跳仍应重新请求", 2, env.activeRequests)
    }

    @Test
    fun `缓存命中的可用点不发主动请求`() {
        val cached = LocationSample("passive", 31.23, 121.47, 25f, now)
        val env = FakeEnv(cached = listOf(cached))
        val collector = LocationCollector(env)

        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertEquals(0, env.activeRequests)
        assertSame(cached, result)
    }

    @Test
    fun `缓存只有 stale 或超容差点时才发起主动定位`() {
        val stale = LocationSample("passive", 31.23, 121.47, 20f, now - LocationPolicy.MAX_AGE_MS - 1)
        // 精度按 provider 分级后，200m 的 NETWORK 点算低置信但仍可用（见 LocationQuality）；
        // 「不可用」要过了 500m 的容差线。
        val coarse = LocationSample("network", 31.23, 121.47, LocationQuality.NETWORK_LOW_LIMIT_METERS + 100f, now)
        val fresh = LocationSample("network", 39.9042, 116.4074, 18f, now)
        val env = FakeEnv(cached = listOf(stale, coarse), onActive = { _, callback -> callback(fresh) })
        val collector = LocationCollector(env)

        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertEquals(1, env.activeRequests)
        assertSame(fresh, result)
    }

    @Test
    fun `未授权时不发起主动定位`() {
        val env = FakeEnv(fine = false, onActive = { _, callback -> callback(LocationSample("network", 0.0, 0.0, 5f, now)) })
        val collector = LocationCollector(env)
        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertEquals(0, env.activeRequests)
        assertNull(result)
    }

    // ===== 自适应节奏：模式状态机（纯逻辑） =====

    private fun point(
        ts: Long,
        lat: Double = 31.2304,
        lng: Double = 121.4737,
        accuracy: Float = 30f,
    ) = LocationSample("network", lat, lng, accuracy, ts)

    /** 纬度 0.001° ≈ 111m：用它造「走了一段」。 */
    private fun north(lat: Double, meters: Double) = lat + meters / 111_000.0

    @Test
    fun `间隔按模式取 移动30秒 静止3分钟`() {
        assertEquals(30_000L, LocationPace.intervalOf(SampleMode.MOVING))
        assertEquals(180_000L, LocationPace.intervalOf(SampleMode.STILL))
        assertEquals(
            "静止间隔取 1–3 分钟区间的上限（省电优先）",
            3 * 60_000L,
            LocationPace.STILL_INTERVAL_MS,
        )
    }

    @Test
    fun `位移超过100米且在5分钟内 切到移动`() {
        val tracker = LocationModeTracker()
        assertEquals(SampleMode.STILL, tracker.onSample(point(now)))
        val moved = tracker.onSample(point(now + 10_000L, lat = north(31.2304, 150.0)))
        assertEquals(SampleMode.MOVING, moved)
    }

    @Test
    fun `精度明显改善时 50米出头的小位移也算移动`() {
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, accuracy = 80f))
        val moved = tracker.onSample(
            point(now + 20_000L, lat = north(31.2304, 70.0), accuracy = 20f),
        )
        assertEquals("精度从 80m 改善到 20m 且位移 > 50m", SampleMode.MOVING, moved)
    }

    @Test
    fun `精度没改善时 70米位移不足以切移动`() {
        val tracker = LocationModeTracker()
        tracker.onSample(point(now, accuracy = 30f))
        val still = tracker.onSample(point(now + 20_000L, lat = north(31.2304, 70.0), accuracy = 30f))
        assertEquals(SampleMode.STILL, still)
    }

    @Test
    fun `跨天或长断档的大跳变不算移动`() {
        val tracker = LocationModeTracker()
        tracker.onSample(point(now))
        // 隔夜 8 小时、位移 5km：中间没有轨迹，不能当成「刚刚走了一步」
        val mode = tracker.onSample(point(now + 8 * 60 * 60_000L, lat = north(31.2304, 5_000.0)))
        assertEquals(SampleMode.STILL, mode)
    }

    @Test
    fun `零位移连续3次回到静止`() {
        val tracker = LocationModeTracker(initialMode = SampleMode.MOVING)
        val p = point(now)
        tracker.onSample(p)
        assertEquals(SampleMode.MOVING, tracker.onSample(point(now + 10_000L)))
        assertEquals(SampleMode.MOVING, tracker.onSample(point(now + 20_000L)))
        assertEquals("第 3 次原地采样才判静止", SampleMode.STILL, tracker.onSample(point(now + 30_000L)))
    }

    @Test
    fun `模式抖动抑制 一次小位移会清零静止计数`() {
        val tracker = LocationModeTracker(initialMode = SampleMode.MOVING)
        tracker.onSample(point(now))
        tracker.onSample(point(now + 10_000L))
        tracker.onSample(point(now + 20_000L))
        // 第 3 次前走了一步（80m > 50m）：计数清零，仍在移动
        val afterMove = tracker.onSample(point(now + 30_000L, lat = north(31.2304, 80.0)))
        assertEquals(SampleMode.MOVING, afterMove)
        // 重新数 3 次原地
        tracker.onSample(point(now + 40_000L, lat = north(31.2304, 80.0)))
        tracker.onSample(point(now + 50_000L, lat = north(31.2304, 80.0)))
        assertEquals(SampleMode.STILL, tracker.onSample(point(now + 60_000L, lat = north(31.2304, 80.0))))
    }

    @Test
    fun `重启后从静止开始 3 分钟内即可重新识别移动`() {
        val tracker = LocationModeTracker()
        assertEquals("新实例默认静止", SampleMode.STILL, tracker.mode)
        tracker.onSample(point(now))
        // 静止间隔就是 3 分钟：下一次采样若已移动，立刻切回移动
        assertEquals(
            SampleMode.MOVING,
            tracker.onSample(point(now + LocationPace.STILL_INTERVAL_MS, lat = north(31.2304, 150.0))),
        )
    }

    // ===== 自适应节奏：采集器编排 =====

    @Test
    fun `移动时30秒内的缓存直接命中 不主动定位`() {
        val cached = LocationSample("passive", 31.23, 121.47, 20f, now - 10_000L)
        val env = FakeEnv(cached = listOf(cached))
        val collector = LocationCollector(env, modeTracker = LocationModeTracker(SampleMode.MOVING))

        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertEquals(0, env.activeRequests)
        assertSame(cached, result)
    }

    @Test
    fun `移动时旧缓存不够新 触发一次主动定位`() {
        val cached = LocationSample("passive", 31.23, 121.47, 20f, now - 5 * 60_000L)
        val fresh = LocationSample("gps", 31.2304, 121.4737, 8f, now)
        val env = FakeEnv(cached = listOf(cached), onActive = { _, callback -> callback(fresh) })
        val collector = LocationCollector(env, modeTracker = LocationModeTracker(SampleMode.MOVING))

        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertEquals("移动节奏不能被 5 分钟前的旧点糊弄", 1, env.activeRequests)
        assertSame(fresh, result)
    }

    @Test
    fun `静止时旧缓存直接用 不主动定位`() {
        val cached = LocationSample("passive", 31.23, 121.47, 20f, now - 5 * 60_000L)
        val env = FakeEnv(cached = listOf(cached))
        val collector = LocationCollector(env, modeTracker = LocationModeTracker(SampleMode.STILL))

        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertEquals(0, env.activeRequests)
        assertSame(cached, result)
    }

    @Test
    fun `采样后模式与间隔同步更新`() {
        val first = LocationSample("network", 31.2304, 121.4737, 30f, now)
        val moved = LocationSample("network", north(31.2304, 200.0), 121.4737, 25f, now + LocationPace.STILL_INTERVAL_MS)
        val env = FakeEnv(cached = listOf(first))
        val collector = LocationCollector(env)

        assertEquals("初始按静止算", SampleMode.STILL, collector.mode)
        assertEquals(LocationPace.STILL_INTERVAL_MS, collector.samplingIntervalMs())
        collector.collectThrottledAsync(now) { }
        // 3 分钟后再采到明显位移 → 切移动，间隔随之为 30 秒
        env.cached = listOf(moved)
        collector.collectThrottledAsync(now + LocationPace.STILL_INTERVAL_MS) { }
        assertEquals(SampleMode.MOVING, collector.mode)
        assertEquals(LocationPace.MOVING_INTERVAL_MS, collector.samplingIntervalMs())
        assertTrue("移动模式下 30 秒即可再采", collector.shouldSampleNow(now + LocationPace.STILL_INTERVAL_MS + 30_000L))
        assertFalse("不到 30 秒不采", collector.shouldSampleNow(now + LocationPace.STILL_INTERVAL_MS + 29_000L))
    }

    // ===== 主动定位的 provider 编排 =====

    private fun recordingSource(answers: Map<String, LocationSample?>): Pair<SingleFixSource, MutableList<String>> {
        val calls = mutableListOf<String>()
        val source = object : SingleFixSource {
            override fun request(provider: String, timeoutMs: Long, onResult: (LocationSample?) -> Unit) {
                calls += provider
                onResult(answers[provider])
            }
        }
        return source to calls
    }

    @Test
    fun `主动定位按 network 然后 gps 顺序请求`() {
        val (source, calls) = recordingSource(emptyMap())
        val resolver = ActiveFixResolver(source)
        var result: LocationSample? = null
        resolver.resolve(now, { true }) { result = it }
        assertEquals(listOf("network", "gps"), calls)
        assertNull(result)
    }

    @Test
    fun `network 拿不到点时回落到 gps`() {
        val gpsFix = LocationSample("gps", 39.9042, 116.4074, 8f, now)
        val (source, calls) = recordingSource(mapOf("gps" to gpsFix))
        val resolver = ActiveFixResolver(source)
        var result: LocationSample? = null
        resolver.resolve(now, { true }) { result = it }
        assertEquals(listOf("network", "gps"), calls)
        assertSame(gpsFix, result)
    }

    @Test
    fun `不可用的 provider 直接跳过`() {
        val (source, calls) = recordingSource(emptyMap())
        val resolver = ActiveFixResolver(source)
        resolver.resolve(now, { it != "network" }) { }
        assertEquals(listOf("gps"), calls)
    }

    @Test
    fun `预算按 provider 独立计 不再均分`() {
        val resolver = ActiveFixResolver(object : SingleFixSource {
            override fun request(provider: String, timeoutMs: Long, onResult: (LocationSample?) -> Unit) = Unit
        })
        // 均分会把 GPS 压到 2500ms（冷启动必然超时）；现在 GPS 单独拿 15 秒
        assertEquals(15_000L, resolver.timeoutMsOf("gps"))
        assertEquals(5_000L, resolver.timeoutMsOf("network"))
        assertEquals(
            "两个 provider 的预算不再相等",
            false,
            resolver.timeoutMsOf("gps") == resolver.timeoutMsOf("network"),
        )
    }

    @Test
    fun `主动定位拿到的点仍要过 stale 与精度过滤`() {
        val staleFix = LocationSample("network", 39.9042, 116.4074, 10f, now - LocationPolicy.MAX_AGE_MS - 1)
        val (source, calls) = recordingSource(mapOf("network" to staleFix))
        val resolver = ActiveFixResolver(source)
        var result: LocationSample? = null
        resolver.resolve(now, { true }) { result = it }
        // network 的点 stale → 继续问 gps；gps 无点 → 放弃
        assertEquals(listOf("network", "gps"), calls)
        assertNull(result)
    }
}
