package com.bsfeng.monitor.skip

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.ClickPosition
import com.bsfeng.monitor.domain.model.MatchAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ActionExecutor 单元测试（Robolectric）。
 *
 * 覆盖：正常路径（归一化坐标换算、节点中心、BACK 全局返回、LONG_CLICK 时长）、
 * 边界（position 越界与 0/1 边界、空 bounds）、
 * 异常（NONE 无动作、无障碍服务为空、节点不可点击）。
 *
 * 注意：Robolectric 的 dispatchGesture 只是影子里记录手势，不会真的执行，
 * 因此这里断言"手势是否派发/派发坐标"，不断言真实手势成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionExecutorTest {

    /** 最小无障碍服务替身：Robolectric 为 dispatchGesture/performGlobalAction 提供影子实现 */
    private class FakeAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

        override fun onInterrupt() {}
    }

    @After
    fun tearDown() {
        ActionExecutor.accessibilityService = null
    }

    private fun service(): AccessibilityService =
        Robolectric.buildService(FakeAccessibilityService::class.java).create().get()

    private fun node(
        clickable: Boolean = false,
        longClickable: Boolean = false,
        bounds: Rect = Rect(100, 200, 300, 400)
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        isClickable = clickable
        isLongClickable = longClickable
        setBoundsInScreen(bounds)
    }

    /** 取第 index 次派发手势的落点坐标 */
    private fun gesturePoint(service: AccessibilityService, index: Int = 0): Pair<Float, Float> {
        val gestures = shadowOf(service).gesturesDispatched
        assertTrue("应已派发手势，实际 ${gestures.size} 次", gestures.size > index)
        val stroke = gestures[index].description().getStroke(0)
        val bounds = RectF().also { stroke.path.computeBounds(it, true) }
        return bounds.left to bounds.top
    }

    // ========== 正常路径 ==========

    @Test
    fun `CLICK_CENTER 按归一化坐标派发手势`() {
        val service = service()
        ActionExecutor.accessibilityService = service
        val metrics = service.resources.displayMetrics

        val result = ActionExecutor.perform(
            node(),
            MatchAction.CLICK_CENTER,
            ClickPosition(0.5f, 0.25f)
        )

        assertTrue("手势派发成功应返回 true", result)
        assertEquals("应恰好派发一次手势", 1, shadowOf(service).gesturesDispatched.size)
        val (x, y) = gesturePoint(service)
        assertEquals((metrics.widthPixels * 0.5f).toInt().toFloat(), x, 0.5f)
        assertEquals((metrics.heightPixels * 0.25f).toInt().toFloat(), y, 0.5f)
    }

    @Test
    fun `CLICK_CENTER 未指定 position 时点击节点中心`() {
        val service = service()
        ActionExecutor.accessibilityService = service

        assertTrue(ActionExecutor.perform(node(bounds = Rect(100, 200, 300, 400)), MatchAction.CLICK_CENTER))

        val (x, y) = gesturePoint(service)
        assertEquals("节点中心 X", 200f, x, 0.5f)
        assertEquals("节点中心 Y", 300f, y, 0.5f)
    }

    @Test
    fun `BACK 动作触发全局返回`() {
        val service = service()
        ActionExecutor.accessibilityService = service

        assertTrue(ActionExecutor.perform(node(), MatchAction.BACK))

        assertEquals(
            listOf(AccessibilityService.GLOBAL_ACTION_BACK),
            shadowOf(service).globalActionsPerformed
        )
    }

    @Test
    fun `LONG_CLICK 在不可长按节点上派发 400ms 长按手势`() {
        val service = service()
        ActionExecutor.accessibilityService = service

        assertTrue(ActionExecutor.perform(node(longClickable = false), MatchAction.LONG_CLICK))

        val gestures = shadowOf(service).gesturesDispatched
        assertEquals(1, gestures.size)
        assertEquals(LONG_CLICK_DURATION, gestures[0].description().getStroke(0).duration)
    }

    @Test
    fun `可点击节点上 CLICK_NODE 只发 ACTION_CLICK 不降级为手势`() {
        val service = service()
        ActionExecutor.accessibilityService = service

        // ACTION_CLICK 是否被应用接收由系统决定（Robolectric 下 obtain 的节点返回 true），
        // 这里只断言 CLICK_NODE 语义：只发 ACTION_CLICK，绝不降级为坐标手势
        ActionExecutor.perform(node(clickable = true), MatchAction.CLICK_NODE)

        assertTrue("CLICK_NODE 不应降级为手势", shadowOf(service).gesturesDispatched.isEmpty())
    }

    // ========== 边界条件 ==========

    @Test
    fun `position 归一化坐标越界返回 false 且不派发手势`() {
        val service = service()
        ActionExecutor.accessibilityService = service
        val target = node()

        val invalid = listOf(
            ClickPosition(1.5f, 0.5f),
            ClickPosition(-0.1f, 0.5f),
            ClickPosition(0.5f, 1.2f),
            ClickPosition(0.5f, -0.01f),
            ClickPosition(Float.NaN, 0.5f)
        )
        for (position in invalid) {
            assertFalse(
                "越界坐标 $position 应返回 false",
                ActionExecutor.perform(target, MatchAction.CLICK_CENTER, position)
            )
        }
        assertTrue("越界坐标不应派发手势", shadowOf(service).gesturesDispatched.isEmpty())
    }

    @Test
    fun `position 边界值 0 与 1 视为合法`() {
        val service = service()
        ActionExecutor.accessibilityService = service
        val target = node()

        assertTrue(ActionExecutor.perform(target, MatchAction.CLICK_CENTER, ClickPosition(0f, 0f)))
        assertTrue(ActionExecutor.perform(target, MatchAction.CLICK_CENTER, ClickPosition(1f, 1f)))

        assertEquals(2, shadowOf(service).gesturesDispatched.size)
        val (x, y) = gesturePoint(service, index = 1)
        val metrics = service.resources.displayMetrics
        assertEquals("归一化 1 对应屏幕右下角最后一个像素", (metrics.widthPixels - 1).toFloat(), x, 0.5f)
        assertEquals((metrics.heightPixels - 1).toFloat(), y, 0.5f)
    }

    @Test
    fun `空 bounds 节点返回 false`() {
        val service = service()
        ActionExecutor.accessibilityService = service
        val emptyBounds = node(bounds = Rect(0, 0, 0, 0))

        assertFalse(ActionExecutor.perform(emptyBounds, MatchAction.CLICK_CENTER))
        assertFalse("空 bounds 下 CLICK 的降级路径同样失败", ActionExecutor.perform(emptyBounds, MatchAction.CLICK))
        assertTrue("空 bounds 不应派发手势", shadowOf(service).gesturesDispatched.isEmpty())

        var asyncResult: Boolean? = null
        ActionExecutor.performGestureClick(emptyBounds) { asyncResult = it }
        assertEquals("空 bounds 时异步手势必须回调 false", false, asyncResult)
    }

    @Test
    fun `屏幕外坐标 clickAt 返回 false`() {
        val service = service()
        ActionExecutor.accessibilityService = service
        val metrics = service.resources.displayMetrics

        assertFalse(ActionExecutor.clickAt(-1, 10))
        assertFalse(ActionExecutor.clickAt(metrics.widthPixels, 10))
        assertFalse(ActionExecutor.clickAt(10, metrics.heightPixels))
        assertTrue("越界坐标不应派发手势", shadowOf(service).gesturesDispatched.isEmpty())
    }

    // ========== 异常场景 ==========

    @Test
    fun `NONE 不做任何动作并返回 false`() {
        val service = service()
        ActionExecutor.accessibilityService = service

        assertFalse(ActionExecutor.perform(node(clickable = true, longClickable = true), MatchAction.NONE))

        assertTrue("NONE 不应派发手势", shadowOf(service).gesturesDispatched.isEmpty())
        assertTrue("NONE 不应触发全局动作", shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test
    fun `无障碍服务为空时依赖手势与全局动作的路径安全返回 false`() {
        ActionExecutor.accessibilityService = null
        // 不可点击/不可长按：全部动作都会走手势或全局动作路径，与服务强相关
        val target = node(clickable = false, longClickable = false)

        assertFalse("CLICK", ActionExecutor.perform(target, MatchAction.CLICK))
        assertFalse("CLICK_NODE", ActionExecutor.perform(target, MatchAction.CLICK_NODE))
        assertFalse("CLICK_CENTER", ActionExecutor.perform(target, MatchAction.CLICK_CENTER))
        assertFalse("CLICK_CENTER + position", ActionExecutor.perform(
            target, MatchAction.CLICK_CENTER, ClickPosition(0.5f, 0.5f)
        ))
        assertFalse("LONG_CLICK", ActionExecutor.perform(target, MatchAction.LONG_CLICK))
        assertFalse("BACK", ActionExecutor.perform(target, MatchAction.BACK))
        assertFalse("clickAt", ActionExecutor.clickAt(10, 10))
        assertFalse("performGlobalBack", ActionExecutor.performGlobalBack())
    }

    @Test
    fun `无障碍服务为空时异步手势回调 false`() {
        ActionExecutor.accessibilityService = null

        var asyncResult: Boolean? = null
        ActionExecutor.performGestureClick(node()) { asyncResult = it }

        assertNotNull("服务为空时也必须回调，避免调用方悬挂", asyncResult)
        assertEquals(false, asyncResult)
    }

    @Test
    fun `不可点击节点上 CLICK 与 CLICK_NODE 均不派发手势`() {
        val service = service()
        ActionExecutor.accessibilityService = service
        val plain = node(clickable = false)

        assertFalse("CLICK_NODE 要求节点可点击", ActionExecutor.perform(plain, MatchAction.CLICK_NODE))
        assertTrue("CLICK_NODE 不应派发手势", shadowOf(service).gesturesDispatched.isEmpty())

        // CLICK 会降级为坐标点击；服务为空时降级路径必须安全失败而不是抛异常
        ActionExecutor.accessibilityService = null
        assertFalse("CLICK 在不可点击节点 + 无服务时应返回 false", ActionExecutor.perform(plain, MatchAction.CLICK))
    }

    private companion object {
        /** 与 ActionExecutor 的长按时长保持一致（语义：GKD 长按 400ms） */
        const val LONG_CLICK_DURATION = 400L
    }
}
