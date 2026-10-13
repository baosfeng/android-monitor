package com.bsfeng.monitor.domain.model

/**
 * 选择器 AST（GKD 选择器子集）。
 *
 * ## 求值方向
 * 链式选择器 **从右往左** 求值：先用最右侧属性段在节点树中定位候选节点，
 * 再逐段向左校验关系（见 [Relation]），最后返回 [SelectorChain.targetIndex] 指向的节点。
 *
 * ## 支持范围（与 GKD 对齐的部分）
 * - 属性段：`TextView`（≡ `[name='TextView'||name$='.TextView']`）、`* `（任意）、`[布尔表达式]`
 * - `@` 标记点击目标节点；缺省取最右段
 * - 逻辑：`||`、`&&`（优先级更高）、`!(...)`；同一属性段内并列的 `[]` 等价于 `&&`
 * - 关系算子：`>` 祖先、`<` 直接子节点、`<<` 子孙、`+` 前置兄弟、`-` 后置兄弟、空白 ` ` 任意祖先
 * - 关系表达式：`+3`、`<<2`、`>(1,2,3)`、`>n`、`>(-n+4)` 等
 *
 * 不支持 `->`（解析期直接报错，避免静默误匹配）。
 */
sealed interface SelectorExpr

/** 链式选择器：从左到右的属性段，段间为关系约束 */
data class SelectorChain(val parts: List<SelectorPart>) : SelectorExpr {
    init {
        require(parts.isNotEmpty()) { "选择器至少需要一个属性段" }
    }

    /** 点击目标段下标（@ 标记段；缺省最后一段） */
    val targetIndex: Int
        get() = parts.indexOfLast { it.segment.isTarget }.takeIf { it >= 0 } ?: parts.lastIndex
}

/** 逻辑或：短路求值，左侧先匹配 */
data class SelectorOr(val items: List<SelectorExpr>) : SelectorExpr

/** 逻辑且：两侧都需匹配，结果取右侧 */
data class SelectorAnd(val items: List<SelectorExpr>) : SelectorExpr

/** 取反：内部不匹配才算匹配，目标为最右段 */
data class SelectorNot(val inner: SelectorExpr) : SelectorExpr

/** 选择器链中的一段：属性段 + 与前一段的关系 */
data class SelectorPart(
    val segment: AttrSegment,
    /** 与左侧相邻属性段的关系；最左段为 null */
    val relation: Relation? = null
)

/**
 * 属性段。
 *
 * @param nameTag 类名简写（`TextView`），null 表示不约束类名
 * @param expr 方括号内的布尔表达式（多个 `[]` 已合并为 `&&`）
 * @param isTarget 是否被 `@` 标记为点击目标
 * @param fastHints 可走原生 API 快速查询的提示（parser 计算，见 GKD「查询优化」）
 */
data class AttrSegment(
    val nameTag: String? = null,
    val expr: BoolExpr? = null,
    val isTarget: Boolean = false,
    val fastHints: List<FastHint> = emptyList()
)

/** 可快速查询的属性表达式提示 */
data class FastHint(val attr: FastAttr, val op: CompareOp, val value: String)

/** 可快速查询的属性 */
enum class FastAttr { ID, VID, TEXT }

/** 关系约束 */
data class Relation(val op: RelationOp, val range: RelationRange)

/** 关系算子（语义与 GKD 一致，均以「右侧节点」为参照描述左侧节点） */
enum class RelationOp(val symbol: String, val display: String) {
    /** `A + B`：A 是 B 的前置兄弟节点 */
    PRECEDING_SIBLING("+", "前置兄弟"),

    /** `A - B`：A 是 B 的后置兄弟节点 */
    FOLLOWING_SIBLING("-", "后置兄弟"),

    /**
     * `A > B`：A 是 B 的祖先节点。
     *
     * 距离表达式缺省为 `>1` = 直接父节点；`>n`（或空白组合器 `A B`）= 任意祖先；
     * `>2` = 祖父节点。
     */
    ANCESTOR(">", "祖先"),

    /** `A < B`：A 是 B 的直接子节点（`<1` 为第一个子节点，`<n` 为任意子节点） */
    CHILD("<", "直接子节点"),

    /**
     * `A << B`：A 是 B 的子孙节点，按深度先序序号的 **0 基** 索引定位。
     *
     * 距离缺省为 `<<1` = 先序第 1 个子孙；`<<n` = 任意子孙；`<<2` = 先序第 2 个子孙
     * （约束为 `order = a_m - 1`，与 GKD 一致）。
     */
    DESCENDANT("<<", "子孙")
}

/**
 * 关系表达式：限定关系成立时的「距离」集合。
 *
 * - [Any]：`n` / `(n)`，任意正整数
 * - [Tuple]：`(1)`、`(2,3,5)`、`(-n+4)` 展开后的有限集合
 * - [Arithmetic]：`an+b`（a>0 时为无限集合）
 */
sealed interface RelationRange {
    fun contains(value: Int): Boolean

    data object Any : RelationRange {
        override fun contains(value: Int): Boolean = value >= 1
    }

    data class Tuple(val values: Set<Int>) : RelationRange {
        override fun contains(value: Int): Boolean = value in values
    }

    data class Arithmetic(val a: Int, val b: Int) : RelationRange {
        override fun contains(value: Int): Boolean {
            if (value < 1) return false
            if (a == 0) return value == b
            val diff = value - b
            return diff % a == 0 && diff / a >= 1
        }
    }

    companion object {
        /** 解析元组/多项式表达式内容（不含括号），如 `1`、`2,3,5`、`n`、`-n+4`、`2n-1` */
        fun parse(raw: String): RelationRange {
            val text = raw.trim()
            require(text.isNotEmpty()) { "关系表达式不能为空" }
            if (text.contains(',')) {
                val values = text.split(',').map { it.trim().toInt() }.toSet()
                require(values.all { it >= 1 }) { "关系表达式取值必须 >= 1: $raw" }
                return Tuple(values)
            }
            if (text == "n") return Any
            // 常量：`+3` → (0n+3)，即单值 3
            if (CONST.matches(text)) {
                val value = text.toInt()
                require(value >= 1) { "关系表达式取值必须 >= 1: $raw" }
                return Tuple(setOf(value))
            }
            // 多项式：`n`、`+n`、`2n-1`、`-n+4`
            val match = POLY.matchEntire(text)
                ?: throw IllegalArgumentException("非法关系表达式: $raw")
            val a = match.groupValues[1].let {
                when (it) {
                    "", "+" -> 1
                    "-" -> -1
                    else -> it.toInt()
                }
            }
            val b = match.groupValues[2].let {
                when (it) {
                    "", "+" -> 0
                    else -> it.toInt()
                }
            }
            require((1..64).any { a * it + b >= 1 }) { "关系表达式集合为空: $raw" }
            return Arithmetic(a, b)
        }

        /** 常量表达式：`3`、`+3`、`-1` */
        private val CONST = Regex("^[+-]?\\d+$")

        /** 多项式表达式：`n`、`+n`、`3n`、`2n-1`、`-n+4` */
        private val POLY = Regex("^([+-]?\\d*)n([+-]\\d+)?$")
    }
}

// ==================== 值表达式 ====================

/** 值表达式 */
sealed interface ValueExpr

/** 字面量：null / Boolean / Int / String */
data class LiteralValue(val value: Any?) : ValueExpr

/** 标识符：节点属性（text/id/vid/...）、上下文变量（prev/current/global 由求值器解释） */
data class IdentValue(val name: String) : ValueExpr

/** 成员访问：`text.length`、`parent.name` */
data class MemberValue(val target: ValueExpr, val name: String) : ValueExpr

/** 方法调用：`parent.getChild(0)`、`text.substring(1,2)`；[target] 为 null 表示全局函数 */
data class CallValue(val target: ValueExpr?, val name: String, val args: List<ValueExpr>) : ValueExpr

// ==================== 布尔表达式 ====================

/** 布尔表达式 */
sealed interface BoolExpr

/** 比较表达式 */
data class CompareExpr(val left: ValueExpr, val op: CompareOp, val right: ValueExpr) : BoolExpr

/** 逻辑且 */
data class AndExpr(val items: List<BoolExpr>) : BoolExpr

/** 逻辑或 */
data class OrExpr(val items: List<BoolExpr>) : BoolExpr

/** 取反 */
data class NotExpr(val inner: BoolExpr) : BoolExpr

/** 比较操作符（含 GKD 的取反形式） */
enum class CompareOp(val symbol: String) {
    EQ("="),
    NOT_EQ("!="),
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<="),
    STARTS_WITH("^="),
    NOT_STARTS_WITH("!^="),
    CONTAINS("*="),
    NOT_CONTAINS("!*="),
    ENDS_WITH("$="),
    NOT_ENDS_WITH("!$="),
    MATCHES("~="),
    NOT_MATCHES("!~=");

    /** 是否为字符串类操作符（仅字符串类型可用） */
    val isStringOp: Boolean
        get() = this in setOf(
            STARTS_WITH, NOT_STARTS_WITH, CONTAINS, NOT_CONTAINS,
            ENDS_WITH, NOT_ENDS_WITH, MATCHES, NOT_MATCHES
        )

    /** 是否为数值类操作符（仅整数类型可用） */
    val isOrderOp: Boolean get() = this in setOf(GT, GTE, LT, LTE)
}
