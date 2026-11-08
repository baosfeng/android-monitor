package com.bsfeng.monitor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPendingIntent
import org.robolectric.shadows.ShadowSystemClock

/**
 * 保活心跳的排班口径（R5：实测 30 秒一次，不是文档说的 1 分钟）。
 *
 * 根因：`onReceive` 每次都无条件 `scheduleAlarmCheck`，而 `setInexactRepeating` 的 start 参数
 * 语义是「从现在起再等 30 秒」，于是每被触发一次就重新锚定下次触发时刻，等效周期腰斩成 30 秒
 * —— 唤醒、定位采样、6 个桌面组件刷新全部翻倍。
 *
 * 断言走 Robolectric 的 [ShadowAlarmManager]：看真实排进 AlarmManager 的闹钟参数与条数，
 * 不看某个内部布尔标志。用纯 [android.app.Application] 起沙箱，避开 MonitorApp.onCreate
 * 自己排的那几条（额度 / 备份）闹钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HeartbeatAlarmScheduleTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val shadow: ShadowAlarmManager get() = shadowOf(alarmManager)

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        HeartbeatAlarmSchedule.REQUEST_CODE,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ===== 纯函数口径 =====

    @Test
    fun `闹钟已存在时不再排`() {
        assertFalse(
            "已有闹钟还重排 = 把下次触发重锚到 30 秒后",
            HeartbeatAlarmSchedule.shouldSchedule(hasScheduledAlarm = true),
        )
        assertTrue("没有闹钟才补排", HeartbeatAlarmSchedule.shouldSchedule(hasScheduledAlarm = false))
    }

    @Test
    fun `周期是一分钟 首次延迟三十秒`() {
        assertEquals(
            "改这里要同步 docs_后台服务_概述.md 的保活周期行",
            60_000L,
            HeartbeatAlarmSchedule.INTERVAL_MS,
        )
        assertEquals(30_000L, HeartbeatAlarmSchedule.FIRST_DELAY_MS)
    }

    // ===== 真实 AlarmManager（Robolectric）=====

    @Test
    fun `ensureAlarmCheck 首次调用排一条 60 秒重复闹钟`() {
        val before = System.currentTimeMillis()

        ensureAlarmCheck(context)

        val alarm = shadow.scheduledAlarms.singleOrNull()
        assertNotNull("应排进一条心跳闹钟", alarm)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm!!.type)
        assertEquals("重复闹钟的周期必须是 60 秒", 60_000L, alarm.interval)
        assertTrue(
            "首次触发应在 now+30s 附近，实际偏移 " + (alarm.triggerAtTime - before) + "ms",
            alarm.triggerAtTime - before in 25_000L..40_000L,
        )
    }

    @Test
    fun `闹钟已存在时 ensureAlarmCheck 不重排 触发时刻不变`() {
        ensureAlarmCheck(context)
        val first = shadow.scheduledAlarms.single().triggerAtTime

        // 模拟「心跳触发后 onReceive 又调了一次 ensure」：时钟已走过一个周期
        ShadowSystemClock.advanceBy(90_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        ensureAlarmCheck(context)

        assertEquals(
            "已在位的闹钟不得被重锚：重锚就是把周期腰斩成 30 秒的根因",
            first,
            shadow.scheduledAlarms.single().triggerAtTime,
        )
    }

    @Test
    fun `闹钟丢失后 ensureAlarmCheck 会补排`() {
        ensureAlarmCheck(context)
        assertNotNull("前提：第一步已排上", shadow.scheduledAlarms.singleOrNull())

        // 重启 / 进程被杀：AlarmManager 里的闹钟与 PendingIntent 登记一起消失。
        // 只 cancel 闹钟不够 —— PendingIntent 对象仍留在登记表里，FLAG_NO_CREATE 还查得到，
        // 那不是「闹钟丢了」而是「闹钟被取消」，两者的 ensure 结果不同。
        alarmManager.cancel(pendingIntent())
        ShadowPendingIntent.reset()
        assertTrue("前提：取消后没有心跳闹钟", shadow.scheduledAlarms.isEmpty())

        ensureAlarmCheck(context)

        assertNotNull("开机 / 进程重启后必须补得回来", shadow.scheduledAlarms.singleOrNull())
    }
}
