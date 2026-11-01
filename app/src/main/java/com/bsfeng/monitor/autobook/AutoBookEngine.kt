package com.bsfeng.monitor.autobook

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.finance.model.CategoryCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

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
 * - 同一包名事件 300ms 节流（支付结果页会连续抛多个 CONTENT_CHANGED）；
 * - **只在账单 / 交易页识别**（[AutoBookParser.isBillPage] 门禁，挡掉首页消息摘要）；
 * - 同一笔账单短时间只弹一次：内存去重表（[AutoBookDedup]）+ 落库查库双重兜底；
 * - 解析在无障碍主线程（节点不能跨线程），查库/落库切到 IO。
 */
class AutoBookEngine(internal val service: AccessibilityService) {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val financeManager: FinanceManager by lazy { FinanceManager.getInstance(service) }

    internal val overlay = AutoBookOverlay(service)

    /**
     * 页面变化检测：**页面文本一变就处理**，不再按固定时间窗等待。
     *
     * 旧实现用 800ms/300ms/150ms 时间窗：页面渲染完成的那次事件总要等窗口到期，
     * 用户看到浮窗慢半拍。缩短窗口只是减小等待，没有治本（2026-10-20 用户明确要求）。
     * 现在判断依据是「页面内容有没有变」，渲染完成即解析。
     */
    private val pageChangeDetector = AutoBookPageChangeDetector()

    /** 正在弹窗/已弹窗的这笔：防同一笔的连续事件重复建浮窗（detail 分支也要挡） */
    @Volatile internal var pendingOverlaySourceId: String? = null

    /** 弹窗链路诊断时间戳（事件→读文本→解析），仅用于日志定位延迟 */
    @Volatile internal var traceEventAtMs = 0L
    @Volatile internal var traceLinesAtMs = 0L
    @Volatile internal var traceParsedAtMs = 0L

    /**
     * 短期去重表：**同一笔账单短时间只弹一次浮窗**。
     *
     * 改造前这里是两个单槽变量（`showingSourceId` + `dismissedSourceId`），真机实测有两处漏：
     * 1. 用户点完「记一笔」后没有留下任何记忆，页面继续抛事件就**再弹一次**；
     * 2. 只记得住最后一笔，忽略两笔交替时前一笔的安静期会被覆盖。
     * 现在改成按指纹记录三种结局（已记账 / 已忽略 / 刚弹过），各有安静期。
     */
    internal val dedup = AutoBookDedup()

    /**
     * 用户点过「忽略」的这笔：**落盘记住、之后不再弹**（见 [AutoBookIgnoreStore]）。
     *
     * 真机反馈（2026-10-17）：点了忽略之后页面继续抛事件，浮窗又弹一次。内存去重只有 10 分钟，
     * 这里按落库键持久化，退出微信 / 服务重启后同一笔也不会再弹。
     */
    internal val ignoreStore: AutoBookIgnoreStore by lazy {
        // 旧版把遮罩点击也写成忽略：升级后首次初始化时清空一次，之后只认「忽略」按钮的记录
        AutoBookIgnoreStore.getInstance(service).also { it.migrateLegacyOnce() }
    }

    /**
     * 付款前页面预采集：收款方 / 付款方式 / 用户备注等结果页拿不到的信息。
     *
     * 付款前页面**不弹窗**（用户还没付完），只缓存；等支付成功页到了由
     * [AutoBookDraftMerger] 合并。有效期见 [AutoBookDraftMerger.PRE_DRAFT_TTL_MS]。
     */
    internal var preDraft: PrePaymentDraft? = null

    /** 微信「添加备注」弹窗里用户填的备注（弹窗没有金额，单独捕获后并进预采集）。 */
    internal var pendingRemark: String? = null

    /**
     * 刚刚走过「支付宝红包付款前页面」。
     *
     * 支付宝红包付款成功后没有结果页，聊天页里本来就有历史红包气泡；只有记住
     * 「这几分钟里用户确实在发红包」，才敢把刚出现的气泡当成这笔新交易（金额取预采集）。
     */
    internal var redPacketPaymentPending: Boolean = false

    /**
     * 红包付款流程已经走到指纹 / 密码验证页（离付款成功只差一步）。
     *
     * 聊天页气泡只在「已验过指纹」后才认，避免用户在发红包页取消后打开旧聊天、
     * 被历史红包气泡误触发一笔不存在的记账。
     */
    internal var redPacketFingerprintArmed: Boolean = false

    /** 交易详情页补全同一时间只跑一个（详情页会连续抛多个事件） */
    internal val detailEnriching = AtomicBoolean(false)

    /** 本次浮窗是否已经提示过「点遮罩不会关闭」（每笔只一次，避免连点刷屏） */
    internal var scrimHintShown = false

    /**
     * 分类树缓存：一级/二级父子关系，浮窗切类型时不再查库。
     *
     * 分类种子由系统预置、运行期不会变（导入只会按名称去重），因此进程内缓存一次即够。
     * 为 null 表示还没加载成功，下次弹窗会重试（服务连上时 [warmUp] 已经预加载）。
     */
    @Volatile
    internal var categoryTree: CategoryTree? = null

    /** 最近一次识别结果（诊断用） */
    @Volatile
    var lastDraft: AutoBookDraft? = null
        private set

    /** 是否已弹过浮窗（诊断用） */
    internal var shownCount = 0

    /**
     * 服务连接后预热：提前加载分类树，避免第一笔账单弹窗时才发现分类缓存是空的。
     *
     * 真机反馈「识别到后浮窗还要等一会儿」：分类查询是弹窗链路里唯一可能首次建库/慢查询的一步，
     * 放在服务连接时做，用户在支付页停留的几秒足够完成。
     */
    fun warmUp() {
        scope.launch {
            if (categoryTree == null) categoryTree = loadCategoryTree()
        }
    }

    /** 无障碍事件入口，由 [com.bsfeng.monitor.service.MonitorAccessibilityService] 调用。 */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!AutoBookConfig.enabled) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val rule = AutoBookRules.forPackage(packageName) ?: return

        val now = System.currentTimeMillis()
        val eventAt = now
        val isWindowStateChange = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        val lines = collectPageLines(packageName)
        val linesAt = System.currentTimeMillis()
        if (lines.isEmpty()) {
            // 支付结果页刚切换时拿不到内容，等下一次事件即可。
            // 顺带看一眼「节点是不是被标记成不可见了」——微信就是这么藏支付页的，
            // 两种成因的应对完全不同，别白排查（见 docs/记账/微信支付页识别调研.md）。
            logInvisibleNodesIfAny(packageName)
            Log.d(TAG, "未收集到页面文本，跳过: $packageName")
            return
        }

        // 页面文本指纹：渲染完成/内容更新 → 指纹变化 → 立即解析；同一页面重复事件直接跳过。
        // 不再按时间窗等「页面稳定」，这是浮窗延迟的治本点。
        val fingerprint = lines.joinToString("\u0000").hashCode().toString()
        if (!pageChangeDetector.shouldProcess(isWindowStateChange, fingerprint, now)) return

        var draft = runCatching { AutoBookParser.parse(rule, lines, now) }
            .onFailure { Log.w(TAG, "解析失败: ${it.message}") }
            .getOrNull()
        if (draft == null) draft = fallbackDraft(rule, lines, now)

        if (draft == null) return collectPrePayment(rule, lines, now, packageName)

        // 支付成功页 + 付款前页面预采集：成功页空白的字段用预采集补齐（收款方 / 付款方式 / 备注）。
        // 预采集不在这里清空：成功页可能重复抛事件，清早了后续解析就拿不到备注；
        // 等浮窗真正展示（[display]）后再清，失败路径还能复用。
        val merged = AutoBookDraftMerger.merge(draft, preDraft, now)

        // 字段取错时的唯一诊断依据，**必须放在去重判定之前** ——
        // 已记账 / 已忽略的笔会提前 return，放在后面就看不到行文本了。
        // （真机踩过：商户名取成「账单详情」「返回 全部账单」，不看原始行根本定位不到）
        Log.d(TAG, "识别到账单页面行: ${lines.joinToString(" | ").take(600)}")

        lastDraft = merged
        traceEventAtMs = eventAt
        traceLinesAtMs = linesAt
        traceParsedAtMs = System.currentTimeMillis()

        // 浮窗还开着、或正在弹的，就是这一笔：不要重建（会把用户正在填的类型/分类冲掉）。
        // ⚠️ 必须放在详情页分支之前：详情页会连续抛多个事件（商品说明后加载），
        // 旧顺序会走 handleDetailPage → showOverlay → 重复建浮窗，第二次要排队等主线程
        // 绘制第一个浮窗（真机实测排队 679ms，用户感知就是「浮窗慢 1s」）。
        val showing = overlay.currentDraft
        if (showing != null && showing.sourceId == merged.sourceId) return
        if (pendingOverlaySourceId == merged.sourceId) return

        // 交易详情页：如果这笔已经自动记过账，不再弹记账浮窗，而是把详情页字段补进已有记录 + Toast。
        // 未匹配到已记账记录时保持原行为（正常弹窗）。
        if (AutoBookParser.isDetailPage(lines)) {
            handleDetailPage(merged)
            return
        }

        // 短期去重：同一笔（已记账 / 已忽略 / 刚弹过）在各自安静期内不再弹
        val blocked = dedup.blockedReason(merged.dedupKeys, now)
        if (blocked != null) {
            Log.d(TAG, "去重命中，本次不弹窗：$blocked")
            return
        }

        // 用户点过「忽略」：持久化记忆，跨进程 / 跨天都不再弹这一笔
        if (ignoreStore.isIgnored(merged.sourceId)) {
            Log.d(TAG, "该笔已被用户忽略，不再弹窗: ${merged.sourceId}")
            return
        }

        Log.i(
            TAG,
            "识别到账单 channel=${merged.channel} type=${merged.typeLabel} amount=${merged.amount} " +
                "merchant=${merged.merchant} category=${merged.categoryName} " +
                "account=${merged.accountName} remark=${merged.userRemark} markers=${merged.markers}"
        )

        // 检测到支付成功页就立即展示（2026-10-17 用户要求去掉 1s 延迟）：
        // 付款前页面已经由 PRE_PAY_WORDS 挡在门外，不会再出现「没付完就弹」。
        showOverlay(merged)
    }

    /** 这一笔是否已经落过库（持久化去重兜底） */
    internal fun isAlreadyBooked(draft: AutoBookDraft): Boolean = try {
        financeManager.dao.transactionExists(SOURCE_PREFIX + draft.channel, draft.sourceId)
    } catch (e: Exception) {
        Log.w(TAG, "查库去重失败（按未记账处理，宁可多弹一次）: ${e.message}")
        false
    }

    /**
     * 调试入口：不经过识别链路，直接用 [AutoBookDebugDraft] 弹浮窗。
     *
     * 模拟器里没有支付 App，唯一能验证浮窗（关闭口径 / 两级分类 / 说明输入）的办法。
     * 与真实链路共用 [display]，所以分类推荐、落库口径完全一致；只在 debug 构建的设置页调试区调用。
     */
    fun showDebugSheet() {
        scope.launch {
            val tree = categoryTree ?: loadCategoryTree().also { categoryTree = it }
            service.mainExecutor.execute { display(AutoBookDebugDraft.create(), tree) }
        }
    }

    /** 加载分类树；失败返回空树（浮窗照常弹，落库时分类回退「其他」） */
    internal suspend fun loadCategoryTree(): CategoryTree {
        return try {
            var all = financeManager.getAllCategories()
            if (all.isEmpty()) {
                financeManager.seedDefaultCategories()
                all = financeManager.getAllCategories()
            }
            CategoryTree(all)
        } catch (e: Exception) {
            Log.e(TAG, "加载分类树失败: ${e.message}", e)
            CategoryTree(emptyList())
        }
    }

    /**
     * 服务销毁时清理浮窗、预采集、去重表与协程。
     * 这是**唯一**允许被动卸载浮窗的路径（[AutoBookCloseSource.SERVICE_RELEASE]）：
     * 用户操作只能通过浮窗里的「记一笔」/「忽略」按钮，切 App / 点遮罩都不卸载。
     */
    fun release() {
        preDraft = null
        pendingRemark = null
        redPacketPaymentPending = false
        redPacketFingerprintArmed = false
        overlay.hide()
        dedup.clear()
        scope.cancel()
    }

    internal companion object {
        const val TAG = "AutoBook"

        /** 落库来源前缀，最终 source 形如 auto_alipay / auto_wechat */
        const val SOURCE_PREFIX = "auto_"

        /** 收入分类的兜底一级（支出侧的兜底是 [CategoryCatalog.FALLBACK]「其他」） */
        const val INCOME_FALLBACK = "其他收入"
    }
}
