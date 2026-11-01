package com.bsfeng.monitor.domain.totp

/**
 * RFC 4648 Base32（标准字母表、无填充），按验证器密钥的真实来源做了容错。
 *
 * 容错边界：大小写不敏感、忽略空格/制表符/连字符/换行、容忍等号填充——这些差异
 * 全部来自「从网页复制密钥」和「手抄 4 位分组」两个真实场景。
 * 但非法字母、非法长度、末尾非零位一律返回 null：把可疑密钥解成字节再去算码，
 * 只会让用户看到一串永远不对的数字，不如在导入时就说清楚。
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 合法 Base32 长度对 8 取余只能是这几个值（RFC 4648 §6）。 */
    private val VALID_REMAINDERS = setOf(0, 2, 4, 5, 7)

    /**
     * 解码；失败返回 null（不抛异常）。
     * 空串与「只有填充/空白」同样视为失败——空密钥算不出任何有意义的码。
     */
    fun decodeOrNull(input: String): ByteArray? {
        val values = IntArray(input.length)
        var count = 0
        for (ch in input) {
            if (ch == '=' || ch.isWhitespace() || ch == '-') continue
            val index = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a'
                in '2'..'7' -> ch - '2' + 26
                else -> return null
            }
            values[count++] = index
        }
        if (count == 0 || count % 8 !in VALID_REMAINDERS) return null

        val out = ByteArray(count * 5 / 8)
        var buffer = 0
        var bits = 0
        var written = 0
        for (i in 0 until count) {
            buffer = (buffer shl 5) or values[i]
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[written++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        // 尾部填充位必须为 0：非 0 说明密钥在传输中被截断或改坏
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null
        return out
    }

    /** 编码为无填充大写 Base32；空字节数组编码为空串。 */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        return out.toString()
    }
}
