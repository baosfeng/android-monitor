package com.bsfeng.monitor.debug

import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令门槛分档（安全审计 S-01 修订）。
 *
 * 这张表是「同机任意 App 能不能一条广播就驱动这台手机」的唯一裁决点，必须逐条钉死：
 *
 * - `ui` 不能落在免鉴权档 —— 它会用无障碍 performAction / dispatchGesture **真执行**
 *   点击、长按、滑动、返回、主页、任务键；
 * - `dump` / `db` 不能落在免鉴权档 —— 它们把屏幕原文、账目、定位轨迹打回 logcat；
 * - `clear` / `seed` 必须比读数据更严 —— 不可撤销，令牌消费即废；
 * - 新增命令时编译器强制在 forCommand 里表态，不会静默落进免鉴权档。
 */
class DebugCommandGatesTest {

    @Test
    fun `免鉴权档只有不泄露不驱动不清库的命令`() {
        val free = listOf(
            DebugCommand.Unlock,
            DebugCommand.State,
            DebugCommand.Heartbeat,
            DebugCommand.SampleLocation(),
            DebugCommand.SampleLocation(bypassThrottle = true),
            DebugCommand.Open(DebugScreen.OVERVIEW),
            DebugCommand.Open(DebugScreen.TRIP),
        )
        free.forEach {
            assertEquals("命令 " + it.name + " 应当免鉴权", DebugCommandGates.Level.FREE, DebugCommandGates.forCommand(it))
        }
    }

    @Test
    fun `dump 与 db 需要可复用令牌`() {
        assertEquals(
            DebugCommandGates.Level.CONFIRM_REUSABLE,
            DebugCommandGates.forCommand(DebugCommand.Dump()),
        )
        assertEquals(
            DebugCommandGates.Level.CONFIRM_REUSABLE,
            DebugCommandGates.forCommand(DebugCommand.Db(DbOp.QUERY, sql = "select 1", limit = 10)),
        )
        assertEquals(
            DebugCommandGates.Level.CONFIRM_REUSABLE,
            DebugCommandGates.forCommand(DebugCommand.Db(DbOp.TABLES)),
        )
    }

    @Test
    fun `ui 全部动作都需要令牌_包括会真点击的那几个`() {
        val actions = listOf(
            UiAction.Dump(tag = null),
            UiAction.Audit(tag = null),
            UiAction.Click(selector = UiSelector(text = "x")),
            UiAction.LongClick(selector = UiSelector(id = "x")),
            UiAction.Scroll(ScrollDir.DOWN),
            UiAction.Back,
            UiAction.Home,
            UiAction.Recents,
            UiAction.Wait(100),
        )
        actions.forEach {
            assertEquals(
                "ui." + it.op + " 落在免鉴权档就等于「一条广播远程驱动手机触摸」",
                DebugCommandGates.Level.CONFIRM_REUSABLE,
                DebugCommandGates.forCommand(DebugCommand.Ui(it)),
            )
        }
    }

    @Test
    fun `写库命令必须消费型令牌`() {
        assertEquals(
            DebugCommandGates.Level.CONFIRM_ONCE,
            DebugCommandGates.forCommand(DebugCommand.Clear(type = RecordType.SCREEN, all = false)),
        )
        assertEquals(
            DebugCommandGates.Level.CONFIRM_ONCE,
            DebugCommandGates.forCommand(DebugCommand.Clear(type = null, all = true)),
        )
        assertEquals(
            DebugCommandGates.Level.CONFIRM_ONCE,
            DebugCommandGates.forCommand(DebugCommand.Seed(SeedScenario.DAY)),
        )
    }

    @Test
    fun `三个全局键与手势动作都在 ui 命令里`() {
        // 这些是「一条广播就能驱动手机」的具体动作名：它们全在 ui 族下，
        // 而 ui 族整体归 CONFIRM_REUSABLE（见上一条用例）。
        val sideEffectOps = listOf("click", "longclick", "scroll", "swipe", "back", "home", "recents")
        sideEffectOps.forEach { op ->
            assertTrue("ui op 表必须能解析 $op", UiAction.list().contains(op))
        }
    }
}
