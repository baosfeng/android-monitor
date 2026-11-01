package com.bsfeng.monitor.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ui` 命令族的解析口径（纯 JVM）：合法动作 / 缺参 / 越界 / 非法组合都要有断言，
 * 拒绝时必须带可执行的期望值 —— 真机上点错一次就是一个来回。
 */
class DebugUiCommandsTest {

    // ─────────────────────────────── 合法 ───────────────────────────────

    @Test
    fun `合法 - ui dump 不带 tag`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Dump(tag = null))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "dump")),
        )
    }

    @Test
    fun `合法 - ui dump 带 win 作为文件名标签`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Dump(tag = "settings"))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "dump", "win" to " settings ")),
        )
    }

    @Test
    fun `合法 - ui click 按文案`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Click(UiSelector(text = "功能开关")))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "click", "text" to " 功能开关 ")),
        )
    }

    @Test
    fun `合法 - ui click 四个选择器同时给（全部同时满足）`() {
        val extras = mapOf(
            "cmd" to "ui",
            "op" to "click",
            "text" to "保存",
            "desc" to "确认按钮",
            "id" to "btn_save",
            "bounds" to "10,20,110,80",
        )
        assertEquals(
            ParseResult.Ok(
                DebugCommand.Ui(
                    UiAction.Click(
                        UiSelector(text = "保存", desc = "确认按钮", id = "btn_save", bounds = UiRect(10, 20, 110, 80)),
                    ),
                ),
            ),
            DebugCommands.parse(extras),
        )
    }

    @Test
    fun `合法 - ui longclick 按资源名`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.LongClick(UiSelector(id = "row_trip")))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "longclick", "id" to "row_trip")),
        )
    }

    @Test
    fun `合法 - ui scroll 方向大小写不敏感`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Scroll(ScrollDir.DOWN))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "scroll", "dir" to " DOWN ")),
        )
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Scroll(ScrollDir.LEFT))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "scroll", "dir" to "left")),
        )
    }

    @Test
    fun `合法 - ui swipe 四点 + 时长`() {
        val extras = mapOf(
            "cmd" to "ui",
            "op" to "swipe",
            "x1" to 540,
            "y1" to 1800,
            "x2" to 540,
            "y2" to 600,
            "ms" to 400,
        )
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Swipe(x1 = 540, y1 = 1800, x2 = 540, y2 = 600, ms = 400))),
            DebugCommands.parse(extras),
        )
    }

    @Test
    fun `合法 - ui swipe 缺 ms 用默认值`() {
        val extras = mapOf("cmd" to "ui", "op" to "swipe", "x1" to 1, "y1" to 2, "x2" to 3, "y2" to 4)
        assertEquals(
            ParseResult.Ok(
                DebugCommand.Ui(
                    UiAction.Swipe(x1 = 1, y1 = 2, x2 = 3, y2 = 4, ms = DebugCommands.DEFAULT_SWIPE_MS),
                ),
            ),
            DebugCommands.parse(extras),
        )
    }

    @Test
    fun `合法 - ui back、home、recents 免参数`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Back)),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "back")),
        )
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Home)),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "home")),
        )
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Recents)),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "recents")),
        )
    }

    @Test
    fun `合法 - ui wait 默认 1000ms、上限 5000ms 放行`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Wait(DebugCommands.DEFAULT_WAIT_MS))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "wait")),
        )
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Wait(DebugCommands.MAX_WAIT_MS))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "wait", "ms" to DebugCommands.MAX_WAIT_MS)),
        )
    }

    @Test
    fun `命名 - 命令名带 op，RESULT 行能区分动作`() {
        val parsed = DebugCommands.parse(mapOf("cmd" to "ui", "op" to "click", "text" to "x"))
        val command = (parsed as ParseResult.Ok).command
        assertEquals("ui.click", command.name)
        assertEquals("ui.dump", DebugCommand.Ui(UiAction.Dump(null)).name)
        assertTrue("SUPPORTED 必须列出 ui：${DebugCommands.SUPPORTED}", "ui" in DebugCommands.SUPPORTED)
    }

    @Test
    fun `方向口径 - up 与 left 后退，down 与 right 前进`() {
        assertTrue(ScrollDir.DOWN.forward)
        assertTrue(ScrollDir.RIGHT.forward)
        assertTrue(!ScrollDir.UP.forward)
        assertTrue(!ScrollDir.LEFT.forward)
        assertEquals("up|down|left|right", ScrollDir.list())
        assertEquals(null, ScrollDir.from("updown"))
    }

    @Test
    fun `合法 - ui audit 的 tag 走 win，与 dump 一致`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Audit(tag = null))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "audit")),
        )
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Audit(tag = "home-after-launch"))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "audit", "win" to " home-after-launch ")),
        )
        // 回执 token 占着 tag：把文件名标签塞进 --es tag 会被静默忽略，必须仍落到 win
        assertEquals(
            ParseResult.Ok(DebugCommand.Ui(UiAction.Audit(tag = null))),
            DebugCommands.parse(mapOf("cmd" to "ui", "op" to "audit", "tag" to "dbg-123")),
        )
    }

    @Test
    fun `op 表 - 十个动作都能解析`() {
        val cases = mapOf(
            "dump" to mapOf("cmd" to "ui", "op" to "dump"),
            "audit" to mapOf("cmd" to "ui", "op" to "audit"),
            "click" to mapOf("cmd" to "ui", "op" to "click", "text" to "a"),
            "longclick" to mapOf("cmd" to "ui", "op" to "longclick", "text" to "a"),
            "scroll" to mapOf("cmd" to "ui", "op" to "scroll", "dir" to "up"),
            "swipe" to mapOf("cmd" to "ui", "op" to "swipe", "x1" to 1, "y1" to 2, "x2" to 3, "y2" to 4),
            "back" to mapOf("cmd" to "ui", "op" to "back"),
            "home" to mapOf("cmd" to "ui", "op" to "home"),
            "recents" to mapOf("cmd" to "ui", "op" to "recents"),
            "wait" to mapOf("cmd" to "ui", "op" to "wait", "ms" to 10),
        )
        assertEquals("op 表与解析分支必须一一对应", 10, cases.size)
        cases.forEach { (op, extras) ->
            val result = DebugCommands.parse(extras)
            assertTrue("op=$op 应能解析，实际 $result", result is ParseResult.Ok)
            assertEquals("ui.$op", (result as ParseResult.Ok).command.name)
        }
        assertEquals(
            cases.keys.joinToString("|"),
            UiAction.list(),
        )
    }

    // ─────────────────────────────── 缺参 ───────────────────────────────

    @Test
    fun `缺参 - ui 没有 op`() {
        val result = DebugCommands.parse(mapOf("cmd" to "ui"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("--es op"))
    }

    @Test
    fun `缺参 - click 没有任何选择器`() {
        val result = DebugCommands.parse(mapOf("cmd" to "ui", "op" to "click"))
        assertTrue(result is ParseResult.BadArgs)
        val message = (result as ParseResult.BadArgs).message
        assertTrue("要写清四个选择器：$message", message.contains("--es text") && message.contains("--es bounds"))
    }

    @Test
    fun `缺参 - scroll 没有 dir`() {
        val result = DebugCommands.parse(mapOf("cmd" to "ui", "op" to "scroll"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("--es dir"))
    }

    @Test
    fun `缺参 - swipe 少一个坐标`() {
        val result = DebugCommands.parse(mapOf("cmd" to "ui", "op" to "swipe", "x1" to 1, "y1" to 2, "x2" to 3))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("--ei y2"))
    }

    // ──────────────────────────── 非法参数值 ────────────────────────────

    @Test
    fun `非法值 - op 不在动作表里`() {
        val result = DebugCommands.parse(mapOf("cmd" to "ui", "op" to "tap"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("dump|audit|click|longclick"))
    }

    @Test
    fun `非法值 - wait 超过 5000ms`() {
        val result = DebugCommands.parse(mapOf("cmd" to "ui", "op" to "wait", "ms" to 5001))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("0..5000"))
    }

    @Test
    fun `非法值 - bounds 形态错、顺序颠倒、退化都拒`() {
        listOf("10,20,110", "a,b,c,d", "110,20,10,80", "10,80,110,20", "10,20,10,80").forEach { bounds ->
            val result = DebugCommands.parse(
                mapOf("cmd" to "ui", "op" to "click", "bounds" to bounds),
            )
            assertTrue("应拒绝 bounds=$bounds", result is ParseResult.BadArgs)
        }
    }

    @Test
    fun `非法值 - swipe 起止点重合、时长为 0`() {
        val same = DebugCommands.parse(
            mapOf("cmd" to "ui", "op" to "swipe", "x1" to 5, "y1" to 5, "x2" to 5, "y2" to 5),
        )
        assertTrue(same is ParseResult.BadArgs)
        assertTrue((same as ParseResult.BadArgs).message.contains("不能重合"))

        val tooFast = DebugCommands.parse(
            mapOf("cmd" to "ui", "op" to "swipe", "x1" to 1, "y1" to 2, "x2" to 3, "y2" to 4, "ms" to 10),
        )
        assertTrue(tooFast is ParseResult.BadArgs)
        assertTrue((tooFast as ParseResult.BadArgs).message.contains("50..10000"))
    }

    @Test
    fun `非法值 - 坐标越界与负数`() {
        listOf(-1, DebugCommands.MAX_COORDINATE + 1).forEach { bad ->
            val result = DebugCommands.parse(
                mapOf("cmd" to "ui", "op" to "swipe", "x1" to bad, "y1" to 2, "x2" to 3, "y2" to 4),
            )
            assertTrue("应拒绝 x1=$bad", result is ParseResult.BadArgs)
        }
    }

    @Test
    fun `非法值 - 选择器给了 --ei 而不是 --es`() {
        val result = DebugCommands.parse(mapOf("cmd" to "ui", "op" to "click", "text" to 42))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("必须是字符串"))
    }
}
