package com.bsfeng.monitor.autobook

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 自动记账引擎。
 *
 * 数据流（与 `.open-design/23-autobook-styles.html` 原型一致）：
 * ```
 * 无障碍事件 → 包名命中规则 → 收集页面视觉行 → AutoBookParser 解析
 *            → 弹出确认浮窗（跟随 App 主题）→ 用户在浮窗里改类型/分类 → 点「记一笔」
 *            → 幂等落库（finance_transactions）
 * ```
 *
 * 设计约束：
 * - **只弹窗、不自动记账**：识别结果一律要用户确认，避免把识别错误直接写进账本；
 * - 只在 [AutoBookRules] 覆盖的包名（微信 / 支付宝）上运行；
 * - 同一包名事件 800ms 节流（支付结果页会连续抛多个 CONTENT_CHANGED）；
 * - **只在账单 / 交易页识别**（[AutoBookParser.isBillPage] 门禁，挡掉首页消息摘要）；
 * - 同一笔账单短时间只弹一次：内存去重表（[AutoBookDedup]）+ 落库查库双重兜底；
 * - 解析在无障碍主线程（节点不能跨线程），查库/落库切到 IO。
 */
class AutoBookEngine(private val service: AccessibilityService) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val financeManager: FinanceManager by lazy { FinanceManager.getInstance(service) }

    private val overlay = AutoBookOverlay(service)

    /** 上次处理事件的时间，用于节流 */
    private var lastRunAtMs = 0L

    /**
     * 短期去重表：**同一笔账单短时间只弹一次浮窗**。
     *
     * 改造前这里是两个单槽变量（`showingSourceId` + `dismissedSourceId`），真机实测有两处漏：
     * 1. 用户点完「记一笔」后没有留下任何记忆，页面继续抛事件就**再弹一次**；
     * 2. 只记得住最后一笔，忽略两笔交替时前一笔的安静期会被覆盖。
     * 现在改成按指纹记录三种结局（已记账 / 已忽略 / 刚弹过），各有安静期。
     */
    private val dedup = AutoBookDedup()

    /** 分类缓存：0=支出 / 1=收入，切换类型时不再查库 */
    @Volatile
    private var categoriesByType: Map<Int, List<Category>> = emptyMap()

    /** 最近一次识别结果（诊断用） */
    @Volatile
    var lastDraft: AutoBookDraft? = null
        private set

    /** 是否已弹过浮窗（诊断用） */
    @Volatile
    var shownCount: Int = 0
        private set

    /**
     * 无障碍事件入口，由 [com.bsfeng.monitor.service.MonitorAccessibilityService] 调用。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!AutoBookConfig.enabled) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val rule = AutoBookRules.forPackage(packageName) ?: return

        val now = System.currentTimeMillis()
        if (now - lastRunAtMs < THROTTLE_MS) return
        lastRunAtMs = now

        val root = resolveTargetRoot(packageName)
        if (root == null) {
            // 支付结果页刚切换时 root 常为 null，等下一次事件即可（节流会挡住高频重复）
            Log.d(TAG, "未取到 $packageName 的窗口，本次跳过")
            return
        }
        val lines = try {
            NodeTextCollector.collectLines(root)
        } catch (e: Exception) {
            Log.w(TAG, "收集页面文本失败: ${e.message}")
            emptyList()
        } finally {
            runCatching { root.recycle() }
        }
        if (lines.isEmpty()) {
            Log.d(TAG, "未收集到页面文本，跳过: $packageName")
            return
        }

        val draft = try {
            AutoBookParser.parse(rule, lines, now)
        } catch (e: Exception) {
            Log.w(TAG, "解析失败: ${e.message}")
            null
        }
        if (draft == null) {
            // 诊断刚需：能区分「没收到事件」「不是账单页（门禁挡掉）」「是账单页但字段没认出来」。
            // 打印实际文本（截断），否则无法判断是页面结构变了还是节点被系统裁剪。
            val preview = lines.joinToString(" | ").take(400)
            val gate = if (AutoBookParser.isBillPage(lines)) "门禁通过,字段不足" else "非账单页,门禁拒绝"
            Log.d(TAG, "未识别到账单: $packageName [$gate] 行数=${lines.size} 文本=[$preview]")
            return
        }

        lastDraft = draft

        // 浮窗还开着且就是这一笔：不要重建（会把用户正在填的类型/分类冲掉）
        val showing = overlay.currentDraft
        if (showing != null && showing.sourceId == draft.sourceId) return

        // 短期去重：同一笔（已记账 / 已忽略 / 刚弹过）在各自安静期内不再弹
        val blocked = dedup.blockedReason(draft.dedupKeys, now)
        if (blocked != null) {
            Log.d(TAG, "去重命中，本次不弹窗：$blocked")
            return
        }

        Log.i(
            TAG,
            "识别到账单 channel=${draft.channel} type=${draft.typeLabel} amount=${draft.amount} " +
                "merchant=${draft.merchant} category=${draft.categoryName} " +
                "account=${draft.accountName} markers=${draft.markers}"
        )

        showOverlay(draft)
    }

    /**
     * 取目标 App 的窗口根节点。
     *
     * ⚠️ **不能用 `rootInActiveWindow` 直接读**：它返回的是「活动窗口」，只要有第三方悬浮窗盖在
     * 支付页上（真机实测：熊猫记账的记账确认窗一直在支付宝页面上），读到的就是那个悬浮窗的文本，
     * 支付页一个字段都拿不到。正确做法是从 `windows` 里按**包名**挑应用窗口。
     *
     * 依赖无障碍配置里的 `flagRetrieveInteractiveWindows`（本项目已开启）。
     *
     * @return 目标包的根节点；调用方负责 recycle
     */
    private fun resolveTargetRoot(packageName: String): AccessibilityNodeInfo? {
        val windows = try {
            service.windows
        } catch (e: Exception) {
            Log.w(TAG, "读取窗口列表失败: ${e.message}")
            null
        }

        if (!windows.isNullOrEmpty()) {
            // 第一轮：优先应用类型窗口（排除输入法、系统面板、悬浮窗）
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                if (root.packageName?.toString() == packageName) return root
                runCatching { root.recycle() }
            }
            // 第二轮：放宽到任意类型窗口，只要包名匹配
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == packageName) return root
                runCatching { root.recycle() }
            }
        }

        // 兜底：活动窗口恰好就是目标 App（没有第三方悬浮窗时走这里）
        val active = service.rootInActiveWindow
        if (active != null && active.packageName?.toString() == packageName) return active
        runCatching { active?.recycle() }

        // 取不到时打印窗口清单：这样才能区分「页面没开」和「被别的悬浮窗顶掉了」
        Log.d(
            TAG,
            "未取到 $packageName 的窗口，当前窗口=[" +
                (windows ?: emptyList()).joinToString(", ") { w ->
                    val pkg = runCatching { w.root?.packageName?.toString() ?: "?" }.getOrElse { "err" }
                    "type=" + w.type + ":" + pkg
                } + "]"
        )
        return null
    }

    /**
     * 补分类数据、查库兜底去重，然后弹出浮窗。
     *
     * 查库这步是为了覆盖**进程重启**：内存去重表随服务重启清空，但「这一笔已经记过账」
     * 是数据库里的事实，据此可以继续挡住重复弹窗。
     */
    private fun showOverlay(draft: AutoBookDraft) {
        scope.launch {
            val loaded = categoriesByType.ifEmpty { loadCategories().also { categoriesByType = it } }
            if (isAlreadyBooked(draft)) {
                Log.d(TAG, "该笔已记账（查库命中），不再弹窗: ${draft.sourceId}")
                return@launch
            }
            // 回到主线程挂 View（WindowManager 操作要求在同一线程，无障碍服务的主线程即主 Looper）
            service.mainExecutor.execute { display(draft, loaded) }
        }
    }

    /** 这一笔是否已经落过库（持久化去重兜底） */
    private fun isAlreadyBooked(draft: AutoBookDraft): Boolean = try {
        financeManager.dao.transactionExists(SOURCE_PREFIX + draft.channel, draft.sourceId)
    } catch (e: Exception) {
        Log.w(TAG, "查库去重失败（按未记账处理，宁可多弹一次）: ${e.message}")
        false
    }

    private fun display(draft: AutoBookDraft, categories: Map<Int, List<Category>>) {
        try {
            overlay.show(
                draft = draft,
                categoriesByType = categories,
                onConfirm = { type, categoryName ->
                    // 先打标再落库：落库是异步的，这期间页面若再抛事件不能复弹
                    dedup.markConfirmed(draft.dedupKeys, System.currentTimeMillis())
                    Log.i(TAG, "用户确认记账: type=$type category=$categoryName amount=${draft.amount}")
                    scope.launch { persist(draft, type, categoryName) }
                },
                onDismiss = {
                    dedup.markDismissed(draft.dedupKeys, System.currentTimeMillis())
                    Log.i(TAG, "用户忽略: sourceId=${draft.sourceId}")
                }
            )
            dedup.markShown(draft.dedupKeys, System.currentTimeMillis())
            shownCount++
        } catch (e: Exception) {
            // 浮窗挂不上（例如系统限制）不能静默：记日志，下一笔还会再试
            Log.e(TAG, "浮窗展示失败: ${e.message}", e)
        }
    }

    private suspend fun loadCategories(): Map<Int, List<Category>> {
        return try {
            var all = financeManager.getAllCategories()
            if (all.isEmpty()) {
                financeManager.seedDefaultCategories()
                all = financeManager.getAllCategories()
            }
            mapOf(
                AutoBookDraft.TYPE_EXPENSE to all.filter { it.type == 0 },
                AutoBookDraft.TYPE_INCOME to all.filter { it.type == 1 }
            )
        } catch (e: Exception) {
            Log.e(TAG, "加载分类失败: ${e.message}", e)
            emptyMap()
        }
    }

    /**
     * 幂等落库：同 source + sourceId 已存在时直接跳过（同一笔被反复确认只记一次）。
     */
    private suspend fun persist(draft: AutoBookDraft, type: Int, categoryName: String) {
        try {
            val source = SOURCE_PREFIX + draft.channel
            if (financeManager.dao.transactionExists(source, draft.sourceId)) {
                Log.d(TAG, "该笔已记账，跳过: ${draft.sourceId}")
                return
            }

            val now = LocalDateTime.now()
            val date = now.toLocalDate().toString()
            val transaction = Transaction(
                type = type,
                categoryName = categoryName,
                amount = draft.amount,
                accountName = draft.accountName,
                remark = draft.remark(),
                date = date,
                dateTime = Transaction.buildDateTime(date, now),
                source = source,
                sourceId = draft.sourceId
            )

            val id = financeManager.addTransaction(transaction)
            Log.i(TAG, "自动记账已写入 id=$id 金额=${draft.amount} 分类=$categoryName 商户=${draft.merchant}")
        } catch (e: Exception) {
            Log.e(TAG, "自动记账落库失败: ${e.message}", e)
        }
    }

    /** 服务销毁时清理浮窗、去重表与协程 */
    fun release() {
        overlay.hide()
        dedup.clear()
        scope.cancel()
    }

    private companion object {
        const val TAG = "AutoBook"

        /** 同包名事件节流窗口：支付结果页会连续抛出多个内容变化事件 */
        const val THROTTLE_MS = 800L

        /** 落库来源前缀，最终 source 形如 auto_alipay / auto_wechat */
        const val SOURCE_PREFIX = "auto_"
    }
}
