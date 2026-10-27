package com.bsfeng.monitor.finance.ui

// P1-1 回归：三个写方法此前无 try/catch，写库异常直接打穿 viewModelScope 崩进程。
//
// 修复口径：写库动作统一走 [runFinanceWrite] —— 只吞 Exception、**重抛取消**，
// 失败落成 Result.failure 交给调用方给用户可见反馈。这里锁住三件事：
//  1. 写库抛异常时返回失败，不向上抛（不崩进程）；
//  2. 协程取消（CancellationException）必须重抛 —— 不能把「切月份取消」误报成「保存失败」；
//  3. 正常路径原样返回成功值。
//
// 纯 JVM 用例，不组合 Compose、不碰数据库。

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceWriteFailureTest {

    @Test
    fun `写库抛异常时返回失败而不是向上抛`() = runBlocking {
        val boom = IllegalStateException("disk I/O error")
        val result = runFinanceWrite<Long> { throw boom }

        assertTrue("写失败必须落成 failure，否则会打穿 viewModelScope", result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun `正常写入原样返回成功值`() = runBlocking {
        val result = runFinanceWrite { 42L }

        assertTrue(result.isSuccess)
        assertEquals(42L, result.getOrNull())
    }

    @Test
    fun `协程取消必须重抛而不是当成写失败`() = runBlocking {
        // runCatching 会把 CancellationException 一起吞掉，进而弹一句假的「保存失败」给用户
        val thrown = runCatching {
            runFinanceWrite<Long> { throw CancellationException("切月份取消") }
        }.exceptionOrNull()

        assertTrue("取消必须重抛", thrown is CancellationException)
    }

    @Test
    fun `失败文案不复用成功文案`() {
        // 用户看到「已保存」就得真存上了：失败三句必须各自独立且都不含「已保存 / 已删除」
        assertFalse(SAVE_FAILED_NOTICE.contains("已保存"))
        assertFalse(UPDATE_FAILED_NOTICE.contains("已保存"))
        assertFalse(DELETE_FAILED_NOTICE.contains("已删除"))
        assertTrue(SAVE_FAILED_NOTICE.contains("失败"))
        assertTrue(UPDATE_FAILED_NOTICE.contains("失败"))
        assertTrue(DELETE_FAILED_NOTICE.contains("失败"))
    }
}
