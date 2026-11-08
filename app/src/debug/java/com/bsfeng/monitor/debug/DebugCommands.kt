package com.bsfeng.monitor.debug

import com.bsfeng.monitor.domain.model.RecordType

/**
 * 调试命令口径（**只在 debug 变体编译**，见 `app/src/debug/`）——纯逻辑，可纯 JVM 单测。
 *
 * 为什么要有这一层：把「解析 + 参数校验 + 拒绝理由」从 BroadcastReceiver 里拆出来，
 * 单测不需要 Robolectric、不需要 Intent，非法入参的处置（fail-closed）能被断言住。
 * Android 侧的 `Intent -> Map<String, Any?>` 适配放在 [DebugCommandReceiver]。
 */
sealed interface DebugCommand {
    /** logcat 里 `RESULT cmd=<name>` 用的稳定标识，改它等于改 CLI 口径。 */
    val name: String

    /** 解锁调试层（唯一的免解锁命令）。 */
    data object Unlock : DebugCommand {
        override val name: String = "unlock"
    }

    /** 输出功能开关、定位权限、各类型当日计数、最新记录时间。 */
    data object State : DebugCommand {
        override val name: String = "state"
    }

    /** 立刻采一次位置并落库。 */
    data class SampleLocation(val bypassThrottle: Boolean = false) : DebugCommand {
        override val name: String = "sample_location"
    }

    /** 打印最近 N 条记录（可按类型过滤），JSON 数组分片输出。 */
    data class Dump(
        val limit: Int = DebugCommands.DEFAULT_DUMP_LIMIT,
        val type: RecordType? = null,
    ) : DebugCommand {
        override val name: String = "dump"
    }

    /** 插入一套可看的模拟记录（**写库**：属破坏性命令档，receiver 侧还要求一次性确认令牌）。 */
    data class Seed(val scenario: SeedScenario) : DebugCommand {
        override val name: String = "seed"
    }

    /**
     * 删除记录。**`type` 与 `all` 必须二选一**（两个都给也拒绝，避免「以为删了全部其实只删了一类」）：
     * - `--es type LOCATION`：只删**当天**该类型；
     * - `--ez all true`：删**全部类型、全部日期**（全表），此时不接受 `type`。
     *
     * 历史事故：`clear --ez all true` 没带 type 时命令被拒，但 `am broadcast` 照样打印
     * `Broadcast completed: result=0`，验证方误判成「已清空」（total 还是 282）。
     */
    data class Clear(val type: RecordType?, val all: Boolean) : DebugCommand {
        override val name: String = "clear"
    }

    /** 拉起界面并直达指定屏。 */
    data class Open(val screen: DebugScreen) : DebugCommand {
        override val name: String = "open"
    }

    /** 转发一次真实心跳（AlarmReceiver 的 onReceive）。 */
    data object Heartbeat : DebugCommand {
        override val name: String = "heartbeat"
    }

    /**
     * 只读数据库查询：`tables`（表名 + 行数 + user_version）、`schema`（列定义）、
     * `query`（单条 select/with）。
     *
     * 为什么不在 CLI 侧 `adb pull` 库文件：真机库 8MB 级，USB 一断就是半个文件；
     * 库开着 WAL 时直接拷主文件还会拷到下坏页。查询走进程内、只读连接，拿到的永远是
     * 一份一致的快照。
     */
    data class Db(val op: DbOp, val table: String? = null, val sql: String? = null, val limit: Int = 0) :
        DebugCommand {
        override val name: String = "db"
    }

    /**
     * 无障碍界面通道：看控件树、点、滚、返回。
     *
     * 为什么必须走这里而不是 `adb shell input`：输入注入是伪造事件，绕开 App 真实交互且在部分
     * ROM 上被拦；这条通道在 App 进程内读树 + performAction，不需要 root、不注入事件。
     * 服务未开时一律 `ok=false reason=accessibility_off`，绝不静默失败。
     */
    data class Ui(val action: UiAction) : DebugCommand {
        override val name: String get() = "ui." + action.op
    }
}

/** `db` 的子操作。 */
enum class DbOp(val value: String) {
    /** 所有表名 + 行数 + user_version。 */
    TABLES("tables"),

    /** 单表列定义（PRAGMA table_info）。 */
    SCHEMA("schema"),

    /** 单条只读查询。 */
    QUERY("query"),
    ;

    companion object {
        fun from(text: String): DbOp? = entries.firstOrNull { it.value == text }

        fun list(): String = entries.joinToString("|") { it.value }
    }
}

/** `open` 支持的目标屏。 */
enum class DebugScreen(val value: String, val appTabIndex: Int?) {
    OVERVIEW("overview", 0),
    LIVE("live", 1),

    /** 跳过广告页（Tab 下标 2）——验收截图直接落到 s03，不用手动点 Tab。 */
    AD("ad", 2),
    SETTINGS("settings", 5),

    /** 没有 Tab 下标：走独立的 debug 宿页（见 `DebugTripActivity`）。 */
    TRIP("trip", null),
    ;

    companion object {
        fun from(text: String): DebugScreen? = entries.firstOrNull { it.value == text }
    }
}

/** `seed` 支持的场景。 */
enum class SeedScenario(val value: String) {
    /** 上午在家 / 中午移动 / 下午在公司 / 晚上回家（3 段停留 + 2 段移动）。 */
    DAY("day"),

    /** 全天一个地点（1 段停留、0 段移动）。 */
    STAY("stay"),

    /**
     * 全天在路上：步进 5 分钟 / 550m，两端只做 10 分钟短停。
     *
     * 预期聚合结果：**没有长期停留**（每段停留 = 10 分钟）、**≥2 段移动**、未记录只占首尾几分钟。
     */
    MOVE("move"),

    /** 两段停留之间留出 >1 小时空档（验证空档口径：空档不算移动）。 */
    GAP("gap"),

    /**
     * 带噪声移动：直线步行 ±30m 抖动（accuracy 15~60m）+ 室内 network 段（accuracy 250~450m）。
     *
     * 给「轨迹线按精度分档」与「虚线说明行」做截图对照：步行段应当全实线，室内段应当全虚线。
     */
    NOISE("noise"),
    ;

    companion object {
        fun from(text: String): SeedScenario? = entries.firstOrNull { it.value == text }
    }
}

/** 解析结果：成功或被拒绝的理由（拒绝必须带可执行的提示）。 */
sealed interface ParseResult {
    data class Ok(val command: DebugCommand) : ParseResult

    /** 缺 `cmd`：调用方连「要干什么」都没给。 */
    data class MissingCmd(val supported: List<String>) : ParseResult

    /** `cmd` 不在命令表里。 */
    data class UnknownCmd(val cmd: String, val supported: List<String>) : ParseResult

    /** 命令合法但参数缺失 / 类型不符 / 取值越界 / 组合非法。 */
    data class BadArgs(val cmd: String, val message: String) : ParseResult
}

/**
 * 广播 extra 的解析与校验（纯函数）。
 *
 * 取值约定（与 `adb shell am broadcast` 一致）：
 * - `--es cmd <name>` → `Map["cmd"] = String`
 * - `--ez bypass_throttle true` → `Map["bypass_throttle"] = Boolean`
 * - `--ei limit 20` → `Map["limit"] = Int`
 *
 * 非法入参一律 fail-closed：不猜默认值、不静默吞掉，返回 [ParseResult.BadArgs] 并给出期望形态。
 */
object DebugCommands {

    const val EXTRA_CMD = "cmd"
    const val EXTRA_TAG = "tag"
    const val EXTRA_TYPE = "type"
    const val EXTRA_LIMIT = "limit"
    const val EXTRA_SCENARIO = "scenario"
    const val EXTRA_SCREEN = "screen"
    const val EXTRA_BYPASS_THROTTLE = "bypass_throttle"
    const val EXTRA_ALL = "all"

    /** 破坏性命令的一次性确认令牌（值由 `unlock` 回执下发，一次有效、5 分钟过期）。 */
    const val EXTRA_CONFIRM = "confirm"

    const val EXTRA_OP = "op"
    const val EXTRA_TABLE = "table"
    const val EXTRA_SQL = "sql"

    const val EXTRA_TEXT = "text"
    const val EXTRA_DESC = "desc"
    const val EXTRA_ID = "id"
    const val EXTRA_BOUNDS = "bounds"
    const val EXTRA_DIR = "dir"
    const val EXTRA_WIN = "win"
    const val EXTRA_MS = "ms"
    const val EXTRA_X1 = "x1"
    const val EXTRA_Y1 = "y1"
    const val EXTRA_X2 = "x2"
    const val EXTRA_Y2 = "y2"

    const val DEFAULT_DUMP_LIMIT = 20
    const val MAX_DUMP_LIMIT = 200

    /** `db query` 的行数默认值 / 上限：比 dump 宽一点（排障常要整段分布），但仍有硬顶。 */
    const val DEFAULT_QUERY_LIMIT = 50
    const val MAX_QUERY_LIMIT = 200

    /** 表名只接受普通标识符：PRAGMA 不支持占位符，限制字符集比拼接转义更稳。 */
    private val TABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** `ui wait` 的上限：等待只是「等动画稳定」，再长就该让调用方自己重试，别占着广播不放。 */
    const val MAX_WAIT_MS = 5_000
    const val DEFAULT_WAIT_MS = 1_000

    /** `ui swipe` 的时长边界：短于 50ms 会被系统当 fling，长于 10s 会一直占住触摸。 */
    const val MIN_SWIPE_MS = 50
    const val MAX_SWIPE_MS = 10_000
    const val DEFAULT_SWIPE_MS = 300

    /** 屏幕坐标的合理上界：超过它基本是参数串位（比如把 ms 填进了 x2）。 */
    const val MAX_COORDINATE = 20_000

    /** 命令表（顺序 = 文档顺序，错误信息里直接复用它）。 */
    val SUPPORTED: List<String> = listOf(
        "unlock", "state", "sample_location", "dump", "seed", "clear", "open", "heartbeat", "db", "ui",
    )

    /**
     * `RecordType` 的全部类型名（`state` 的计数要列出 0 值，不能只列「有记录的」）。
     *
     * 直接用 [RecordType.CANONICAL_NAMES]：落库口径只有一份，新增类型时不会再出现
     * 「fromString 认识、CLI 提示里没有」的两份口径。
     */
    val KNOWN_TYPES: List<String> = RecordType.CANONICAL_NAMES

    fun parse(extras: Map<String, Any?>): ParseResult {
        val rawCmd = extras[EXTRA_CMD]
        if (rawCmd == null) return ParseResult.MissingCmd(SUPPORTED)
        if (rawCmd !is String) {
            return ParseResult.BadArgs("-", "cmd 必须是字符串（--es cmd <name>），实际 ${rawCmd.javaClass.simpleName}")
        }
        val cmd = rawCmd.trim().lowercase()
        if (cmd.isEmpty()) return ParseResult.MissingCmd(SUPPORTED)
        if (cmd !in SUPPORTED) return ParseResult.UnknownCmd(cmd, SUPPORTED)

        return try {
            when (cmd) {
                "unlock" -> ParseResult.Ok(DebugCommand.Unlock)
                "state" -> ParseResult.Ok(DebugCommand.State)
                "heartbeat" -> ParseResult.Ok(DebugCommand.Heartbeat)
                "sample_location" -> ParseResult.Ok(
                    DebugCommand.SampleLocation(bypassThrottle = boolOf(extras, EXTRA_BYPASS_THROTTLE)),
                )

                "dump" -> {
                    val limit = intOf(extras, EXTRA_LIMIT, DEFAULT_DUMP_LIMIT)
                    if (limit < 1 || limit > MAX_DUMP_LIMIT) {
                        throw ArgError("limit 超出范围：期望 1..$MAX_DUMP_LIMIT，实际 $limit")
                    }
                    ParseResult.Ok(DebugCommand.Dump(limit = limit, type = typeOf(extras, required = false)))
                }

                "seed" -> {
                    val text = scenarioOf(extras)
                    val scenario = SeedScenario.from(text)
                        ?: throw ArgError("scenario 取值非法：期望 ${scenarioList()}，实际 $text")
                    ParseResult.Ok(DebugCommand.Seed(scenario))
                }

                "clear" -> {
                    // type 与 all 二选一：两个都给 / 都不给都拒绝，不留「以为删了」的空间
                    val all = boolOf(extras, EXTRA_ALL)
                    val type = typeOf(extras, required = false)
                    when {
                        all && type != null -> throw ArgError(
                            "clear 的 type 与 all 互斥：all=true 已是全表删除（全部类型 + 全部日期），不要再给 type",
                        )

                        !all && type == null -> throw ArgError(
                            "clear 需要 --es type <${KNOWN_TYPES.joinToString("|")}> 或 --ez all true（二选一），" +
                                "都不给等于什么都没删",
                        )

                        else -> ParseResult.Ok(DebugCommand.Clear(type = type, all = all))
                    }
                }

                "open" -> {
                    val text = (extras[EXTRA_SCREEN] as? String)?.trim()?.lowercase()
                    if (text.isNullOrEmpty()) {
                        throw ArgError("缺少 --es screen <${screenList()}>")
                    }
                    val screen = DebugScreen.from(text)
                        ?: throw ArgError("screen 取值非法：期望 ${screenList()}，实际 $text")
                    ParseResult.Ok(DebugCommand.Open(screen))
                }

                "db" -> ParseResult.Ok(dbCommandOf(extras))

                "ui" -> ParseResult.Ok(DebugCommand.Ui(uiActionOf(extras)))

                else -> throw ArgError("未实现的命令：$cmd")
            }
        } catch (e: ArgError) {
            ParseResult.BadArgs(cmd, e.message ?: "参数非法")
        }
    }

    /** 广播里的回执 token：CLI 靠它把「本次调用」的输出从历史日志里区分出来。 */
    fun tokenOf(extras: Map<String, Any?>): String? = (extras[EXTRA_TAG] as? String)?.trim()?.ifEmpty { null }

    /**
     * 破坏性命令的确认令牌。
     *
     * 只在解析期取值、不做校验 —— 校验要读进程内状态（`DebugConfirmToken`），属于 receiver 的职责；
     * 解析层只保证「这是个字符串」，非字符串（`--ei confirm 1`）照样当参数错误拒掉。
     */
    fun confirmOf(extras: Map<String, Any?>): String? =
        stringOf(extras, EXTRA_CONFIRM)?.trim()?.ifEmpty { null }

    fun scenarioList(): String = SeedScenario.entries.joinToString("|") { it.value }

    fun screenList(): String = DebugScreen.entries.joinToString("|") { it.value }

    // ──────────────────────────── 参数读取 ────────────────────────────

    /** 参数非法的内部信号：`parse` 统一转成 [ParseResult.BadArgs]，避免回调金字塔。 */
    private class ArgError(message: String) : Exception(message)

    private fun boolOf(extras: Map<String, Any?>, key: String, default: Boolean = false): Boolean =
        when (val raw = extras[key]) {
            null -> default
            is Boolean -> raw
            is String -> when (raw.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> throw ArgError("参数 $key 取值非法：期望 true|false，实际 $raw")
            }

            else -> throw ArgError("参数 $key 必须是布尔（--ez $key true），实际 ${raw.javaClass.simpleName}")
        }

    private fun intOf(extras: Map<String, Any?>, key: String, default: Int): Int =
        when (val raw = extras[key]) {
            null -> default
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
                ?: throw ArgError("参数 $key 必须是整数（--ei $key 20），实际 $raw")

            else -> throw ArgError("参数 $key 必须是整数（--ei $key 20），实际 ${raw.javaClass.simpleName}")
        }

    /** `type` 缺省或空串 → null（dump 不筛类型 / clear 交给 all 分支判定）。 */
    private fun typeOf(extras: Map<String, Any?>, required: Boolean): RecordType? {
        val raw = extras[EXTRA_TYPE]
        if (raw == null) {
            if (required) throw ArgError("缺少 --es type <${KNOWN_TYPES.joinToString("|")}>")
            return null
        }
        if (raw !is String) {
            throw ArgError("参数 type 必须是字符串（--es type LOCATION），实际 ${raw.javaClass.simpleName}")
        }
        val text = raw.trim().uppercase()
        if (text.isEmpty()) {
            if (required) throw ArgError("缺少 --es type <${KNOWN_TYPES.joinToString("|")}>")
            return null
        }
        return RecordType.fromString(text)
            ?: throw ArgError("type 取值非法：期望 ${KNOWN_TYPES.joinToString("|")}，实际 $text")
    }

    /** `db` 的 op / table / sql / limit 解析；非法组合一律抛 [ArgError] 转 BadArgs。 */
    private fun dbCommandOf(extras: Map<String, Any?>): DebugCommand.Db {
        val rawOp = extras[EXTRA_OP]
        if (rawOp == null) throw ArgError("db 需要 --es op <${DbOp.list()}>")
        if (rawOp !is String) throw ArgError("参数 op 必须是字符串（--es op tables），实际 ${rawOp.javaClass.simpleName}")
        val opText = rawOp.trim().lowercase()
        if (opText.isEmpty()) throw ArgError("db 需要 --es op <${DbOp.list()}>")
        val op = DbOp.from(opText) ?: throw ArgError("op 取值非法：期望 ${DbOp.list()}，实际 $opText")
        return when (op) {
            DbOp.TABLES -> DebugCommand.Db(DbOp.TABLES)
            DbOp.SCHEMA -> DebugCommand.Db(DbOp.SCHEMA, table = tableOf(extras))
            DbOp.QUERY -> {
                val limit = intOf(extras, EXTRA_LIMIT, DEFAULT_QUERY_LIMIT)
                if (limit < 1 || limit > MAX_QUERY_LIMIT) {
                    throw ArgError("limit 超出范围：期望 1..$MAX_QUERY_LIMIT，实际 $limit")
                }
                val check = DbSql.validate(sqlOf(extras))
                if (!check.ok) throw ArgError("sql 被拒绝：" + check.reason)
                DebugCommand.Db(DbOp.QUERY, sql = check.sql, limit = limit)
            }
        }
    }

    private fun tableOf(extras: Map<String, Any?>): String {
        val raw = extras[EXTRA_TABLE]
        if (raw == null) throw ArgError("db schema 需要 --es table <表名>")
        if (raw !is String) throw ArgError("参数 table 必须是字符串（--es table records），实际 ${raw.javaClass.simpleName}")
        val table = raw.trim()
        if (table.isEmpty()) throw ArgError("db schema 需要 --es table <表名>")
        if (!TABLE_NAME.matches(table)) {
            throw ArgError("table 取值非法：只接受字母/数字/下划线、且不以数字开头，实际 $table")
        }
        return table
    }

    private fun sqlOf(extras: Map<String, Any?>): String {
        val raw = extras[EXTRA_SQL]
        if (raw == null) throw ArgError("db query 需要 --es sql \"select ...\"")
        if (raw !is String) throw ArgError("参数 sql 必须是字符串（--es sql \"select ...\"），实际 ${raw.javaClass.simpleName}")
        return raw
    }

    /** `ui` 的 op + 各动作参数解析；非法组合一律抛 [ArgError] 转 BadArgs。 */
    private fun uiActionOf(extras: Map<String, Any?>): UiAction {
        val rawOp = extras[EXTRA_OP]
        if (rawOp == null) throw ArgError("ui 需要 --es op <${UiAction.list()}>")
        if (rawOp !is String) throw ArgError("参数 op 必须是字符串（--es op dump），实际 ${rawOp.javaClass.simpleName}")
        val op = rawOp.trim().lowercase()
        if (op.isEmpty()) throw ArgError("ui 需要 --es op <${UiAction.list()}>")
        return when (op) {
            "dump" -> UiAction.Dump(tag = stringOf(extras, EXTRA_WIN)?.trim()?.ifEmpty { null })

            // 标签沿用 dump 的 --es win：`--es tag` 已被 CLI 的回执 token 占用
            "audit" -> UiAction.Audit(tag = stringOf(extras, EXTRA_WIN)?.trim()?.ifEmpty { null })

            "click" -> UiAction.Click(selectorOf(extras, op))
            "longclick" -> UiAction.LongClick(selectorOf(extras, op))

            "scroll" -> {
                val dirText = stringOf(extras, EXTRA_DIR)?.trim()?.lowercase().orEmpty()
                if (dirText.isEmpty()) throw ArgError("ui scroll 需要 --es dir <${ScrollDir.list()}>")
                val dir = ScrollDir.from(dirText)
                    ?: throw ArgError("dir 取值非法：期望 ${ScrollDir.list()}，实际 $dirText")
                UiAction.Scroll(dir)
            }

            "swipe" -> {
                val x1 = coordinateOf(extras, EXTRA_X1)
                val y1 = coordinateOf(extras, EXTRA_Y1)
                val x2 = coordinateOf(extras, EXTRA_X2)
                val y2 = coordinateOf(extras, EXTRA_Y2)
                if (x1 == x2 && y1 == y2) throw ArgError("swipe 的起止点不能重合：给 --ei x1/y1/x2/y2 两个不同的点")
                val ms = intOf(extras, EXTRA_MS, DEFAULT_SWIPE_MS)
                if (ms < MIN_SWIPE_MS || ms > MAX_SWIPE_MS) {
                    throw ArgError("ms 超出范围：期望 $MIN_SWIPE_MS..$MAX_SWIPE_MS，实际 $ms")
                }
                UiAction.Swipe(x1 = x1, y1 = y1, x2 = x2, y2 = y2, ms = ms)
            }

            "back" -> UiAction.Back
            "home" -> UiAction.Home
            "recents" -> UiAction.Recents

            "wait" -> {
                val ms = intOf(extras, EXTRA_MS, DEFAULT_WAIT_MS)
                if (ms < 0 || ms > MAX_WAIT_MS) throw ArgError("ms 超出范围：期望 0..$MAX_WAIT_MS，实际 $ms")
                UiAction.Wait(ms)
            }

            else -> throw ArgError("ui 的 op 取值非法：期望 ${UiAction.list()}，实际 $op")
        }
    }

    /** `click` / `longclick` 至少要给一个选择器：空选择器会点到页面第一个节点。 */
    private fun selectorOf(extras: Map<String, Any?>, op: String): UiSelector {
        val boundsText = stringOf(extras, EXTRA_BOUNDS)?.trim()?.ifEmpty { null }
        val bounds = boundsText?.let {
            UiRect.parse(it) ?: throw ArgError(
                "bounds 取值非法：期望 x1,y1,x2,y2（如 10,20,110,80 且 x2>x1、y2>y1），实际 $it",
            )
        }
        val selector = UiSelector(
            text = stringOf(extras, EXTRA_TEXT)?.trim()?.ifEmpty { null },
            desc = stringOf(extras, EXTRA_DESC)?.trim()?.ifEmpty { null },
            id = stringOf(extras, EXTRA_ID)?.trim()?.ifEmpty { null },
            bounds = bounds,
        )
        if (selector.isEmpty) {
            throw ArgError(
                "ui $op 需要选择器（至少给一个）：--es text <片段> / --es desc <片段> / " +
                    "--es id <资源名片段> / --es bounds x1,y1,x2,y2",
            )
        }
        return selector
    }

    /** 屏幕坐标：负值只可能是参数写错（手势坐标原点在左上角）。 */
    private fun coordinateOf(extras: Map<String, Any?>, key: String): Int {
        val value = intOf(extras, key, Int.MIN_VALUE)
        if (value == Int.MIN_VALUE) throw ArgError("ui swipe 缺少 --ei $key <坐标>")
        if (value < 0 || value > MAX_COORDINATE) {
            throw ArgError("参数 $key 超出范围：期望 0..$MAX_COORDINATE，实际 $value")
        }
        return value
    }

    /** 字符串参数：给了非字符串一律拒（`--es` 与 `--ei` 用错时能一眼看出来）。 */
    private fun stringOf(extras: Map<String, Any?>, key: String): String? =
        when (val raw = extras[key]) {
            null -> null
            is String -> raw
            else -> throw ArgError("参数 $key 必须是字符串（--es $key <值>），实际 ${raw.javaClass.simpleName}")
        }

    private fun scenarioOf(extras: Map<String, Any?>): String =
        when (val raw = extras[EXTRA_SCENARIO]) {
            null -> SeedScenario.DAY.value
            is String -> raw.trim().lowercase().ifEmpty { SeedScenario.DAY.value }
            else -> throw ArgError("scenario 必须是字符串（--es scenario ${scenarioList()}）")
        }
}

/**
 * `db` 的只读闸门（纯函数，纯 JVM 单测）。
 *
 * 判定顺序（任一不满足即拒，理由都写进 [SqlCheck.reason] 供 CLI 直接回显）：
 * 1. **单语句**：只允许结尾一个 `;`，中间再出现 `;` 一律拒；
 * 2. **不做注释**：`--` / `/* */` 一律拒 —— 注释能把真实语句藏在无害前缀后面；
 * 3. **白名单开头**：去掉字符串字面量后必须以 `select` / `with` 开头；
 * 4. **黑名单词元**：attach / pragma / insert / update / delete / drop / alter / create /
 *    replace / vacuum 等词元一律拒（[DANGEROUS]）。
 *
 * 词元级比较：`created_at` 不会误判成 `create`；但被引号包起来的 `"delete"` 会被拒 ——
 * 宁可多拒一条，也不放过一条写语句。
 */
object DbSql {

    /** 单字段截断长度：detail 里的 JSON 也可能很长，500 字足够看清又不会撑爆 logcat。 */
    const val MAX_FIELD_CHARS = 500

    /** 只读模式拒绝的关键字（词元级比较，忽略大小写）。 */
    val DANGEROUS: List<String> = listOf(
        "attach", "pragma", "insert", "update", "delete",
        "drop", "alter", "create", "replace", "vacuum", "load_extension",
    )

    private val LITERAL = Regex("'(?:[^']|'')*'")
    private val TOKEN_SEPARATOR = Regex("[^a-z0-9_]+")

    fun validate(raw: String): SqlCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return SqlCheck(null, "sql 不能为空")
        val body = if (trimmed.endsWith(";")) trimmed.dropLast(1).trim() else trimmed
        if (body.isEmpty()) return SqlCheck(null, "sql 不能只有分号")
        if (body.contains(';')) {
            return SqlCheck(null, "只允许单条语句：分号后面不能再有内容")
        }
        if (body.contains("--") || body.contains("/*") || body.contains("*/")) {
            return SqlCheck(null, "不接受注释（-- 或 /* */）：注释会把真实语句藏起来")
        }
        val stripped = stripLiterals(body)
        val head = stripped.lowercase().takeWhile { it.isLetter() && it.code < 128 }
        if (head != "select" && head != "with") {
            return SqlCheck(null, "只读：语句必须以 select 或 with 开头，实际以「${body.take(24)}」开头")
        }
        val hit = stripped.lowercase().split(TOKEN_SEPARATOR).firstOrNull { it in DANGEROUS }
        if (hit != null) {
            return SqlCheck(null, "只读模式拒绝 `$hit`：只允许 select / with 查询，不允许改库或读库结构")
        }
        return SqlCheck(body, null)
    }

    /** 把单引号字符串字面量的内容抹掉，避免 `where type='DELETE'` 被关键字表误伤。 */
    fun stripLiterals(sql: String): String = LITERAL.replace(sql, "''")

    /** 超长字段截断并加省略号（调用方据此计数「截断了几处」）。 */
    fun truncate(value: String, max: Int = MAX_FIELD_CHARS): String =
        if (value.length <= max) value else value.take(max) + "…"
}

/** [DbSql.validate] 的结果：ok 时 [sql] 是去掉结尾分号的语句，否则 [reason] 给拒绝理由。 */
data class SqlCheck(val sql: String?, val reason: String?) {
    val ok: Boolean get() = sql != null
}
