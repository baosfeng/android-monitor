package com.bsfeng.monitor.ui.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MotionConfig] 的纯 JVM 回归（无 Robolectric）：默认档 / 落盘 / 系统优先级 / 自动降档只降一次 / stagger 分档。
 *
 * 这些是产品口径而不是实现细节：默认 FULL（性能机不妥协）、系统动画缩放为 0 时恒为 REDUCED、
 * 卡顿只从 FULL 降到 NORMAL 一次且不自动回升。
 */
class MotionTierTest {

    /** 内存后端：模拟 SharedPreferences，可断言「确实落盘了」。 */
    private class FakeStore(
        var tier: MotionTier? = null,
        var downgraded: Boolean = false,
    ) : MotionTierStore {
        var tierWrites = 0
        var downgradedWrites = 0

        override fun readTier(): MotionTier? = tier

        override fun writeTier(tier: MotionTier) {
            tierWrites++
            this.tier = tier
        }

        override fun readAutoDowngraded(): Boolean = downgraded

        override fun writeAutoDowngraded(downgraded: Boolean) {
            downgradedWrites++
            this.downgraded = downgraded
        }
    }

    // ── 1 · 默认档 ──

    @Test
    fun `未选过档位时默认 FULL`() {
        val config = MotionConfig(FakeStore())

        assertEquals(MotionTier.FULL, MotionConfig.DEFAULT_TIER)
        assertEquals(MotionTier.FULL, config.current())
        assertEquals(MotionTier.FULL, config.userTier.value)
        assertEquals(1f, config.tierScale(), 0f)
    }

    @Test
    fun `重启进程后从存储读回上次选择的档位`() {
        val store = FakeStore(tier = MotionTier.REDUCED)
        val config = MotionConfig(store)

        assertEquals(MotionTier.REDUCED, config.current())
    }

    @Test
    fun `未知名字不抛异常且回落到默认档`() {
        // 存储里是旧版本 / 脏数据（如 "HALF"）时按「未设置」处理
        val store = object : MotionTierStore by FakeStore() {
            override fun readTier(): MotionTier? = null
        }
        assertEquals(MotionTier.FULL, MotionConfig(store).current())
    }

    // ── 2 · 选档 / 落盘 ──

    @Test
    fun `选档写入即落盘并立即生效`() {
        val store = FakeStore()
        val config = MotionConfig(store)

        assertTrue("档位变化应返回 true", config.setTier(MotionTier.NORMAL))
        assertEquals(MotionTier.NORMAL, config.current())
        assertEquals(MotionTier.NORMAL, store.tier)
        assertEquals(0.75f, config.tierScale(), 0f)

        assertFalse("重复设置同一档位返回 false", config.setTier(MotionTier.NORMAL))
        assertEquals(1, store.tierWrites)
    }

    @Test
    fun `恢复默认回到 FULL`() {
        val config = MotionConfig(FakeStore())
        config.setTier(MotionTier.REDUCED)

        config.resetToDefault()

        assertEquals(MotionTier.FULL, config.current())
        assertFalse(config.autoDowngraded.value)
    }

    // ── 3 · 系统动画缩放优先级最高 ──

    @Test
    fun `系统禁止动画时生效档位恒为 REDUCED`() {
        val store = FakeStore(tier = MotionTier.FULL)
        val config = MotionConfig(store) { false }

        assertEquals("系统优先于用户选择", MotionTier.REDUCED, config.current())
        assertEquals("用户选择保留，系统恢复后还原", MotionTier.FULL, config.userTier.value)
        assertEquals(0f, config.tierScale(), 0f)
    }

    @Test
    fun `系统禁止动画时手工选 FULL 也是 REDUCED`() {
        var allowed = false
        val config = MotionConfig(FakeStore()) { allowed }

        config.setTier(MotionTier.FULL)
        assertEquals(MotionTier.REDUCED, config.current())

        // 用户在系统设置里重新打开动画后刷新策略 → 回到用户选择
        allowed = true
        config.refreshSystemPolicy()
        assertEquals(MotionTier.FULL, config.current())
    }

    @Test
    fun `纯函数 effectiveMotionTier 三档逐一对应`() {
        MotionTier.entries.forEach { tier ->
            assertEquals(tier, effectiveMotionTier(tier, animationsAllowed = true))
            assertEquals(MotionTier.REDUCED, effectiveMotionTier(tier, animationsAllowed = false))
        }
    }

    // ── 4 · 卡顿自动降档：只降一次、不回升 ──

    @Test
    fun `自动降档把 FULL 降到 NORMAL 并落盘打标`() {
        val store = FakeStore(tier = MotionTier.FULL)
        val config = MotionConfig(store)

        assertTrue(config.autoDowngradeToNormal())
        assertEquals(MotionTier.NORMAL, config.current())
        assertEquals(MotionTier.NORMAL, store.tier)
        assertTrue("设置页据此显示已自动切到常规", config.autoDowngraded.value)
        assertTrue(store.downgraded)
    }

    @Test
    fun `已是 NORMAL 时不再降档`() {
        val config = MotionConfig(FakeStore(tier = MotionTier.NORMAL))

        assertFalse(config.autoDowngradeToNormal())
        assertEquals(MotionTier.NORMAL, config.current())
        assertFalse("没有发生降档就不该打标", config.autoDowngraded.value)
    }

    @Test
    fun `已是 REDUCED 时不再降档`() {
        val config = MotionConfig(FakeStore(tier = MotionTier.REDUCED))

        assertFalse(config.autoDowngradeToNormal())
        assertEquals(MotionTier.REDUCED, config.current())
    }

    @Test
    fun `连续判定卡顿只降一次`() {
        val store = FakeStore()
        val config = MotionConfig(store)

        repeat(5) { config.autoDowngradeToNormal() }

        assertEquals(MotionTier.NORMAL, config.current())
        assertEquals("落盘只应发生一次", 1, store.tierWrites)
    }

    @Test
    fun `用户手动选档清除自动降档标记`() {
        val store = FakeStore()
        val config = MotionConfig(store)
        config.autoDowngradeToNormal()

        config.setTier(MotionTier.FULL)

        assertFalse(config.autoDowngraded.value)
        assertFalse(store.downgraded)
    }

    // ── 5 · 档位参数 ──

    @Test
    fun `三档时长系数为 1 与 0点75 与 0`() {
        assertEquals(1f, MotionTier.FULL.scale, 0f)
        assertEquals(0.75f, MotionTier.NORMAL.scale, 0f)
        assertEquals(0f, MotionTier.REDUCED.scale, 0f)
    }

    @Test
    fun `时长按档位缩放且 REDUCED 归零`() {
        assertEquals(340, MotionTier.FULL.scaleDuration(340))
        assertEquals(255, MotionTier.NORMAL.scaleDuration(340))
        assertEquals(0, MotionTier.REDUCED.scaleDuration(340))
        // 24ms 这类短 token 缩放后取整
        assertEquals(18, MotionTier.NORMAL.scaleDuration(24))
        assertEquals(0, MotionTier.FULL.scaleDuration(-5))
    }

    @Test
    fun `stagger 步数只有 FULL 逐项，其余档位归零`() {
        assertEquals(FULL_STAGGER_MAX_STEPS, staggerStepsFor(MotionTier.FULL))
        assertEquals(0, staggerStepsFor(MotionTier.NORMAL))
        assertEquals(0, staggerStepsFor(MotionTier.REDUCED))
        // 调用点自带上限时按调用点取值
        assertEquals(5, staggerStepsFor(MotionTier.FULL, maxSteps = 5))
        assertEquals(0, staggerStepsFor(MotionTier.NORMAL, maxSteps = 5))
        assertEquals(0, staggerStepsFor(MotionTier.FULL, maxSteps = -3))
    }

    @Test
    fun `系统动画开关缓存可清除以支持设置页改动后立即生效`() {
        // 进程级缓存是性能取舍：读一次够用；refreshSystemPolicy 负责清缓存重读
        MotionConfig.resetSystemAnimationsCache()
        var reads = 0
        var allowed = true
        val config = MotionConfig(FakeStore()) {
            reads++
            allowed
        }

        config.current()
        assertEquals("首次构造时读了一次", 1, reads)

        allowed = false
        config.refreshSystemPolicy()

        assertEquals(MotionTier.REDUCED, config.current())
        assertEquals("刷新策略必须重新读系统开关", 2, reads)
    }
}
