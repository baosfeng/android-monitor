package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * requester 表窗口裁剪（A10）。
 *
 * 回归点：`rowRequesters.getOrPut(id)` 在每行组合时写入，LazyColumn 只回收 composable、
 * 不回收这张 Map —— 不裁的话滚动过的事件会永久累计。
 */
class MainScreenRetainOnlyTest {

    @Test
    fun `只保留当前可见行 滚动过的行被移除`() {
        val requesterMap = mutableMapOf(
            "row-1" to "requester-1",
            "row-2" to "requester-2",
            "row-3" to "requester-3",
        )

        requesterMap.retainOnly(listOf("row-2", "row-3"))

        assertEquals(setOf("row-2", "row-3"), requesterMap.keys)
    }

    @Test
    fun `空列表裁剪到 0 条 内存上界不随滚动增长`() {
        val requesterMap = mutableMapOf("row-1" to "requester-1")
        requesterMap.retainOnly(emptyList())
        assertTrue(requesterMap.isEmpty())
    }

    @Test
    fun `可见集合含未登记的行时 不新增也不崩`() {
        val requesterMap = mutableMapOf("row-1" to "requester-1")
        requesterMap.retainOnly(listOf("row-1", "row-2"))

        assertEquals(setOf("row-1"), requesterMap.keys)
    }
}
