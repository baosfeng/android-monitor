package com.bsfeng.monitor.skip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.MatchAction
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.util.DebugConfig

/**
 * 开屏跳过规则引擎（GKD 语义）。
 *
 * ## 触发模型
 * `MonitorAccessibilityService` 在窗口/内容变化时调用本引擎；引擎以 **App 切换时刻** 为锚点
 * 开启一个匹配窗口（[SkipConfig.watchWindowMs]，默认 10 秒，设置页可选 5/10/15 秒），窗口内：
 * - `TYPE_WINDOW_CONTENT_CHANGED` 事件到达即扫描（100ms 节流）——广告渲染完成的第一时间命中
 * - 兜底轮询每 [BACKUP_SCAN_INTERVAL_MS] 一次，防止事件丢失
 * - 根节点为 null（启动瞬间很常见）时不放弃，等下轮扫描重试
 *
 * ## 弱网顺延（P2-2 增强）
 * 弱网/慢页面下跳过按钮可能在窗口结束后才渲染出来（窗口一到就停止扫描 → 规则再准也点不到）。
 * 因此窗口**到点**时若仍满足：①未命中任何规则 ②页面仍在变化（距最近一次 CONTENT_CHANGED
 * < [WindowExtendPolicy.stillRenderingMs]，默认 1 秒）③顺延次数未达上限，就把截止时间往后推
 * [WindowExtendPolicy.stepMs]（默认 2 秒，最多 [WindowExtendPolicy.maxExtensions] 次，即最多 +4 秒）。
 *
 * - **与 `matchTime` 的关系**：规则的时间闸门按**已顺延的额度**一起放宽
 *   （`now - enterTime > matchTime + extensionGrantedMs` 才算过期）。理由：内置规则的
 *   `matchTime` 就等于监控窗口（见 [SkipConfig]），若只延长"扫描"而不放宽闸门，顺延期间扫到了也
 *   不允许执行，这个增强就是死代码；宽限额度受顺延上限（默认 +4 秒）硬约束，且只在"页面仍在渲染"
 *   这个弱网证据下发放，不会无限外扩。
 * - **只在未命中时顺延**：命中后走点击后确认（[verifyDelaysMs]）流程，复核期间不再顺延；
 *   复核到点照常上报，不受窗口截止时间影响。
 * - **不顺延的情况**：熄屏、切 App、扫描次数达到 [MAX_SCANS] 护栏、页面已静止（距上次内容变化 ≥
 *   [WindowExtendPolicy.stillRenderingMs]）、到点判定时引擎已长时间未扫描（距上次扫描 >
 *   [WindowExtendPolicy.maxScanGapMs]，用于排除进程冻结/系统时间跳变导致的"复活过期窗口"）、
 *   顺延次数用尽——以上任一条件成立即结束窗口（护栏优先于顺延）。
 * - 每次顺延都写 [DebugConfig.log]（触发原因 + 新的截止时间 + 已顺延次数）。
 *
 * ## 防误触
 * - 只执行规则命中的节点动作，**不再有"坐标连点"或"返回键"兜底**（业界调研结论见
 *   `docs/开屏跳过/业界调研与改进方案.md` §4 P0-2）
 * - `matchTime` 限定规则参与匹配的时间窗口；`actionMaximum` + `resetMatch` 限定执行次数；
 *   `actionCd` 限制最小间隔；`excludeMatches` 排除误触场景
 * - 熄屏（Dozing）时不做任何点击
 * - 白名单 App 完全不处理
 * - **点击后确认**（[verifyDelaysMs]）：动作执行后再用同一选择器复核一次，广告没消失就标为
 *   "未确认"（避免点到装饰性节点也算"跳过成功"，成功率数字才可信）
 *
 * ## 与旧实现的差异（修复的缺陷）
 * - `matchDelay` 改为"到期标记"（[pendingDelays]），不再用 Handler 取消/重投，避免被
 *   CONTENT_CHANGED 风暴无限推迟导致规则永不执行
 * - 匹配起点固定为当前活动窗口根节点，选择器快速查询由 [SelectorCache]/[NodeMatcher] 完成
 */
class SkipRuleEngine(private val ruleManager: SkipRuleManager) {

    /**
     * 一次跳过动作的结果。
     *
     * @param confirmed 点击后确认结果：true = 复核时广告已消失；false = 复核时仍在；
     *   null = 未做复核（[verifyDelaysMs] 为空，如单元测试）
     */
    data class SkipResult(
        val packageName: String,
        val ruleName: String,
        val selector: String,
        val action: String,
        val confirmed: Boolean? = null
    )

    private val handler = Handler(Looper.getMainLooper())
    private var rootNodeProvider: (() -> AccessibilityNodeInfo?)? = null

    /** 跳过成功回调（服务层用于记录事件与提示） */
    var onSkipSuccess: ((SkipResult) -> Unit)? = null

    // ==================== 运行状态 ====================

    private var currentPackage: String? = null
    private var currentClassName: String = ""
    private var enterTimeMs = 0L
    private var watching = false
    private var scanCount = 0

    /** 最近一次 CONTENT_CHANGED 触发时刻；null = 本窗口内还没有内容变化（弱网顺延的判定依据） */
    private var lastContentTriggerMs: Long? = null

    /** 本次窗口的截止时刻（绝对毫秒；弱网顺延会把它往后推） */
    private var windowDeadlineMs = 0L

    /** 本次窗口已顺延次数（随窗口重建重置，见 [startWatching]） */
    private var extensionCount = 0

    /** 本次窗口累计顺延时长（毫秒）：同时是规则时间闸门 `matchTime` 的宽限额度 */
    private var extensionGrantedMs = 0L

    /** 上一次扫描时的引擎时间（毫秒）：用于确认"引擎一直在连续扫描"（见 [tryExtendWindow]） */
    private var lastScanAtMs: Long? = null

    /** 规则计数（key = ruleId#groupKey#ruleKey）→ 已执行次数 */
    private val counters = mutableMapOf<String, Int>()

    /** 规则冷却（key 同上）→ 允许执行的时间点 */
    private val cooldowns = mutableMapOf<String, Long>()

    /** matchDelay 到期时间（key 同上）：到期前该规则不参与执行 */
    private val pendingDelays = mutableMapOf<String, Long>()

    /** actionDelay 确认时间（key 同上）：到期且**再次命中**才真正执行动作（GKD `actionDelay`） */
    private val actionConfirmDelays = mutableMapOf<String, Long>()

    /** 各规则组最近一次执行的规则 key（map key = ruleId#groupKey），用于 `preKeys` 顺序闸门 */
    private val lastExecuted = mutableMapOf<String, Int>()

    /** 时钟与屏幕状态提供器（测试注入） */
    internal var currentTimeMillis: () -> Long = { System.currentTimeMillis() }
    internal var screenInteractive: () -> Boolean = { defaultScreenInteractive() }

    /** 匹配窗口提供器（毫秒；默认取用户配置 [SkipConfig.watchWindowMs]，测试可注入） */
    internal var watchWindowProvider: () -> Long = { SkipConfig.watchWindowMs }

    /**
     * 弱网顺延策略（默认取 [SkipConfig] 中的常量，测试可注入替换步长/上限）。
     * 语义与上限理由见 [WindowExtendPolicy] 与 [SkipConfig.WINDOW_MAX_EXTENSIONS]。
     */
    internal var windowExtendPolicy: WindowExtendPolicy = WindowExtendPolicy()

    /**
     * 点击后确认的复核延时表（毫秒）：第 i 项是第 i 次复核前的等待时间。
     *
     * 空列表 = 不做复核（动作执行后立即上报，`confirmed = null`）。
     */
    internal var verifyDelaysMs: List<Long> = VERIFY_DELAYS.toList()

    /** 待确认的跳过动作（复核期间只做复核，不再执行任何规则，避免重复点击） */
    private var pendingVerify: PendingVerify? = null

    private class PendingVerify(
        val result: SkipResult,
        val selector: String,
        val fastQuery: Boolean,
        var attempt: Int,
        var dueAt: Long
    )

    private val scanTask = Runnable { performScan() }

    // ==================== 外部接口 ====================

    /** 设置根节点提供器（服务层提供 `rootInActiveWindow`） */
    fun setRootNodeProvider(provider: () -> AccessibilityNodeInfo?) {
        rootNodeProvider = provider
    }

    /**
     * 窗口状态变化（前台 App 或 Activity 变化）。
     *
     * 新 App：重置计数/冷却并开启匹配窗口；同 App 内 Activity 变化：按 [ResetMatch.ACTIVITY] 重置计数。
     */
    fun onWindowChanged(packageName: String, className: String) {
        if (packageName == MonitorApp.instance.packageName) {
            stopWatching()
            return
        }
        if (isWhitelisted(packageName)) {
            DebugConfig.log("SkipEngine: 白名单跳过 " + packageName)
            return
        }
        if (!screenInteractive()) {
            DebugConfig.log("SkipEngine: 屏幕未点亮，忽略窗口变化 " + packageName)
            return
        }
        val now = currentTimeMillis()
        if (packageName != currentPackage) {
            resetAppState(packageName)
            currentPackage = packageName
            enterTimeMs = now
            DebugConfig.log("SkipEngine: 进入 App " + packageName + " / " + className)
        } else if (className != currentClassName) {
            resetActivityScopedCounters(packageName)
            DebugConfig.log("SkipEngine: Activity 变化 " + className)
        }
        currentClassName = className
        startWatching(now)
    }

    /**
     * 窗口内容变化（事件驱动扫描）。
     *
     * 仅处理正在监控的 App；100ms 节流以吸收 CONTENT_CHANGED 风暴。
     */
    fun onContentChanged(packageName: String) {
        val pkg = currentPackage ?: return
        if (!watching || packageName != pkg) return
        if (!screenInteractive()) return
        val now = currentTimeMillis()
        if (lastContentTriggerMs?.let { now - it < CONTENT_TRIGGER_INTERVAL_MS } == true) return
        lastContentTriggerMs = now
        handler.removeCallbacks(scanTask)
        performScan()
    }

    /**
     * 停止监控并清理规则状态。
     *
     * 顺延计数（[extensionCount]/[extensionGrantedMs]）**不在这里清零**：它们随下一次
     * [startWatching] 重置，停窗后保留最后一次的取值便于诊断与单测断言。
     */
    fun stopWatching() {
        watching = false
        handler.removeCallbacks(scanTask)
        scanCount = 0
        pendingVerify = null
    }

    /** 当前是否处于匹配窗口内 */
    fun isWatching(): Boolean = watching

    /** 本次（或最近一次）窗口已顺延的次数（诊断/单测） */
    internal fun extensionCount(): Int = extensionCount

    /** 本次（或最近一次）窗口累计顺延时长（毫秒，诊断/单测） */
    internal fun extensionGrantedMs(): Long = extensionGrantedMs

    /** 本次（或最近一次）窗口的截止时刻（绝对毫秒，诊断/单测） */
    internal fun windowDeadlineMs(): Long = windowDeadlineMs

    /** 正在监控的包名 */
    fun watchingPackage(): String? = if (watching) currentPackage else null

    /** 重置某个 App 的状态（进入本 App 或用户手动重置时调用） */
    fun resetAppState(packageName: String?) {
        counters.clear()
        cooldowns.clear()
        pendingDelays.clear()
        actionConfirmDelays.clear()
        lastExecuted.clear()
        stopWatching()
        if (packageName != null && packageName != currentPackage) {
            currentPackage = null
        }
    }

    // ==================== 匹配窗口 ====================

    private fun startWatching(now: Long) {
        watching = true
        scanCount = 0
        val windowMs = watchWindowProvider()
        windowDeadlineMs = now + windowMs
        extensionCount = 0
        extensionGrantedMs = 0
        lastContentTriggerMs = null
        lastScanAtMs = null
        handler.removeCallbacks(scanTask)
        handler.post(scanTask)
        val policy = windowExtendPolicy
        val extendHint = if (policy.enabled) {
            "（弱网下页面仍在渲染时最多顺延 " + policy.maxExtensions + " 次 × " + policy.stepMs + "ms）"
        } else {
            "（弱网顺延已关闭）"
        }
        DebugConfig.log("SkipEngine: 开启匹配窗口 " + windowMs + "ms" + extendHint + ", 当前 " + currentPackage +
            " / " + currentClassName + ", now=" + now + ", 截止=" + windowDeadlineMs)
    }

    private fun schedule(delayMs: Long) {
        if (!watching) return
        handler.removeCallbacks(scanTask)
        handler.postDelayed(scanTask, delayMs)
    }

    // ==================== 扫描 ====================

    private fun performScan() {
        val pkg = currentPackage ?: return
        if (!watching) return
        val now = currentTimeMillis()
        // 到点判定要确认引擎一直在连续扫描：先取"距上次扫描"的间隔，再刷新（见 [tryExtendWindow]）
        val sinceLastScanMs = lastScanAtMs?.let { now - it }
        lastScanAtMs = now
        if (!screenInteractive()) {
            DebugConfig.log("SkipEngine: 屏幕未点亮，停止扫描 " + pkg)
            stopWatching()
            return
        }
        // 点击后确认优先：复核期间不执行任何规则（否则同一条广告可能被点多次）
        pendingVerify?.let { pv ->
            if (now < pv.dueAt) {
                schedule(pv.dueAt - now)
                return
            }
            verifyAndFinish(pv, now)
            return
        }
        // 护栏优先：扫描次数用尽直接结束（密集 CONTENT_CHANGED 场景下先于顺延上限生效）
        if (scanCount >= MAX_SCANS) {
            DebugConfig.log("SkipEngine: 匹配窗口结束 " + pkg + " (扫描次数到达护栏 " + MAX_SCANS + ")")
            stopWatching()
            return
        }
        if (now >= windowDeadlineMs && !tryExtendWindow(pkg, now, sinceLastScanMs)) {
            DebugConfig.log("SkipEngine: 匹配窗口结束 " + pkg + " (扫描 " + scanCount + " 次，顺延 " +
                extensionCount + " 次)")
            stopWatching()
            return
        }
        scanCount++
        val root = rootNodeProvider?.invoke()
        if (root == null) {
            DebugConfig.log("SkipEngine: 根节点未就绪，稍后重试 " + pkg)
            schedule(BACKUP_SCAN_INTERVAL_MS)
            return
        }
        val hit = try {
            applyRules(pkg, root, now)
        } finally {
            root.recycle()
        }
        if (hit != null) {
            startVerification(hit, now)
            return
        }
        schedule(BACKUP_SCAN_INTERVAL_MS)
    }

    /**
     * 弱网顺延（P2-2 增强）：窗口**到点**时判断是否还值得继续扫描。
     *
     * 四个条件同时成立才顺延：①策略启用且顺延次数未用尽；②页面仍在渲染
     * （距最近一次 CONTENT_CHANGED < [WindowExtendPolicy.stillRenderingMs]）；③引擎一直在连续扫描
     * （距上次扫描 ≤ [WindowExtendPolicy.maxScanGapMs]，排除进程被冻结/时间跳变导致的"复活过期窗口"）；
     * ④调用方已保证当前**未命中**任何规则、也没有待复核动作（[performScan] 里的前置分支已排除）。
     *
     * 截止时间取「原截止时间 + stepMs」而不是「now + stepMs」：这样"基础窗口 + 步长×次数"就是
     * **精确**的硬上限（默认 10s + 4s），不会被到点检测延迟（正常 ≤ 一个兜底轮询周期）叠加放大；
     * 到点判定晚于截止时间时，顺延额度照样按原截止时间累加。
     * 累计顺延额度 [extensionGrantedMs] 会被 [ruleAllowed] 用作 `matchTime` 的宽限额度。
     *
     * @return true = 已顺延（窗口继续）；false = 不顺延（调用方结束窗口）
     */
    private fun tryExtendWindow(packageName: String, now: Long, sinceLastScanMs: Long?): Boolean {
        val policy = windowExtendPolicy
        if (!policy.enabled) return false
        if (extensionCount >= policy.maxExtensions) {
            DebugConfig.log("SkipEngine: 窗口到点不顺延（顺延次数已达上限 " + extensionCount + "/" +
                policy.maxExtensions + "）" + packageName)
            return false
        }
        if (sinceLastScanMs != null && sinceLastScanMs > policy.maxScanGapMs) {
            DebugConfig.log("SkipEngine: 窗口到点不顺延（距上次扫描 " + sinceLastScanMs + "ms > 连续扫描上限 " +
                policy.maxScanGapMs + "ms，引擎长时间未运行/时间跳变，窗口已过期）" + packageName)
            return false
        }
        val last = lastContentTriggerMs
        val idleMs = if (last == null) Long.MAX_VALUE else now - last
        if (idleMs > policy.stillRenderingMs) {
            val idleText = if (last == null) "窗口内无内容变化" else "距上次内容变化 " + idleMs + "ms"
            DebugConfig.log("SkipEngine: 窗口到点不顺延（页面已静止：" + idleText + "）" + packageName)
            return false
        }
        val newDeadline = windowDeadlineMs + policy.stepMs
        extensionCount++
        extensionGrantedMs += policy.stepMs
        windowDeadlineMs = newDeadline
        DebugConfig.log("SkipEngine: 弱网顺延 #" + extensionCount + "/" + policy.maxExtensions +
            " 触发原因=页面仍在渲染（距上次内容变化 " + idleMs + "ms < " + policy.stillRenderingMs + "ms, now=" +
            now + "） → 窗口延长 " + policy.stepMs + "ms, 新截止=" + newDeadline +
            ", 累计顺延=" + extensionGrantedMs + "ms, " + packageName)
        return true
    }

    /**
     * 动作已执行：安排「点击后确认」（P2-4）。
     *
     * 跳过按钮点了不等于广告真的消失（点到装饰性节点、点了没反应、广告换了容器都会误判成功），
     * 因此延迟 [verifyDelaysMs] 后重新用同一选择器复核：命中不到才算"已确认"。
     */
    private fun startVerification(hit: Hit, now: Long) {
        val delays = verifyDelaysMs
        if (delays.isEmpty()) {
            finishSkip(hit.result)
            return
        }
        pendingVerify = PendingVerify(hit.result, hit.result.selector, hit.matchRule.fastQuery, 0, now + delays[0])
        DebugConfig.log("SkipEngine: 动作已执行，等待 " + delays[0] + "ms 确认广告是否消失 → " + hit.result.selector)
        schedule(delays[0])
    }

    /**
     * 复核：用命中的选择器重新匹配，命中不到则确认跳过成功。
     *
     * 复核失败（广告仍在 / 根节点不可用）时还有剩余复核次数就再等一轮——
     * 广告消失通常有几十到几百毫秒的动画延迟。
     */
    private fun verifyAndFinish(pv: PendingVerify, now: Long) {
        val root = rootNodeProvider?.invoke()
        var confirmed: Boolean? = null
        if (root != null) {
            try {
                confirmed = RuleNodeFinder.matches(pv.selector, pv.fastQuery, root)?.not()
            } finally {
                root.recycle()
            }
        }
        val delays = verifyDelaysMs
        if (confirmed != true && pv.attempt + 1 < delays.size) {
            pv.attempt++
            pv.dueAt = now + delays[pv.attempt]
            DebugConfig.log("SkipEngine: 复核未通过（广告仍在或根节点不可用），" + delays[pv.attempt] + "ms 后再复核一次")
            schedule(delays[pv.attempt])
            return
        }
        finishSkip(pv.result.copy(confirmed = confirmed))
    }

    /** 上报跳过结果并结束窗口 */
    private fun finishSkip(result: SkipResult) {
        pendingVerify = null
        when (result.confirmed) {
            true -> Log.d(TAG, "✅ 跳过成功（已确认广告消失）: " + result.packageName + " ← " + result.selector)
            false -> Log.d(TAG, "⚠️ 跳过未确认（点完广告仍在）: " + result.packageName + " ← " + result.selector)
            null -> Log.d(TAG, "✅ 跳过成功: " + result.packageName + " ← " + result.selector)
        }
        onSkipSuccess?.invoke(result)
        stopWatching()
    }

    /** 一次命中：结果 + 用于确认复核的匹配规则 */
    private class Hit(val result: SkipResult, val matchRule: MatchRule)

    /** 依次尝试匹配规则；返回命中的结果 */
    private fun applyRules(packageName: String, root: AccessibilityNodeInfo, now: Long): Hit? {
        val rules = ruleManager.findRulesFor(packageName)
            .sortedWith(compareBy({ if (it.isGlobal) 1 else 0 }, { it.id }))
        for (rule in rules) {
            val hit = applyRule(rule, packageName, root, now) ?: continue
            return hit
        }
        DebugConfig.log("SkipEngine: 未命中任何规则 " + packageName + " / " + currentClassName)
        return null
    }

    private fun applyRule(rule: SkipRule, packageName: String, root: AccessibilityNodeInfo, now: Long): Hit? {
        for (group in rule.groups.sortedBy { it.order }) {
            if (!group.enabled || !activityMatches(group, packageName)) continue
            for (matchRule in group.rules) {
                val selector = applyMatchRule(rule, group, matchRule, root, now) ?: continue
                val ruleName = matchRule.name.ifEmpty { group.name }
                val result = SkipResult(packageName, rule.name + "/" + ruleName, selector, matchRule.action.value)
                return Hit(result, matchRule)
            }
        }
        return null
    }

    /** 执行单条匹配规则；返回命中的选择器 */
    private fun applyMatchRule(
        rule: SkipRule,
        group: RuleGroup,
        matchRule: MatchRule,
        root: AccessibilityNodeInfo,
        now: Long
    ): String? {
        val key = ruleKey(rule.id, group.key, matchRule.key)
        if (!ruleAllowed(rule.id, group, matchRule, now)) return null
        if (matchRule.action == MatchAction.NONE) return null
        if (RuleNodeFinder.anySelectorMatches(matchRule.excludeMatches, matchRule.fastQuery, root)) return null
        val target = RuleNodeFinder.findTarget(matchRule, root) ?: return null
        if (!delaySatisfied(matchRule, key, now) || !actionDelaySatisfied(matchRule, key, now)) {
            NodeMatcher.recycleAll(target.nodes)
            return null
        }
        val executed = try {
            ActionExecutor.perform(target.node, matchRule.action, matchRule.position)
        } finally {
            NodeMatcher.recycleAll(target.nodes)
        }
        if (!executed) {
            DebugConfig.log("SkipEngine: 动作执行失败 " + key + " → " + target.selector)
            return null
        }
        recordExecution(rule.id, group, matchRule, now)
        return target.selector
    }

    /**
     * 规则是否可执行：匹配窗口、执行次数（含 [MatchRule.actionMaximumKey] 共享）、
     * 冷却（含 [MatchRule.actionCdKey] 共享）、[MatchRule.preKeys] 顺序闸门。
     */
    private fun ruleAllowed(ruleId: String, group: RuleGroup, matchRule: MatchRule, now: Long): Boolean {
        val window = matchRule.matchTime ?: group.matchTime
        // 弱网顺延联动：闸门按「本窗口已顺延的额度」一起放宽，额度上限 = 步长 × 次数（默认 +4 秒）。
        // 理由见类 KDoc「与 matchTime 的关系」：内置规则的 matchTime 就等于监控窗口，
        // 若只延长扫描而不放宽闸门，顺延期间扫到广告也不允许执行（增强失效）。
        if (window > 0 && now - enterTimeMs > window + extensionGrantedMs) return false
        val limit = matchRule.actionMaximum ?: group.actionMaximum
        if (limit > 0) {
            val used = counterKeys(ruleId, group.key, matchRule).maxOf { counters[it] ?: 0 }
            if (used >= limit) return false
        }
        val cooldown = matchRule.actionCd ?: group.actionCd
        if (cooldown > 0) {
            val until = cooldownKeys(ruleId, group.key, matchRule).maxOf { cooldowns[it] ?: 0L }
            if (now < until) return false
        }
        if (matchRule.preKeys.isNotEmpty()) {
            val last = lastExecuted[groupScopeKey(ruleId, group.key)]
            if (last == null || last !in matchRule.preKeys) return false
        }
        return true
    }

    /** 记录一次执行：写入计数、冷却与 preKeys 游标 */
    private fun recordExecution(ruleId: String, group: RuleGroup, matchRule: MatchRule, now: Long) {
        counterKeys(ruleId, group.key, matchRule).forEach { counters[it] = (counters[it] ?: 0) + 1 }
        val cooldown = matchRule.actionCd ?: group.actionCd
        if (cooldown > 0) {
            cooldownKeys(ruleId, group.key, matchRule).forEach { cooldowns[it] = now + cooldown }
        }
        lastExecuted[groupScopeKey(ruleId, group.key)] = matchRule.key
    }

    private fun groupScopeKey(ruleId: String, groupKey: Int): String = ruleId + "#" + groupKey

    private fun ruleKey(ruleId: String, groupKey: Int, key: Int): String = groupScopeKey(ruleId, groupKey) + "#" + key

    /** 计数键：自身 + [MatchRule.actionMaximumKey] 共享键 */
    private fun counterKeys(ruleId: String, groupKey: Int, matchRule: MatchRule): List<String> {
        val keys = mutableListOf(ruleKey(ruleId, groupKey, matchRule.key))
        matchRule.actionMaximumKey?.let { keys.add(ruleKey(ruleId, groupKey, it)) }
        return keys.distinct()
    }

    /** 冷却键：自身 + [MatchRule.actionCdKey] 共享键 */
    private fun cooldownKeys(ruleId: String, groupKey: Int, matchRule: MatchRule): List<String> {
        val keys = mutableListOf(ruleKey(ruleId, groupKey, matchRule.key))
        matchRule.actionCdKey?.let { keys.add(ruleKey(ruleId, groupKey, it)) }
        return keys.distinct()
    }

    /**
     * actionDelay 语义：命中后先等待，**到期且仍然命中**才执行动作（GKD `actionDelay`，抗误触）。
     * 与 matchDelay 一样以实现为时间戳，不会被后续扫描取消。
     */
    private fun actionDelaySatisfied(matchRule: MatchRule, key: String, now: Long): Boolean {
        if (matchRule.actionDelay <= 0) return true
        val confirmAt = actionConfirmDelays[key]
        if (confirmAt == null) {
            actionConfirmDelays[key] = now + matchRule.actionDelay
            DebugConfig.log("SkipEngine: 命中后等待 actionDelay=" + matchRule.actionDelay + "ms 复核 " + key)
            return false
        }
        if (now < confirmAt) return false
        actionConfirmDelays.remove(key)
        return true
    }

    /**
     * matchDelay 语义：首次命中后挂起 [MatchRule.matchDelay]，到期后重新命中才执行。
     * 用"到期时间戳"实现，避免被后续扫描取消（旧实现在 CONTENT_CHANGED 风暴下永不执行）。
     */
    private fun delaySatisfied(matchRule: MatchRule, key: String, now: Long): Boolean {
        if (matchRule.matchDelay <= 0) return true
        val readyAt = pendingDelays[key]
        if (readyAt == null) {
            pendingDelays[key] = now + matchRule.matchDelay
            DebugConfig.log("SkipEngine: 规则挂起等待 matchDelay=" + matchRule.matchDelay + "ms " + key)
            return false
        }
        return now >= readyAt
    }
    // ==================== 选择器匹配 ====================

    // 命中节点的查找与筛选（选择器求值 + 面积守卫）抽到 RuleNodeFinder，
    // 本类只负责「什么时候允许动作」与「动作之后如何确认」。

    // ==================== 作用域与状态 ====================

    /** Activity 是否匹配规则组（前缀匹配，`.` 开头自动补包名；exclude 优先） */
    private fun activityMatches(group: RuleGroup, packageName: String): Boolean {
        val className = currentClassName
        if (className.isEmpty()) return group.excludeActivityIds.isEmpty()
        if (group.excludeActivityIds.any { matchesActivity(it, className, packageName) }) return false
        if (group.activityIds.isEmpty()) return true
        return group.activityIds.any { matchesActivity(it, className, packageName) }
    }

    private fun matchesActivity(pattern: String, className: String, packageName: String): Boolean {
        val full = if (pattern.startsWith(".")) packageName + pattern else pattern
        return className == full || className.startsWith(full)
    }

    /** Activity 变化时，重置 [ResetMatch.ACTIVITY] 的作用域计数 */
    private fun resetActivityScopedCounters(packageName: String) {
        val keys = ruleManager.findRulesFor(packageName).flatMap { rule ->
            rule.groups.filter { it.resetMatch == ResetMatch.ACTIVITY }.flatMap { group ->
                group.rules.map { rule.id + "#" + group.key + "#" + it.key }
            }
        }
        keys.forEach {
            counters.remove(it)
            pendingDelays.remove(it)
            actionConfirmDelays.remove(it)
        }
    }

    private fun isWhitelisted(packageName: String): Boolean = try {
        val prefs = MonitorApp.instance.getSharedPreferences(WHITELIST_PREFS, Context.MODE_PRIVATE)
        packageName in (prefs.getStringSet(WHITELIST_KEY, emptySet()) ?: emptySet())
    } catch (_: Exception) {
        false
    }

    private fun defaultScreenInteractive(): Boolean = try {
        val manager = MonitorApp.instance.getSystemService(Context.POWER_SERVICE) as? PowerManager
        manager?.isInteractive ?: true
    } catch (_: Exception) {
        true
    }

    private companion object {
        const val TAG = "SkipRuleEngine"
        const val WHITELIST_PREFS = "skip_whitelist"
        const val WHITELIST_KEY = "whitelist"

        /** 兜底轮询间隔（毫秒；主驱动为 CONTENT_CHANGED 事件） */
        const val BACKUP_SCAN_INTERVAL_MS = 300L

        /** CONTENT_CHANGED 触发扫描的节流间隔 */
        const val CONTENT_TRIGGER_INTERVAL_MS = 100L

        /** 单次窗口内的扫描次数上限（护栏，防止异常场景空转） */
        const val MAX_SCANS = 80

        /**
         * 点击后确认的复核延时（毫秒）：第一次 400ms 后复核，未通过再等 600ms 复核一次。
         * 广告消失有动画延迟，太快复核会把"正在消失"误判成"没点掉"。
         */
        val VERIFY_DELAYS: List<Long> = listOf(400L, 600L)
    }
}