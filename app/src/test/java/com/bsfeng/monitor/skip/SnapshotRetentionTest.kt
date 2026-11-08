package com.bsfeng.monitor.skip

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 页面快照保留策略（SEC-03，纯 JVM）。
 *
 * 快照是第三方 App 的屏幕原文：份数上限、最旧优先淘汰、一键清空三条口径都在这里钉住。
 */
class SnapshotRetentionTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("snapshots-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 造一份快照并把修改时间设成 [modifiedAt]（毫秒）：淘汰顺序按修改时间，不按文件名。 */
    private fun snapshot(name: String, modifiedAt: Long): File =
        File(dir, "$name.json").apply {
            writeText("{\"packageName\":\"com.demo\"}")
            setLastModified(modifiedAt)
        }

    @Test
    fun `保留份数上限是 10`() {
        assertEquals(10, SnapshotRetention.MAX_SNAPSHOTS)
    }

    @Test
    fun `目录不存在时列表与计数都是空`() {
        val missing = File(dir, "nope")
        assertTrue(SnapshotRetention.listNewestFirst(missing).isEmpty())
        assertEquals(0, SnapshotRetention.count(missing))
        assertEquals(0, SnapshotRetention.clear(missing))
    }

    @Test
    fun `只统计 json 快照文件`() {
        snapshot("a", 1_000L)
        File(dir, "readme.txt").writeText("x")
        assertEquals(1, SnapshotRetention.count(dir))
    }

    @Test
    fun `列表按修改时间最新在前`() {
        snapshot("old", 1_000L)
        snapshot("new", 3_000L)
        snapshot("mid", 2_000L)
        assertEquals(
            listOf("new.json", "mid.json", "old.json"),
            SnapshotRetention.listNewestFirst(dir).map { it.name },
        )
    }

    @Test
    fun `导出前淘汰到 keep 份并优先删最旧的`() {
        snapshot("old1", 1_000L)
        snapshot("old2", 2_000L)
        snapshot("latest", 3_000L)

        val removed = SnapshotRetention.prune(dir, keep = 2)

        assertEquals(1, removed)
        assertEquals(listOf("latest.json", "old2.json"), SnapshotRetention.listNewestFirst(dir).map { it.name })
        assertFalse("最旧的一份应被删掉", File(dir, "old1.json").exists())
    }

    @Test
    fun `不足 keep 份时一份都不删`() {
        snapshot("a", 1_000L)
        snapshot("b", 2_000L)
        assertEquals(0, SnapshotRetention.prune(dir, keep = 5))
        assertEquals(2, SnapshotRetention.count(dir))
    }

    @Test
    fun `清空会删掉全部快照并返回份数`() {
        snapshot("a", 1_000L)
        snapshot("b", 2_000L)
        assertEquals(2, SnapshotRetention.clear(dir))
        assertEquals(0, SnapshotRetention.count(dir))
        assertTrue("目录本身保留", dir.exists())
    }

    @Test
    fun `清空不动非快照文件`() {
        snapshot("a", 1_000L)
        File(dir, "keep.txt").writeText("x")
        SnapshotRetention.clear(dir)
        assertTrue(File(dir, "keep.txt").exists())
    }
}
