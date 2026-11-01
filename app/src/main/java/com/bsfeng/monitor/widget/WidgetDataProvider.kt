package com.bsfeng.monitor.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bsfeng.monitor.core.di.serviceContainer
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.domain.quota.ProviderAggregateInput
import com.bsfeng.monitor.domain.quota.QuotaAggregator
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.quota.QuotaSnapshotStore
import com.bsfeng.monitor.record.RecordStore
import com.bsfeng.monitor.service.HeartbeatWatchdog
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.transfer.service.TransferEngineHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// 小组件的数据读取：唯一入口，全部在 IO 线程且逐项兜底。

/**
 * 6 个组件的数据来源。
 *
 * 三条硬约束（widget 与界面不同，崩一次就是整块白屏 / 刷新卡死）：
 * 1. 所有方法 `suspend` + [Dispatchers.IO]：SQLite / SharedPreferences / Keystore 解密都是阻塞调用，
 *    放到 Glance 的 composition 里会卡住整块桌面组件；
 * 2. 每个方法整段 [runCatching]：读库失败、库损坏、provider 注册表没装配，都退化成安全默认值
 *    （T2/T3 显示「0 / 未运行 / 未配置」的空态），绝不把异常抛进刷新链路；
 * 3. 组件内**不发起网络请求**：额度只读 [QuotaSnapshotStore] 已落盘的快照，
 *    拉取仍由 `QuotaScheduler` 在 App 内完成（调研结论见 docs/小组件/调研.md）。
 */
object WidgetDataProvider {

    private const val TAG = "WidgetDataProvider"
    private const val DAY_MS = 86_400_000L

    /** 广告跳过事件的落库口径：`type=SCREEN, action=AD_SKIPPED`（见 RecordCollector）。 */
    private const val AD_SKIPPED_ACTION = "AD_SKIPPED"

    /**
     * 「已复核确认」在 detail 里的字面形态：`{"...":"...","confirmed":"true"}` 里的那一段。
     *
     * 为什么是 LIKE 精确片段：detail 列是 `JSONObject(Map<String, String>).toString()` 的结果，
     * 值以**字符串**存（`RecordCollector.onSkipAd` 里 `confirmed.toString()`），org.json 默认不带空格。
     * 一旦改成存裸布尔（`"confirmed":true`）或用带缩进的序列化（`toString(2)` 会插空格），
     * LIKE 立刻失配、确认率静默变成 0% —— 改 detail 结构时必须同步改这里。
     */
    private const val CONFIRMED_TRUE_PATTERN = "%\"confirmed\":\"true\"%"

    /**
     * 记账月度概览。
     *
     * 预算口径：优先取**总预算行**（`categoryName` 为空）；没有总预算行时退到「所有分项预算之和」。
     * 记账页写入时会把总预算维护成「分类预算合计」，但用户完全可能只设了分类预算 ——
     * 这时组件若只认总预算行就会一直显示「未设预算」，与首页预算条不一致。
     *
     * 进度与剩余都用**当月实际支出**现算（不读 Budget.spentAmount）：那个字段是导入时的历史快照，
     * 直接展示会让进度条停在旧值上（与 FinanceManager.getMonthlyBudget 的 KDoc 同一结论）。
     */
    suspend fun finance(context: Context): FinanceWidgetData = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val yearMonth = String.format(Locale.US, "%04d-%02d", today.year, today.monthValue)
        val monthLabel = today.monthValue.toString() + " 月"
        runCatching {
            val manager = FinanceManager.getInstance(context)
            val stats = manager.getMonthlyStats(yearMonth)
            val budgets = manager.getMonthlyBudget(yearMonth)
            val totalBudget = budgets.firstOrNull { it.isOverall && it.budgetAmount > 0.0 }?.budgetAmount
                ?: budgets.filterNot { it.isOverall }.sumOf { it.budgetAmount }
            val hasBudget = totalBudget > 0.0
            FinanceWidgetData(
                monthLabel = monthLabel,
                expense = stats.totalExpense,
                income = stats.totalIncome,
                budgetProgress = if (hasBudget) {
                    (stats.totalExpense / totalBudget).toFloat().coerceIn(0f, 1f)
                } else {
                    null
                },
                budgetRemaining = if (hasBudget) (totalBudget - stats.totalExpense).coerceAtLeast(0.0) else null,
                hasBudget = hasBudget,
            )
        }.getOrElse { error ->
            Log.w(TAG, "读取记账月度统计失败，组件回落为零值", error)
            FinanceWidgetData(monthLabel, 0.0, 0.0, null, null, hasBudget = false)
        }
    }

    /**
     * 监控健康。
     *
     * 为什么要叠一层心跳：`isRunning` 是静态标志，进程被冻结时它仍是 true（见 [HeartbeatWatchdog] 的 KDoc），
     * 只看它会让「服务其实没在干活」显示成绿点。心跳停更才说明进程没被调度。
     */
    suspend fun health(context: Context): HealthWidgetData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val todayStart = startOfToday()
        runCatching {
            val store = recordStore(context)
            val serviceRunning = MonitorAccessibilityService.isRunning &&
                !HeartbeatWatchdog.isStale(
                    HeartbeatWatchdog.lastBeatMs(context),
                    SystemClock.elapsedRealtime(),
                )
            HealthWidgetData(
                serviceRunning = serviceRunning,
                todayEvents = store.countByDate(todayStart, now),
                todaySkips = store.countByTypeAndAction(RecordType.SCREEN, AD_SKIPPED_ACTION, todayStart, now),
            )
        }.getOrElse { error ->
            Log.w(TAG, "读取监控健康数据失败", error)
            HealthWidgetData(MonitorAccessibilityService.isRunning, 0, 0)
        }
    }

    /**
     * 额度见底榜单。
     *
     * 数据来自 [QuotaSnapshotStore] 的落盘快照（**不联网**）：没有快照说明这次进程还没拉过额度，
     * 组件显示「未配置 / 尚未更新」，等 `QuotaScheduler` 在 App 内刷新完成后再推一次。
     */
    suspend fun quota(context: Context): QuotaWidgetData = withContext(Dispatchers.IO) {
        runCatching {
            val container = context.serviceContainer
            val snapshots = QuotaSnapshotStore(context)
            val inputs = container.quotaRegistry.all().map { provider ->
                val snapshot = runCatching { snapshots.load(provider.id) }.getOrNull()
                ProviderAggregateInput(
                    providerId = provider.id,
                    displayName = provider.displayName,
                    items = snapshot?.items.orEmpty(),
                    // 凭据是判断「用户到底配了没有」的唯一依据：残留的旧快照不代表还在用
                    balanceConfigured = runCatching {
                        container.quotaCredentials.has(provider.id, provider.balanceCredentialKind)
                    }.getOrDefault(false),
                    stale = snapshot?.stale ?: false,
                    fetchedAtEpochSecond = snapshot?.fetchedAtEpochSecond ?: 0L,
                )
            }
            val latestFetchedAt = inputs.maxOfOrNull { it.fetchedAtEpochSecond } ?: 0L
            QuotaWidgetData(
                configured = inputs.any { it.balanceConfigured },
                rows = QuotaAggregator.aggregate(inputs).map { row ->
                    QuotaRow(
                        providerId = row.providerId,
                        label = row.displayName,
                        remainingPercent = percent(row.primaryFraction),
                    )
                },
                fetchedAtText = if (latestFetchedAt > 0L) {
                    "更新于 " + ageText(System.currentTimeMillis(), latestFetchedAt * 1000L)
                } else {
                    null
                },
            )
        }.getOrElse { error ->
            Log.w(TAG, "读取额度快照失败，组件显示未配置", error)
            QuotaWidgetData(configured = false, rows = emptyList(), fetchedAtText = null)
        }
    }

    /**
     * 广告跳过战报。
     *
     * 全部走 SQL 的 COUNT（见 [RecordStore.countByTypeAndAction]），不复用逐行 JSON 解析的 `query()`：
     * 累计值没有时间下界，逐行反序列化会随表增长把刷新拖慢到秒级。
     *
     * 确认率口径（匹配规则见 [CONFIRMED_TRUE_PATTERN]）：分子 [AdWidgetData.confirmedCount] =
     * 累计里 detail 含 `"confirmed":"true"` 的记录，分母 = 累计全部 [AdWidgetData.totalCount]（含未复核）。
     * 算的是「所有跳过里确认掉了多少」而不是「复核过的成功率」：未复核（`confirmed = null`）
     * 不计入分子，所以数值偏低 —— 方向是对的，规则被广告 App 骗到（点了但广告还在，
     * `confirmed = false`）时不会虚高。
     */
    suspend fun ads(context: Context): AdWidgetData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val todayStart = startOfToday()
        runCatching {
            val store = recordStore(context)
            AdWidgetData(
                todayCount = store.countByTypeAndAction(RecordType.SCREEN, AD_SKIPPED_ACTION, todayStart, now),
                weekCount = store.countByTypeAndAction(
                    RecordType.SCREEN, AD_SKIPPED_ACTION, todayStart - 6 * DAY_MS, now,
                ),
                totalCount = store.countByTypeAndAction(RecordType.SCREEN, AD_SKIPPED_ACTION, 0L, now),
                confirmedCount = countConfirmedSkips(context, now),
            )
        }.getOrElse { error ->
            Log.w(TAG, "读取广告跳过计数失败", error)
            AdWidgetData(todayCount = 0, weekCount = 0, totalCount = 0, confirmedCount = 0)
        }
    }

    /**
     * 累计里「已复核确认」的跳过条数。
     *
     * 为什么绕开 [RecordStore]：它只暴露 type/action 的计数入口，这条统计要下推到 detail 的
     * JSON 片段上；放在这里是不想改动 RecordStore 的公开口径（App 内 UI 也共用它）。
     * 代价是错误兜底得由调用方负责 —— 所以在 [ads] 的 [runCatching] 里被调用。
     *
     * 性能：`detail LIKE` 走不了索引（命中 timestamp 区间后逐行比对 detail），但只 COUNT、
     * 不把行读回内存，量级与一次区间 `COUNT(*)` 同级；后续若真卡顿，再把 confirmed 提成独立列
     * （需要一次数据库版本升级 + 历史回填）。
     */
    private fun countConfirmedSkips(context: Context, nowMs: Long): Int =
        DatabaseHelper.getInstance(context).readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM records WHERE type = ? AND action = ?" +
                " AND timestamp >= ? AND timestamp <= ? AND detail LIKE ?",
            arrayOf(
                RecordType.Companion.toString(RecordType.SCREEN),
                AD_SKIPPED_ACTION,
                "0",
                nowMs.toString(),
                CONFIRMED_TRUE_PATTERN,
            ),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /**
     * 传送状态。
     *
     * 纯内存态（进程回收即丢）：拿不到引擎实例时按「未运行」处理，组件不该为了显示状态
     * 去启动一条 HTTP/UDP 链路。
     */
    suspend fun transfer(context: Context): TransferWidgetData = withContext(Dispatchers.IO) {
        runCatching {
            val engine = TransferEngineHolder.get(context)
            val running = engine.running.value
            TransferWidgetData(
                running = running,
                onlineDevices = engine.devices.value.size,
                receiving = running && engine.discoverable.value,
            )
        }.getOrElse { error ->
            Log.w(TAG, "读取传送状态失败（按未运行处理）", error)
            TransferWidgetData(running = false, onlineDevices = 0, receiving = false)
        }
    }

    /** 每次新建很便宜（只持有 SQLiteOpenHelper），但坚持用同一个 [DatabaseHelper] 单例的库句柄。 */
    private fun recordStore(context: Context): RecordStore =
        RecordStore(DatabaseHelper.getInstance(context))

    /** 本地时区的今天 00:00；日界必须按系统时区取，不能用 UTC 偏移。 */
    private fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
