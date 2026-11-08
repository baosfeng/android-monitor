package com.bsfeng.monitor.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出到公共 Downloads 的纯逻辑（不改文件系统，纯 JVM）。
 *
 * 钉住三件事：目标路径怎么拼（含目录穿越边界）、没确认不写盘、取消后不写盘。
 * 「写盘」这一步（MediaStore 复制）故意不在这层，所以这里断言的是**闸门**：
 * 只有 [BackupExportGate.confirm] 交出文件名，上层才可能去复制。
 */
class BackupExportTest {

    private val normal = "monitor-backup-20261108-120000.zip"

    @Test
    fun `导出目标路径按下载目录加子目录拼接`() {
        assertEquals(normal, BackupExport.targetName(normal))
        assertEquals("Downloads/行为记录备份/" + normal, BackupExport.displayPath(normal))
        // MediaStore 的 RELATIVE_PATH 末尾必须带斜杠，否则包会落到下载根目录
        assertEquals("Download/行为记录备份/", BackupExport.relativePath())
    }

    @Test
    fun `文件名里的目录穿越被压平成一个文件名`() {
        // 边界：名字来自本地扫描，但导出目标必须是单个文件 —— 任何 ../ 都会被当成路径片段
        assertEquals("evil.zip", BackupExport.targetName("../../evil.zip"))
        assertEquals("evil.zip", BackupExport.targetName("C:\\tmp\\evil.zip"))
        assertEquals("backup.zip", BackupExport.targetName(".."))
        assertEquals("backup.zip", BackupExport.targetName("   "))
        assertEquals("Downloads/行为记录备份/evil.zip", BackupExport.displayPath("../evil.zip"))
    }

    @Test
    fun `没经确认的请求拿不到可写的文件名`() {
        val gate = BackupExportGate()
        assertNull("没请求过就不该有可写文件名", gate.confirm())

        assertTrue(gate.request(normal))
        assertEquals("请求后停在「等确认」，不自动写盘", normal, gate.pendingName)

        assertEquals("确认后才交出文件名", normal, gate.confirm())
        assertNull("确认一次即消费掉，不会重复导出", gate.confirm())
        assertNull(gate.pendingName)
    }

    @Test
    fun `取消与空名字都不写盘`() {
        val gate = BackupExportGate()
        assertTrue(gate.request(normal))

        gate.cancel()

        assertNull("取消后调用方拿不到名字，一个字节都不写", gate.confirm())
        assertNull(gate.pendingName)

        // 异常输入：空名字视为无效请求，不进入确认态
        assertFalse(gate.request(""))
        assertNull(gate.pendingName)
        assertNull(gate.confirm())
    }
}
