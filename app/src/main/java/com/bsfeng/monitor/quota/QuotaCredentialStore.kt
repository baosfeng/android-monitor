package com.bsfeng.monitor.quota

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据的对称加解密接口。
 *
 * 抽成接口只为可测：Robolectric 沙箱里没有可用的 AndroidKeyStore provider。
 * **生产只走 [KeystoreQuotaSecretCipher]**，软件实现只允许出现在 `app/src/test/`
 * （照 `TotpSecretStore` 的同一做法，main 里不出现任何非 Keystore 分支）。
 */
interface QuotaSecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(blob: ByteArray): ByteArray
}

/** 生产实现：AndroidKeyStore 里的 AES-256-GCM 密钥（别名 quota_credential_key，不可导出）。 */
class KeystoreQuotaSecretCipher : QuotaSecretCipher {

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
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
        const val KEY_ALIAS = "quota_credential_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

/**
 * 额度凭据存储 —— Android Keystore AES-GCM 加密后落 SharedPreferences。
 *
 * 口径：
 * - 每个 provider 可分类型存（API Key / 平台登录令牌），键为 `providerId:kind`；
 * - 明文只在内存里出现，落盘是 `iv(12B) + ciphertext` 的 Base64；
 * - 不写日志、不导出、不进备份包（`android:allowBackup` 已关闭相关文件）。
 *
 * 读失败（密钥被系统重置、数据损坏）时返回 null 并清掉坏数据，让用户重填，不抛异常。
 */
class QuotaCredentialStore(
    context: Context,
    private val cipher: QuotaSecretCipher = KeystoreQuotaSecretCipher(),
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 写入凭据；空串等同于清除。 */
    fun save(providerId: String, kind: QuotaCredentialKind, value: String?) {
        val key = storageKey(providerId, kind)
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit { remove(key) }
            return
        }
        runCatching { cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)) }
            .onSuccess { encrypted -> prefs.edit { putString(key, base64(encrypted)) } }
            .onFailure { Log.w(TAG, "凭据加密失败：${it.javaClass.simpleName}") }
    }

    /** 读取凭据；未配置或解密失败返回 null。 */
    fun load(providerId: String, kind: QuotaCredentialKind): String? {
        val key = storageKey(providerId, kind)
        val stored = prefs.getString(key, null) ?: return null
        return runCatching { String(cipher.decrypt(unBase64(stored)), Charsets.UTF_8) }
            .onFailure {
                Log.w(TAG, "凭据解密失败，已清除：${it.javaClass.simpleName}")
                prefs.edit { remove(key) }
            }
            .getOrNull()
    }

    /** 是否已配置。 */
    fun has(providerId: String, kind: QuotaCredentialKind): Boolean = load(providerId, kind) != null

    /** 清除某个 provider 的全部凭据（两种类型都清）。 */
    fun clearAll(providerId: String) {
        prefs.edit {
            QuotaCredentialKind.entries.forEach { remove(storageKey(providerId, it)) }
        }
    }

    private fun storageKey(providerId: String, kind: QuotaCredentialKind): String =
        "$providerId:${kind.name}"

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unBase64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    internal companion object {
        const val TAG = "QuotaCredentialStore"
        const val PREFS_NAME = "quota_credentials"
    }
}
