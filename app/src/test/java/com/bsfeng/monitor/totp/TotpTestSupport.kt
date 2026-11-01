package com.bsfeng.monitor.totp

import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpParams
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** 公开测试密钥（RFC 6238 附录里常见的样例值，不是真实账号）。 */
const val TEST_SECRET = "JBSWY3DPEHPK3PXP"

/**
 * 测试替身：JVM 内存密钥的 AES-256-GCM。
 *
 * Robolectric 沙箱里没有可用的 AndroidKeyStore provider，所以生产实现
 * [KeystoreTotpSecretCipher] 只能在真机验证；本替身与它同格式（iv12 + ciphertext），
 * 用来验证 [TotpSecretStore] 的存取、隔离与失败行为。
 */
class SoftwareAesGcmCipher : TotpSecretCipher {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}

fun testAccount(
    issuer: String = "GitHub",
    accountName: String = "alice@acme.com",
    secret: String = TEST_SECRET,
    algorithm: String = TotpParams.ALGORITHM_SHA1,
    digits: Int = TotpParams.DEFAULT_DIGITS,
    periodSec: Int = TotpParams.DEFAULT_PERIOD_SEC,
): TotpAccount = TotpAccount(
    issuer = issuer,
    accountName = accountName,
    secretBase32 = secret,
    params = TotpParams(algorithm = algorithm, digits = digits, periodSec = periodSec),
)

fun testRow(
    issuer: String = "GitHub",
    accountName: String = "alice@acme.com",
    algorithm: String = TotpParams.ALGORITHM_SHA1,
    digits: Int = TotpParams.DEFAULT_DIGITS,
    periodSec: Int = TotpParams.DEFAULT_PERIOD_SEC,
    sortOrder: Int = 0,
    now: Long = 1_700_000_000_000L,
): TotpAccountRow = TotpEntity.toRow(
    account = testAccount(issuer, accountName, algorithm = algorithm, digits = digits, periodSec = periodSec),
    sortOrder = sortOrder,
    now = now,
)

/** 随机字节（测试密文/盐用）。 */
fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
