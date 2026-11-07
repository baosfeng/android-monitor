package com.bsfeng.monitor.finance.importer

/**
 * 极简 XML 扫描工具（零第三方，纯 JVM）。
 *
 * 完整解析器在纯 JVM 里没有现成可用实现（`org.xmlpull.v1` 属于 Android，单测 classpath 里没有），
 * 而 xlsx 的表格 xml 只需要读到标签、文本与属性，就手写这一层。
 * 只够这一种用途：不处理 CDATA、不校验良构，注释与处理指令整段跳过。
 */
internal object BillXmlScan {

    /** 一次扫描结果：[text] 是上一个标签之后的文本，[tag] 是下一个标签原文（不含尖括号） */
    class Token(val text: String, val tag: String)

    /** 顺序扫描器：每次 [next] 前进一段 */
    class Scanner(private val src: String) {
        private var pos = 0

        fun next(): Token? {
            if (pos >= src.length) return null
            val lt = src.indexOf('<', pos)
            if (lt < 0) {
                val text = src.substring(pos)
                pos = src.length
                return Token(text, "")
            }
            val text = src.substring(pos, lt)
            val gt = src.indexOf('>', lt)
            if (gt < 0) {
                pos = src.length
                return Token(text, "")
            }
            val tag = src.substring(lt + 1, gt)
            pos = gt + 1
            if (tag.startsWith("!--")) {
                val end = src.indexOf("-->", lt)
                pos = if (end < 0) src.length else end + 3
                return Token(text, "")
            }
            if (tag.startsWith("?")) return Token(text, "")
            return Token(text, tag)
        }
    }

    /** 标签名（去 `/`、去属性）；`</row` 与 `row r="1"` 都得到 `row` */
    fun tagName(tag: String): String {
        var i = 0
        if (i < tag.length && (tag[i] == '/' || tag[i] == '?' || tag[i] == '!')) i++
        val start = i
        while (i < tag.length && !tag[i].isWhitespace() && tag[i] != '/' && tag[i] != '>') i++
        return tag.substring(start, i)
    }

    /** 取标签属性值（`key="value"`），没有返回空串 */
    fun attribute(tag: String, key: String): String {
        var i = 0
        while (i < tag.length && !tag[i].isWhitespace()) i++
        while (i < tag.length) {
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i >= tag.length) return ""
            if (tag[i] == '/') break
            val nameStart = i
            while (i < tag.length && tag[i] != '=' && !tag[i].isWhitespace()) i++
            val name = tag.substring(nameStart, i)
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i >= tag.length) return ""
            if (tag[i] != '=') {
                i++
                continue
            }
            i++
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i >= tag.length) return ""
            val quote = tag[i]
            if (quote != '"' && quote != '\'') return ""
            val end = tag.indexOf(quote, i + 1)
            if (end < 0) return ""
            if (name == key) return tag.substring(i + 1, end)
            i = end + 1
        }
        return ""
    }

    /** XML 实体还原；金额/商户名里的 `&` 不还原就会带进账本 */
    fun decode(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '&') {
                out.append(ch)
                i++
                continue
            }
            val end = text.indexOf(';', i)
            if (end < 0) {
                out.append(ch)
                i++
                continue
            }
            val entity = text.substring(i + 1, end)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let(::codePointToText)
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.let(::codePointToText)
                else -> null
            }
            if (decoded == null) {
                out.append(ch)
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }

    private fun codePointToText(codePoint: Int): String? =
        if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else null
}
