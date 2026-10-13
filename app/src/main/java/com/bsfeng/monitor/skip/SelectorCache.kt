package com.bsfeng.monitor.skip

import android.util.Log
import com.bsfeng.monitor.domain.model.AndExpr
import com.bsfeng.monitor.domain.model.AttrSegment
import com.bsfeng.monitor.domain.model.BoolExpr
import com.bsfeng.monitor.domain.model.SelectorAnd
import com.bsfeng.monitor.domain.model.SelectorChain
import com.bsfeng.monitor.domain.model.SelectorExpr
import com.bsfeng.monitor.domain.model.SelectorNot
import com.bsfeng.monitor.domain.model.SelectorOr
import com.bsfeng.monitor.domain.model.SelectorPart

/**
 * 选择器解析缓存。
 *
 * 规则引擎每次扫描都会用到同一批选择器字符串，重复解析（含正则编译）代价高，
 * 这里做进程内缓存；解析失败的表达式缓存为 null 并只告警一次。
 *
 * `fastQuery = false` 的规则需要保持深度优先遍历顺序，因此缓存键包含该标志。
 */
internal object SelectorCache {

    private const val TAG = "SelectorCache"
    private const val MAX_ENTRIES = 2_000

    private val cache = HashMap<String, SelectorExpr?>()
    private val warned = HashSet<String>()

    /**
     * 解析并缓存选择器。
     *
     * @param expression 选择器字符串
     * @param fastQuery 是否允许快速查询（false 时清空快速查询提示，保证遍历顺序）
     * @return 解析结果；解析失败返回 null（调用方跳过该选择器并记录日志）
     */
    fun get(expression: String, fastQuery: Boolean = true): SelectorExpr? {
        val key = (if (fastQuery) "1|" else "0|") + expression
        if (cache.containsKey(key)) return cache[key]
        val parsed = SelectorParser.tryParse(expression)?.let { if (fastQuery) it else withoutFastQuery(it) }
        if (parsed == null && warned.add(expression)) {
            Log.w(TAG, "选择器解析失败，已跳过: " + expression)
        }
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[key] = parsed
        return parsed
    }

    /** 清空缓存（规则更新后调用） */
    fun clear() {
        cache.clear()
        warned.clear()
    }

    /** 递归清空快速查询提示，强制走深度优先遍历 */
    fun withoutFastQuery(expr: SelectorExpr): SelectorExpr = when (expr) {
        is SelectorChain -> expr.copy(parts = expr.parts.map { part -> SelectorPart(withoutHints(part.segment), part.relation) })
        is SelectorOr -> expr.copy(items = expr.items.map { withoutFastQuery(it) })
        is SelectorAnd -> expr.copy(items = expr.items.map { withoutFastQuery(it) })
        is SelectorNot -> expr.copy(inner = withoutFastQuery(expr.inner))
    }

    private fun withoutHints(segment: AttrSegment): AttrSegment =
        if (segment.fastHints.isEmpty()) segment else segment.copy(fastHints = emptyList())

    /** 供测试使用：当前缓存条目数 */
    fun size(): Int = cache.size

    /** 供测试使用：判断表达式是否解析成功（不产生副作用之外的日志） */
    fun canParse(expression: String): Boolean = SelectorParser.tryParse(expression) != null

    /** 供测试使用：清理布尔表达式的空分支 */
    fun isEmptyBool(expr: BoolExpr?): Boolean = when (expr) {
        null -> true
        is AndExpr -> expr.items.isEmpty()
        else -> false
    }
}
