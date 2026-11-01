package com.bsfeng.monitor.debug

import org.json.JSONArray
import org.json.JSONObject

/**
 * 控件树节点（纯数据，不含任何 Android 类型）。
 *
 * 为什么要有这一层：无障碍节点树只能在主线程读，读一次就得当场转成快照 —— 之后「按文案找节点」
 * 「回溯可点祖先」都是纯函数，可以纯 JVM 断言（见 `DebugUiModelTest`），不必起 Robolectric。
 *
 * 隐私：`text` / `contentDescription` 可能含用户屏幕原文，只允许落 App 私有目录
 * （`files/dsh-ui/`），落盘时按 [UiText.MAX_TEXT_CHARS] 截断；日志里只打数量与包名。
 */
data class UiNode(
    val index: Int,
    val parent: Int,
    val depth: Int,
    val window: Int,
    val className: String,
    val viewId: String,
    val text: String,
    val desc: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val childCount: Int,
    /** 可聚焦（含 TalkBack 焦点）：审计要用它认「读屏能落在上面」的控件，dump 时也一并带上。 */
    val focusable: Boolean = false,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** 屏幕坐标四元组：手势回落与 `--es bounds` 选择器都用它，避免「文案匹配了但点偏」。 */
    fun boundsJson(): JSONArray = JSONArray().put(left).put(top).put(right).put(bottom)

    /**
     * 落盘形态。字段名与文档口径一致（`viewIdResourceName` 保留原名，值已去掉 `pkg:id/` 前缀）。
     *
     * `truncated` 只表示「这条节点的文本被砍过」，不是整棵树被砍（那是 dump 顶层的
     * `truncatedNodes`）—— 两个截断混在一起会让 agent 误判自己看到的是不是完整页面。
     */
    fun toJson(): JSONObject {
        val trimmedText = UiText.truncate(text)
        val trimmedDesc = UiText.truncate(desc)
        return JSONObject()
            .put("index", index)
            .put("parent", parent)
            .put("depth", depth)
            .put("window", window)
            .put("className", className)
            .put("viewIdResourceName", viewId)
            .put("text", trimmedText)
            .put("contentDescription", trimmedDesc)
            .put("boundsInScreen", boundsJson())
            .put("clickable", clickable)
            .put("scrollable", scrollable)
            .put("editable", editable)
            .put("enabled", enabled)
            .put("selected", selected)
            .put("childCount", childCount)
            .put("focusable", focusable)
            .put("truncated", trimmedText.length != text.length || trimmedDesc.length != desc.length)
    }
}

/** 文本截断：单节点超过 [MAX_TEXT_CHARS] 就砍掉并标记，避免一屏长文本把 dump 撑成几 MB。 */
object UiText {
    const val MAX_TEXT_CHARS = 200

    fun truncate(value: String): String =
        if (value.length <= MAX_TEXT_CHARS) value else value.take(MAX_TEXT_CHARS)
}

/** 屏幕矩形（`--es bounds x1,y1,x2,y2`）。 */
data class UiRect(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {

    /** 退化矩形（宽或高 ≤ 0）不可能是真实控件的可点区域，解析期就拒。 */
    val isValid: Boolean get() = x2 > x1 && y2 > y1

    fun contains(node: UiNode): Boolean = node.left == x1 && node.top == y1 && node.right == x2 && node.bottom == y2

    companion object {
        /** @return null = 形态不合法（不是四个整数 / 顺序颠倒 / 退化为点线） */
        fun parse(text: String): UiRect? {
            val parts = text.split(",").map { it.trim() }
            if (parts.size != 4) return null
            val values = parts.map { it.toIntOrNull() ?: return null }
            val rect = UiRect(values[0], values[1], values[2], values[3])
            return rect.takeIf { it.isValid }
        }
    }
}

/**
 * 节点选择器：文案 / 描述 / 资源名片段 / 屏幕矩形，**全部条件同时满足**才算命中。
 *
 * 用「包含」而不是相等：Compose 的 Text 常被拆成多段语义节点，精确相等在真机上匹配率太低。
 * 全空选择器不允许（解析期拒绝）——否则 `ui click` 会点到页面第一个节点。
 */
data class UiSelector(
    val text: String? = null,
    val desc: String? = null,
    val id: String? = null,
    val bounds: UiRect? = null,
) {
    val isEmpty: Boolean get() = text == null && desc == null && id == null && bounds == null

    fun matches(node: UiNode): Boolean {
        if (text != null && !node.text.contains(text, ignoreCase = true)) return false
        if (desc != null && !node.desc.contains(desc, ignoreCase = true)) return false
        if (id != null && !node.viewId.contains(id, ignoreCase = true)) return false
        if (bounds != null && !bounds.contains(node)) return false
        return true
    }

    /** 回显给 CLI 的人读形态（`ok=false` 时说明自己到底找的是什么）。 */
    fun describe(): String = listOfNotNull(
        text?.let { "text=$it" },
        desc?.let { "desc=$it" },
        id?.let { "id=$it" },
        bounds?.let { "bounds=${it.x1},${it.y1},${it.x2},${it.y2}" },
    ).joinToString(" ")
}

/** 控件树上的纯查询（快照之后才用，全部可纯 JVM 断言）。 */
object UiTree {

    /** 第一个命中选择器的节点（遍历顺序 = 先父后子，因此天然的「最靠上/最外层」优先）。 */
    fun findFirst(nodes: List<UiNode>, selector: UiSelector): UiNode? =
        nodes.firstOrNull { selector.matches(it) }

    /**
     * 命中节点自身或其最近的可点祖先。
     *
     * Compose 的文本节点自己不 clickable，点击落在包裹它的 Row/Box 上；只对自己 performAction
     * 会一直返回 false。深度上限 [MAX_ANCESTOR_HOPS] 防坏父链成环死循环。
     */
    fun clickableAncestor(nodes: List<UiNode>, index: Int): UiNode? {
        var current = nodes.getOrNull(index) ?: return null
        repeat(MAX_ANCESTOR_HOPS) {
            if (current.clickable && current.enabled) return current
            if (current.parent < 0) return null
            current = nodes.getOrNull(current.parent) ?: return null
        }
        return null
    }

    /**
     * 选滚动目标：**方向对得上、且覆盖屏幕中部**的优先；方向对不上就返回 null（交给手势兜底）。
     *
     * 历史缺陷（真机实测）：只取「第一个 scrollable」，可点屏顶一条横向 HorizontalScrollView 会把
     * `ui scroll --es dir down` 吃掉 —— 横条自己左移、竖向内容一动不动，验证方误判成「已经滚到底」。
     *
     * **为什么方向对不上不回落**：设置页实测只有一个 scrollable，正是那条横向横条
     * （`[48,346,1152,478]`，外层 ScrollView 的 isScrollable 是 false）。回落到它 = 又滚错轴，
     * 所以这里宁可返回 null，让调用方用手势滑屏——手势落点全是竖向内容，语义正确。
     *
     * @param screenHeight 屏幕高度（px）：用来判断「贯穿中部」；<=0 表示不知道屏幕尺寸，只按方向挑
     * @param preferVertical true = 找竖向容器（up/down），false = 找横向容器（left/right）
     */
    fun firstScrollable(
        nodes: List<UiNode>,
        screenHeight: Int = 0,
        preferVertical: Boolean = true,
    ): UiNode? {
        // 0 尺寸的容器滚不动：先滤掉，后面的挑选只在真实容器里做
        val aligned = nodes.filter {
            it.scrollable && it.enabled && it.width > 0 && it.height > 0 &&
                (it.height >= it.width) == preferVertical
        }.ifEmpty { return null }
        if (screenHeight > 0) {
            val middle = screenHeight / 2
            aligned.firstOrNull { it.top <= middle && it.bottom >= middle }?.let { return it }
        }
        return aligned.first()
    }

    /** 屏上有没有任何可滚容器（方向不论）：用来区分「没得滚」与「只有反方向的轴」。 */
    fun anyScrollable(nodes: List<UiNode>): Boolean =
        nodes.any { it.scrollable && it.enabled && it.width > 0 && it.height > 0 }

    private const val MAX_ANCESTOR_HOPS = 64
}

/** dump 文件名标签：只允许 ASCII 字母数字与 `-_`，其余替换成 `_`（防路径穿越与中文文件名）。 */
object UiTag {
    const val DEFAULT = "ui"
    private const val MAX_CHARS = 40

    fun sanitize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT
        val builder = StringBuilder()
        trimmed.forEach { ch ->
            builder.append(if (ch.isAsciiTagChar()) ch else '_')
        }
        return builder.take(MAX_CHARS).toString().trim('_').ifEmpty { DEFAULT }
    }

    private fun Char.isAsciiTagChar(): Boolean =
        (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9') || this == '-' || this == '_'
}
