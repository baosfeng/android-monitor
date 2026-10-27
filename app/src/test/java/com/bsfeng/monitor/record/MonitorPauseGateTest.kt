package com.bsfeng.monitor.record

import android.content.Context
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.model.RecordType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 监控暂停闸门（契约 s09-monitor「暂停后停止写新事件、实时流不再增长」）。
 *
 * 走真实 SQLite（Robolectric），断言的是**事件表里的行数**而不是某个内存标志 ——
 * 这一条正是原实现的假反馈所在：标志改了、库还在涨。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitorPauseGateTest {

    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var store: RecordStore
    private lateinit var collector: RecordCollector

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        helper = DatabaseHelper(context)
        store = RecordStore(helper)
        // 与 ServiceContainer.kt:113-115 同口径：UsageTracker 的记录经回调落库（测试不接回调就永远写不进去）
        val usageTracker = UsageTracker { record -> store.insertOrMerge(record) }
        collector = RecordCollector(store, usageTracker, RecordHandlerRegistry.createDefault(), geocoder = null)
        // 上一个用例可能把暂停态写进 prefs：内存与盘都清，避免用例间互相污染
        context.getSharedPreferences(MonitorPauseGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(MonitorPauseGate.KEY_PAUSED).commit()
        MonitorPauseGate.resetForTesting()
    }

    @After
    fun tearDown() {
        MonitorPauseGate.resetForTesting()
        helper.close()
    }

    private fun screenRecordCount(): Int =
        store.queryByType(RecordType.SCREEN, 0L, Long.MAX_VALUE).size

    @Test
    fun `暂停后采集入口不落库 恢复后继续写`() {
        val ts = System.currentTimeMillis()
        collector.onScreenChanged("UNLOCKED", ts)
        assertEquals("基线：未暂停时正常落库", 1, screenRecordCount())

        MonitorPauseGate.setPaused(true)
        collector.onScreenChanged("UNLOCKED", ts + 1_000L)
        collector.onScreenChanged("OFF", ts + 2_000L)
        collector.onWifiChanged("CONNECTED", "test-wifi", ts + 3_000L)
        assertEquals("暂停期间事件数不增长", 1, screenRecordCount())

        MonitorPauseGate.setPaused(false)
        collector.onScreenChanged("UNLOCKED", ts + 4_000L)
        assertEquals("恢复后继续采集", 2, screenRecordCount())
    }

    @Test
    fun `暂停态落盘并跨实例保持`() {
        MonitorPauseGate.setPaused(true)
        val prefs = context.getSharedPreferences(MonitorPauseGate.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue("暂停必须落盘，否则服务被系统重建后监控会自己跑起来", prefs.getBoolean(MonitorPauseGate.KEY_PAUSED, false))

        MonitorPauseGate.resetForTesting()
        assertTrue("重新装载后仍是暂停态", MonitorPauseGate.isPaused)

        MonitorPauseGate.setPaused(false)
        assertFalse(MonitorPauseGate.isPaused)
        assertFalse(prefs.getBoolean(MonitorPauseGate.KEY_PAUSED, true))
    }

    @Test
    fun `暂停期间使用时长 tick 不写 APP_USAGE`() {
        val ts = System.currentTimeMillis()
        MonitorPauseGate.setPaused(true)
        collector.onTick("com.example.app", "示例应用", ts)
        collector.onTick("com.example.other", "另一个应用", ts + 1_000L)
        assertEquals(
            "UsageTracker 的回调绕过 emit，闸门必须在 onTick 单独拦一次",
            0,
            store.queryByType(RecordType.APP_USAGE, 0L, Long.MAX_VALUE).size,
        )
    }

    @Test
    fun `暂停时长不被记进恢复后的使用时长`() {
        val ts = System.currentTimeMillis()
        collector.onTick("com.example.app", "示例应用", ts)
        collector.onTick("com.example.app", "示例应用", ts + 1_000L)

        // 暂停 10 分钟：期间 tick 全被拦，session 也必须被丢弃
        MonitorPauseGate.setPaused(true)
        collector.onTick("com.example.app", "示例应用", ts + 600_000L)
        MonitorPauseGate.setPaused(false)

        // 恢复后继续用同一个 App 1 秒，再切走 → 只落一段收尾记录
        collector.onTick("com.example.app", "示例应用", ts + 601_000L)
        collector.onTick("com.example.app", "示例应用", ts + 602_000L)
        collector.onTick("com.example.other", "另一个应用", ts + 603_000L)
        val usages = store.queryByType(RecordType.APP_USAGE, 0L, Long.MAX_VALUE)
        assertEquals("暂停期间的 session 被丢弃，恢复后只落一段", 1, usages.size)
        assertTrue(
            "暂停的 10 分钟不能被算成使用时长，实际 " + (usages.first().duration ?: -1L) + "ms 应 < 60s",
            (usages.first().duration ?: 0L) < 60_000L,
        )
    }
}
