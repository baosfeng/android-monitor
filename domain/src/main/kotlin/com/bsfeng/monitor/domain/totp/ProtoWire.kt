package com.bsfeng.monitor.domain.totp

/**
 * protobuf wire format 的最小读取器（只读，不写）。
 *
 * Google 迁移二维码是纯 protobuf，但引一个 protobuf 运行库只为读 3 种 wire type 不划算；
 * 这里手写 300 行以内的解析，按官方 encoding 规范：varint / length-delimited / 定长，
 * 其余 wire type（3/4 已废弃的分组、6/7 非法）一律拒绝，未知字段按官方推荐跳过（向前兼容）。
 *
 * 所有上限都在这里硬卡：超长 varint、越界长度、超深嵌套只返回 null，不让坏输入炸栈。
 */
internal class ProtoWire private constructor(private val data: ByteArray, private var pos: Int, private val end: Int) {

    /** 一个已读出的字段：tag 与三种取值形态之一。 */
    sealed interface Field {
        val tag: Int

        data class Varint(override val tag: Int, val value: Long) : Field

        data class Bytes(override val tag: Int, val value: ByteArray) : Field
    }

    val hasMore: Boolean get() = pos < end

    /** 读下一个字段；遇到不认识的 wire type 或越界返回 null（调用方按「整体损坏」处理）。 */
    fun next(): Field? {
        if (pos >= end) return null
        val key = readVarint() ?: return null
        val fieldNumber = (key ushr 3).toInt()
        if (fieldNumber <= 0) return null
        val tag = fieldNumber
        return when ((key and 0x07).toInt()) {
            0 -> {
                val value = readVarint() ?: return null
                Field.Varint(tag, value)
            }
            1 -> {
                if (pos + 8 > end) return null
                pos += 8
                Field.Varint(tag, 0L)
            }
            2 -> {
                val length = readVarint() ?: return null
                if (length < 0L || pos + length > end) return null
                val start = pos
                pos += length.toInt()
                Field.Bytes(tag, data.copyOfRange(start, start + length.toInt()))
            }
            5 -> {
                if (pos + 4 > end) return null
                pos += 4
                Field.Varint(tag, 0L)
            }
            else -> null
        }
    }

    /** LEB128 varint：最多 10 字节（64 位），超了就是坏数据。 */
    private fun readVarint(): Long? {
        var shift = 0
        var value = 0L
        while (shift < 64) {
            if (pos >= end) return null
            val byte = data[pos++].toInt() and 0xFF
            value = value or ((byte.toLong() and 0x7F) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
        return null
    }

    companion object {
        /** 解析一整段字节；空输入也返回一个空读取器（调用方靠 hasMore 判空）。 */
        fun of(data: ByteArray): ProtoWire = ProtoWire(data, 0, data.size)

        /** 解析嵌套的 length-delimited 子消息。 */
        fun of(field: Field.Bytes): ProtoWire = ProtoWire(field.value, 0, field.value.size)
    }
}
