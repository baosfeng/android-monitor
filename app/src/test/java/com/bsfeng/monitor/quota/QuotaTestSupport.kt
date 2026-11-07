package com.bsfeng.monitor.quota

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import java.math.BigDecimal
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * 测试替身：JVM 内存密钥的 AES-256-GCM。
 *
 * Robolectric 沙箱里没有可用的 AndroidKeyStore provider，所以生产实现
 * [KeystoreQuotaSecretCipher] 只能在真机验证；本替身与它同格式（iv12 + ciphertext），
 * 用来验证 [QuotaCredentialStore] 的存取与清除。**只存在于测试源集**，main 不引用它。
 */
class SoftwareQuotaCipher : QuotaSecretCipher {

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

/** 只回固定额度项的假 provider：测试不联网，凭证内容不影响返回值。 */
class FakeQuotaProvider(
    override val id: String = "fake",
    override val displayName: String = "假 provider",
    override val balanceCredentialKind: QuotaCredentialKind = QuotaCredentialKind.API_KEY,
    override val usageCredentialKind: QuotaCredentialKind? = QuotaCredentialKind.PLATFORM_TOKEN,
    override val supportsModelBreakdown: Boolean = false,
    override val credentialHint: String = "测试 Key",
    override val credentialHelp: String = "",
    private val items: List<QuotaItem> = listOf(sampleItem()),
) : QuotaProvider {

    /** 被调用次数：用来断言「清除后不再发请求」。 */
    var fetchCount: Int = 0
        private set

    override suspend fun fetchItems(credential: String): List<QuotaItem> {
        fetchCount++
        return items
    }

    override suspend fun fetchUsage(credential: String, range: QuotaRange): QuotaUsage? {
        fetchCount++
        return null
    }
}

/** 一条最小额度项（百分比型，能进聚合榜）。 */
fun sampleItem(id: String = "session", remaining: String = "42"): QuotaItem = QuotaItem(
    id = id,
    title = "会话额度",
    kind = QuotaKind.PERCENT,
    remaining = BigDecimal(remaining),
    total = BigDecimal(100),
    unit = QuotaUnit.PERCENT,
    primary = true,
)

/** 生成一段不会进仓库的占位密钥（每进程随机，杜绝真实凭据进测试）。 */
fun placeholderSecret(prefix: String = "sk-test-"): String {
    val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
    return prefix + bytes.joinToString("") { "%02x".format(it) }
}
