package com.bsfeng.monitor.totp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * [TotpBackup] 单元测试（Robolectric，只为拿到可用的 org.json）。
 *
 * 覆盖：导出→导入往返等价、错密码失败、短密码拒绝、篡改密文失败、超大输入拒绝、
 * 未知字段忽略（向后兼容）、未知版本拒绝、Aegis / 2FAS 解析（含跳过原因）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpBackupTest {

    private val backup = TotpBackup()
    private val passphrase = "correct horse battery"

    private val entries = listOf(
        TotpBackupEntry(
            issuer = "GitHub",
            account = "alice@acme.com",
            secret = "JBSWY3DPEHPK3PXP",
        ),
        TotpBackupEntry(
            issuer = "阿里云",
            account = "dev@acme.cn",
            secret = "GEZDGNBVGY3TQOJQ",
            algorithm = "SHA256",
            digits = 8,
            period = 60,
        ),
    )

    @Test
    fun `导出再导入往返等价`() {
        val data = backup.export(entries, passphrase, now = 1_700_000_000_000L)
        val restored = backup.import(data, passphrase)

        assertEquals(entries, restored)
    }

    @Test
    fun `错误口令给出明确失败而不是脏数据`() {
        val data = backup.export(entries, passphrase)
        try {
            backup.import(data, "wrong passphrase")
            fail("错误口令不应成功")
        } catch (e: TotpBackupException) {
            assertTrue(e.message!!.contains("口令不正确"))
        }
    }

    @Test
    fun `短密码与空列表直接拒绝`() {
        assertRejected { backup.export(entries, "1234567") }
        assertRejected { backup.export(emptyList(), passphrase) }
    }

    @Test
    fun `篡改密文后导入失败`() {
        val text = backup.export(entries, passphrase).toString(Charsets.UTF_8)
        val lines = text.trim().lines().toMutableList()
        val decoded = Base64.getDecoder().decode(lines[2])
        decoded[decoded.size - 1] = (decoded[decoded.size - 1] + 1).toByte()
        lines[2] = Base64.getEncoder().encodeToString(decoded)

        assertRejected { backup.import(lines.joinToString("\n").toByteArray(), passphrase) }
    }

    @Test
    fun `超大输入直接拒绝`() {
        assertRejected { backup.import(ByteArray(5 * 1024 * 1024 + 1), passphrase) }
        assertRejected { backup.importExternal(" ".repeat(5 * 1024 * 1024 + 1)) }
    }

    @Test
    fun `未知字段忽略且老版本数据仍可导入`() {
        val payload = JSONObject().apply {
            put("version", 1)
            put("app", "android-monitor")
            put("futureTop", "ignored")
            put(
                "accounts",
                JSONArray().put(
                    JSONObject().apply {
                        put("issuer", "GitHub")
                        put("account", "alice@acme.com")
                        put("secret", "JBSWY3DPEHPK3PXP")
                        put("futureField", true)
                    },
                ),
            )
        }
        val restored = backup.import(encryptPayload(payload), passphrase)

        assertEquals(1, restored.size)
        assertEquals("GitHub", restored[0].issuer)
        assertEquals("JBSWY3DPEHPK3PXP", restored[0].secret)
    }

    @Test
    fun `未知版本拒绝而非猜着读`() {
        val payload = JSONObject().apply {
            put("version", 99)
            put("accounts", JSONArray())
        }
        assertRejected { backup.import(encryptPayload(payload), passphrase) }
    }

    @Test
    fun `Aegis 明文导入：可用条目收下，HOTP 缺密钥 7 位码全部跳过并说明原因`() {
        val json = """
            {"version":1,"entries":[
              {"type":"totp","name":"alice@acme.com","issuer":"GitHub",
               "info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA1","digits":6,"period":30}},
              {"type":"hotp","name":"legacy","issuer":"Old",
               "info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA1","digits":6,"period":30}},
              {"type":"totp","name":"no-secret","issuer":"X","info":{"algo":"SHA1"}},
              {"type":"totp","name":"bad-digits","issuer":"Y",
               "info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA1","digits":7,"period":30}}
            ]}
        """.trimIndent()

        val result = backup.importExternal(json)

        assertEquals(1, result.entries.size)
        assertEquals("GitHub", result.entries[0].issuer)
        assertEquals(3, result.skipped.size)
        assertEquals(4, result.total)
        assertTrue(result.skipped.any { it.contains("legacy") })
        assertTrue(result.skipped.any { it.contains("no-secret") })
        assertTrue(result.skipped.any { it.contains("bad-digits") })
    }

    @Test
    fun `2FAS 导入：STEAM 变体跳过且不静默`() {
        val json = """
            {"services":[
              {"name":"GitHub","secret":"JBSWY3DPEHPK3PXP",
               "otp":{"label":"alice","account":"alice@acme.com","issuer":"GitHub",
                      "digits":6,"period":30,"algorithm":"SHA1","tokenType":"TOTP"}},
              {"name":"Steam","secret":"JBSWY3DPEHPK3PXP",
               "otp":{"label":"steam","issuer":"Steam","tokenType":"STEAM"}}
            ],"schemaVersion":3}
        """.trimIndent()

        val result = backup.importExternal(json)

        assertEquals(1, result.entries.size)
        assertEquals("alice@acme.com", result.entries[0].account)
        assertEquals(1, result.skipped.size)
        assertTrue(result.skipped[0].contains("Steam"))
    }

    @Test
    fun `无法识别的 JSON 与坏 JSON 都报错`() {
        assertRejected { backup.importExternal("{\"foo\":1}") }
        assertRejected { backup.importExternal("not json") }
    }

    /** 用与生产相同的 KDF/封装格式，自定义载荷 —— 测未知字段/未知版本路径。 */
    private fun encryptPayload(payload: JSONObject): ByteArray {
        val salt = randomBytes(16)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 210_000, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        val blob = salt + cipher.iv + cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val text = "AMTOTP/1\npbkdf2-sha256:210000\n" +
            Base64.getEncoder().encodeToString(blob) + "\n"
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("应当抛 TotpBackupException")
        } catch (_: TotpBackupException) {
            // 预期
        }
    }
}
