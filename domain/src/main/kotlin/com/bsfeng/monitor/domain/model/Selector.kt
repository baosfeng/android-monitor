package com.bsfeng.monitor.domain.model

/**
 * 选择器 DSL — 用于在 Android 无障碍节点树中定位目标节点。
 *
 * 语法类似 CSS 选择器，支持标签名、属性匹配、组合器。
 *
 * ## 语法
 * ```
 * selector = simple_selector (combinator selector)*
 * simple_selector = tag? attr*
 * tag = 类名简写 (如 TextView, Button)
 * attr = [name op? value?]
 * ```
 *
 * ## 示例
 * - `TextView` — 标签选择
 * - `[text="跳过"]` — 文本精确匹配
 * - `[text*="广告"]` — 文本包含匹配
 * - `TextView[id="com.xxx:id/btn_skip"]` — 标签+ID
 * - `FrameLayout > TextView[text="跳过"]` — 父子组合
 */
data class Selector(
    /** 标签名 (如 TextView, ImageView, Button)，null 表示任意标签 */
    val tag: String? = null,
    /** 属性条件列表 */
    val attributes: List<AttrCondition> = emptyList(),
    /** 与右侧选择器的组合关系 */
    val combinator: Combinator? = null,
    /** 组合右侧的选择器 */
    val right: Selector? = null
) {
    /** 是否为叶子选择器（无组合器+右侧） */
    val isLeaf: Boolean get() = combinator == null && right == null
}

/**
 * 属性匹配条件。
 */
data class AttrCondition(
    /** 属性名 (text, desc, id, vid, clickable, visible, etc.) */
    val name: String,
    /** 匹配操作符 */
    val op: AttrOp,
    /** 匹配值 */
    val value: String
)

/**
 * 属性匹配操作符。
 */
enum class AttrOp(val symbol: String) {
    /** 精确等于 [text="跳过"] */
    EQ("="),
    /** 包含 [text*="广告"] */
    CONTAINS("*="),
    /** 前缀匹配 [text^="跳过"] */
    STARTS_WITH("^="),
    /** 后缀匹配 [text$="关闭"] */
    ENDS_WITH("$="),
    /** 正则匹配 [text~="^(跳过|关闭).*"] */
    REGEX("~="),
    /** 不等于 [text!="确定"] */
    NOT_EQ("!=");
}

/**
 * 选择器组合器。
 */
enum class Combinator(val symbol: String) {
    /** 父子关系 A > B — A 的直接子节点 B */
    CHILD(">"),
    /** 后代关系 A B — A 的任意后代节点 B */
    DESCENDANT(" "),
    /** 紧邻兄弟 A + B — A 之后的下一个兄弟节点 B */
    SIBLING("+");
}
