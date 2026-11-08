package com.bsfeng.monitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SecretBox] 的落盘编码口径：`v1:` 前缀是「密文 vs 旧明文」的唯一判据。
 *
 * 这条判据必须严 —— 旧明文（hex / JWT / JSON）与 Base64 可能撞形，
 * 猜错要么迁移丢数据，要么把明文当密文解成空。
 */
class SecretBoxTest {

    private val cipher = SoftSecretCipher()

    @Test
    fun `编码解码往返一致`() {
        val plain = "hello-机密-42".toByteArray(Charsets.UTF_8)
        val encoded = SecretBox.encode(cipher, plain)

        assertTrue(SecretBox.isEncoded(encoded))
        assertTrue(encoded.startsWith("v1:"))
        assertFalse("Base64 里不该出现明文", encoded.contains("hello"))
        assertEquals("hello-机密-42", String(SecretBox.decode(cipher, encoded), Charsets.UTF_8))
    }

    @Test
    fun `旧明文一律判为非密文`() {
        assertFalse(SecretBox.isEncoded(null))
        assertFalse(SecretBox.isEncoded(""))
        assertFalse(SecretBox.isEncoded("{}"))
        assertFalse(SecretBox.isEncoded("""{"and-7f3a9c":"ab12"}"""))
        assertFalse(SecretBox.isEncoded("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `对明文调用解码直接拒绝`() {
        SecretBox.decode(cipher, "plain-text")
    }

    @Test(expected = Exception::class)
    fun `篡改密文解码抛异常`() {
        val encoded = SecretBox.encode(cipher, "x".toByteArray())
        SecretBox.decode(cipher, encoded.dropLast(4) + "AAAA")
    }
}
