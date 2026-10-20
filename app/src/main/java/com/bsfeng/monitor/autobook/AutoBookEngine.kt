package com.bsfeng.monitor.autobook

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.FinanceInsertSignal
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.finance.model.Transaction
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
class AutoBookEngine(private val service: AccessibilityService) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val financeManager: FinanceManager by lazy { FinanceManager.getInstance(service) }

    private val overlay = AutoBookOverlay(service)

    /**
     * 页面变化检测：**页面文本一变就处理**，不再按固定时间窗等待。
     *
     * 旧实现用 800ms/300ms/150ms 时间窗：页面渲染完成的那次事件总要等窗口到期，
     * 用户看到浮窗慢半拍。缩短窗口只是减小等待，没有治本（2026-10-20 用户明确要求）。
     * 现在判断依据是「页面内容有没有变」，渲染完成即解析。
     */
    private val pageChangeDetector = AutoBookPageChangeDetector()

    /** 正在弹窗/已弹窗的这笔：防同一笔的连续事件重复建浮窗（detail 分支也要挡） */
    @Volatile private var pendingOverlaySourceId: String? = null

    /** 弹窗链路诊断时间戳（事件→读文本→解析），仅用于日志定位延迟 */
    @Volatile private var traceEventAtMs = 0L
    @Volatile private var traceLinesAtMs = 0L
    @Volatile private var traceParsedAtMs = 0L

    /**
     * 短期去重表：**同一笔账单短时间只弹一次浮窗**。
     *
     * 改造前这里是两个单槽变量（`showingSourceId` + `dismissedSourceId`），真机实测有两处漏：
     * 1. 用户点完「记一笔」后没有留下任何记忆，页面继续抛事件就**再弹一次**；
     * 2. 只记得住最后一笔，忽略两笔交替时前一笔的安静期会被覆盖。
     * 现在改成按指纹记录三种结局（已记账 / 已忽略 / 刚弹过），各有安静期。
     */
    private val dedup = AutoBookDedup()

    /**
     * 用户点过「忽略」的这笔：**落盘记住、之后不再弹**（见 [AutoBookIgnoreStore]）。
     *
     * 真机反馈（2026-10-17）：点了忽略之后页面继续抛事件，浮窗又弹一次。内存去重只有 10 分钟，
     * 这里按落库键持久化，退出微信 / 服务重启后同一笔也不会再弹。
     */
    private val ignoreStore: AutoBookIgnoreStore by lazy {
        // 旧版把遮罩点击也写成忽略：升级后首次初始化时清空一次，之后只认「忽略」按钮的记录
        AutoBookIgnoreStore.getInstance(service).also { it.migrateLegacyOnce() }
    }

    /**
     * 付款前页面预采集：收款方 / 付款方式 / 用户备注等结果页拿不到的信息。
     *
     * 付款前页面**不弹窗**（用户还没付完），只缓存；等支付成功页到了由
     * [AutoBookDraftMerger] 合并。有效期见 [AutoBookDraftMerger.PRE_DRAFT_TTL_MS]。
     */
    private var preDraft: PrePaymentDraft? = null

    /** 微信「添加备注」弹窗里用户填的备注（弹窗没有金额，单独捕获后并进预采集）。 */
    private var pendingRemark: String? = null

    /**
     * 刚刚走过「支付宝红包付款前页面」。
     *
     * 支付宝红包付款成功后没有结果页，聊天页里本来就有历史红包气泡；只有记住
     * 「这几分钟里用户确实在发红包」，才敢把刚出现的气泡当成这笔新交易（金额取预采集）。
     */
    private var redPacketPaymentPending: Boolean = false

    /**
     * 红包付款流程已经走到指纹 / 密码验证页（离付款成功只差一步）。
     *
     * 聊天页气泡只在「已验过指纹」后才认，避免用户在发红包页取消后打开旧聊天、
     * 被历史红包气泡误触发一笔不存在的记账。
     */
    private var redPacketFingerprintArmed: Boolean = false

    /** 交易详情页补全同一时间只跑一个（详情页会连续抛多个事件） */
    private val detailEnriching = AtomicBoolean(false)

    /** 本次浮窗是否已经提示过「点遮罩不会关闭」（每笔只一次，避免连点刷屏） */
    private var scrimHintShown = false

    /**
     * 分类树缓存：一级/二级父子关系，浮窗切类型时不再查库。
     *
     * 分类种子由系统预置、运行期不会变（导入只会按名称去重），因此进程内缓存一次即够。
     * 为 null 表示还没加载成功，下次弹窗会重试（服务连上时 [warmUp] 已经预加载）。
     */
    @Volatile
    private var categoryTree: CategoryTree? = null

    /** 最近一次识别结果（诊断用） */
    @Volatile
    var lastDraft: AutoBookDraft? = null
        private set

    /** 是否已弹过浮窗（诊断用） */
    @Volatile
    var shownCount: Int = 0
        private set

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
        if (!pageChangeDetector.shouldProcess(isWindowStateChange, fingerprint, now)) {
            return
        }

        var draft = try {
            AutoBookParser.parse(rule, lines, now)
        } catch (e: Exception) {
            Log.w(TAG, "解析失败: ${e.message}")
            null
        }
        if (draft == null) {
            // 记住「刚走过红包付款前页面」：聊天页兜底只用它的金额，并据此区分新旧红包气泡
            if (AutoBookParser.isRedPacketPrePayPage(lines)) {
                redPacketPaymentPending = true
                Log.d(TAG, "红包付款前页面：等待发出后聊天页的红包气泡")
            }
            if (redPacketPaymentPending && AutoBookParser.isRedPacketFingerprintPage(lines)) {
                redPacketFingerprintArmed = true
                Log.d(TAG, "红包付款已验证指纹/密码：允许从聊天页气泡补记")
            }
            // 微信「添加备注」弹窗：没有金额，不会进预采集；但用户填的备注只在这里可见。
            val dialogRemark = try {
                AutoBookParser.parseRemarkDialog(lines)
            } catch (e: Exception) {
                null
            }
            if (dialogRemark != null) {
                pendingRemark = dialogRemark
                preDraft = preDraft?.copy(remark = dialogRemark)
                Log.d(TAG, "捕获备注弹窗: $dialogRemark")
                return
            }

            // 支付宝红包兜底：付款成功后没有结果页，而是回到聊天页（气泡不带金额）。
            // 必须排在付款前页面预采集之前 —— 聊天页底部有「发红包」快捷入口，
            // 但它不含金额/付款页特征，不会进预采集；这里只用付款前缓存的金额。
            draft = try {
                AutoBookParser.parseSentRedPacketChat(
                    rule, lines, preDraft, now,
                    redPacketFlowPending = redPacketPaymentPending,
                    allowBubbleOnly = redPacketFingerprintArmed
                )
            } catch (e: Exception) {
                Log.w(TAG, "红包聊天页解析失败: ${e.message}")
                null
            }
            if (draft != null) {
                Log.d(
                    TAG,
                    "聊天页识别到已发出的红包: amount=${draft.amount} 领取人=${draft.merchant}"
                )
            }
        }
        if (draft == null) {
            // 付款前页面（转账输入 / 收银台 / 指纹 / 确认付款）：**不弹窗**，只预采集。
            // 收款方、付款方式、用户备注这些信息只在付款前页面出现；成功页通常只给金额。
            val pre = try {
                AutoBookParser.parsePrePayment(rule, lines, now)
            } catch (e: Exception) {
                Log.w(TAG, "付款前页面预采集失败: ${e.message}")
                null
            }
            if (pre != null) {
                // 备注弹窗先于本页出现时，把备注补进来
                val savedRemark = pendingRemark
                val withRemark = if (pre.remark.isBlank() && !savedRemark.isNullOrBlank()) {
                    pre.copy(remark = savedRemark)
                } else pre
                // 付款前是分步的（输入页 → 收银台 → 指纹）：按字段累积，后一页不能把备注冲掉
                preDraft = preDraft?.let { AutoBookDraftMerger.accumulate(it, withRemark) } ?: withRemark
                Log.d(
                    TAG,
                    "付款前页面预采集: amount=${withRemark.amount} merchant=${withRemark.merchant} " +
                        "payMethod=${withRemark.payMethod} remark=${withRemark.remark}"
                )
                return
            }

            // 诊断刚需：能区分「没收到事件」「不是账单页（门禁挡掉）」「是账单页但字段没认出来」。
            // 打印实际文本（截断），否则无法判断是页面结构变了还是节点被系统裁剪。
            val preview = lines.joinToString(" | ").take(400)
            val gate = if (AutoBookParser.isBillPage(lines)) "门禁通过,字段不足" else "非账单页,门禁拒绝"
            Log.d(TAG, "未识别到账单: $packageName [$gate] 行数=${lines.size} 文本=[$preview]")
            return
        }

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

    /**
     * 取目标 App 的页面文本。
     *
     * ⚠️ **不能用 `rootInActiveWindow` 直接读**：它返回的是「活动窗口」，只要有第三方悬浮窗盖在
     * 支付页上（真机实测：熊猫记账的记账确认窗一直在支付宝页面上），读到的就是那个悬浮窗的文本，
     * 支付页一个字段都拿不到。
     *
     * ⚠️ **也不能只取「第一个包名匹配的窗口」**：微信会同时挂好几个窗口（真机实测
     * `LauncherUI` / `MallIndexUIv2` / `UIPageFragmentActivity` / `MMWebViewUI`），
     * 栈底那个 `LauncherUI` 往往是个读不出内容的空壳 —— 于是出现「明明停在支付成功页/转账详情页，
     * 却一直提示未收集到页面文本」。这里逐个候选窗口试读，**取内容最丰富的那个**。
     *
     * 依赖无障碍配置里的 `flagRetrieveInteractiveWindows`（本项目已开启）。
     */
    private fun collectPageLines(packageName: String): List<String> {
        val windows = try {
            service.windows
        } catch (e: Exception) {
            Log.w(TAG, "读取窗口列表失败: ${e.message}")
            null
        }

        if (windows.isNullOrEmpty()) {
            // 兜底：活动窗口恰好就是目标 App（没有第三方悬浮窗时走这里）
            val active = try {
                service.rootInActiveWindow
            } catch (e: Exception) {
                null
            } ?: return emptyList()
            return try {
                if (active.packageName?.toString() == packageName) {
                    NodeTextCollector.collectLines(active)
                } else {
                    emptyList()
                }
            } finally {
                runCatching { active.recycle() }
            }
        }

        // 活动窗口优先试，其余保持系统给的顺序
        val ordered = windows.sortedByDescending { window ->
            (if (window.isActive) 2 else 0) + (if (window.isFocused) 1 else 0)
        }

        var best: List<String> = emptyList()
        val tried = mutableListOf<String>()
        for (window in ordered) {
            val root = try {
                window.root
            } catch (e: Exception) {
                null
            } ?: continue
            val lines = try {
                if (root.packageName?.toString() == packageName) {
                    NodeTextCollector.collectLines(root)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "收集页面文本失败: ${e.message}")
                emptyList()
            } finally {
                runCatching { root.recycle() }
            }
            if (lines.isNotEmpty()) tried += "type=${window.type}:${lines.size}行"
            if (lines.size > best.size) best = lines
        }

        if (best.isEmpty()) {
            Log.d(TAG, "未取到 $packageName 的有内容窗口，候选=[${tried.joinToString(", ")}]")
            // 光凭「候选=[]」分不清「窗口不在清单里」和「窗口在但树是空的」，这两者应对完全不同
            logWindowDiagnostics(packageName, windows)
        }
        return best
    }

    /**
     * 交易详情页：匹配已记账的那笔并补全字段。
     *
     * 用户需求（2026-10-17）：详情页信息更全（订单号 / 精确时间 / 商品说明 / 商户全称），
     * 已经记过账的那笔不要再弹记账浮窗，而是补全已有记录，并弹 Toast「详情信息已补全」。
     *
     * 匹配不到已记账记录时，说明这笔是第一次见 —— 走原来的弹窗流程（含去重）。
     * 详情页会连续抛多个事件，用 [detailEnriching] 保证同一时间只跑一个；补全后重复事件
     * 不会再有字段变化，因此不会重复 Toast。
     */
    private fun handleDetailPage(draft: AutoBookDraft) {
        if (!detailEnriching.compareAndSet(false, true)) return
        scope.launch {
            try {
                val source = SOURCE_PREFIX + draft.channel
                val candidates = financeManager.dao.findAutoTransactionsByAmount(source, draft.amount)
                val match = AutoBookDetailEnricher.findMatch(
                    candidates, draft, System.currentTimeMillis()
                )
                if (match == null) {
                    Log.d(TAG, "详情页没有匹配到已记账记录，按新账单处理")
                    val blocked = dedup.blockedReason(draft.dedupKeys, System.currentTimeMillis())
                    if (blocked != null) {
                        Log.d(TAG, "去重命中，本次不弹窗：$blocked")
                        return@launch
                    }
                    showOverlay(draft)
                    return@launch
                }

                val result = AutoBookDetailEnricher.enrich(
                    match, draft, System.currentTimeMillis()
                )
                if (!result.changed) {
                    Log.d(TAG, "详情页没有新增信息，不提示: id=${match.id}")
                    return@launch
                }
                financeManager.dao.updateTransaction(result.transaction)
                // 详情补全只补字段、不走 FinanceManager.updateTransaction（那会重算账户余额）；
                // 但 UI 也要立刻看到补全后的商户 / 商品说明，所以显式发一次数据变更通知。
                financeManager.notifyChanged()
                Log.i(
                    TAG,
                    "已记账笔补全详情: id=${match.id} merchant=${result.transaction.merchant} " +
                        "payMethod=${result.transaction.payMethod} orderId=${result.transaction.orderId} " +
                        "time=${result.transaction.dateTime}"
                )
                service.mainExecutor.execute {
                    Toast.makeText(service, "详情信息已补全", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "详情页补全失败: ${e.message}")
            } finally {
                detailEnriching.set(false)
            }
        }
    }

    /**
     * 补分类数据、查库兜底去重，然后弹出浮窗。
     *
     * 查库这步是为了覆盖**进程重启**：内存去重表随服务重启清空，但「这一笔已经记过账」
     * 是数据库里的事实，据此可以继续挡住重复弹窗。
     */
    private fun showOverlay(draft: AutoBookDraft) {
        // 同步占位：同一笔的连续事件（详情页后加载商品说明）在第一次 display 完成前就到，
        // 靠 overlay.currentDraft 拦不住（窗口还没挂上），这里先占住 sourceId。
        if (draft.sourceId == pendingOverlaySourceId) return
        pendingOverlaySourceId = draft.sourceId
        scope.launch {
            val loaded = categoryTree ?: loadCategoryTree().also { categoryTree = it }
            val categoriesAt = System.currentTimeMillis()
            // 已记账查询与「同商户上次选择」互不依赖：并行查，少等一次 SQLite 往返
            val booked: Boolean
            val history: Transaction?
            coroutineScope {
                val bookedDeferred = async { isAlreadyBooked(draft) }
                val historyDeferred = async {
                    try {
                        if (draft.merchant.isBlank()) null
                        else financeManager.getLastTransactionByMerchant(draft.merchant)
                    } catch (e: Exception) {
                        Log.w(TAG, "查询商户历史失败（按无历史处理）: ${e.message}")
                        null
                    }
                }
                booked = bookedDeferred.await()
                history = historyDeferred.await()
            }
            if (booked) {
                Log.d(TAG, "该笔已记账（查库命中），不再弹窗: ${draft.sourceId}")
                return@launch
            }
            if (ignoreStore.isIgnored(draft.sourceId)) {
                Log.d(TAG, "该笔已被用户忽略，不再弹窗: ${draft.sourceId}")
                return@launch
            }
            val enriched = AutoBookDefaults.apply(draft, history)
            if (history != null) {
                Log.d(
                    TAG,
                    "商户「${draft.merchant}」上次选择: type=${history.type} " +
                        "category=${history.categoryName} → 默认勾选"
                )
            }
            // 回到主线程挂 View（WindowManager 操作要求在同一线程，无障碍服务的主线程即主 Looper）
            val dbAt = System.currentTimeMillis()
            Log.i(
                TAG,
                "延迟追踪: 事件→文本=${traceLinesAtMs - traceEventAtMs}ms " +
                    "文本→解析=${traceParsedAtMs - traceLinesAtMs}ms " +
                    "解析→分类=${categoriesAt - traceParsedAtMs}ms " +
                    "分类→查库=${dbAt - categoriesAt}ms"
            )
            service.mainExecutor.execute { display(enriched, loaded, dbAt) }
        }
    }

    /**
     * 诊断：页面文本为空时，再看一眼**节点是不是被 App 标记成不可见**了。
     *
     * 微信会按「无障碍服务身份」把支付页 / 聊天页 / WebView 页的节点标记为不可见并把 bounds 归零，
     * 于是正常收集为空。此时忽略坐标再读一遍，结果能区分两种成因：
     * - **能读到文本** → 节点树还在，只是被标记不可见 → 改采集策略还有救；
     * - **仍然是空** → 节点树被系统裁掉了 → 只能换通道（通知 / 截图 OCR / 伪装服务）。
     *
     * 详见 docs/记账/微信支付页识别调研.md 的「待真机验证的实验清单」。
     */
    private fun logInvisibleNodesIfAny(packageName: String) {
        val windows = try {
            service.windows
        } catch (e: Exception) {
            null
        } ?: return

        for (window in windows) {
            val root = try {
                window.root
            } catch (e: Exception) {
                null
            } ?: continue
            try {
                if (root.packageName?.toString() != packageName) continue
                val invisible = NodeTextCollector.collect(root, ignoreBounds = true)
                if (invisible.isNotEmpty()) {
                    val sample = invisible.take(12).joinToString(" | ") { it.text }
                    Log.d(
                        TAG,
                        "诊断：$packageName 有 ${invisible.size} 个文本节点被标记为不可见（bounds 归零），样本=[$sample]"
                    )
                }
            } catch (e: Exception) {
                // 诊断失败绝不能影响主流程
            } finally {
                runCatching { root.recycle() }
            }
        }
    }

    /**
     * 诊断：把「无障碍交互窗口清单」原样打进日志。
     *
     * 读不到页面时，只靠 `候选=[]` 无法区分两种成因，而它们的应对完全不同：
     * - **清单里根本没有目标 App 的窗口** → 窗口枚举被系统或第三方悬浮窗影响
     *   （真机踩过：熊猫记账的悬浮窗会顶掉根窗口，见 docs/踩坑/第三方悬浮窗顶掉无障碍根窗口.md）；
     * - **窗口在，但 `childCount=0` / 文本数为 0** → App 端把节点树藏起来了（微信就是这种）。
     *
     * 真机实测（小米 14 / HyperOS，2026-10-16）微信「支付成功」页与微信首页都落在第二种，
     * 完整证据与可行通道见 docs/记账/微信支付页识别调研.md。
     */
    private fun logWindowDiagnostics(packageName: String, windows: List<AccessibilityWindowInfo>) {
        val parts = windows.map { window ->
            val root = try {
                window.root
            } catch (e: Exception) {
                null
            }
            try {
                val pkg = root?.packageName?.toString() ?: "null"
                val childCount = root?.childCount ?: -1
                val textCount = if (root != null) NodeTextCollector.collect(root).size else -1
                "type=${window.type},pkg=$pkg,childCount=$childCount,文本=$textCount"
            } catch (e: Exception) {
                "type=${window.type},读取异常=${e.message}"
            } finally {
                runCatching { root?.recycle() }
            }
        }
        Log.d(
            TAG,
            "窗口诊断: 目标=$packageName, 交互窗口共 ${windows.size} 个 -> [${parts.joinToString("; ")}]"
        )
    }

    /** 这一笔是否已经落过库（持久化去重兜底） */
    private fun isAlreadyBooked(draft: AutoBookDraft): Boolean = try {
        financeManager.dao.transactionExists(SOURCE_PREFIX + draft.channel, draft.sourceId)
    } catch (e: Exception) {
        Log.w(TAG, "查库去重失败（按未记账处理，宁可多弹一次）: ${e.message}")
        false
    }

    /**
     * 调试入口：不经过识别链路，直接用 [AutoBookDebugDraft] 弹浮窗。
     *
     * 模拟器里没有支付 App，唯一能验证浮窗（关闭契约 / 两级分类 / 说明输入）的办法。
     * 与真实链路共用 [display]，所以分类推荐、落库口径完全一致；只在 debug 构建的设置页调试区调用。
     */
    fun showDebugSheet() {
        scope.launch {
            val tree = categoryTree ?: loadCategoryTree().also { categoryTree = it }
            service.mainExecutor.execute { display(AutoBookDebugDraft.create(), tree) }
        }
    }

    private fun display(
        draft: AutoBookDraft,
        tree: CategoryTree,
        scheduledAtMs: Long = System.currentTimeMillis(),
    ) {
        val mainAt = System.currentTimeMillis()
        scrimHintShown = false
        try {
            overlay.show(
                draft = draft,
                categoryTree = tree,
                onConfirm = { type, categoryName, remark ->
                    // 先打标再落库：落库是异步的，这期间页面若再抛事件不能复弹
                    val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.CONFIRM)
                    if (effect.markConfirmed) {
                        dedup.markConfirmed(draft.dedupKeys, System.currentTimeMillis())
                    }
                    Log.i(
                        TAG,
                        "用户确认记账: type=$type category=$categoryName amount=${draft.amount} 说明=$remark"
                    )
                    scope.launch { persist(draft, type, categoryName, remark, tree) }
                },
                onIgnore = {
                    // **唯一**会写「忽略」记忆的地方：只有用户点「忽略」按钮才走到这里
                    val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.IGNORE)
                    if (effect.markDismissed) {
                        dedup.markDismissed(draft.dedupKeys, System.currentTimeMillis())
                    }
                    if (effect.persistIgnored) ignoreStore.markIgnored(draft.sourceId)
                    Log.i(TAG, "用户忽略: sourceId=${draft.sourceId}")
                },
                onScrimTap = {
                    // 遮罩点击 = 吞掉点击：不关浮窗、不写 ignore、不写 DISMISSED（2026-10-20 用户要求）
                    val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.SCRIM_TAP)
                    Log.d(TAG, "遮罩点击已吞掉（closeOverlay=${effect.closeOverlay}）")
                    if (!scrimHintShown) {
                        scrimHintShown = true
                        service.mainExecutor.execute {
                            Toast.makeText(
                                service,
                                "点「记一笔」或「忽略」才会关闭",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
            Log.i(
                TAG,
                "延迟追踪: 查库→主线程=${mainAt - scheduledAtMs}ms " +
                    "挂载=${System.currentTimeMillis() - mainAt}ms"
            )
            dedup.markShown(draft.dedupKeys, System.currentTimeMillis())
            shownCount++
            // 浮窗已展示：预采集完成使命，清掉避免串到之后的同金额交易
            preDraft = null
            pendingRemark = null
            redPacketPaymentPending = false
            redPacketFingerprintArmed = false
        } catch (e: Exception) {
            // 浮窗挂不上（例如系统限制）不能静默：记日志，下一笔还会再试
            Log.e(TAG, "浮窗展示失败: ${e.message}", e)
        } finally {
            pendingOverlaySourceId = null
        }
    }

    /** 加载分类树；失败返回空树（浮窗照常弹，落库时分类回退「其他」） */
    private suspend fun loadCategoryTree(): CategoryTree {
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
     * 落库前的分类校验：名字必须属于该类型的分类树（一级或二级），否则回退一级兜底。
     *
     * 分类名可能来自三处：识别映射（应已是树内名字）、浮窗里用户的选择（树内）、
     * 或「商户上次选择」的历史值（可能已被分类体系升级后的迁移改掉）。最后一种如果原样落库，
     * 统计与预算就会遇到分类表里不存在的名字 —— 这里统一兜住。
     *
     * 树加载失败（空树）时保留原名：宁可分类名游离，也不能让用户确认过的这笔账记不下来。
     */
    private fun sanitizeCategoryName(tree: CategoryTree, type: Int, name: String): String {
        val pickerType = if (type == AutoBookDraft.TYPE_INCOME) 1 else 0
        val names = tree.topLevel(pickerType).flatMap { top ->
            listOf(top.name) + tree.childrenOf(top.name).map { it.name }
        }
        if (names.isEmpty()) return name
        if (names.contains(name)) return name
        val fallback = if (pickerType == 1) INCOME_FALLBACK else CategoryCatalog.FALLBACK
        val picked = if (names.contains(fallback)) fallback else names.first()
        Log.d(TAG, "分类名不在分类树中，回退: [$name] → [$picked]")
        return picked
    }

    /**
     * 幂等落库：同 source + sourceId 已存在时直接跳过（同一笔被反复确认只记一次）。
     *
     * @param editedRemark 用户在浮窗「说明」行里填的文字（空串 = 不写说明）
     */
    private suspend fun persist(
        draft: AutoBookDraft,
        type: Int,
        categoryName: String,
        editedRemark: String,
        tree: CategoryTree,
    ) {
        try {
            val source = SOURCE_PREFIX + draft.channel
            if (financeManager.dao.transactionExists(source, draft.sourceId)) {
                Log.d(TAG, "该笔已记账，跳过: ${draft.sourceId}")
                return
            }

            val bookedCategory = sanitizeCategoryName(tree, type, categoryName)
            val now = LocalDateTime.now()
            // 交易时间优先用**页面上的**（对账口径）；页面没给或格式不认识才退回记账时刻。
            // 用「点记一笔的时刻」会让同一笔在不同时间被识别时算出不同的落库键
            // （真机出现过同一笔 18:32 的交易被记成 20:51、21:00 两条）。
            val dateTime = AutoBookParser.normalizeTradeTime(draft.timeText)
                ?: Transaction.buildDateTime(now.toLocalDate().toString(), now)
            val date = dateTime.substringBefore(" ")
            val transaction = Transaction(
                type = type,
                categoryName = bookedCategory,
                amount = draft.amount,
                accountName = draft.accountName,
                // 账本「说明」：只用用户在浮窗里填的文字（编辑值优先、空则不写）。
                // 商户 / 商品说明 / 付款方式已是独立字段，不再拼进说明。
                remark = draft.bookedRemark(editedRemark),
                date = date,
                dateTime = dateTime,
                source = source,
                sourceId = draft.sourceId,
                merchant = draft.merchant,
                productDesc = draft.productDesc,
                payMethod = draft.payMethod,
                orderId = draft.orderId,
                platformCategory = draft.platformCategory,
                tradeTime = draft.timeText
            )

            val id = financeManager.addTransaction(transaction)
            // 记账列表靠这个一次性信号做「顶部新行高亮 1.6s」：只有自动记账落库才点亮，
            // 手写记录 / 切 Tab / 回前台重载都不点亮（见 FinanceInsertSignal）
            FinanceInsertSignal.mark(id)
            Log.i(TAG, "自动记账已写入 id=$id 金额=${draft.amount} 分类=$bookedCategory 商户=${draft.merchant}")
        } catch (e: Exception) {
            Log.e(TAG, "自动记账落库失败: ${e.message}", e)
        }
    }

    /**
     * 服务销毁时清理浮窗、预采集、去重表与协程。
     *
     * 这是**唯一**允许被动卸载浮窗的路径（[AutoBookCloseSource.SERVICE_RELEASE]）：
     * 用户操作只能通过浮窗里的「记一笔」/「忽略」两个按钮，切 App / 点遮罩都不卸载。
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

    private companion object {
        const val TAG = "AutoBook"

        /** 落库来源前缀，最终 source 形如 auto_alipay / auto_wechat */
        const val SOURCE_PREFIX = "auto_"

        /** 收入分类的兜底一级（支出侧的兜底是 [CategoryCatalog.FALLBACK]「其他」） */
        const val INCOME_FALLBACK = "其他收入"
    }
}
