package com.bsfeng.monitor.domain.totp

// ProtoWire 是手写的 protobuf wire 读取器：三种 wire type 解析、其余拒绝、未知字段跳过、越界一律返回 null。
// 每条用例的字节布局都写在注释里（首字节 = tag<<3 | wireType）。

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoWireTest {

    // ───────────────────────── 正常路径 ─────────────────────────

    @Test
    fun `空输入没有字段也不报错`() {
        val wire = ProtoWire.of(ByteArray(0))
        assertFalse(wire.hasMore)
        assertNull(wire.next())
    }

    @Test
    fun `读 varint 字段`() {
        // 0x10 = tag 2 / wire 0；0x96 0x01 = LEB128 150
        val wire = ProtoWire.of(byteArrayOf(0x10, 0x96.toByte(), 0x01))
        assertTrue(wire.hasMore)
        val field = wire.next() as ProtoWire.Field.Varint
        assertEquals(2, field.tag)
        assertEquals(150L, field.value)
        assertFalse(wire.hasMore)
    }

    @Test
    fun `读 length-delimited 字段并给出子消息读取器`() {
        // 0x0A = tag 1 / wire 2；0x03 = 长度 3；61 62 63 = "abc"
        val wire = ProtoWire.of(byteArrayOf(0x0A, 0x03, 0x61, 0x62, 0x63))
        val field = wire.next() as ProtoWire.Field.Bytes
        assertEquals(1, field.tag)
        assertArrayEquals(byteArrayOf(0x61, 0x62, 0x63), field.value)
        assertFalse(wire.hasMore)

        // 嵌套子消息：0x0A 0x02 10 01（长度 2 的载荷 = tag2 varint 1）
        val nested = ProtoWire.of(byteArrayOf(0x0A, 0x02, 0x10, 0x01)).next() as ProtoWire.Field.Bytes
        val inner = ProtoWire.of(nested)
        assertTrue("子读取器自带 hasMore", inner.hasMore)
        assertEquals(2, inner.next()!!.tag)
        assertFalse(inner.hasMore)
    }

    @Test
    fun `逐字段读完整段`() {
        // 0x0A 0x01 0x58（tag1 bytes "X"）+ 0x10 0x0B（tag2 varint 11）+ 0x18 0x01（tag3 varint 1）
        val wire = ProtoWire.of(byteArrayOf(0x0A, 0x01, 0x58, 0x10, 0x0B, 0x18, 0x01))
        val read = mutableListOf<ProtoWire.Field>()
        while (wire.hasMore) read += wire.next()!!
        assertEquals(listOf(1, 2, 3), read.map { it.tag })
        assertArrayEquals(byteArrayOf(0x58), (read[0] as ProtoWire.Field.Bytes).value)
        assertEquals(11L, (read[1] as ProtoWire.Field.Varint).value)
    }

    @Test
    fun `定长 wire type 按官方长度跳过`() {
        // 0x19 = tag 3 / wire 1（fixed64）→ 吃掉 8 字节，再读 0x10 0x07
        val fixed64 = ProtoWire.of(byteArrayOf(0x19) + ByteArray(8) + byteArrayOf(0x10, 0x07))
        assertEquals(3, fixed64.next()!!.tag)
        assertEquals(7L, (fixed64.next() as ProtoWire.Field.Varint).value)
        // 0x25 = tag 4 / wire 5（fixed32）→ 吃掉 4 字节
        val fixed32 = ProtoWire.of(byteArrayOf(0x25) + ByteArray(4) + byteArrayOf(0x10, 0x08))
        assertEquals(4, fixed32.next()!!.tag)
        assertEquals(8L, (fixed32.next() as ProtoWire.Field.Varint).value)
    }

    // ───────────────────────── 边界 ─────────────────────────

    @Test
    fun `长度恰好等于剩余字节时仍可读`() {
        // 0x0A 0x02 61 62：长度 2 正好吃到末尾
        val wire = ProtoWire.of(byteArrayOf(0x0A, 0x02, 0x61, 0x62))
        val field = wire.next() as ProtoWire.Field.Bytes
        assertArrayEquals(byteArrayOf(0x61, 0x62), field.value)
        assertFalse(wire.hasMore)
    }

    @Test
    fun `零长度字段读成空字节数组`() {
        val wire = ProtoWire.of(byteArrayOf(0x0A, 0x00, 0x10, 0x01))
        val field = wire.next() as ProtoWire.Field.Bytes
        assertEquals(0, field.value.size)
        assertEquals(1L, (wire.next() as ProtoWire.Field.Varint).value)
    }

    @Test
    fun `最大 field number 不越界`() {
        // protobuf 的 field number 上限是 2^29-1；key = (536870911<<3) << 3 wire0
        val key = 536870911L shl 3
        val wire = ProtoWire.of(varintBytes(key) + byteArrayOf(0x01))
        val field = wire.next() as ProtoWire.Field.Varint
        assertEquals(536870911, field.tag)
        assertEquals(1L, field.value)
    }

    // ───────────────────────── 异常 / 拒绝 ─────────────────────────

    @Test
    fun `不认识的 wire type 一律返回 null`() {
        // wire 3（已废弃的 start group）/ 4（end group）/ 6 / 7 都不是合法取值
        for (wireType in listOf(3, 4, 6, 7)) {
            val key = (1 shl 3) or wireType
            assertNull("wire type " + wireType + " 必须拒绝", ProtoWire.of(byteArrayOf(key.toByte())).next())
        }
    }

    @Test
    fun `field number 为 0 时返回 null`() {
        // key = 0 → field number 0；protobuf 规定 field number 从 1 开始
        assertNull(ProtoWire.of(byteArrayOf(0x00)).next())
        // wire 2 但 field number 0：0x02
        assertNull(ProtoWire.of(byteArrayOf(0x02, 0x01, 0x58)).next())
    }

    @Test
    fun `长度超过剩余字节时返回 null`() {
        // 声明 5 字节却只剩 2 字节
        assertNull(ProtoWire.of(byteArrayOf(0x0A, 0x05, 0x61, 0x62)).next())
    }

    @Test
    fun `length-delimited 的长度字段被截断时返回 null`() {
        // 0x0A 之后没有长度字节
        assertNull(ProtoWire.of(byteArrayOf(0x0A)).next())
        // 长度 varint 以续位结尾却没有后续字节
        assertNull(ProtoWire.of(byteArrayOf(0x0A, 0x80.toByte())).next())
    }

    @Test
    fun `负长度（10 字节全续位的 varint）被拒绝`() {
        // LEB128 解出的值超过 Long 范围时是负数；负长度必须直接拒，不能当长度用
        val negativeLength = ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x7F)
        val wire = ProtoWire.of(byteArrayOf(0x0A) + negativeLength + byteArrayOf(0x61, 0x62))
        assertNull(wire.next())
    }

    @Test
    fun `定长字段越界时返回 null`() {
        // fixed64 只剩 4 字节
        assertNull(ProtoWire.of(byteArrayOf(0x19) + ByteArray(4)).next())
        // fixed32 只剩 3 字节
        assertNull(ProtoWire.of(byteArrayOf(0x25) + ByteArray(3)).next())
    }

    @Test
    fun `超长 varint 返回 null`() {
        // 连续 10 个 0x80：LEB128 超过 64 位还没见到终止位
        assertNull(ProtoWire.of(ByteArray(10) { 0x80.toByte() }).next())
        // key 本身超长
        assertNull(ProtoWire.of(ByteArray(11) { 0x80.toByte() }).next())
    }

    @Test
    fun `varint 截断在末尾返回 null`() {
        // 0x10（tag2 wire0）后没有值字节
        assertNull(ProtoWire.of(byteArrayOf(0x10)).next())
        // 0x80 结尾表示还有后续字节，但没有
        assertNull(ProtoWire.of(byteArrayOf(0x10, 0x80.toByte())).next())
    }

    @Test
    fun `一次坏字段之后不再继续读`() {
        // 第一个字段坏（长度 9 越界）→ next() 为 null；调用方按「整体损坏」处理
        val wire = ProtoWire.of(byteArrayOf(0x0A, 0x09, 0x61))
        assertNull(wire.next())
        // 读取器状态停在坏字段起点之前，hasMore 仍为真（由调用方决定不再读）
        assertTrue(wire.hasMore)
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private fun varintBytes(value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v < 0x80) {
                out.write(v.toInt())
                return out.toByteArray()
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}
