package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s10「清除页面快照」的副标题文案（SEC-03，纯函数）。
 *
 * 份数是清除入口的前置信息：用户必须一眼看到「已保存 N 份」才知道清除会删掉什么。
 */
class SkipRuleSnapshotSubtitleTest {

    @Test
    fun `没有快照时只报份数`() {
        assertEquals("已保存 0 份", snapshotSubtitle(0, null))
    }

    @Test
    fun `有快照时把份数与最近文件名一起给出`() {
        assertEquals(
            "已保存 3 份 · 最近 1760000000000.json",
            snapshotSubtitle(3, "/data/user/0/com.bsfeng.monitor/files/snapshots/1760000000000.json"),
        )
    }

    @Test
    fun `路径为空白时回落纯份数`() {
        assertEquals("已保存 2 份", snapshotSubtitle(2, "   "))
    }
}
