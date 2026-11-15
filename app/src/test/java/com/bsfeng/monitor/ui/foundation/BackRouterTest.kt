package com.bsfeng.monitor.ui.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回栈排序口径的回归（纯逻辑，无 Compose、无时间）。
 *
 * 原型没有「返回层级」这种静态页，但真机上必然出现「覆盖层压着子页」的叠加形态
 * （设置子页 → 规则管理），口径以用户预期为准：一次返回退一层，且退的是眼里最上面那层。
 * 曾经的反例：规则页在最上层，返回键却关掉它下面的设置子页，用户看到「退了两层」。
 */
class BackRouterTest {

    @Test
    fun `空栈时不能返回`() {
        val router = BackRouter()
        assertFalse(router.canGoBack)
        assertFalse(router.pop())
    }

    @Test
    fun `层级高的先退`() {
        val router = BackRouter()
        val order = mutableListOf<String>()
        // 先开低层（设置子页），再开高层（覆盖层）：返回必须先退覆盖层
        router.register("sub", BackLayer.PAGE_SUB) { order += "sub" }
        router.register("overlay", BackLayer.OVERLAY) { order += "overlay" }

        assertTrue(router.pop())
        assertEquals(listOf("overlay"), order)
    }

    @Test
    fun `同层按打开顺序后进先出`() {
        val router = BackRouter()
        val order = mutableListOf<String>()
        router.register("first", BackLayer.OVERLAY) { order += "first" }
        router.register("second", BackLayer.OVERLAY) { order += "second" }

        router.pop()
        assertEquals(listOf("second"), order)
    }

    @Test
    fun `一次 pop 只调用一层`() {
        val router = BackRouter()
        val calls = mutableListOf<String>()
        router.register("overlay", BackLayer.OVERLAY) { calls += "overlay" }
        router.register("route", BackLayer.PAGE_ROUTE) { calls += "route" }

        router.pop()
        assertEquals(listOf("overlay"), calls)
    }

    @Test
    fun `注销后不再返回该层`() {
        val router = BackRouter()
        val order = mutableListOf<String>()
        val dispose = router.register("overlay", BackLayer.OVERLAY) { order += "overlay" }
        router.register("route", BackLayer.PAGE_ROUTE) { order += "route" }

        dispose()
        router.pop()
        assertEquals(listOf("route"), order)
    }

    @Test
    fun `同 key 重复登记是替换而不是叠加`() {
        val router = BackRouter()
        val order = mutableListOf<String>()
        router.register("overlay", BackLayer.OVERLAY) { order += "旧" }
        router.register("overlay", BackLayer.OVERLAY) { order += "新" }

        router.pop()
        assertEquals(listOf("新"), order)
        // 栈里只剩这一层：再 pop 也只会调它，不会冒出被替换掉的旧回调
        order.clear()
        router.pop()
        assertEquals(listOf("新"), order)
    }

    @Test
    fun `退一层只调用回调不移除登记`() {
        val router = BackRouter()
        val order = mutableListOf<String>()
        router.register("overlay", BackLayer.OVERLAY) { order += "overlay" }

        router.pop()
        // 登记随可见性注销（DisposableEffect），pop 自己不动栈
        assertTrue(router.canGoBack)
        router.pop()
        assertEquals(listOf("overlay", "overlay"), order)
    }

    @Test
    fun `栈空后 canGoBack 变为 false`() {
        val router = BackRouter()
        val dispose = router.register("overlay", BackLayer.OVERLAY) { }
        assertTrue(router.canGoBack)
        dispose()
        assertFalse(router.canGoBack)
    }
}
