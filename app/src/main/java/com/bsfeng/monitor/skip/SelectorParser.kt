package com.bsfeng.monitor.skip

/**
 * 选择器字符串解析器。
 *
 * 将 CSS 风格的选择器字符串解析为 [Selector] 语法树。
 *
 * ## 解析示例
 * ```
 * "TextView"                    → Selector(tag="TextView")
 * "[text=\"跳过\"]"             → Selector(attr=[(text, EQ, "跳过")])
 * "TextView[id=\"btn_skip\"]"   → Selector(tag="TextView", attr=[(id, EQ, "btn_skip")])
 * "A > B"                       → Selector(tag="A", combinator=CHILD, right=Selector(tag="B"))
 * ```
 */
object SelectorParser {

    /**
     * 解析选择器字符串。
     * @throws IllegalArgumentException 语法无效时抛出
     */
    fun parse(input: String): Selector {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("选择器不能为空")

        val result = parseSingle(trimmed, 0)
        return result.first
    }

    /**
     * 递归解析组合选择器。
     * @return (Selector, consumedLength) — 解析结果和已消费的字符数
     */
    private fun parseSingle(input: String, start: Int): Pair<Selector, Int> {
        var pos = skipWhitespace(input, start)

        // 1) 解析标签名（可选）：字母/数字/点开头的单词
        val tag = parseTag(input, pos)
        if (tag != null) {
            pos = tag.second
        }

        // 2) 解析属性条件列表 [name op "value"]
        val attrs = mutableListOf<AttrCondition>()
        while (pos < input.length && input[pos] == '[') {
            val attrResult = parseAttr(input, pos)
            attrs.add(attrResult.first)
            pos = attrResult.second
        }

        // 3) 检查是否有组合器 + 右侧选择器
        // 注意：传入原始 pos（含空白），让 parseCombinator 自己处理空白检测
        val combinator = parseCombinator(input, pos)
        if (combinator != null) {
            val combinatorStart = skipWhitespace(input, pos) + combinator.symbol.trim().length
            val rightResult = parseSingle(input, combinatorStart)
            return Selector(
                tag = tag?.first,
                attributes = attrs,
                combinator = combinator,
                right = rightResult.first
            ) to rightResult.second
        }

        return Selector(tag = tag?.first, attributes = attrs) to pos
    }

    /** 解析标签名（字母/数字/点组成的单词） */
    private fun parseTag(input: String, start: Int): Pair<String, Int>? {
        if (start >= input.length) return null
        val ch = input[start]
        // 标签名必须以字母开头（跳过属性开始的 [）
        if (!ch.isLetter()) return null

        var end = start
        while (end < input.length && (input[end].isLetterOrDigit() || input[end] == '.')) {
            end++
        }
        return input.substring(start, end) to end
    }

    /** 跳过空白字符 */
    private fun skipWhitespace(input: String, start: Int): Int {
        var pos = start
        while (pos < input.length && input[pos].isWhitespace()) pos++
        return pos
    }

    /** 解析属性条件 [name op "value"] */
    private fun parseAttr(input: String, start: Int): Pair<AttrCondition, Int> {
        var pos = start
        if (pos >= input.length || input[pos] != '[') {
            throw IllegalArgumentException("期望 '[' 在位置 $pos: ${input.substring(start)}")
        }
        pos++ // 跳过 [

        // 解析属性名
        pos = skipWhitespace(input, pos)
        val nameStart = pos
        while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_' || input[pos] == '.')) {
            pos++
        }
        val name = input.substring(nameStart, pos)
        if (name.isEmpty()) {
            throw IllegalArgumentException("空的属性名在位置 $pos: ${input.substring(start)}")
        }

        pos = skipWhitespace(input, pos)

        // 解析操作符（2字符或1字符）
        val op: AttrOp
        if (pos < input.length) {
            val twoChar = if (pos + 1 < input.length) input.substring(pos, pos + 2) else ""
            op = when {
                twoChar == "*=" -> { pos += 2; AttrOp.CONTAINS }
                twoChar == "^=" -> { pos += 2; AttrOp.STARTS_WITH }
                twoChar == "$=" -> { pos += 2; AttrOp.ENDS_WITH }
                twoChar == "~=" -> { pos += 2; AttrOp.REGEX }
                twoChar == "!=" -> { pos += 2; AttrOp.NOT_EQ }
                input[pos] == '=' -> { pos += 1; AttrOp.EQ }
                else -> AttrOp.EQ // 无操作符时的默认行为
            }
        } else {
            op = AttrOp.EQ
        }

        pos = skipWhitespace(input, pos)

        // 解析值（带引号）
        val value: String
        if (pos < input.length && (input[pos] == '"' || input[pos] == '\'')) {
            val quote = input[pos]
            pos++ // 跳过引号
            val valueStart = pos
            while (pos < input.length && input[pos] != quote) {
                // 处理转义
                if (input[pos] == '\\' && pos + 1 < input.length) {
                    pos += 2
                } else {
                    pos++
                }
            }
            if (pos >= input.length) {
                throw IllegalArgumentException("未闭合的引号在位置 $pos")
            }
            value = input.substring(valueStart, pos)
            pos++ // 跳过结束引号
        } else {
            // 无引号的值（true/false等）
            val valueStart = pos
            while (pos < input.length && input[pos] != ']' && !input[pos].isWhitespace()) {
                pos++
            }
            value = input.substring(valueStart, pos).trim()
        }

        pos = skipWhitespace(input, pos)

        // 闭合 ]
        if (pos >= input.length || input[pos] != ']') {
            throw IllegalArgumentException("期望 ']' 在位置 $pos: ${input.substring(start)}")
        }
        pos++

        return AttrCondition(name = name, op = op, value = value) to pos
    }

    /** 解析组合器 */
    private fun parseCombinator(input: String, start: Int): Combinator? {
        var pos = start
        // 跳过前导空白
        while (pos < input.length && input[pos].isWhitespace()) pos++

        if (pos >= input.length) return null

        return when (input[pos]) {
            '>' -> Combinator.CHILD
            '+' -> Combinator.SIBLING
            else -> {
                // 空白符被跳过了，说明是空格组合器（DESCENDANT）
                if (pos > start) Combinator.DESCENDANT
                else null
            }
        }
    }
}
