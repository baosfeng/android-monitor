package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleEngine.kt；职责：开屏跳过的窗口生命周期与扫描调度（触发/顺延/复核/上报）。
// 窗口状态与顺延判定见 SkipWatchWindow，规则求值与动作执行见 SkipRuleMatcher，执行闸门见 SkipRuleGate，
// 点击后确认见 SkipClickVerifier，白名单与屏幕状态见 SkipEngineEnvironment。

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.util.ActivityNames
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
 * ## 行为边界
 * - **弱网顺延**：窗口到点时页面仍在渲染就顺延（有次数与硬上限），规则 `matchTime` 同步放宽已顺延的
 *   额度 —— 判定细节与理由见 [SkipWatchWindow.tryExtend]。
 * - **点击后确认**：动作执行后用同一选择器复核，广告没消失标为"未确认"（见 [SkipClickVerifier]）。
 * - **防误触**：只执行规则命中的节点动作，没有"坐标连点"或"返回键"兜底；熄屏不点击；白名单 App
 *   完全不处理；`matchTime`/`actionMaximum`/`resetMatch`/`actionCd`/`excludeMatches` 各管一道闸门
 *   （见 [SkipRuleGate]）。业界调研结论见 `docs/开屏跳过/业界调研与改进方案.md` §4 P0-2。
 * - **与旧实现的差异**：`matchDelay` 改为"到期标记"（见 [SkipRuleGate]），不再用 Handler 取消/重投，
 *   避免被 CONTENT_CHANGED 风暴无限推迟导致规则永不执行。
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

    /** 匹配窗口状态（截止时间、扫描计数、顺延额度、内容变化时刻） */
    private val window = SkipWatchWindow()

    /** 规则求值 + 执行闸门（计数/冷却/延迟状态都在里面） */
    private val gate = SkipRuleGate()
    private val matcher = SkipRuleMatcher(ruleManager, gate)
    private val environment = SkipEngineEnvironment()
    private val verifier = SkipClickVerifier(
        rootNodeProvider = { rootNodeProvider?.invoke() },
        verifyDelaysMs = { verifyDelaysMs },
        onFinished = { finishSkip(it) },
    )

    /** 时钟与屏幕状态提供器（测试注入） */
    internal var currentTimeMillis: () -> Long = { System.currentTimeMillis() }
    internal var screenInteractive: () -> Boolean = { environment.defaultScreenInteractive() }

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
        if (environment.isWhitelisted(packageName)) {
            DebugConfig.log("SkipEngine: 白名单跳过 " + packageName)
            return
        }
        if (!screenInteractive()) {
            DebugConfig.log("SkipEngine: 屏幕未点亮，忽略窗口变化 " + packageName)
            return
        }
        val now = currentTimeMillis()
        val appSwitched = packageName != currentPackage
        // Toast/弹窗/输入法也会发 WINDOW_STATE_CHANGED，且 className 是 widget 类名（真机 30 份快照里
        // 12 份如此）。直接采信会冲掉"当前 Activity"，让写了 activityIds 的规则静默失效 → 交给
        // ActivityNames 判定：同 App 内保留已知 Activity，跨 App 清空。
        val activityClass = ActivityNames.resolveActivityClass(currentClassName, className, appSwitched)
        if (appSwitched) {
            resetAppState(packageName)
            currentPackage = packageName
            window.markEnter(now)
            DebugConfig.log("SkipEngine: 进入 App " + packageName + " / " + activityClass)
        } else if (activityClass != currentClassName) {
            gate.resetActivityScoped(ruleManager.findRulesFor(packageName))
            DebugConfig.log("SkipEngine: Activity 变化 " + activityClass)
        }
        currentClassName = activityClass
        startWatching(now)
    }

    /**
     * 窗口内容变化（事件驱动扫描）。
     *
     * 仅处理正在监控的 App；100ms 节流以吸收 CONTENT_CHANGED 风暴。
     */
    fun onContentChanged(packageName: String) {
        val pkg = currentPackage ?: return
        if (!window.watching || packageName != pkg) return
        if (!screenInteractive()) return
        if (!window.noteContentChanged(currentTimeMillis(), CONTENT_TRIGGER_INTERVAL_MS)) return
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
        window.stop()
        handler.removeCallbacks(scanTask)
        verifier.clear()
    }

    /** 当前是否处于匹配窗口内 */
    fun isWatching(): Boolean = window.watching

    /** 本次（或最近一次）窗口已顺延的次数（诊断/单测） */
    internal fun extensionCount(): Int = window.extensionCount

    /** 本次（或最近一次）窗口累计顺延时长（毫秒，诊断/单测） */
    internal fun extensionGrantedMs(): Long = window.extensionGrantedMs

    /** 本次（或最近一次）窗口的截止时刻（绝对毫秒，诊断/单测） */
    internal fun windowDeadlineMs(): Long = window.deadlineMs

    /** 正在监控的包名 */
    fun watchingPackage(): String? = if (window.watching) currentPackage else null

    /** 重置某个 App 的状态（进入本 App 或用户手动重置时调用） */
    fun resetAppState(packageName: String?) {
        gate.clear()
        stopWatching()
        if (packageName != null && packageName != currentPackage) {
            currentPackage = null
        }
    }

    // ==================== 匹配窗口 ====================

    private fun startWatching(now: Long) {
        val windowMs = watchWindowProvider()
        window.start(now, windowMs)
        handler.removeCallbacks(scanTask)
        handler.post(scanTask)
        val policy = windowExtendPolicy
        val extendHint = if (policy.enabled) {
            "（弱网下页面仍在渲染时最多顺延 " + policy.maxExtensions + " 次 × " + policy.stepMs + "ms）"
        } else {
            "（弱网顺延已关闭）"
        }
        DebugConfig.log("SkipEngine: 开启匹配窗口 " + windowMs + "ms" + extendHint + ", 当前 " + currentPackage +
            " / " + currentClassName + ", now=" + now + ", 截止=" + window.deadlineMs)
    }

    private fun schedule(delayMs: Long) {
        if (!window.watching) return
        handler.removeCallbacks(scanTask)
        handler.postDelayed(scanTask, delayMs)
    }

    // ==================== 扫描 ====================

    private fun performScan() {
        val pkg = currentPackage ?: return
        if (!window.watching) return
        val now = currentTimeMillis()
        // 到点判定要确认引擎一直在连续扫描：先取"距上次扫描"的间隔，再刷新（见 [SkipWatchWindow.tryExtend]）
        val sinceLastScanMs = window.beginScan(now)
        if (!screenInteractive()) {
            DebugConfig.log("SkipEngine: 屏幕未点亮，停止扫描 " + pkg)
            stopWatching()
            return
        }
        // 点击后确认优先：复核期间不执行任何规则（否则同一条广告可能被点多次）
        verifier.pending?.let { pv ->
            if (now < pv.dueAt) {
                schedule(pv.dueAt - now)
                return
            }
            val nextDelay = verifier.verify(pv, now)
            if (nextDelay != null) schedule(nextDelay)
            return
        }
        // 护栏优先：扫描次数用尽直接结束（密集 CONTENT_CHANGED 场景下先于顺延上限生效）
        if (window.scanCount >= MAX_SCANS) {
            DebugConfig.log("SkipEngine: 匹配窗口结束 " + pkg + " (扫描次数到达护栏 " + MAX_SCANS + ")")
            stopWatching()
            return
        }
        if (now >= window.deadlineMs && !window.tryExtend(pkg, now, sinceLastScanMs, windowExtendPolicy)) {
            DebugConfig.log("SkipEngine: 匹配窗口结束 " + pkg + " (扫描 " + window.scanCount + " 次，顺延 " +
                window.extensionCount + " 次)")
            stopWatching()
            return
        }
        window.countScan()
        val root = rootNodeProvider?.invoke()
        if (root == null) {
            DebugConfig.log("SkipEngine: 根节点未就绪，稍后重试 " + pkg)
            schedule(BACKUP_SCAN_INTERVAL_MS)
            return
        }
        val hit = try {
            matcher.match(
                pkg, root, now, currentClassName,
                window.enterTimeMs, window.extensionGrantedMs,
            )
        } finally {
            root.recycle()
        }
        if (hit != null) {
            val delays = verifier.start(hit, now)
            if (delays != null) schedule(delays)
            return
        }
        schedule(BACKUP_SCAN_INTERVAL_MS)
    }

    /** 上报跳过结果并结束窗口 */
    private fun finishSkip(result: SkipResult) {
        verifier.finish(result, TAG, onSkipSuccess)
        stopWatching()
    }

    private companion object {
        const val TAG = "SkipRuleEngine"

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
