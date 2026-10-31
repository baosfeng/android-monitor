package com.bsfeng.monitor.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.database.sqlite.transaction
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.core.di.serviceContainer
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.record.LocationPolicy
import com.bsfeng.monitor.record.LocationSample
import com.bsfeng.monitor.service.AlarmReceiver
import com.bsfeng.monitor.ui.live.startOfDay
import com.bsfeng.monitor.util.DebugConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 调试命令广播接收器（**只在 debug 变体注册**，见 `app/src/debug/AndroidManifest.xml`）。
 *
 * 用法：
 * ```
 * adb shell am broadcast -n com.bsfeng.monitor/.debug.DebugCommandReceiver --es cmd state
 * ```
 * 输出统一走 logcat，tag `DebugCmd`，单行：
 * `RESULT cmd=<name>[ reason=<r>][ tag=<t>] ok=<true|false> data=<json>`
 *
 * 约定：
 * - **除 `unlock` 外一律要求已解锁**（`DebugConfig.unlocked`），未解锁回 `ok=false reason=locked`，
 *   避免 debug 包被别人/旧脚本误触发写库；
 * - 全部命令在 IO 协程里跑并持 `goAsync()`，广播回调立即返回（定位最多 5 秒，DB 写毫秒级），
 *   结果行可能在 `am broadcast` 返回之后才出现 —— CLI 用 `tag` 回执轮询；
 * - 不改生产链路签名：定位复用 `LocationCollector.collectThrottledAsync` +
 *   `RecordCollector.onLocation`，心跳复用真实 `AlarmReceiver` 广播。
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = extrasOf(intent)
        val token = DebugCommands.tokenOf(extras)
        when (val parsed = DebugCommands.parse(extras)) {
            is ParseResult.MissingCmd -> emitFailure(
                cmd = "-",
                token = token,
                reason = "bad_args",
                data = JSONObject()
                    .put("message", "缺少 --es cmd <name>")
                    .put("expected", JSONArray(parsed.supported)),
            )

            is ParseResult.UnknownCmd -> emitFailure(
                cmd = parsed.cmd,
                token = token,
                reason = "bad_args",
                data = JSONObject()
                    .put("message", "未知命令")
                    .put("expected", JSONArray(parsed.supported)),
            )

            is ParseResult.BadArgs -> emitFailure(
                cmd = parsed.cmd,
                token = token,
                reason = "bad_args",
                data = JSONObject().put("message", parsed.message),
            )

            is ParseResult.Ok -> run(context, parsed.command, token)
        }
    }

    private fun run(context: Context, command: DebugCommand, token: String?) {
        // unlock 是唯一免解锁命令：先把闸打开，后面的命令才有意义
        if (command is DebugCommand.Unlock) {
            runCatching {
                DebugConfig.unlock()
                DebugConfig.setEnabled(true)
            }
            emit(
                cmd = command.name,
                token = token,
                data = JSONObject()
                    .put("buildDebug", BuildConfig.DEBUG)
                    .put("unlocked", DebugConfig.unlocked)
                    .put("debugEnabled", DebugConfig.enabled),
            )
            return
        }
        if (!DebugConfig.unlocked) {
            emitFailure(
                cmd = command.name,
                token = token,
                reason = "locked",
                data = JSONObject()
                    .put("message", "调试层未解锁")
                    .put("hint", "先执行 scripts/debug-cli.sh unlock"),
            )
            return
        }

        // 广播回调必须立刻返回：所有命令（含 DB 读写）都丢到 IO 协程，任务结束才 finish
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                execute(context.applicationContext, command, token)
            } catch (t: Throwable) {
                Log.w(TAG, "命令执行失败 cmd=${command.name}", t)
                emitFailure(
                    cmd = command.name,
                    token = token,
                    reason = "error",
                    data = JSONObject()
                        .put("message", t.javaClass.simpleName + ": " + (t.message ?: "")),
                )
            } finally {
                runCatching { pending?.finish() }
            }
        }
    }

    private suspend fun execute(context: Context, command: DebugCommand, token: String?) {
        when (command) {
            DebugCommand.Unlock -> Unit // 已在 run() 里处理
            DebugCommand.State -> emitState(context, token)
            is DebugCommand.SampleLocation -> emitSampleLocation(context, command, token)
            is DebugCommand.Dump -> emitDump(context, command, token)
            is DebugCommand.Seed -> emitSeed(context, command, token)
            is DebugCommand.Clear -> emitClear(context, command, token)
            is DebugCommand.Open -> emitOpen(context, command, token)
            is DebugCommand.Db -> emitDb(context, command, token)
            DebugCommand.Heartbeat -> emitHeartbeat(context, token)
            is DebugCommand.Ui -> emitUi(context, command, token)
        }
    }

    // ─────────────────────────────── ui ───────────────────────────────

    /**
     * 无障碍界面通道。全部动作在 [DebugUiBridge] 里 post 到主线程执行。
     *
     * 超时保护比 CLI 的 12 秒轮询**短 1 秒**：目标 App 不响应（动画卡死 / 窗口不给节点）时，
     * 必须由端上先打出 `reason=timeout`，否则 CLI 先放弃、验证方只看到「没有结果行」，
     * 分不清是「命令超时」还是「广播没投递到」。
     */
    private suspend fun emitUi(context: Context, cmd: DebugCommand.Ui, token: String?) {
        val outcome = withTimeoutOrNull(UI_TIMEOUT_MS) {
            DebugUiBridge.run(context.applicationContext, cmd.action)
        }
        when (outcome) {
            null -> emitFailure(
                cmd = cmd.name,
                token = token,
                reason = "timeout",
                data = JSONObject()
                    .put("op", cmd.action.op)
                    .put("timeoutMs", UI_TIMEOUT_MS)
                    .put("hint", "无障碍主线程没在时限内返回：确认屏幕亮着、服务未被系统重连（再跑一次 ui dump）"),
            )

            is DebugUiBridge.Outcome.Fail -> emitFailure(
                cmd = cmd.name,
                token = token,
                reason = outcome.reason,
                data = outcome.data,
            )

            is DebugUiBridge.Outcome.Ok -> {
                // dump 的 path/nodes/pkg 额外打到 RESULT 行前缀：CLI 不用解析 data 就能直接 cat 文件
                val extra = LinkedHashMap<String, String>()
                if (cmd.action is UiAction.Dump) {
                    extra["path"] = outcome.data.optString("path")
                    extra["nodes"] = outcome.data.optInt("nodes").toString()
                    extra["pkg"] = outcome.data.optString("pkg", "?")
                }
                emitWithDetail(cmd = cmd.name, token = token, detail = extra, data = outcome.data)
            }
        }
    }

    // ────────────────────────────── state ──────────────────────────────

    private fun emitState(context: Context, token: String?) {
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)
        val store = context.serviceContainer.recordStore
        val records = store.queryByDate(dayStart, now + 1)

        val byType = records.groupingBy { RecordType.toString(it.type) }.eachCount()
        val counts = JSONObject()
        // 先铺 0 值：只看「有记录的」会让「某类一条没采到」看起来像命令没生效
        DebugCommands.KNOWN_TYPES.forEach { counts.put(it, byType[it] ?: 0) }
        byType.keys.filter { it !in DebugCommands.KNOWN_TYPES }.forEach { counts.put(it, byType[it] ?: 0) }

        val flags = FeatureFlags.get(context)
        val features = JSONObject()
        flags.snapshot().forEach { (feature, on) -> features.put(feature.name, on) }

        val location = JSONObject()
            .put("permission", LocationCollector.get(context).status().name)
            .put("featureEnabled", flags.isEnabled(Feature.LOCATION))

        emit(
            cmd = "state",
            token = token,
            data = JSONObject()
                .put("buildDebug", BuildConfig.DEBUG)
                .put("unlocked", DebugConfig.unlocked)
                .put("debugEnabled", DebugConfig.enabled)
                .put("dayStart", dayStart)
                .put("now", now)
                .put("features", features)
                .put("location", location)
                .put("counts", counts)
                .put("total", records.size)
                .put("latestTs", records.maxOfOrNull { it.timestamp } ?: 0L),
        )
    }

    // ────────────────────────── sample_location ──────────────────────────

    private suspend fun emitSampleLocation(context: Context, cmd: DebugCommand.SampleLocation, token: String?) {
        // 绕过节流 = 用一个新实例（节流状态挂在实例上）；不绕流走进程内单例，与心跳共享 10 分钟窗口
        val collector = if (cmd.bypassThrottle) LocationCollector(context) else LocationCollector.get(context)
        val sample = awaitSample(collector)
        if (sample == null) {
            val status = collector.status()
            val reason = if (status == LocationPermissionStatus.NOT_GRANTED) "permission_denied" else "no_fix"
            emitFailure(
                cmd = cmd.name,
                token = token,
                reason = reason,
                data = JSONObject()
                    .put("permission", status.name)
                    .put("bypassThrottle", cmd.bypassThrottle)
                    .put(
                        "hint",
                        if (status == LocationPermissionStatus.NOT_GRANTED) {
                            "先给定位权限：adb shell pm grant com.bsfeng.monitor android.permission.ACCESS_FINE_LOCATION"
                        } else {
                            "缓存为空且主动定位未在 " + LocationPolicy.ACTIVE_FIX_BUDGET_MS + "ms 内出点；" +
                                "也可能是 10 分钟节流（用 --ez bypass_throttle true 重试）"
                        },
                    ),
            )
            return
        }
        // 与心跳同一条落库链路（不另开写入路径，避免调试数据与生产数据两套语义）
        context.serviceContainer.recordCollector.onLocation(sample)
        emit(
            cmd = cmd.name,
            token = token,
            data = JSONObject()
                .put("provider", sample.provider)
                .put("accuracy_m", sample.accuracyMeters.toInt())
                .put("lat", LocationPolicy.quantize(sample.latitude))
                .put("lng", LocationPolicy.quantize(sample.longitude))
                .put("timeMs", sample.timeMs)
                .put("bypassThrottle", cmd.bypassThrottle)
                .put("persisted", true),
        )
    }

    /** `collectThrottledAsync` 的「恰好一次回调」契约 → 转成挂起函数。 */
    private suspend fun awaitSample(collector: LocationCollector): LocationSample? =
        suspendCancellableCoroutine { continuation ->
            try {
                collector.collectThrottledAsync { sample ->
                    if (continuation.isActive) continuation.resume(sample)
                }
            } catch (t: Throwable) {
                // 采集器已声明不上抛，这里只是兜底：异常也必须让调用方拿到结果
                if (continuation.isActive) continuation.resume(null)
            }
        }

    // ────────────────────────────── dump ──────────────────────────────

    private fun emitDump(context: Context, cmd: DebugCommand.Dump, token: String?) {
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)
        val wanted = cmd.type?.let { RecordType.toString(it) }
        val records = context.serviceContainer.recordStore
            .queryByDate(dayStart, now + 1)
            .asSequence()
            .filter { wanted == null || RecordType.toString(it.type) == wanted }
            .sortedByDescending { it.timestamp }
            .take(cmd.limit)
            .toList()

        // logcat 单行上限约 4KB：按记录切片输出，part/parts 让 CLI 知道「收齐了没」
        val chunks = chunkByChars(records, MAX_LINE_CHARS)
        chunks.forEachIndexed { index, chunk ->
            val array = JSONArray()
            chunk.forEach { array.put(it.toJson()) }
            emit(
                cmd = cmd.name,
                token = token,
                data = JSONObject()
                    .put("type", wanted ?: "ALL")
                    .put("limit", cmd.limit)
                    .put("count", records.size)
                    .put("part", index + 1)
                    .put("parts", chunks.size)
                    .put("records", array),
            )
        }
        if (chunks.isEmpty()) {
            emit(
                cmd = cmd.name,
                token = token,
                data = JSONObject()
                    .put("type", wanted ?: "ALL")
                    .put("limit", cmd.limit)
                    .put("count", 0)
                    .put("part", 1)
                    .put("parts", 1)
                    .put("records", JSONArray()),
            )
        }
    }

    private fun chunkByChars(records: List<Record>, maxChars: Int): List<List<Record>> {
        if (records.isEmpty()) return emptyList()
        val chunks = ArrayList<List<Record>>()
        var current = ArrayList<Record>()
        var size = 0
        for (record in records) {
            val length = record.toJson().toString().length + 1
            if (current.isNotEmpty() && size + length > maxChars) {
                chunks.add(current)
                current = ArrayList()
                size = 0
            }
            current.add(record)
            size += length
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    // ────────────────────────────── seed ──────────────────────────────

    private fun emitSeed(context: Context, cmd: DebugCommand.Seed, token: String?) {
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)
        val records = DebugSeed.build(cmd.scenario, dayStart, now)
        val container = context.serviceContainer
        val db = container.databaseHelper.writableDatabase
        // 批量插入走事务（代码规范：批量写必须事务；用 androidx.core 的 transaction 扩展，
        // 失败自动 rollback，lint 的 UseKtx 也要求这个写法）
        db.transaction {
            records.forEach { container.recordStore.insert(it) }
        }
        emit(
            cmd = cmd.name,
            token = token,
            data = JSONObject()
                .put("scenario", cmd.scenario.value)
                .put("inserted", records.size)
                .put("dayStart", dayStart)
                .put("firstTs", records.minOfOrNull { it.timestamp } ?: 0L)
                .put("lastTs", records.maxOfOrNull { it.timestamp } ?: 0L)
                .put("source", DebugSeed.SOURCE)
                .put("hint", "行程页按分钟缓存：重新执行 open trip 会先清缓存并重建页面（无需等分钟滚动）"),
        )
    }

    // ────────────────────────────── clear ──────────────────────────────

    private fun emitClear(context: Context, cmd: DebugCommand.Clear, token: String?) {
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)
        val db = context.serviceContainer.databaseHelper.writableDatabase
        val scope: String
        val typeLabel: String
        val deleted: Int
        if (cmd.all) {
            // all=true：全表删除（全部类型 + 全部日期）。parse 已保证此时没有 type（互斥）
            scope = "all"
            typeLabel = "ALL"
            deleted = db.delete("records", null, null)
        } else {
            val type = RecordType.toString(cmd.type!!)
            scope = "today"
            typeLabel = type
            deleted = db.delete(
                "records",
                "type = ? AND timestamp >= ? AND timestamp <= ?",
                arrayOf(type, dayStart.toString(), (now + 1).toString()),
            )
        }
        // 顺手回报删除后的总条数：验证方不必再跑一次 state 才能确认「真删了」
        val totalLeft = DatabaseUtils.queryNumEntries(db, "records")
        emit(
            cmd = cmd.name,
            token = token,
            data = JSONObject()
                .put("scope", scope)
                .put("type", typeLabel)
                .put("deleted", deleted)
                .put("totalLeft", totalLeft),
        )
    }

    // ────────────────────────────── open ──────────────────────────────

    private fun emitOpen(context: Context, cmd: DebugCommand.Open, token: String?) {
        val screen = cmd.screen
        val tabIndex = screen.appTabIndex
        if (tabIndex == null) {
            // 没有 Tab 的二级页（行程）：走 debug 专属宿页，main 侧不用为调试加钩子。
            // TripCache 的键含「分钟」，同一分钟内的 seed 会命中旧结果 → 拉起前先清缓存（直接调用，
            // 不走反射，见 DebugTripCache 的 KDoc），宿页在 onNewIntent 里 recreate()，
            // 两步合起来保证「seed 完立刻 open 就能看到」。
            val cacheError = DebugTripCache.clear()
            val intent = Intent(context, DebugTripActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
            val data = JSONObject()
                .put("screen", screen.value)
                .put("target", DebugTripActivity::class.java.name)
                .put("cacheCleared", cacheError == null)
            // 清缓存失败时把真实原因带出来：下次一眼能看出是「没清」而不是「没数据」
            cacheError?.let { data.put("cacheClearedError", it) }
            emit(cmd = cmd.name, token = token, data = data)
            return
        }
        // 复用 MainActivity 已有的深链 extra（通知跳转同一条路）；SINGLE_TOP+CLEAR_TOP 保证复用实例并触发 onNewIntent
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(context, MainActivity::class.java.name)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            .putExtra(MainActivity.EXTRA_START_TAB, tabIndex)
        context.startActivity(intent)
        emit(
            cmd = cmd.name,
            token = token,
            data = JSONObject()
                .put("screen", screen.value)
                .put("target", MainActivity::class.java.name)
                .put("tabIndex", tabIndex),
        )
    }


    // ──────────────────────────────── db ────────────────────────────────

    /**
     * 只读查询通道，替代 `adb pull` 整个库文件（USB 一断就是半个文件；WAL 库拷主文件会拷到下坏页）。
     *
     * 三道闸：解析期已按 [DbSql.validate] 拒掉非单条 select/with → 优先只读连接打开 →
     * 全程只走 `rawQuery`，不碰 execSQL。
     *
     * 只读连接优先 `OPEN_READONLY`；WAL 库在部分机型上只读打开会失败（缺 `-shm` 写权限），
     * 退回到 App 已打开的可读连接，并在输出里标注 `mode`，让验证方知道走的是哪条路。
     */
    private fun emitDb(context: Context, cmd: DebugCommand.Db, token: String?) {
        val file = context.getDatabasePath(MONITOR_DB)
        if (!file.exists()) {
            emitFailure(
                cmd = cmd.name,
                token = token,
                reason = "db_missing",
                data = JSONObject().put("path", file.path).put("hint", "App 尚未写过库：先跑一次 state"),
            )
            return
        }
        val own = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull()
        val db = own ?: context.serviceContainer.databaseHelper.readableDatabase
        val mode = if (own != null) "readonly" else "app-connection"
        try {
            when (cmd.op) {
                DbOp.TABLES -> emitDbTables(db, cmd, mode, token)
                DbOp.SCHEMA -> emitDbSchema(db, cmd, mode, token)
                DbOp.QUERY -> emitDbQuery(db, cmd, mode, token)
            }
        } finally {
            // 只关自己的连接；退回 App 连接时不能关，否则把生产链路一起关掉
            own?.close()
        }
    }

    private fun emitDbTables(db: SQLiteDatabase, cmd: DebugCommand.Db, mode: String, token: String?) {
        val userVersion = db.rawQuery("PRAGMA user_version", null)
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        val names = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null,
        ).use { cursor -> while (cursor.moveToNext()) names.add(cursor.getString(0)) }

        val tables = JSONArray()
        names.forEach { name ->
            val rows = db.rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(name)}", null)
                .use { if (it.moveToFirst()) it.getLong(0) else -1L }
            tables.put(JSONObject().put("name", name).put("rows", rows))
        }
        emit(
            cmd = cmd.name,
            token = token,
            data = JSONObject()
                .put("op", DbOp.TABLES.value)
                .put("mode", mode)
                .put("userVersion", userVersion)
                .put("count", names.size)
                .put("tables", tables)
                .put("part", 1)
                .put("parts", 1),
        )
    }

    private fun emitDbSchema(db: SQLiteDatabase, cmd: DebugCommand.Db, mode: String, token: String?) {
        val table = cmd.table.orEmpty()
        val columns = JSONArray()
        db.rawQuery("PRAGMA table_info(${quoteIdentifier(table)})", null).use { cursor ->
            while (cursor.moveToNext()) {
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                columns.put(
                    JSONObject()
                        .put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                        .put("type", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                        .put("notNull", cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0)
                        .put("default", if (cursor.isNull(defaultIndex)) JSONObject.NULL else cursor.getString(defaultIndex))
                        .put("pk", cursor.getInt(cursor.getColumnIndexOrThrow("pk"))),
                )
            }
        }
        if (columns.length() == 0) {
            // PRAGMA 对不存在的表返回空结果集，不会报错：这里显式拦下，别让「空 schema」看起来像成功
            emitFailure(
                cmd = cmd.name,
                token = token,
                reason = "no_such_table",
                data = JSONObject().put("table", table).put("hint", "先跑 db tables 看实际表名"),
            )
            return
        }
        emit(
            cmd = cmd.name,
            token = token,
            data = JSONObject()
                .put("op", DbOp.SCHEMA.value)
                .put("mode", mode)
                .put("table", table)
                .put("count", columns.length())
                .put("columns", columns)
                .put("part", 1)
                .put("parts", 1),
        )
    }

    private fun emitDbQuery(db: SQLiteDatabase, cmd: DebugCommand.Db, mode: String, token: String?) {
        val sql = cmd.sql.orEmpty()
        val rows = mutableListOf<JSONObject>()
        val columns = JSONArray()
        var truncatedFields = 0
        db.rawQuery(sql, null).use { cursor ->
            cursor.columnNames.forEach { columns.put(it) }
            var taken = 0
            while (taken < cmd.limit && cursor.moveToNext()) {
                val row = JSONObject()
                cursor.columnNames.forEachIndexed { index, name ->
                    when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(index))
                        // BLOB 只报长度：调试通道不该把二进制塞进 logcat
                        Cursor.FIELD_TYPE_BLOB -> row.put(name, "<blob:" + cursor.getBlob(index).size + "B>")
                        else -> {
                            val text = cursor.getString(index) ?: ""
                            val cut = DbSql.truncate(text)
                            if (cut.length != text.length) truncatedFields++
                            row.put(name, cut)
                        }
                    }
                }
                rows.add(row)
                taken++
            }
        }

        // 与 dump 同一套分片：logcat 单行约 4KB，CLI 靠 part/parts 判断「收齐了没」
        val chunks = chunkRows(rows, columns, MAX_LINE_CHARS)
        chunks.forEachIndexed { index, chunk ->
            emit(
                cmd = cmd.name,
                token = token,
                data = JSONObject()
                    .put("op", DbOp.QUERY.value)
                    .put("mode", mode)
                    .put("sql", sql)
                    .put("limit", cmd.limit)
                    .put("count", rows.size)
                    .put("truncatedFields", truncatedFields)
                    .put("columns", columns)
                    .put("part", index + 1)
                    .put("parts", chunks.size)
                    .put("rows", chunk),
            )
        }
    }

    /** 按字符预算切片；单行超预算时自己独占一片（宁可多片，也不截断行）。 */
    private fun chunkRows(rows: List<JSONObject>, columns: JSONArray, maxChars: Int): List<JSONArray> {
        val headerChars = columns.toString().length
        val chunks = ArrayList<JSONArray>()
        var current = JSONArray()
        var size = headerChars
        for (row in rows) {
            val length = row.toString().length + 1
            if (current.length() > 0 && size + length > maxChars) {
                chunks.add(current)
                current = JSONArray()
                size = headerChars
            }
            current.put(row)
            size += length
        }
        if (current.length() > 0) chunks.add(current)
        if (chunks.isEmpty()) chunks.add(JSONArray())
        return chunks
    }

    /** 标识符加双引号：表名只来自 sqlite_master 或解析期校验过的普通标识符。 */
    private fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    // ──────────────────────────── heartbeat ────────────────────────────

    private fun emitHeartbeat(context: Context, token: String?) {
        // 转发一次真实心跳广播：AlarmReceiver 自己持 PendingResult，不能由这里直接调 onReceive
        // （非派发态调 goAsync() 会拿到 null，后续 finish() 会 NPE）
        val intent = Intent()
            .setComponent(ComponentName(context, AlarmReceiver::class.java))
        context.sendBroadcast(intent)
        emit(
            cmd = "heartbeat",
            token = token,
            data = JSONObject()
                .put("dispatched", true)
                .put("receiver", AlarmReceiver::class.java.name)
                .put("hint", "采样/上报结果异步出现：adb logcat -d -s AlarmReceiver LocationCollector"),
        )
    }

    // ──────────────────────────── 输出 ────────────────────────────

    private fun emit(cmd: String, token: String?, data: JSONObject) =
        write("${prefix(cmd, true, null, token)} data=$data")

    /** 成功行 + 若干 `k=v` 明细（只给 dump 用：path/nodes/pkg 要能直接从行里读到）。 */
    private fun emitWithDetail(cmd: String, token: String?, detail: Map<String, String>, data: JSONObject) =
        write(buildString {
            append(prefix(cmd, true, null, token))
            detail.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
            append(" data=").append(data)
        })

    private fun emitFailure(cmd: String, token: String?, reason: String, data: JSONObject) =
        write("${prefix(cmd, false, reason, token)} data=$data")

    private fun prefix(cmd: String, ok: Boolean, reason: String?, token: String?): String {
        val builder = StringBuilder("RESULT cmd=").append(cmd)
            .append(" ok=").append(ok)
        if (reason != null) builder.append(" reason=").append(reason)
        if (!token.isNullOrBlank()) builder.append(" tag=").append(token)
        return builder.toString()
    }

    private fun write(line: String) = Log.i(TAG, line)

    private fun extrasOf(intent: Intent): Map<String, Any?> {
        val bundle = intent.extras ?: return emptyMap()
        val map = HashMap<String, Any?>()
        bundle.keySet().forEach { key -> map[key] = bundle.get(key) }
        return map
    }

    private fun Record.toJson(): JSONObject {
        val obj = JSONObject()
            .put("id", id)
            .put("type", RecordType.toString(type))
            .put("action", action)
            .put("ts", timestamp)
        duration?.let { obj.put("duration", it) }
        obj.put("label", label)
        detail?.let { detail ->
            val json = JSONObject()
            detail.forEach { (key, value) -> json.put(key, value) }
            obj.put("detail", json)
        }
        return obj
    }

    private companion object {
        const val TAG = "DebugCmd"

        /** 单片 JSON 的字符上限：logcat 约 4KB/行，中文按 UTF-8 最坏 3 字节/字符，取 1200 留足余量。 */
        const val MAX_LINE_CHARS = 1200

        /** 监控库文件名（`DatabaseHelper.DATABASE_NAME` 是私有的，这里只读打开要用绝对路径）。 */
        const val MONITOR_DB = "monitor.db"

        /** ui 命令的端上超时：必须小于 CLI 的 12 秒轮询上限，保证超时理由先被打出来。 */
        const val UI_TIMEOUT_MS = 11_000L
    }
}
