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

    @Test
    fun `合法 - open ad（广告页验收截图入口）`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Open(DebugScreen.AD)),
            DebugCommands.parse(mapOf("cmd" to "open", "screen" to "ad")),
        )
        // 大小写 / 空白都要能落到同一个屏（CLI 手敲容易带空格）
        assertEquals(
            ParseResult.Ok(DebugCommand.Open(DebugScreen.AD)),
            DebugCommands.parse(mapOf("cmd" to "open", "screen" to " AD ")),
        )
    }

    @Test
    fun `open 屏表 - 广告页是 Tab 2，且 Tab 下标不重复`() {
        assertEquals("广告页必须是第 3 个 Tab（概览 0 / 实时 1 / 广告 2）", 2, DebugScreen.AD.appTabIndex)
        val tabIndexes = DebugScreen.entries.mapNotNull { it.appTabIndex }
        assertEquals("每个 App Tab 只能被一个调试屏占用：$tabIndexes", tabIndexes.size, tabIndexes.toSet().size)
        assertTrue("screenList 要能列出 ad：${DebugCommands.screenList()}", "ad" in DebugCommands.screenList())
        assertEquals("KNOWN_TYPES 与落库契约同源", RecordType.CANONICAL_NAMES, DebugCommands.KNOWN_TYPES)
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

    // ────────────────────────────── db：解析 ──────────────────────────────

    @Test
    fun `合法 - db tables 免参数`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Db(DbOp.TABLES)),
            DebugCommands.parse(mapOf("cmd" to "db", "op" to "tables")),
        )
    }

    @Test
    fun `合法 - db schema 带表名`() {
        assertEquals(
            ParseResult.Ok(DebugCommand.Db(DbOp.SCHEMA, table = "records")),
            DebugCommands.parse(mapOf("cmd" to "db", "op" to "schema", "table" to " records ")),
        )
    }

    @Test
    fun `合法 - db query 默认 limit 50、结尾分号被吃掉`() {
        val result = DebugCommands.parse(
            mapOf("cmd" to "db", "op" to "query", "sql" to "select type, count(*) as c from records group by type;"),
        )
        assertEquals(
            ParseResult.Ok(
                DebugCommand.Db(
                    DbOp.QUERY,
                    sql = "select type, count(*) as c from records group by type",
                    limit = DebugCommands.DEFAULT_QUERY_LIMIT,
                ),
            ),
            result,
        )
    }

    @Test
    fun `合法 - db query 指定 limit`() {
        val result = DebugCommands.parse(
            mapOf("cmd" to "db", "op" to "query", "sql" to "select 1", "limit" to 200),
        )
        assertEquals(ParseResult.Ok(DebugCommand.Db(DbOp.QUERY, sql = "select 1", limit = 200)), result)
    }

    @Test
    fun `缺参 - db 没有 op`() {
        val result = DebugCommands.parse(mapOf("cmd" to "db"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("--es op"))
    }

    @Test
    fun `非法值 - db 的 op 不在表里`() {
        val result = DebugCommands.parse(mapOf("cmd" to "db", "op" to "exec"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("tables|schema|query"))
    }

    @Test
    fun `非法值 - db query 缺 sql`() {
        val result = DebugCommands.parse(mapOf("cmd" to "db", "op" to "query"))
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("--es sql"))
    }

    @Test
    fun `非法值 - db query 的 limit 越界（0 与 201）`() {
        val base = mapOf("cmd" to "db", "op" to "query", "sql" to "select 1")
        assertTrue(DebugCommands.parse(base + ("limit" to 0)) is ParseResult.BadArgs)
        assertTrue(DebugCommands.parse(base + ("limit" to 201)) is ParseResult.BadArgs)
    }

    @Test
    fun `非法值 - db schema 表名带注入字符被拒`() {
        val result = DebugCommands.parse(
            mapOf("cmd" to "db", "op" to "schema", "table" to "records; drop table records"),
        )
        assertTrue(result is ParseResult.BadArgs)
        assertTrue((result as ParseResult.BadArgs).message.contains("table 取值非法"))
    }

    // ──────────────────────── db：只读校验（拒绝） ────────────────────────

    @Test
    fun `只读 - 10 类写关键字全部拒绝`() {
        val samples = listOf(
            "insert into records(id) values('x')",
            "update records set label='x'",
            "delete from records",
            "drop table records",
            "alter table records add column x text",
            "create table evil(id text)",
            "replace into records(id) values('x')",
            "vacuum",
            "attach database '/sdcard/x.db' as x",
            "pragma user_version",
        )
        samples.forEach { sql ->
            val check = DbSql.validate(sql)
            assertTrue("应拒绝：$sql", !check.ok)
            val parsed = DebugCommands.parse(mapOf("cmd" to "db", "op" to "query", "sql" to sql))
            assertTrue("解析期就应拒：$sql", parsed is ParseResult.BadArgs)
        }
        assertEquals(10, samples.size)
    }

    @Test
    fun `只读 - 大小写与 with 前缀都绕不过去`() {
        // 大写关键字
        assertTrue(!DbSql.validate("DELETE FROM records").ok)
        // with 开头但内层是写语句
        assertTrue(!DbSql.validate("with x as (select 1) delete from records").ok)
        // 多语句
        assertTrue(!DbSql.validate("select 1; delete from records").ok)
        // 注释藏语句
        assertTrue(!DbSql.validate("select 1 -- ; delete from records").ok)
        assertTrue(!DbSql.validate("select 1 /* x */ delete from records").ok)
        // 开头不是 select/with（selectx / 前导括号）
        assertTrue(!DbSql.validate("selectx * from records").ok)
        assertTrue(!DbSql.validate("(select 1)").ok)
        // 空语句
        assertTrue(!DbSql.validate("   ").ok)
        assertTrue(!DbSql.validate(";").ok)
    }

    // ──────────────────────── db：只读校验（允许） ────────────────────────

    @Test
    fun `只读 - 三种典型查询放行`() {
        val samples = listOf(
            "select * from records limit 3",
            "SELECT type, count(*) c FROM records GROUP BY type ORDER BY c DESC",
            "with t as (select 1 as n) select * from t",
        )
        samples.forEach { sql ->
            val check = DbSql.validate(sql)
            assertTrue("应放行：$sql（${check.reason}）", check.ok)
            assertEquals(sql, check.sql)
        }
    }

    @Test
    fun `只读 - 字面量里的关键字不误伤（created_at 同理）`() {
        assertTrue(DbSql.validate("select * from records where action = 'DELETE'").ok)
        assertTrue(DbSql.validate("select created_at from transfer_tasks").ok)
        // 但被引号包起来的标识符仍然算词元
        assertTrue(!DbSql.validate("select \"delete\" from records").ok)
    }

    // ──────────────────────────── db：字段截断 ────────────────────────────

    @Test
    fun `截断 - 500 字以内原样、超出截断并加省略号`() {
        val short = "a".repeat(DbSql.MAX_FIELD_CHARS)
        assertEquals(short, DbSql.truncate(short))
        val long = "b".repeat(DbSql.MAX_FIELD_CHARS + 1)
        val cut = DbSql.truncate(long)
        assertEquals(DbSql.MAX_FIELD_CHARS + 1, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals(DbSql.MAX_FIELD_CHARS, cut.dropLast(1).length)
        assertEquals("", DbSql.truncate(""))
    }
}
