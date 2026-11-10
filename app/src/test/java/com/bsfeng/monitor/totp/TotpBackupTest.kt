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
    fun `Aegis 明文导入：TOTP 与 HOTP 都收下（含 7 位码），只有缺密钥那条跳过并说明原因`() {
        val json = """
            {"version":1,"entries":[
              {"type":"totp","name":"alice@acme.com","issuer":"GitHub",
               "info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA1","digits":6,"period":30}},
              {"type":"hotp","name":"legacy","issuer":"Old",
               "info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA1","digits":6,"period":30,"counter":5}},
              {"type":"totp","name":"no-secret","issuer":"X","info":{"algo":"SHA1"}},
              {"type":"totp","name":"seven-digits","issuer":"Y",
               "info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA1","digits":7,"period":30}}
            ]}
        """.trimIndent()

        val result = backup.importExternal(json)

        // 契约放宽后 7 位码与 HOTP 都在支持面内，不再是「跳过」
        assertEquals(3, result.entries.size)
        assertEquals(1, result.skipped.size)
        assertEquals(4, result.total)
        assertEquals("GitHub", result.entries[0].issuer)
        assertTrue(result.skipped[0].contains("no-secret"))
        assertEquals("HOTP", result.entries[1].type)
        assertEquals(5L, result.entries[1].counter)
        assertEquals(7, result.entries[2].digits)
    }

    @Test
    fun `2FAS 导入：STEAM 是展示变体不是类型，按 TOTP 收下不跳过`() {
        val json = """
            {"services":[
              {"name":"GitHub","secret":"JBSWY3DPEHPK3PXP",
               "otp":{"label":"alice","account":"alice@acme.com","issuer":"GitHub",
                      "digits":6,"period":30,"algorithm":"SHA1","tokenType":"TOTP"}},
              {"name":"Steam","secret":"cnOgvVunvLFR1gZAwzDxDrgbXWM=",
               "otp":{"label":"steam","issuer":"Steam","tokenType":"STEAM"}}
            ],"schemaVersion":3}
        """.trimIndent()

        val result = backup.importExternal(json)

        // STEAM 只影响展示形态（5 位字母表），出码口径仍是 TOTP，因此不再当作不支持而跳过
        assertEquals(2, result.entries.size)
        assertEquals("alice@acme.com", result.entries[0].account)
        assertEquals("Steam", result.entries[1].issuer)
        assertEquals(TotpBackupEntry.TYPE_TOTP, result.entries[1].type)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `2FAS v4 有 otp_link 时以 link 为准，7 位码与 20 秒周期原样收下`() {
        val json = """
            {"services":[
              {"name":"Real","secret":"JBSWY3DPEHPK3PXP",
               "otp":{"link":"otpauth://totp/Real:acct@acme.com?secret=JBSWY3DPEHPK3PXP&issuer=Real&digits=7&period=20&algorithm=SHA256"}},
              {"name":"Bad link","secret":"JBSWY3DPEHPK3PXP",
               "otp":{"link":"otpauth://totp/Bad?secret="}},
              {"name":"Broken link","secret":"JBSWY3DPEHPK3PXP",
               "otp":{"link":"https://example.com/steal"}}
            ],"schemaVersion":4}
        """.trimIndent()

        val result = backup.importExternal(json)

        assertEquals(1, result.entries.size)
        assertEquals(7, result.entries[0].digits)
        assertEquals(20, result.entries[0].period)
        assertEquals("SHA256", result.entries[0].algorithm)
        assertEquals(2, result.skipped.size)
        assertTrue(result.skipped.any { it.contains("Bad link") })
        assertTrue(result.skipped.any { it.contains("Broken link") })
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
