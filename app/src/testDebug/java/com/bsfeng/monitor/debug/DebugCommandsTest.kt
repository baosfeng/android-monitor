package com.bsfeng.monitor.debug

import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DebugCommands.parse` 的契约测试（纯 JVM，不需要 Robolectric）：
 * 合法命令 / 缺参 / 非法 cmd / 非法参数值四类都要有用例，且拒绝时必须带可执行的期望值。
 */
class DebugCommandsTest {

    // ─────────────────────────────── 合法 ───────────────────────────────

    @Test
    fun `合法 - unlock 免参数`() {
        assertEquals(ParseResult.Ok(DebugCommand.Unlock), DebugCommands.parse(mapOf("cmd" to "unlock")))
    }

    @Test
    fun `合法 - state 免参数`() {
        assertEquals(ParseResult.Ok(DebugCommand.State), DebugCommands.parse(mapOf("cmd" to "state")))
    }

    @Test
    fun `合法 - heartbeat 免参数`() {
        assertEquals(ParseResult.Ok(DebugCommand.Heartbeat), DebugCommands.parse(mapOf("cmd" to "heartbeat")))
    }

    @Test
    fun `合法 - sample_location 默认不绕节流`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.SampleLocation(bypassThrottle = false)),
            DebugCommands.parse(mapOf("cmd" to "sample_location")),
        )
    }

    @Test
    fun `合法 - sample_location 绕节流`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.SampleLocation(bypassThrottle = true)),
            DebugCommands.parse(mapOf("cmd" to "sample_location", "bypass_throttle" to true)),
        )
    }

    @Test
    fun `合法 - dump 默认 limit 20 且不筛类型`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Dump(limit = 20, type = null)),
            DebugCommands.parse(mapOf("cmd" to "dump")),
        )
    }

    @Test
    fun `合法 - dump 带 limit 与 type（大小写不敏感）`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Dump(limit = 3, type = RecordType.PAGE)),
            DebugCommands.parse(mapOf("cmd" to "dump", "limit" to 3, "type" to "page")),
        )
    }

    @Test
    fun `合法 - seed 缺省用 day 场景`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Seed(SeedScenario.DAY)),
            DebugCommands.parse(mapOf("cmd" to "seed")),
        )
    }

    @Test
    fun `合法 - seed 指定 gap 场景`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Seed(SeedScenario.GAP)),
            DebugCommands.parse(mapOf("cmd" to "seed", "scenario" to "gap")),
        )
    }

    @Test
    fun `合法 - clear 当日指定类型`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Clear(type = RecordType.LOCATION, all = false)),
            DebugCommands.parse(mapOf("cmd" to "clear", "type" to "LOCATION")),
        )
    }

    @Test
    fun `合法 - clear 全表（只给 all=true，不带 type）`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Clear(type = null, all = true)),
            DebugCommands.parse(mapOf("cmd" to "clear", "all" to true)),
        )
    }

    @Test
    fun `合法 - open trip`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Open(DebugScreen.TRIP)),
            DebugCommands.parse(mapOf("cmd" to "open", "screen" to "trip")),
        )
    }

    // ─────────────────────────────── 缺参 ───────────────────────────────

    @Test
    fun `缺参 - 完全没有 cmd`() {
        val result = DebugCommands.parse(emptyMap())
        assertTrue(result is ParseResult.MissingCmd)
        assertEquals(DebugCommands.SUPPORTED, (result as ParseResult.MissingCmd).supported)
    }

    @Test
    fun `缺参 - cmd 是空白串`() {
        assertTrue(DebugCommands.parse(mapOf("cmd" to "   ")) is ParseResult.MissingCmd)
    }

    @Test
    fun `缺参 - clear 既没 type 也没 all（P1 回归：以前静默没删）`() {
        val result = DebugCommands.parse(mapOf("cmd" to "clear"))
        assertTrue(result is ParseResult.BadArgs)
        val message = (result as ParseResult.BadArgs).message
        assertTrue("必须写清两条出路：$message", message.contains("--es type") && message.contains("--ez all true"))
    }

    @Test
    fun `非法组合 - clear 同时给 type 与 all 被拒`() {
        val result = DebugCommands.parse(mapOf("cmd" to "clear", "type" to "PAGE", "all" to true))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("互斥"))
    }

    @Test
    fun `缺参 - open 没给 screen`() {
        val result = DebugCommands.parse(mapOf("cmd" to "open"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("--es screen"))
    }

    // ───────────────────────────── 非法 cmd ─────────────────────────────

    @Test
    fun `非法 cmd - 不在命令表里`() {
        val result = DebugCommands.parse(mapOf("cmd" to "reboot"))
        assertTrue(result is ParseResult.UnknownCmd)
        val unknown = result as ParseResult.UnknownCmd
        assertEquals("reboot", unknown.cmd)
        assertEquals(DebugCommands.SUPPORTED, unknown.supported)
    }

    @Test
    fun `非法 cmd - cmd 不是字符串`() {
        assertTrue(DebugCommands.parse(mapOf("cmd" to 42)) is ParseResult.BadArgs)
    }

    // ──────────────────────────── 非法参数值 ────────────────────────────

    @Test
    fun `非法值 - scenario 不在枚举里`() {
        val result = DebugCommands.parse(mapOf("cmd" to "seed", "scenario" to "week"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("day|stay|move|gap"))
    }

    @Test
    fun `非法值 - screen 不在枚举里`() {
        val result = DebugCommands.parse(mapOf("cmd" to "open", "screen" to "trip2"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("trip"))
    }

    @Test
    fun `非法值 - limit 不是整数`() {
        val result = DebugCommands.parse(mapOf("cmd" to "dump", "limit" to "many"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("--ei limit"))
    }

    @Test
    fun `非法值 - limit 越界（0 与 201）`() {
        assertTrue(DebugCommands.parse(mapOf("cmd" to "dump", "limit" to 0)) is ParseResult.BadArgs)
        assertTrue(DebugCommands.parse(mapOf("cmd" to "dump", "limit" to 201)) is ParseResult.BadArgs)
    }

    @Test
    fun `非法值 - type 不在类型表里`() {
        val result = DebugCommands.parse(mapOf("cmd" to "dump", "type" to "NOT_A_TYPE"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("PAGE"))
    }

    @Test
    fun `非法值 - bypass_throttle 传了非布尔串`() {
        val result = DebugCommands.parse(mapOf("cmd" to "sample_location", "bypass_throttle" to "maybe"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("bypass_throttle"))
    }

    @Test
    fun `回执 token - 空串视为没给`() {
        assertEquals(null, DebugCommands.tokenOf(emptyMap()))
        assertEquals(null, DebugCommands.tokenOf(mapOf("tag" to "  ")))
        assertEquals("dbg-1", DebugCommands.tokenOf(mapOf("tag" to "dbg-1")))
    }
}
