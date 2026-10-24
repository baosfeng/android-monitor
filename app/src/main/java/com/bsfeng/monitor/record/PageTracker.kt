package com.bsfeng.monitor.record

import java.security.MessageDigest
import java.util.Locale

/**
 * 一次页面切换（指纹档）。
 *
 * 带包名 / 应用名 / 指纹 / 来源 / **可选的页面标题**：页面文字参与指纹计算后即丢弃，
 * 标题是唯一的例外 —— 由用户确认「仅本地保存、不上报」，长度截断到 [PageTitle.MAX_LENGTH]。
 * 不进上报、不参与指纹（隐私口径见 SECURITY.md）。
 */
data class PageEvent(
    val action: String,
    val packageName: String,
    val appName: String?,
    val pageKey: String,
    val source: String,
    val timestamp: Long,
    /** 结算离开的那一页时带停留时长；进入新页（[ACTION_ENTER]）时为 null。 */
    val durationMs: Long?,
    /** 页面标题（≤ [PageTitle.MAX_LENGTH] 字）；采集失败 / 开关关闭时为 null。仅本地，不上报。 */
    val title: String? = null,
) {
    companion object {
        /** 进入一个 App 的第一个页面（换 App 时产生）。 */
        const val ACTION_ENTER = "ENTER"

        /** 同一 App 内页面切换（离开上一页时产生，带停留时长）。 */
        const val ACTION_CHANGED = "CHANGED"

        /** 数据来源：无障碍窗口事件。 */
        const val SOURCE_WINDOW_EVENT = "window_event"

        /** 数据来源：1 秒前台轮询的轻量节点遍历。 */
        const val SOURCE_POLL = "poll"

        /** 数据来源：服务销毁 / 熄屏等强制结算。 */
        const val SOURCE_FLUSH = "flush"
    }
}

/**
 * 页面轨迹状态机 —— 纯逻辑，不碰 Android API，可直接在 JVM 单测里断言。
 *
 * 状态 = 「当前页面指纹 + 进入时间」。指纹由 [fingerprint] 计算，页面文字只作为哈希输入。
 *
 * 事件口径：
 * - 换 App：先为旧页写一条 [PageEvent.ACTION_CHANGED]（带停留时长），再为新 App 首页写一条
 *   [PageEvent.ACTION_ENTER]（标记轨迹起点）；
 * - 同 App 换页：只写一条 [PageEvent.ACTION_CHANGED]，duration = 旧页停留时长；
 * - 离开 App（包名为 null，含桌面 / 系统 UI / 息屏）：结算当前页后清空状态。
 *
 * 抗抖动两条：
 * 1. 窗口事件先到、页面文字要等轮询才读得到时，同一 (包名, Activity) 的指纹从「无文字」
 *    升级到「有文字」只更新指纹、不结算 —— 否则每个页面都会被拆成两条记录；
 * 2. 轮询路径的指纹由页面文字算出，滚动列表 / 秒级变化的内容会让它每轮都抖，
 *    因此轮询发现的新指纹要连续稳定 [pollConfirmMs] 才认定换页（事件路径立即结算）。
 *
 * 自身包名（[selfPackage]）与「离开 App」等价：用户在本 App 里切 Tab 也会产生窗口事件，
 * 若不排除就会记录自己（真机表现为一秒刷多条 PAGE）。过滤放在这一层而不是各个调用点，
 * 是为了让窗口事件 / 轮询 / WINDOWS_CHANGED 三条入口以及将来新增的入口都不会漏。
 */
class PageTracker(
    private val pollConfirmMs: Long = DEFAULT_POLL_CONFIRM_MS,
    /** 自身包名：本 App 内的页面切换不产生 PAGE 记录。 */
    private val selfPackage: String = SELF_PACKAGE,
) {

    private data class PageState(
        val packageName: String,
        val activityClass: String,
        val pageKey: String,
        /** 参与指纹的可见文本条数；用于判定「文字尚未就绪」。 */
        val textCount: Int,
        val appName: String?,
        val source: String,
        val enteredAt: Long,
        /** 该页标题（可能后到：窗口事件先到、轮询才读得到文字）。 */
        val title: String?,
    )

    private var current: PageState? = null

    /** 轮询路径的候选指纹：连续稳定 [pollConfirmMs] 才结算。 */
    private var pendingKey: String? = null
    private var pendingSince: Long = 0L

    /** 当前页面指纹（无页面时为 null），供调试与单测断言。 */
    val currentPageKey: String? get() = current?.pageKey

    /** 当前页面所属包名（无页面时为 null）。 */
    val currentPackage: String? get() = current?.packageName

    /**
     * 喂一次页面观测。
     *
     * @param packageName 当前前台包名；null = 离开 App（桌面 / 系统 UI / 息屏）。
     *   等于 [selfPackage]（本 App）时按「离开外部 App」处理：结算上一页并清空，不产生新页。
     * @param activityClass Activity 类名，来自窗口事件；轮询路径可传 null
     * @param visibleTexts 当前可见文本（参与指纹计算；仅当 [collectTitle] 时用来推断页面标题）
     * @param appName 应用显示名；null 时沿用同包名旧状态的缓存
     * @param source 来源标识，见 [PageEvent.SOURCE_WINDOW_EVENT] 等
     * @param windowTitle 无障碍窗口标题（优先级最高；拿不到传 null）
     * @param collectTitle 是否采集页面标题（对应「记录页面标题」开关，关闭时既不采集也不落库）
     * @return 需要落库的事件（0~2 条），调用方按顺序写入
     */
    fun observe(
        packageName: String?,
        activityClass: String?,
        visibleTexts: List<String>,
        appName: String?,
        source: String,
        timestamp: Long,
        windowTitle: String? = null,
        collectTitle: Boolean = true,
    ): List<PageEvent> {
        val previous = current
        // 自身包名与「离开 App」同义：本 App 内的 Tab/页面切换不产生任何 PAGE 记录，
        // 但要把上一个外部页面的停留时长结算掉（否则那段时间会一直挂着）
        if (packageName == null || packageName == selfPackage) {
            current = null
            pendingKey = null
            return previous?.let { listOf(settle(it, timestamp, source)) } ?: emptyList()
        }

        val activity = activityClass ?: previous?.takeIf { it.packageName == packageName }?.activityClass ?: ""
        val normalized = normalizeVisibleTexts(visibleTexts)
        val pageKey = fingerprint(packageName, activity, normalized)
        val resolvedAppName = appName
            ?: previous?.takeIf { it.packageName == packageName }?.appName
        val title = if (collectTitle) PageTitle.resolve(windowTitle, visibleTexts, resolvedAppName) else null
        val next = PageState(
            packageName = packageName,
            activityClass = activity,
            pageKey = pageKey,
            textCount = normalized.size,
            appName = resolvedAppName,
            source = source,
            enteredAt = timestamp,
            // 同一页标题可能后到：页面未变时沿用上一轮读到的标题
            title = title ?: previous?.takeIf { it.packageName == packageName && it.pageKey == pageKey }?.title,
        )

        if (previous == null) {
            pendingKey = null
            current = next
            return listOf(enter(next, timestamp))
        }
        if (previous.pageKey == pageKey) {
            pendingKey = null
            // 标题后到（窗口事件先到、轮询才读到文字）：只补状态，不算换页
            if (previous.title != next.title) current = next.copy(enteredAt = previous.enteredAt)
            return emptyList()
        }

        val sameApp = previous.packageName == packageName
        val sameActivity = previous.activityClass == activity
        // 文字就绪度变化（事件路径无文字 → 轮询路径读到文字，或反之）不算换页
        if (sameApp && sameActivity && (previous.textCount == 0 || normalized.isEmpty())) {
            pendingKey = null
            current = next.copy(enteredAt = previous.enteredAt, title = next.title ?: previous.title)
            return emptyList()
        }

        // 轮询路径：新指纹要连续稳定 pollConfirmMs 才认定换页（滚动/动态内容会让指纹每秒抖）
        if (source == PageEvent.SOURCE_POLL && pollConfirmMs > 0L) {
            if (pendingKey != pageKey) {
                pendingKey = pageKey
                pendingSince = timestamp
                return emptyList()
            }
            if (timestamp - pendingSince < pollConfirmMs) return emptyList()
        }
        pendingKey = null
        // 换页时刻回填成「首次观测到新指纹」的时刻，duration 才不会被确认窗口吃掉
        val settledAt = if (source == PageEvent.SOURCE_POLL && pendingSince > 0L) pendingSince else timestamp
        pendingSince = 0L
        current = next.copy(enteredAt = settledAt)
        val events = mutableListOf(settle(previous, timestamp, previous.source))
        if (!sameApp) events.add(enter(next, timestamp))
        return events
    }

    /** 强制结算当前页（服务销毁 / 息屏）并清空状态；无页面时返回空列表。 */
    fun flush(timestamp: Long): List<PageEvent> {
        pendingKey = null
        val previous = current ?: return emptyList()
        current = null
        return listOf(settle(previous, timestamp, PageEvent.SOURCE_FLUSH))
    }

    private fun settle(state: PageState, timestamp: Long, source: String): PageEvent = PageEvent(
        action = PageEvent.ACTION_CHANGED,
        packageName = state.packageName,
        appName = state.appName,
        pageKey = state.pageKey,
        source = source,
        timestamp = timestamp,
        durationMs = (timestamp - state.enteredAt).coerceAtLeast(0L),
        title = state.title,
    )

    private fun enter(state: PageState, timestamp: Long): PageEvent = PageEvent(
        action = PageEvent.ACTION_ENTER,
        packageName = state.packageName,
        appName = state.appName,
        pageKey = state.pageKey,
        source = state.source,
        timestamp = timestamp,
        durationMs = null,
        title = state.title,
    )

    companion object {
        /** 本 App 包名（默认值；Service 里会传入实际的 `packageName` 以适配改包名构建）。 */
        const val SELF_PACKAGE = "com.bsfeng.monitor"

        /** 指纹最多取前 20 条可见文本。 */
        const val MAX_TEXTS = 20

        /** 指纹取 SHA-256 的前 8 位十六进制。 */
        const val KEY_LENGTH = 8

        /** 轮询路径换页的默认确认窗口：页面文字每秒可能变，稳定 3 秒才算换页。 */
        const val DEFAULT_POLL_CONFIRM_MS = 3_000L

        /** 字段分隔符：用不可见控制字符，避免包名/文本拼接时产生歧义。 */
        private const val SEPARATOR = "\u0001"

        /**
         * 规整可见文本：去首尾空白、丢空串、去重、排序后取前 [MAX_TEXTS] 条。
         *
         * 排序是关键 —— 同一页面每次遍历得到的文字顺序可能不同（Compose/列表复用时尤其明显），
         * 不排序会让指纹在页面没变的情况下反复变化。
         */
        fun normalizeVisibleTexts(raw: List<String>): List<String> = raw.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .take(MAX_TEXTS)
            .toList()

        /**
         * 页面指纹 = sha256(包名 + Activity + 前 20 条可见文本)[0,8)。
         *
         * 同包名同 Activity、同一批文字（顺序无关）必得同一指纹；文字只在签名内部使用。
         */
        fun fingerprint(packageName: String, activityClass: String?, visibleTexts: List<String>): String {
            val payload = buildString {
                append(packageName)
                append(SEPARATOR)
                append(activityClass ?: "")
                append(SEPARATOR)
                append(normalizeVisibleTexts(visibleTexts).joinToString(SEPARATOR))
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            return digest.take(KEY_LENGTH / 2)
                .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
        }
    }
}

/**
 * 页面标题提取（纯逻辑，可单测）。
 *
 * 优先级：窗口标题 > 顶部可见标题文本 > 首个短文本；取不到就返回 null，由调用方回落到指纹文案。
 * **不参与指纹**：标题是同一页面的展示属性，变与不变都不应产生换页事件。
 *
 * 顶部可见文本按遍历顺序（BFS，大致从上到下）判定：跳过常见操作词与状态栏样式文本，
 * 选第一个 2~[MAX_LENGTH] 字、非纯数字/时间/百分比的文本。截断到 [MAX_LENGTH] 字以内。
 */
object PageTitle {

    /** 标题上限 30 字：超过就截断，避免把整段正文当标题存下来。 */
    const val MAX_LENGTH = 30

    /** 常见操作/导航词：它们是按钮不是标题，命中就跳过（避免「返回」「更多」当标题）。 */
    private val NOISE = setOf(
        "返回", "更多", "关闭", "取消", "确定", "完成", "搜索", "分享", "编辑", "设置", "菜单", "刷新",
    )

    /** 状态栏样式文本：时间 / 百分比 / 纯数字，均不是标题。 */
    private fun isStatusLike(text: String): Boolean = text.all { ch -> ch.isDigit() || ch in " :%.-+" }

    /**
     * 提取标题：**窗口标题有信息时优先，否则看顶部可见文本**，最后回落到窗口标题（通常是应用名）。
     *
     * 窗口标题在多数 App 里就等于应用名（「微信」「抖音」），直接用它会把页面名吞掉；
     * 因此 [appName] 与窗口标题相同就跳过它，改看可见文本，只有文本也取不到时才把应用名当标题
     * （由 PageHandler 决定只显示应用名，不拼「抖音 · 抖音」）。
     */
    fun resolve(windowTitle: String?, visibleTexts: List<String>, appName: String? = null): String? {
        val win = clean(windowTitle)
        val texts = visibleTexts.mapNotNull { raw ->
            collapseWhitespace(raw).takeIf { it.isNotEmpty() }
        }
        // 应用名本身不是页面标题
        val titles = if (appName.isNullOrBlank()) texts else texts.filter { it != appName }
        if (win != null && win != appName) return win
        // 顶部可见标题：遍历顺序靠前、长度合适、不是操作词 / 状态栏文本
        titles.firstOrNull { text ->
            text.length in 2..MAX_LENGTH && text !in NOISE && !isStatusLike(text)
        }?.let { return it }
        // 兜底：首个非空短文本（可能是 1 字标题）
        titles.firstOrNull { it.length <= MAX_LENGTH }?.let { return it }
        // 再兜底：窗口标题（通常是应用名），否则首个文本截断到上限
        return win ?: titles.firstOrNull()?.take(MAX_LENGTH)?.trim()
    }

    private fun clean(raw: String?): String? {
        if (raw == null) return null
        val text = collapseWhitespace(raw)
        if (text.isEmpty()) return null
        return if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH).trim()
    }

    /** 折叠空白（连续空格 / 换行压成一个空格）：不用正则，避免转义与性能坑。 */
    private fun collapseWhitespace(raw: String): String =
        raw.trim().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")
}
