package com.bsfeng.monitor.finance.ui

import android.app.Application
import com.bsfeng.monitor.finance.FinanceDatabaseHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * [FinanceViewModel] 月份条边界（Robolectric）。
 *
 * 可浏览月窗口 [FinanceViewModel.availableMonths] 本应由数据库填充；本测试只验证月份条的边界判定，
 * 因此直接注入该状态，不依赖 DB 与主线程调度（[FinanceViewModel.loadMonth] 会同步更新 currentMonth）。
 * 越界口径：已在最早 / 最新可浏览月时只发一次性提示、不改月份。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceViewModelMonthTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        FinanceDatabaseHelper.resetForTesting()
        application = RuntimeEnvironment.getApplication()
        application.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        FinanceDatabaseHelper.resetForTesting()
        application.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
    }

    /** 造一个 ViewModel，并把可浏览月窗口注入为 [availableMonths]（替代 DB 填充）。 */
    @Suppress("UNCHECKED_CAST")
    private fun viewModelWithMonths(vararg availableMonths: String): FinanceViewModel {
        val vm = FinanceViewModel(application)
        val field = FinanceViewModel::class.java.getDeclaredField("_availableMonths")
        field.isAccessible = true
        (field.get(vm) as MutableStateFlow<List<String>>).value = availableMonths.toList()
        return vm
    }

    @Test
    fun `已在最早可浏览月时上一个月只提示不改月`() {
        val vm = viewModelWithMonths("2026-03", "2026-05")
        vm.loadMonth("2026-03")

        vm.previousMonth()

        assertEquals("2026-03", vm.currentMonth.value)
        assertEquals("已经是可浏览的最早月份（2026 年 3 月）", vm.monthNotice.value)
    }

    @Test
    fun `已在最新可浏览月时下一个月只提示不改月`() {
        val vm = viewModelWithMonths("2026-03", "2026-05")
        vm.loadMonth("2026-05")

        vm.nextMonth()

        assertEquals("2026-05", vm.currentMonth.value)
        assertEquals("已经是可浏览的最新月份（2026 年 5 月）", vm.monthNotice.value)
    }

    @Test
    fun `窗口内上一个月正常切换且不产生提示`() {
        val vm = viewModelWithMonths("2026-03", "2026-09")
        vm.loadMonth("2026-04")

        vm.previousMonth()

        assertEquals("2026-03", vm.currentMonth.value)
        assertNull(vm.monthNotice.value)
    }

    @Test
    fun `窗口内下一个月正常切换且不产生提示`() {
        val vm = viewModelWithMonths("2026-03", "2026-09")
        vm.loadMonth("2026-08")

        vm.nextMonth()

        assertEquals("2026-09", vm.currentMonth.value)
        assertNull(vm.monthNotice.value)
    }

    @Test
    fun `一月回退到上一年十二月`() {
        val vm = viewModelWithMonths("2025-12", "2026-06")
        vm.loadMonth("2026-01")

        vm.previousMonth()

        assertEquals("2025-12", vm.currentMonth.value)
    }

    @Test
    fun `十二月前进到次年一月`() {
        val vm = viewModelWithMonths("2026-01", "2027-01")
        vm.loadMonth("2026-12")

        vm.nextMonth()

        assertEquals("2027-01", vm.currentMonth.value)
    }

    @Test
    fun `越界提示是一次性消费的`() {
        val vm = viewModelWithMonths("2026-03")
        vm.loadMonth("2026-03")
        vm.previousMonth()

        vm.consumeMonthNotice()

        assertNull(vm.monthNotice.value)
        assertEquals("消费提示不应改变月份", "2026-03", vm.currentMonth.value)
    }

    @Test
    fun `没有可浏览月时不拦截切换`() {
        val vm = viewModelWithMonths()
        vm.loadMonth("2026-05")

        vm.nextMonth()

        assertEquals("2026-06", vm.currentMonth.value)
        assertNull(vm.monthNotice.value)
    }

    @Test
    fun `当前月标识为系统月且格式为 yyyy-MM`() {
        // AddTransactionScreen 的「浏览月 = 当前月则不替换默认日期」判定依赖这个值
        assertEquals(YearMonth.now().toString(), FinanceViewModel.currentMonthStr())
    }
}
