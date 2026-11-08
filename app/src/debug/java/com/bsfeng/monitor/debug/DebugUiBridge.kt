package com.bsfeng.monitor.debug

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.service.MonitorAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `ui` 命令族的执行层：读无障碍节点树、点、滚、手势、全局动作。
 *
 * **为什么所有无障碍调用都包在 [onMain] 里**：`rootInActiveWindow` / `getWindows()` / `performAction`
 * 都只能在主线程调用，广播 receiver 跑在 IO 协程里，直接调会抛
 * "Calling from main thread" / 拿到过期快照。这里统一 post 到主线程再回传结果。
 *
 * **失败一律带 reason**：无障碍关着、根窗口为空、选择器没命中、系统拒绝动作、手势被拒都是
 * 可预期的返回，不是异常；异常才走 [Outcome.Fail] 的 `error`，并**不吞掉**堆栈（记 logcat）。
 *
 * 隐私：dump 的 JSON 只写 App 私有目录 `files/dsh-ui/`，日志里只有路径/数量/包名。
 */
internal object DebugUiBridge {

    private const val TAG = "DebugUi"

    /** 节点上限：Compose 页面通常几百节点，1200 足够覆盖整屏又不会把 logcat 回执拖垮。 */
    private const val MAX_NODES = 1200

    /** 深度上限：异常布局（无限嵌套的自定义 View）会让递归吃满栈。 */
    private const val MAX_DEPTH = 30

    /**
     * `files/dsh-ui/` 的份数上限。
     *
     * dump 内容是**屏幕原文**（页面上的金额、聊天、通知文案都在 nodes 的 text 里）。
     * 不设上限等于把每次取证的屏幕副本长期留在私有目录，且它会随取证次数无限膨胀。
     * 取 20：一次取证常连发多次 dump 做前后对照，留够翻一轮的量即可。
     */
    private const val MAX_DUMP_FILES = 20

    /** 手势参数：点击/长按/滑动时长与滑动跨屏比例。 */
    private const val TAP_MS = 60L
    private const val LONG_PRESS_MS = 800L
    private const val SCROLL_MS = 300L
    private const val SWIPE_SPAN = 0.24f

    /** 命令结果：成功带 data，失败带可机读的 reason。 */
    sealed interface Outcome {
        data class Ok(val data: JSONObject) : Outcome

        data class Fail(val reason: String, val data: JSONObject) : Outcome
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 执行一条 ui 动作。无障碍服务未连接时直接 `accessibility_off`，不抛异常。 */
    suspend fun run(context: Context, action: UiAction): Outcome {
        val service = MonitorAccessibilityService.instance
        if (service == null || !MonitorAccessibilityService.isRunning) {
            return Outcome.Fail(
                "accessibility_off",
                JSONObject()
                    .put("message", "无障碍服务未连接，界面通道不可用")
                    .put("hint", "在系统「设置 → 无障碍 → 已下载的应用」里重新打开本服务；" +
                        "不要用 adb force-stop，那会摘掉无障碍授权"),
            )
        }
        return try {
            when (action) {
                is UiAction.Dump -> dump(context, service, action.tag)
                is UiAction.Audit -> audit(context, service, action.tag)
                is UiAction.Click -> tap(service, action.selector, longPress = false)
                is UiAction.LongClick -> tap(service, action.selector, longPress = true)
                is UiAction.Scroll -> scroll(service, action.dir)
                is UiAction.Swipe -> swipe(service, action)
                is UiAction.Back -> global(service, AccessibilityService.GLOBAL_ACTION_BACK, "back")
                is UiAction.Home -> global(service, AccessibilityService.GLOBAL_ACTION_HOME, "home")
                is UiAction.Recents -> global(service, AccessibilityService.GLOBAL_ACTION_RECENTS, "recents")
                is UiAction.Wait -> {
                    // 纯延时放 IO 线程：占主线程会让界面动画冻结，等完再回结果
                    delay(action.ms.toLong())
                    Outcome.Ok(JSONObject().put("op", action.op).put("waitedMs", action.ms))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ui " + action.op + " 执行异常", t)
            Outcome.Fail(
                "error",
                JSONObject()
                    .put("op", action.op)
                    .put("message", t.javaClass.simpleName + ": " + (t.message ?: "")),
            )
        }
    }

    // ────────────────────────────── dump ──────────────────────────────

    private suspend fun dump(context: Context, service: MonitorAccessibilityService, tag: String?): Outcome {
        val snapshot = onMain { snapshotOf(service) }
        if (snapshot.nodes.isEmpty()) return noRoot()
        val file = writeDump(context, UiTag.sanitize(tag), snapshot)
        return Outcome.Ok(
            JSONObject()
                .put("op", "dump")
                .put("path", relativePath(context, file))
                .put("absolutePath", file.absolutePath)
                .put("nodes", snapshot.nodes.size)
                .put("windows", snapshot.windows.size)
                .put("pkg", snapshot.pkg ?: "?")
                .put("truncatedNodes", snapshot.truncated),
        )
    }

    /**
     * 只保留最近 [MAX_DUMP_FILES] 份 dump / audit。
     *
     * 屏幕原文的落盘副本没有「用完即删」的自然时机（agent 取证后才去读），所以用份数上限兜住：
     * 超过就按修改时间删最旧的。目录不可读时静默跳过 —— 清理失败不该让命令本身失败。
     *
     * 刻意**不**复用 `skip/SnapshotRetention`：那份策略管的是用户手动触发的页面快照
     * （一次一两份、保留 10 份），dsh-ui 是取证时连发的多次 dump 对照（保留 20 份）。
     * 两个上限会各自演进，抽成一处反而要给它加参数、把两个语义混成「份数」一个概念。
     */
    private fun pruneDumps(dir: File) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
            if (files.size < MAX_DUMP_FILES) return
            files.sortedByDescending { it.lastModified() }
                .drop(MAX_DUMP_FILES - 1)
                .forEach { it.delete() }
        }.onFailure { Log.w(TAG, "清理旧 dump 失败", it) }
    }

    /** 相对 `/data/data/<pkg>/` 的路径：logcat 里短、且不暴露绝对私有路径。 */
    private fun relativePath(context: Context, file: File): String =
        "files" + file.absolutePath.removePrefix(context.filesDir.absolutePath)

    /** `files/dsh-ui/`：dump 与 audit 共用同一个私有目录（已 gitignore 之外，也不外置）。 */
    private fun uiDir(context: Context): File {
        val dir = File(context.filesDir, "dsh-ui")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("无法创建 dump 目录：" + dir.absolutePath)
        }
        return dir
    }

    private fun writeDump(context: Context, tag: String, snapshot: Snapshot): File {
        val dir = uiDir(context)
        // 落盘前先按份数裁剪：dump 里是屏幕原文（账目、聊天、通知），无限囤在私有目录
        // 等于长期留一份屏幕副本。口径与 SnapshotExporter 一致（保留最近 20 份）。
        pruneDumps(dir)
        val windows = JSONArray()
        snapshot.windows.forEach { windows.put(it.toJson()) }
        val nodes = JSONArray()
        snapshot.nodes.forEach { nodes.put(it.toJson()) }
        val json = JSONObject()
            .put("tag", tag)
            .put("generatedAt", System.currentTimeMillis())
            .put("pkg", snapshot.pkg ?: "")
            .put("nodeCount", snapshot.nodes.size)
            .put("truncatedNodes", snapshot.truncated)
            .put("windows", windows)
            .put("nodes", nodes)
        val file = File(dir, tag + ".json")
        // 单行紧凑输出：agent 直接解析，人要看也能 jq 格式化
        file.writeText(json.toString(), Charsets.UTF_8)
        return file
    }

    // ──────────────────────────── audit ────────────────────────────

    /**
     * 无障碍审计：扫当前活动窗口，落 `files/dsh-ui/a11y-<tag>.json`。
     *
     * **有缺口就是 Fail**：`OK` 只表示「命令跑完了」，而这条命令的语义是「这屏能不能放行」，
     * 所以 gapCount > 0 时回 `reason=a11y_gaps`（ok=false → CLI 退出码 1），脚本才能拦回归。
     * 判定全在 [UiAudit] 里（纯函数），这里只负责读树、换算密度、落盘。
     */
    private suspend fun audit(context: Context, service: MonitorAccessibilityService, tag: String?): Outcome {
        val density = context.resources.displayMetrics.density
        val snapshot = onMain { snapshotOf(service) }
        if (snapshot.nodes.isEmpty()) return noRoot()
        val report = UiAudit.run(snapshot.activeNodes(), density, snapshot.pkg.orEmpty())
        val file = writeAudit(context, UiTag.sanitize(tag), report)
        val data = JSONObject()
            .put("op", "audit")
            .put("path", relativePath(context, file))
            .put("absolutePath", file.absolutePath)
            .put("pkg", report.pkg.ifEmpty { "?" })
            .put("nodes", report.nodeCount)
            .put("gaps", report.gapCount)
            .put("clickable", report.clickableCount)
            .put("info", report.infoJson())
            .put("top", JSONArray(report.headSummaries()))
        return if (report.ok) {
            Outcome.Ok(data)
        } else {
            Outcome.Fail(
                "a11y_gaps",
                data.put(
                    "hint",
                    "每条缺口给控件加 text 或 contentDescription（图标按钮用 Modifier.semantics / Icon 的 " +
                        "contentDescription）；触达尺寸 ≥48dp（iconButton 默认只有 40dp，用 minimumInteractiveComponentSize 兜住）。" +
                        "完整报告见 path",
                ),
            )
        }
    }

    private fun writeAudit(context: Context, tag: String, report: UiAuditReport): File {
        val dir = uiDir(context)
        pruneDumps(dir)
        val file = File(dir, "a11y-" + tag + ".json")
        file.writeText(report.toJson(System.currentTimeMillis()).toString(), Charsets.UTF_8)
        return file
    }

    // ───────────────────────── 点 / 长按 ─────────────────────────

    private suspend fun tap(service: MonitorAccessibilityService, selector: UiSelector, longPress: Boolean): Outcome =
        onMain {
            val snapshot = snapshotOf(service)
            if (snapshot.nodes.isEmpty()) return@onMain noRoot()
            val match = UiTree.findFirst(snapshot.activeNodes(), selector)
                ?: return@onMain notFound(selector, snapshot)
            val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            val ancestor = UiTree.clickableAncestor(snapshot.nodes, match.index)
            val performed = ancestor != null && snapshot.refs[ancestor.index].performAction(action)
            if (performed) {
                return@onMain Outcome.Ok(describe("action", ancestor!!, longPress))
            }
            // 没有可点祖先 / performAction 被拒：按 bounds 中心做手势，别静默当成点过了
            val queued = dispatchGesture(
                service,
                tapPath(match.centerX, match.centerY),
                if (longPress) LONG_PRESS_MS else TAP_MS,
            )
            val data = describe(if (queued) "gesture" else "none", match, longPress)
                .put("clickableAncestor", ancestor != null)
            if (queued) Outcome.Ok(data) else Outcome.Fail("gesture_rejected", data)
        }

    // ────────────────────────── 滚动 ──────────────────────────

    private suspend fun scroll(service: MonitorAccessibilityService, dir: ScrollDir): Outcome = onMain {
        val snapshot = snapshotOf(service)
        if (snapshot.nodes.isEmpty()) return@onMain noRoot()
        // 方向对得上、且贯穿屏幕中部的容器才用：页顶的横向横条不该吃掉竖向滚动（真机实测缺陷）
        val metrics = service.resources.displayMetrics
        val active = snapshot.activeNodes()
        val target = UiTree.firstScrollable(
            active,
            screenHeight = metrics.heightPixels,
            preferVertical = dir == ScrollDir.UP || dir == ScrollDir.DOWN,
        )
        if (target != null) {
            val action = if (dir.forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (snapshot.refs[target.index].performAction(action)) {
                return@onMain Outcome.Ok(scrollOk(target, dir).put("strategy", "action"))
            }
        }
        // 没有同方向容器（Compose 的 LazyColumn 常只暴露在语义层 / 屏上只有一条横向横条）→ 手势兜底：
        // 落点全是当前内容，方向永远是对的；硬滚反方向的容器才是那个「看着滚了、其实没动」的坑
        val queued = dispatchGesture(
            service,
            scrollPath(metrics.widthPixels, metrics.heightPixels, dir),
            SCROLL_MS,
        )
        val data = scrollOk(null, dir)
            .put("strategy", if (queued) "gesture" else "none")
            .put("scrollableMismatch", UiTree.anyScrollable(active))
        if (queued) Outcome.Ok(data) else Outcome.Fail("gesture_rejected", data)
    }

    /**
     * 滚动回执。`scrollableFound/scrollableVertical` 说明这次是「命中容器」还是「手势兜底」——
     * 验证方据此判断「页面其实没得滚」而不是「命令没生效」。
     */
    private fun scrollOk(target: UiNode?, dir: ScrollDir): JSONObject = JSONObject()
        .put("op", "scroll")
        .put("direction", dir.value)
        .put("scrollableFound", target != null)
        .put("scrollableVertical", target != null && target.height >= target.width)
        .put("index", target?.index ?: -1)
        .put("boundsInScreen", target?.boundsJson() ?: JSONArray().put(0).put(0).put(0).put(0))

    // ────────────────────────── 滑动 ──────────────────────────

    private suspend fun swipe(service: MonitorAccessibilityService, action: UiAction.Swipe): Outcome = onMain {
        val path = Path().apply {
            moveTo(action.x1.toFloat(), action.y1.toFloat())
            lineTo(action.x2.toFloat(), action.y2.toFloat())
        }
        val queued = dispatchGesture(service, path, action.ms.toLong())
        val data = JSONObject()
            .put("op", action.op)
            .put("from", JSONArray().put(action.x1).put(action.y1))
            .put("to", JSONArray().put(action.x2).put(action.y2))
            .put("ms", action.ms)
            .put("strategy", if (queued) "gesture" else "none")
        if (queued) Outcome.Ok(data) else Outcome.Fail("gesture_rejected", data)
    }

    // ────────────────────────── 全局动作 ──────────────────────────

    private suspend fun global(service: MonitorAccessibilityService, actionId: Int, name: String): Outcome = onMain {
        val ok = runCatching { service.performGlobalAction(actionId) }.getOrDefault(false)
        if (ok) {
            Outcome.Ok(JSONObject().put("op", name).put("action", name))
        } else {
            Outcome.Fail(
                "action_rejected",
                JSONObject()
                    .put("op", name)
                    .put("message", "系统拒绝全局动作 " + name)
                    .put("hint", "锁屏 / 无障碍服务刚重连 / 手势导航被占用时会出现；先 ui dump 确认当前屏"),
            )
        }
    }

    // ────────────────────────── 快照 ──────────────────────────

    /** 一次读树的完整结果：纯模型 + 同一序号的真实节点（动作要在同一主线程事务里用）。 */
    private class Snapshot(
        val nodes: List<UiNode>,
        val refs: List<AccessibilityNodeInfo>,
        val windows: List<WindowMeta>,
        val activeWindow: Int,
        val pkg: String?,
        val truncated: Boolean,
    ) {
        /** 当前活动窗口的节点：动作只在这棵子树里找，避免点到后台窗口。 */
        fun activeNodes(): List<UiNode> = nodes.filter { it.window == activeWindow }
    }

    private data class WindowMeta(
        val index: Int,
        val type: Int,
        val layer: Int,
        val active: Boolean,
        val focused: Boolean,
        val pkg: String,
        val nodeCount: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("index", index)
            .put("type", type)
            .put("typeName", typeName(type))
            .put("layer", layer)
            .put("active", active)
            .put("focused", focused)
            .put("pkg", pkg)
            .put("nodes", nodeCount)

        private fun typeName(value: Int): String = when (value) {
            android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
            else -> "UNKNOWN"
        }
    }

    /** 节点收集器：把主线程上的节点树压成纯模型，之后所有查询都不再碰 Android 对象。 */
    private class Recorder {
        val nodes = ArrayList<UiNode>()
        val refs = ArrayList<AccessibilityNodeInfo>()
        var truncated = false
        var window = 0

        fun walk(node: AccessibilityNodeInfo, parent: Int, depth: Int) {
            if (nodes.size >= MAX_NODES) {
                truncated = true
                return
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val index = nodes.size
            nodes.add(
                UiNode(
                    index = index,
                    parent = parent,
                    depth = depth,
                    window = window,
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty().substringAfterLast('/'),
                    text = node.text?.toString().orEmpty(),
                    desc = node.contentDescription?.toString().orEmpty(),
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    clickable = node.isClickable,
                    focusable = node.isFocusable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                    selected = node.isSelected,
                    childCount = node.childCount,
                ),
            )
            refs.add(node)
            if (depth >= MAX_DEPTH) {
                // 只截断有子节点的分支：叶子节点到此为止是正常形态，不算「树被砍」
                if (node.childCount > 0) truncated = true
                return
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                walk(child, index, depth + 1)
            }
        }
    }

    /**
     * 读根窗口 + `getWindows()` 里的全部交互窗口。
     *
     * 只看 `rootInActiveWindow` 会漏掉输入法/系统弹窗（它们各占一个窗口）；而 `windows` 为空
     * （ROM 不给窗口列表）时退回根窗口，保证 dump 至少不是空。
     */
    private fun snapshotOf(service: MonitorAccessibilityService): Snapshot {
        val recorder = Recorder()
        val metas = ArrayList<WindowMeta>()
        val windowInfos = runCatching { service.windows }.getOrNull().orEmpty()
        if (windowInfos.isEmpty()) {
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
            if (root != null) {
                recorder.window = 0
                recorder.walk(root, -1, 0)
                metas.add(
                    WindowMeta(
                        index = 0,
                        type = android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
                        layer = 0,
                        active = true,
                        focused = true,
                        pkg = root.packageName?.toString().orEmpty(),
                        nodeCount = recorder.nodes.size,
                    ),
                )
            }
        } else {
            windowInfos.forEachIndexed { index, info ->
                val root = runCatching { info.root }.getOrNull() ?: return@forEachIndexed
                val start = recorder.nodes.size
                recorder.window = index
                recorder.walk(root, -1, 0)
                metas.add(
                    WindowMeta(
                        index = index,
                        type = info.type,
                        layer = info.layer,
                        active = info.isActive,
                        focused = info.isFocused,
                        pkg = root.packageName?.toString().orEmpty(),
                        nodeCount = recorder.nodes.size - start,
                    ),
                )
            }
        }
        val active = windowInfos.indexOfFirst { it.isActive }
        val activeIndex = if (active >= 0) active else 0
        val pkg = metas.getOrNull(activeIndex)?.pkg ?: service.rootInActiveWindow?.packageName?.toString()
        return Snapshot(
            nodes = recorder.nodes,
            refs = recorder.refs,
            windows = metas,
            activeWindow = activeIndex,
            pkg = pkg,
            truncated = recorder.truncated,
        )
    }

    // ────────────────────────── 手势 ──────────────────────────

    private fun dispatchGesture(service: MonitorAccessibilityService, path: Path, durationMs: Long): Boolean {
        val safeDuration = durationMs.coerceAtLeast(1L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    /** 单点点击：官方文档的写法就是只 `moveTo`（零长度描边会被系统当点按）。 */
    private fun tapPath(x: Int, y: Int): Path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    /** 手势滚动兜底：方向按「内容前进」算，与 [ScrollDir.forward] 同一套语义。 */
    private fun scrollPath(width: Int, height: Int, dir: ScrollDir): Path {
        val cx = width / 2
        val cy = height / 2
        val dx = (width * SWIPE_SPAN).toInt()
        val dy = (height * SWIPE_SPAN).toInt()
        return when (dir) {
            ScrollDir.DOWN -> Path().apply {
                moveTo(cx.toFloat(), (cy + dy).toFloat())
                lineTo(cx.toFloat(), (cy - dy).toFloat())
            }

            ScrollDir.UP -> Path().apply {
                moveTo(cx.toFloat(), (cy - dy).toFloat())
                lineTo(cx.toFloat(), (cy + dy).toFloat())
            }

            ScrollDir.RIGHT -> Path().apply {
                moveTo((cx + dx).toFloat(), cy.toFloat())
                lineTo((cx - dx).toFloat(), cy.toFloat())
            }

            ScrollDir.LEFT -> Path().apply {
                moveTo((cx - dx).toFloat(), cy.toFloat())
                lineTo((cx + dx).toFloat(), cy.toFloat())
            }
        }
    }

    // ────────────────────────── 结果封装 ──────────────────────────

    private fun describe(strategy: String, node: UiNode, longPress: Boolean): JSONObject = JSONObject()
        .put("op", if (longPress) "longclick" else "click")
        .put("strategy", strategy)
        .put("index", node.index)
        .put("window", node.window)
        .put("depth", node.depth)
        .put("className", node.className)
        .put("viewIdResourceName", node.viewId)
        .put("text", UiText.truncate(node.text))
        .put("contentDescription", UiText.truncate(node.desc))
        .put("boundsInScreen", node.boundsJson())
        .put("clickable", node.clickable)

    private fun notFound(selector: UiSelector, snapshot: Snapshot): Outcome.Fail = Outcome.Fail(
        "not_found",
        JSONObject()
            .put("selector", selector.describe())
            .put("window", snapshot.activeWindow)
            .put("nodes", snapshot.activeNodes().size)
            .put("hint", "先 scripts/debug-cli.sh ui dump 看当前屏：文案可能被拆成多段语义节点，改用 --es id 或 --es bounds"),
    )

    private fun noRoot(): Outcome.Fail = Outcome.Fail(
        "no_root",
        JSONObject()
            .put("message", "rootInActiveWindow 与 windows 都拿不到节点")
            .put("hint", "锁屏 / 前台窗口不给节点（微信支付页等）/ 无障碍服务刚重连：解锁屏幕后重试一次"),
    )

    // ────────────────────────── 主线程桥 ──────────────────────────

    /**
     * 把一段无障碍调用 post 到主线程并等待结果。
     *
     * 不用 `Dispatchers.Main`：这条通道是排障工具，不能因为协程库的主线程调度器没配置好就整条失效；
     * 直接 post 到 main Looper，语义与「广播里 post 到主线程」完全一致。
     */
    private suspend fun <T> onMain(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val posted = mainHandler.post {
            try {
                val value = block()
                if (continuation.isActive) continuation.resume(value)
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }
        if (!posted && continuation.isActive) {
            continuation.resumeWithException(IllegalStateException("主线程 Handler 拒绝投递"))
        }
    }
}
