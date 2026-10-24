package com.bsfeng.monitor.debug

import com.bsfeng.monitor.domain.model.RecordType

/**
 * 调试命令契约（**只在 debug 变体编译**，见 `app/src/debug/`）——纯逻辑，可纯 JVM 单测。
 *
 * 为什么要有这一层：把「解析 + 参数校验 + 拒绝理由」从 BroadcastReceiver 里拆出来，
 * 单测不需要 Robolectric、不需要 Intent，非法入参的处置（fail-closed）能被断言住。
 * Android 侧的 `Intent -> Map<String, Any?>` 适配放在 [DebugCommandReceiver]。
 */
sealed interface DebugCommand {
    /** logcat 里 `RESULT cmd=<name>` 用的稳定标识，改它等于改 CLI 契约。 */
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

    /** 插入一套可看的模拟记录。 */
    data class Seed(val scenario: SeedScenario) : DebugCommand {
        override val name: String = "seed"
    }

    /**
     * 删除记录。**`type` 与 `all` 必须二选一**（两个都给也拒绝，避免「以为删了全部其实只删了一类」）：
     * - `--es type PAGE`：只删**当天**该类型；
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

    const val DEFAULT_DUMP_LIMIT = 20
    const val MAX_DUMP_LIMIT = 200

    /** 命令表（顺序 = 文档顺序，错误信息里直接复用它）。 */
    val SUPPORTED: List<String> = listOf(
        "unlock", "state", "sample_location", "dump", "seed", "clear", "open", "heartbeat",
    )

    /**
     * `RecordType` 的全部类型名（`state` 的计数要列出 0 值，不能只列「有记录的」）。
     *
     * 直接用 [RecordType.CANONICAL_NAMES]：落库契约只有一份，新增类型时不会再出现
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

                else -> throw ArgError("未实现的命令：$cmd")
            }
        } catch (e: ArgError) {
            ParseResult.BadArgs(cmd, e.message ?: "参数非法")
        }
    }

    /** 广播里的回执 token：CLI 靠它把「本次调用」的输出从历史日志里区分出来。 */
    fun tokenOf(extras: Map<String, Any?>): String? = (extras[EXTRA_TAG] as? String)?.trim()?.ifEmpty { null }

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
            throw ArgError("参数 type 必须是字符串（--es type PAGE），实际 ${raw.javaClass.simpleName}")
        }
        val text = raw.trim().uppercase()
        if (text.isEmpty()) {
            if (required) throw ArgError("缺少 --es type <${KNOWN_TYPES.joinToString("|")}>")
            return null
        }
        return RecordType.fromString(text)
            ?: throw ArgError("type 取值非法：期望 ${KNOWN_TYPES.joinToString("|")}，实际 $text")
    }

    private fun scenarioOf(extras: Map<String, Any?>): String =
        when (val raw = extras[EXTRA_SCENARIO]) {
            null -> SeedScenario.DAY.value
            is String -> raw.trim().lowercase().ifEmpty { SeedScenario.DAY.value }
            else -> throw ArgError("scenario 必须是字符串（--es scenario ${scenarioList()}）")
        }
}
