package com.bsfeng.monitor.core.di

import android.app.Application
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.transfer.service.TransferEngine
import com.bsfeng.monitor.transfer.service.TransferEngineHolder
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 容器的装配口径（A1）：记账 / 互传 / 验证器三条链路都必须是容器给出同一个实例。
 *
 * 断言「同一个对象」而不是「不抛异常」：这三条链路的 bug 形态都是「各自 new 一份」，
 * 比如两份 TransferEngine 抢同一个端口、两份 FinanceManager 各持一个库句柄。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceContainerTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        // 引擎单例是进程级的，用例之间要复位（否则下一个用例拿到已释放的 context）
        TransferEngine.resetForTesting()
    }

    @Test
    fun `记账实例就是 FinanceManager 的进程级单例`() {
        val container = ServiceContainer(app)

        assertSame(FinanceManager.getInstance(app), container.financeManager)
        assertSame(container.financeManager, container.financeManager)
    }

    @Test
    fun `互传引擎就是 holder 持有的那个实例`() {
        val container = ServiceContainer(app)

        assertSame(TransferEngineHolder.get(app), container.transferEngine)
        assertNotNull(container.transferEngine.settings)
    }

    @Test
    fun `验证器仓库由容器只装配一次`() {
        val container = ServiceContainer(app)

        assertSame(container.totpRepository, container.totpRepository)
    }
}
