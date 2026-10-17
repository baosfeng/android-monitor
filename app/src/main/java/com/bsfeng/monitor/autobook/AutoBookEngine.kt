package com.bsfeng.monitor.autobook

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
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
 * - 同一包名事件 800ms 节流（支付结果页会连续抛多个 CONTENT_CHANGED）；
 * - **只在账单 / 交易页识别**（[AutoBookParser.isBillPage] 门禁，挡掉首页消息摘要）；
 * - 同一笔账单短时间只弹一次：内存去重表（[AutoBookDedup]）+ 落库查库双重兜底；
 * - 解析在无障碍主线程（节点不能跨线程），查库/落库切到 IO。
 */
class AutoBookEngine(private val service: AccessibilityService) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val financeManager: FinanceManager by lazy { FinanceManager.getInstance(service) }

    private val overlay = AutoBookOverlay(service)

    /**
     * 事件节流：只有 CONTENT_CHANGED 走 800ms 窗口；WINDOW_STATE_CHANGED 永远放行。
     *
     * 真机事故（2026-10-17）：支付成功页的 WINDOW_STATE_CHANGED 在指纹页之后 402ms 到达，
     * 被旧实现的 800ms 节流丢掉；结果页是静态的、之后不再有事件 —— 那一笔永远不弹窗。
     */
    private val eventThrottle = AutoBookEventThrottle()

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
        // 页面切换（WINDOW_STATE_CHANGED）是低频且关键的事件，不能被内容变化节流吃掉；
        // 否则「指纹页 → 支付成功页」这种 400ms 内的切换会永远漏掉（真机已复现）。
        if (!eventThrottle.shouldProcess(
                isWindowStateChange = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                nowMs = now,
            )
        ) return

        val lines = collectPageLines(packageName)
        if (lines.isEmpty()) {
            // 支付结果页刚切换时拿不到内容，等下一次事件即可（节流会挡住高频重复）。
            // 顺带看一眼「节点是不是被标记成不可见了」——微信就是这么藏支付页的，
            // 两种成因的应对完全不同，别白排查（见 docs/记账/微信支付页识别调研.md）。
            logInvisibleNodesIfAny(packageName)
            Log.d(TAG, "未收集到页面文本，跳过: $packageName")
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

        // 交易详情页：如果这笔已经自动记过账，不再弹记账浮窗，而是把详情页字段补进已有记录 + Toast。
        // 未匹配到已记账记录时保持原行为（正常弹窗）。
        if (AutoBookParser.isDetailPage(lines)) {
            handleDetailPage(merged)
            return
        }

        // 浮窗还开着且就是这一笔：不要重建（会把用户正在填的类型/分类冲掉）
        val showing = overlay.currentDraft
        if (showing != null && showing.sourceId == merged.sourceId) return

        // 短期去重：同一笔（已记账 / 已忽略 / 刚弹过）在各自安静期内不再弹
        val blocked = dedup.blockedReason(merged.dedupKeys, now)
        if (blocked != null) {
            Log.d(TAG, "去重命中，本次不弹窗：$blocked")
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
        scope.launch {
            val loaded = categoriesByType.ifEmpty { loadCategories().also { categoriesByType = it } }
            if (isAlreadyBooked(draft)) {
                Log.d(TAG, "该笔已记账（查库命中），不再弹窗: ${draft.sourceId}")
                return@launch
            }
            // 同一家店上次确认时选的类型 / 分类：默认勾选（没有历史就保持解析器结果）
            val history = try {
                if (draft.merchant.isBlank()) null
                else financeManager.getLastTransactionByMerchant(draft.merchant)
            } catch (e: Exception) {
                Log.w(TAG, "查询商户历史失败（按无历史处理）: ${e.message}")
                null
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
            service.mainExecutor.execute { display(enriched, loaded) }
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
            // 浮窗已展示：预采集完成使命，清掉避免串到之后的同金额交易
            preDraft = null
            pendingRemark = null
            redPacketPaymentPending = false
            redPacketFingerprintArmed = false
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
            // 交易时间优先用**页面上的**（对账口径）；页面没给或格式不认识才退回记账时刻。
            // 用「点记一笔的时刻」会让同一笔在不同时间被识别时算出不同的落库键
            // （真机出现过同一笔 18:32 的交易被记成 20:51、21:00 两条）。
            val dateTime = AutoBookParser.normalizeTradeTime(draft.timeText)
                ?: Transaction.buildDateTime(now.toLocalDate().toString(), now)
            val date = dateTime.substringBefore(" ")
            val transaction = Transaction(
                type = type,
                categoryName = categoryName,
                amount = draft.amount,
                accountName = draft.accountName,
                remark = draft.remark(),
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
            Log.i(TAG, "自动记账已写入 id=$id 金额=${draft.amount} 分类=$categoryName 商户=${draft.merchant}")
        } catch (e: Exception) {
            Log.e(TAG, "自动记账落库失败: ${e.message}", e)
        }
    }

    /** 服务销毁时清理浮窗、预采集、去重表与协程 */
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
    }
}
