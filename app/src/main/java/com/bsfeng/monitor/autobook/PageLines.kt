package com.bsfeng.monitor.autobook

/**
 * 页面文本行的纯数据与合成逻辑（**不含任何 Android 类型**，便于 JVM 单测）。
 *
 * @param text 节点文本（text 优先，为空时取 contentDescription）
 * @param left 屏幕左边界
 * @param top 屏幕上边界
 */
data class NodeText(
    val text: String,
    val left: Int,
    val top: Int
)

/**
 * 把带位置的文本节点合成为「视觉行」。
 *
 * 支付详情页是「左侧标签 + 右侧值」的表格布局，同一行的两个节点 Y 坐标对齐
 * （真机实测：`收款方全称` [75,1349] 与 `阿里云计算有限公司` [375,1346]）。
 * 按 Y 分行、按 X 排序拼接后，解析端就能用「标签 → 同行右侧内容」稳定取值。
 */
object PageLines {

    /** 判定「同一行」的 Y 容差（px）：实测同行节点 top 差 ≤ 6px，取 25px 留出字体高度差异 */
    const val SAME_LINE_TOLERANCE_PX = 25

    /** 一行内最多拼接的文本节点数，防止超长行影响解析 */
    private const val MAX_TEXTS_PER_LINE = 6

    /**
     * @return 自上而下的视觉行文本，行内按从左到右拼接
     */
    fun toLines(nodes: List<NodeText>): List<String> {
        if (nodes.isEmpty()) return emptyList()

        val sorted = nodes.sortedWith(compareBy({ it.top }, { it.left }))
        val lines = ArrayList<String>(sorted.size)
        var group = ArrayList<NodeText>()
        var groupTop = Int.MIN_VALUE

        // 先把 Y 相近的节点聚成一行，**行内再按 X 排序**再拼接。
        // 不能边比较边拼：同一行的两个节点 top 有 3~6px 差（阿里云计算有限公司 1346 / 收款方全称 1349），
        // 按 top 排序会把「值」排在「标签」前面，拼出「阿里云计算有限公司 收款方全称」这种反序行。
        fun flush() {
            if (group.isNotEmpty()) {
                val text = group.sortedBy { it.left }
                    .take(MAX_TEXTS_PER_LINE)
                    .joinToString(" ") { it.text }
                    .trim()
                if (text.isNotEmpty()) lines.add(text)
                group = ArrayList()
            }
        }

        for (node in sorted) {
            val sameLine = groupTop != Int.MIN_VALUE &&
                kotlin.math.abs(node.top - groupTop) <= SAME_LINE_TOLERANCE_PX
            if (!sameLine) {
                flush()
                groupTop = node.top
            }
            group.add(node)
        }
        flush()
        return lines
    }
}
