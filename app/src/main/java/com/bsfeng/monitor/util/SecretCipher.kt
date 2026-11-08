package com.bsfeng.monitor.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 落盘前的对称加解密接口。
 *
 * 抽成接口只为可测：Robolectric 沙箱里没有 AndroidKeyStore provider，
 * 生产只走 [KeystoreAesGcmCipher]，软件实现只允许出现在 `app/src/test/`
 * （与 quota/QuotaCredentialStore、totp/TotpSecretStore 的既有做法同一口径）。
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * 生产实现：AndroidKeyStore 里的 AES-256-GCM 密钥，别名由调用方指定。
 *
 * 为什么一处一个别名：某个功能的密钥被系统重置时不该连带别处一起失效，
 * 审计/排障时也能按别名回答「这条数据由哪把钥匙保护」。别名必须是稳定字符串 ——
 * 换名字等于换钥匙，旧密文直接不可解。
 *
 * 输出格式：`iv(12B) + ciphertext + tag(16B)`，与 quota/totp 两份既有实现逐字节同构。
 */
class KeystoreAesGcmCipher(private val alias: String) : SecretCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE) { "密文长度不足" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

/**
 * SharedPreferences 里「明文 → 密文」的落盘编码：`v1:` + Base64(iv + ciphertext)。
 *
 * 用 java.util.Base64（minSdk 29 可用）而不是 android.util.Base64：本类因此不依赖 android 运行时，
 * 纯 JVM 单测能直接覆盖编码口径。
 *
 * 为什么加版本前缀而不是只写 Base64：老版本把这几个键当明文存，
 * 读的时候必须能无歧义区分「这是旧明文」和「这是新密文」——
 * 旧明文（随机 hex / JWT / JSON）与 Base64 完全可能撞形，靠长度或字符集猜会误判。
 */
object SecretBox {

    private const val PREFIX = "v1:"

    /** 是否为本类密文格式（false 即按旧明文处理）。 */
    fun isEncoded(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

    /** 加密并编码成可直接放进 prefs 的字符串。 */
    fun encode(cipher: SecretCipher, plain: ByteArray): String =
        PREFIX + Base64.getEncoder().encodeToString(cipher.encrypt(plain))

    /**
     * 解码成明文。
     *
     * 非本格式或解密失败（密钥被重置 / 数据损坏）一律抛异常，由调用方决定
     * 「按没有处理」还是「保留原数据」——本层不替业务做清除决定。
     */
    fun decode(cipher: SecretCipher, stored: String): ByteArray {
        require(isEncoded(stored)) { "不是本类密文格式" }
        val blob = Base64.getDecoder().decode(stored.substring(PREFIX.length))
        return cipher.decrypt(blob)
    }
}
