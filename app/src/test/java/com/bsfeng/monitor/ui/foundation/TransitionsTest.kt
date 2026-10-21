package com.bsfeng.monitor.ui.foundation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一转场基元的纯函数回归（JVM，无 Robolectric）。
 *
 * 钉住三件事：
 *  1. 减弱动效（系统动画缩放 = 0 → motionEnabled = false）时四个方向一律 `None`，不播动画；
 *  2. 时长 / 缓动确实来自 design token（durOverlayIn / durOverlayOut / durPage、easeOut / easeIn），不是魔数；
 *  3. 几何口径固定：整屏水平 1f、抽屉与折叠垂直 1f、弹窗无位移且缩放 0.94。
 */
class TransitionsTest {

    private val apple = DesignTokens("apple")

    private fun spec(role: TransitionRole, motionEnabled: Boolean = true) =
        transitionSpecFor(role, motionEnabled, apple)

    // ── 1 · 减弱动效：直接落终态 ──

    @Test
    fun `减弱动效时四个方向的进场都返回 None`() {
        val enterRoles = listOf(
            TransitionRole.ScreenEnter,
            TransitionRole.SheetEnter,
            TransitionRole.DialogEnter,
            TransitionRole.ExpandEnter,
        )
        for (role in enterRoles) {
            val s = spec(role, motionEnabled = false)
            assertFalse("$role 在减弱动效下不应播动画", s.animated)
            assertSame(
                "$role 在减弱动效下必须返回 EnterTransition.None",
                EnterTransition.None,
                s.toEnterTransition(apple),
            )
        }
    }

    @Test
    fun `减弱动效时四个方向的退场都返回 None`() {
        val exitRoles = listOf(
            TransitionRole.ScreenExit,
            TransitionRole.SheetExit,
            TransitionRole.DialogExit,
            TransitionRole.ExpandExit,
        )
        for (role in exitRoles) {
            val s = spec(role, motionEnabled = false)
            assertFalse("$role 在减弱动效下不应播动画", s.animated)
            assertSame(
                "$role 在减弱动效下必须返回 ExitTransition.None",
                ExitTransition.None,
                s.toExitTransition(apple),
            )
        }
    }

    @Test
    fun `减弱动效时仍保留 token 时长便于展示与断言`() {
        // 不播动画 ≠ 时长丢失：spec 里的时长照旧取 token，设置页/调试可读
        assertEquals(apple.durOverlayIn, spec(TransitionRole.ScreenEnter, motionEnabled = false).durationMs)
        assertEquals(apple.durPage, spec(TransitionRole.ExpandEnter, motionEnabled = false).durationMs)
    }

    @Test
    fun `正常档位下转场不是 None`() {
        assertNotEquals(EnterTransition.None, spec(TransitionRole.ScreenEnter).toEnterTransition(apple))
        assertNotEquals(ExitTransition.None, spec(TransitionRole.ScreenExit).toExitTransition(apple))
        assertNotEquals(EnterTransition.None, spec(TransitionRole.DialogEnter).toEnterTransition(apple))
        assertNotEquals(EnterTransition.None, spec(TransitionRole.ExpandEnter).toEnterTransition(apple))
    }

    // ── 2 · 时长 / 缓动取 token ──

    @Test
    fun `覆盖层与抽屉进场取 durOverlayIn、退场取 durOverlayOut`() {
        for (role in listOf(TransitionRole.ScreenEnter, TransitionRole.SheetEnter, TransitionRole.DialogEnter)) {
            assertEquals("$role 进场时长应为 --dur-overlay-in", apple.durOverlayIn, spec(role).durationMs)
            assertEquals("$role 进场缓动应为 --ease-out", TransitionEasing.Out, spec(role).easing)
        }
        for (role in listOf(TransitionRole.ScreenExit, TransitionRole.SheetExit, TransitionRole.DialogExit)) {
            assertEquals("$role 退场时长应为 --dur-overlay-out", apple.durOverlayOut, spec(role).durationMs)
            assertEquals("$role 退场缓动应为 --ease-in", TransitionEasing.In, spec(role).easing)
        }
    }

    @Test
    fun `折叠展开进出都取 durPage`() {
        assertEquals(apple.durPage, spec(TransitionRole.ExpandEnter).durationMs)
        assertEquals(apple.durPage, spec(TransitionRole.ExpandExit).durationMs)
    }

    @Test
    fun `时长确实来自主题 token 而非硬编码常数`() {
        // 逐主题核对：任何一处把 220 / 150 / 340 写死成魔数，这里就会出现不等
        for (id in ThemeRepository.themeIds) {
            val tokens = DesignTokens(id)
            assertEquals(
                "$id 的覆盖层进场时长必须等于 --dur-overlay-in",
                tokens.durOverlayIn,
                transitionSpecFor(TransitionRole.ScreenEnter, true, tokens).durationMs,
            )
            assertEquals(
                "$id 的覆盖层退场时长必须等于 --dur-overlay-out",
                tokens.durOverlayOut,
                transitionSpecFor(TransitionRole.ScreenExit, true, tokens).durationMs,
            )
            assertEquals(
                "$id 的折叠时长必须等于 --dur-page",
                tokens.durPage,
                transitionSpecFor(TransitionRole.ExpandEnter, true, tokens).durationMs,
            )
            assertTrue("$id 的 token 时长必须为正", tokens.durOverlayIn > 0)
        }
    }

    // ── 3 · 几何口径 ──

    @Test
    fun `整屏覆盖层水平整宽滑入`() {
        val enter = spec(TransitionRole.ScreenEnter)
        assertEquals(SlideAxis.Horizontal, enter.axis)
        assertEquals(1f, enter.slideBy, 0f)
        assertEquals(1f, enter.scaleFrom, 0f)
    }

    @Test
    fun `底部抽屉垂直整高滑入`() {
        assertEquals(SlideAxis.Vertical, spec(TransitionRole.SheetEnter).axis)
        assertEquals(1f, spec(TransitionRole.SheetEnter).slideBy, 0f)
        assertEquals(SlideAxis.Vertical, spec(TransitionRole.SheetExit).axis)
    }

    @Test
    fun `弹窗无位移且缩放起点为 0_94`() {
        val enter = spec(TransitionRole.DialogEnter)
        assertEquals(SlideAxis.None, enter.axis)
        assertEquals(0f, enter.slideBy, 0f)
        assertEquals(0.94f, enter.scaleFrom, 0.0001f)
        assertEquals(0.94f, spec(TransitionRole.DialogExit).scaleFrom, 0.0001f)
    }

    @Test
    fun `折叠展开走垂直轴`() {
        assertEquals(SlideAxis.Vertical, spec(TransitionRole.ExpandEnter).axis)
        assertEquals(SlideAxis.Vertical, spec(TransitionRole.ExpandExit).axis)
    }

    @Test
    fun `规格保留传入的角色`() {
        for (role in TransitionRole.entries) {
            assertEquals(role, spec(role).role)
        }
    }

    // ── 4 · 角色错配的兜底（不抛异常、返回 None） ──

    @Test
    fun `进场角色取退场动画时返回 None 而不是抛异常`() {
        for (role in listOf(
            TransitionRole.ScreenEnter,
            TransitionRole.SheetEnter,
            TransitionRole.DialogEnter,
            TransitionRole.ExpandEnter,
        )) {
            assertSame("$role 没有退场动画", ExitTransition.None, spec(role).toExitTransition(apple))
        }
    }

    @Test
    fun `退场角色取进场动画时返回 None 而不是抛异常`() {
        for (role in listOf(
            TransitionRole.ScreenExit,
            TransitionRole.SheetExit,
            TransitionRole.DialogExit,
            TransitionRole.ExpandExit,
        )) {
            assertSame("$role 没有进场动画", EnterTransition.None, spec(role).toEnterTransition(apple))
        }
    }
}
