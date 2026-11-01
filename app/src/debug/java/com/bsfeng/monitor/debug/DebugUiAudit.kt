package com.bsfeng.monitor.debug

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 无障碍运行时审计：把「这屏有哪些控件用户读不到 / 点不准」变成一条命令的输出。
 *
 * 为什么要有：静态检查只能看源码里的用法，读屏实际拿到的是**语义树** —— Compose 的图标按钮、
 * 自绘 Canvas、被拆碎的文案节点在树上都可能没有可读名。站在任意一屏跑一次审计，才知道真实缺口。
 *
 * 判定只看两类硬缺口（其余只统计，不阻断）：
 * - [REASON_NO_LABEL]：可点或可聚焦，自己与**整棵子树**都没有 text / contentDescription —— 读屏只会念「按钮」；
 * - [REASON_SMALL_TARGET]：可点节点短边 < [MIN_TARGET_DP] dp —— 低于 Material / WCAG 2.2 AA 的 48dp 触达下限。
 *
 * **为什么可读名要往子树里找**：真机实测（概览页）14 个可点节点全是 `android.view.View` 且自身
 * text/desc 为空，文案挂在子节点上（Compose 的 `clickable` 会 mergeDescendants，读屏念的是合并后的
 * 子节点文案）。只判自身会把一整屏正常控件全报成缺口，工具就没人看了。
 * 子树里**嵌套的可交互节点不算**——它是独立的读屏目标，名字不借给父节点。
 *
 * 一个节点同时犯两条时**各出一行**：每行 `reason` 是单一词元，脚本能按 reason 过滤统计。
 *
 * 尺寸判定的两个例外（都会计数进 `info`，不是静默丢弃）：
 * - 0 尺寸 → 多为未布局的语义壳，判尺寸没有意义；
 * - 被可滚祖先裁掉半截 → `boundsInScreen` 是可见矩形，不是真实触达高度。真机实测：概览页底部
 *   「今日行程」卡片被视口截在 91px（30.3dp），整页啥都没动却报缺口——列表滚到哪都会踩这个，必须排除。
 */
object UiAudit {

    const val REASON_NO_LABEL = "no_label"
    const val REASON_SMALL_TARGET = "small_target"

    /**
     * 触达目标的最小短边（dp）= 设计 token `tap-min` 的 44px（WCAG 2.1 AA 的下限）。
     *
     * 曾经写 48（Material 默认值），与项目 token 打架：全站按钮/行都按 `t.tapMin` 做 44dp，
     * 拿 48 判就会把「照规范做的控件」一律报成缺口（真机实测额度页「保存并查询」44.6dp 被误报）。
     * 判定线必须与 token 同源，否则审计结果没人敢信。
     */
    const val MIN_TARGET_DP = 44.0

    /** RESULT 行里带几条可读缺口摘要：够定位，又不会把 logcat 一行撑爆。 */
    const val HEAD_LIMIT = 5

    /**
     * 扫一遍节点。纯函数：密度换算与判定都不碰 Android 类型，可纯 JVM 断言。
     *
     * @param density `context.resources.displayMetrics.density`（每 dp 多少像素）
     */
    fun run(nodes: List<UiNode>, density: Float, pkg: String = ""): UiAuditReport {
        val ratio = if (density > 0f) density.toDouble() else 1.0
        val children = nodes.filter { it.parent >= 0 }.groupBy { it.parent }
        val byIndex = nodes.associateBy { it.index }
        val gaps = ArrayList<UiGap>()
        var clickableCount = 0
        var textWithoutId = 0
        var labelFromChildren = 0
        var clippedSkipped = 0
        nodes.forEach { node ->
            if (node.clickable) clickableCount++
            if (node.clickable && node.text.isNotBlank() && node.viewId.isEmpty()) textWithoutId++
            val dp = shortSideDp(node, ratio)
            if ((node.clickable || node.focusable) && node.text.isBlank() && node.desc.isBlank()) {
                if (hasLabelInSubtree(node.index, children)) {
                    labelFromChildren++
                } else {
                    gaps.add(node.toGap(REASON_NO_LABEL, dp))
                }
            }
            if (node.clickable && node.width > 0 && node.height > 0) {
                if (clippedByScrollable(node, byIndex)) {
                    clippedSkipped++
                } else if (dp < MIN_TARGET_DP) {
                    gaps.add(node.toGap(REASON_SMALL_TARGET, dp))
                }
            }
        }
        return UiAuditReport(
            pkg = pkg,
            nodeCount = nodes.size,
            clickableCount = clickableCount,
            gaps = gaps,
            textWithoutId = textWithoutId,
            labelFromChildren = labelFromChildren,
            clippedSkipped = clippedSkipped,
            density = density,
        )
    }

    /**
     * 节点是否被某个可滚祖先裁掉半截。
     *
     * 只看「前进方向那一侧」：竖向容器比 bottom、横向容器比 right —— 内容往下/往右继续，那侧的
     * `boundsInScreen` 一定是被截过的。**不**拿 top/left 贴边当裁切依据：列表停在顶部时第一条
     * 正好 top == 视口 top，属正常对齐（否则每屏第一条永远不判尺寸）。
     */
    private fun clippedByScrollable(node: UiNode, byIndex: Map<Int, UiNode>): Boolean {
        var hops = 0
        var current = byIndex[node.parent]
        while (current != null && hops++ < MAX_ANCESTOR_HOPS) {
            if (current.scrollable && current.width > 0 && current.height > 0) {
                val clipped = if (current.height >= current.width) {
                    node.top < current.top || node.bottom >= current.bottom
                } else {
                    node.left < current.left || node.right >= current.right
                }
                if (clipped) return true
            }
            current = byIndex[current.parent]
        }
        return false
    }

    /** 父链深度上限：与 [UiTree.clickableAncestor] 同一个防环数字。 */
    private const val MAX_ANCESTOR_HOPS = 64

    /**
     * 子树里（不含自己）有没有可读文案。
     *
     * 遇到嵌套的可交互节点就**不再往下**：它有自己的一行读屏文案，不替父节点作证。
     * `seen` 防坏父链成环（与 [UiTree.clickableAncestor] 的深度上限同一动机）。
     */
    private fun hasLabelInSubtree(index: Int, children: Map<Int, List<UiNode>>): Boolean {
        val seen = HashSet<Int>()
        val queue = ArrayDeque(children[index].orEmpty())
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!seen.add(node.index)) continue
            // 先判交互性再判文案：嵌套控件的名字属于它自己，不能替父节点作证
            if (node.clickable || node.focusable) continue
            if (node.text.isNotBlank() || node.desc.isNotBlank()) return true
            queue.addAll(children[node.index].orEmpty())
        }
        return false
    }

    /** 短边像素 → dp，保留一位小数（47.9 与 48.1 的差别决定放不放行，不能先取整）。 */
    private fun shortSideDp(node: UiNode, ratio: Double): Double =
        ((min(node.width, node.height) / ratio) * 10).roundToInt() / 10.0

    private fun UiNode.toGap(reason: String, shortSideDp: Double): UiGap = UiGap(
        index = index,
        reason = reason,
        className = className,
        text = text,
        desc = desc,
        viewId = viewId,
        bounds = UiRect(left, top, right, bottom),
        shortSideDp = shortSideDp,
    )
}

/** 一条缺口：字段与落盘 JSON 一一对应（`desc` 是 contentDescription 的短名）。 */
data class UiGap(
    val index: Int,
    val reason: String,
    val className: String,
    val text: String,
    val desc: String,
    val viewId: String,
    val bounds: UiRect,
    val shortSideDp: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("reason", reason)
        .put("className", className)
        .put("text", UiText.truncate(text))
        .put("desc", UiText.truncate(desc))
        .put("viewId", viewId)
        .put("bounds", JSONArray().put(bounds.x1).put(bounds.y1).put(bounds.x2).put(bounds.y2))
        .put("shortSideDp", shortSideDp)

    /** 人读一行：直接粘进报告，不用再解析 JSON（换行压成空格，避免把 logcat 一行拆成两行）。 */
    fun summary(): String = buildString {
        append(reason).append(" #").append(index).append(' ')
        append(className.substringAfterLast('.').ifEmpty { className.ifEmpty { "?" } })
        append(" \"").append(label()).append('"')
        if (viewId.isNotEmpty()) append(" id=").append(viewId)
        append(' ').append(bounds.x2 - bounds.x1).append('x').append(bounds.y2 - bounds.y1)
        append(" short=").append(shortSideDp).append("dp")
    }

    private fun label(): String =
        UiText.truncate(text.ifBlank { desc }).replace('\n', ' ').replace('"', '\'')
}

/**
 * 一屏的审计结果。`gapCount == 0` 才是通过 —— 调用方据此决定 ok 与退出码。
 *
 * 隐私：`gaps` 带屏幕原文，只允许落 App 私有目录 `files/dsh-ui/`；RESULT 行只放摘要。
 */
data class UiAuditReport(
    val pkg: String,
    val nodeCount: Int,
    val clickableCount: Int,
    val gaps: List<UiGap>,
    val textWithoutId: Int,
    val labelFromChildren: Int,
    val clippedSkipped: Int,
    val density: Float,
) {
    val gapCount: Int get() = gaps.size

    val ok: Boolean get() = gaps.isEmpty()

    /** 落盘形态：字段是外部脚本的口径，改名等于改工具链。 */
    fun toJson(generatedAt: Long): JSONObject = JSONObject()
        .put("pkg", pkg)
        .put("generatedAt", generatedAt)
        .put("nodeCount", nodeCount)
        .put("clickableCount", clickableCount)
        .put("gapCount", gapCount)
        .put("info", infoJson())
        .put("gaps", JSONArray().apply { gaps.forEach { put(it.toJson()) } })

    /**
     * 只统计、不阻断的项：
     * - `textWithoutId`：有文案但没资源名（脚本定位/回归测试找不到锚点）；
     * - `labelFromChildren`：可读名来自子树的节点数（解释「为什么这屏没报缺口」）；
     * - `clippedSkipped`：被可滚祖先裁掉、因此没判尺寸的可点节点数（没判就写清楚没判）。
     */
    fun infoJson(): JSONObject = JSONObject()
        .put("textWithoutId", textWithoutId)
        .put("labelFromChildren", labelFromChildren)
        .put("clippedSkipped", clippedSkipped)
        .put("minTargetDp", UiAudit.MIN_TARGET_DP)
        .put("density", density.toDouble())

    fun headSummaries(): List<String> = gaps.take(UiAudit.HEAD_LIMIT).map { it.summary() }
}
