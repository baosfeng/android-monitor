package com.bsfeng.monitor.totp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * TOTP 密钥的对称加解密接口。
 *
 * 抽成接口只为可测：Robolectric 沙箱里没有可用的 AndroidKeyStore provider，
 * 生产走 KeystoreTotpSecretCipher，单测注入软件 AES-GCM 替身。
 */
interface TotpSecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * 生产实现：AndroidKeyStore 里的 AES-256-GCM 密钥（别名 totp_secret_key，不可导出）。
 */
class KeystoreTotpSecretCipher : TotpSecretCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE) { "密文长度不足" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE),
        )
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
        const val KEY_ALIAS = "totp_secret_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

/**
 * TOTP 密钥存储 —— 落独立 SharedPreferences totp_secrets，值是 iv(12B)+ciphertext 的 Base64。
 *
 * 口径：
 * - 键是账户 id（库里 totp_accounts.id），删账户必须同时 delete；
 * - 明文只在内存出现，不写日志、不导出、不进系统备份；
 * - 解密失败不清数据：Keystore 被系统重置或数据损坏时返回 null 并记日志，由上层报「密钥不可读」，
 *   宁可让用户重装也不敢静默删掉唯一一份 2FA 密钥（删除不可恢复）。
 */
class TotpSecretStore(
    context: Context,
    private val cipher: TotpSecretCipher = KeystoreTotpSecretCipher(),
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 写入密钥；空白值等同于删除。加密失败直接抛出（调用方负责回滚，不留下半个账户）。 */
    fun save(accountId: Long, secret: String?) {
        val key = storageKey(accountId)
        val trimmed = secret?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit(commit = true) { remove(key) }
            return
        }
        val blob = cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(blob, Base64.NO_WRAP)
        // 用 commit 而不是 apply：密钥写失败必须当场暴露，让 Repository 回滚刚插入的行。
        // KTX 的 edit(commit = true) 返回 Unit，拿不到 commit 的返回值，改用「写后读回」判成败：
        // commit 失败时值不会落盘，读回拿不到目标值即视为写入失败。
        prefs.edit(commit = true) { putString(key, stored) }
        check(prefs.getString(key, null) == stored) { "密钥写入失败" }
    }

    /** 读取密钥；不存在或解密失败返回 null（失败不清数据）。 */
    fun load(accountId: Long): String? {
        val stored = prefs.getString(storageKey(accountId), null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            String(cipher.decrypt(blob), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "TOTP 密钥解密失败（保留原数据）：" + e.javaClass.simpleName)
            null
        }
    }

    /** 是否存有该账户的密钥（解密失败也算「有数据」，由上层展示错误态）。 */
    fun has(accountId: Long): Boolean = prefs.contains(storageKey(accountId))

    /** 清除某个账户的密钥（删账户时调用）。 */
    fun delete(accountId: Long) {
        prefs.edit(commit = true) { remove(storageKey(accountId)) }
    }

    /** 仅供测试：清空全部密钥。 */
    internal fun clearForTesting() {
        prefs.edit(commit = true) { clear() }
    }

    private fun storageKey(accountId: Long): String = "account:" + accountId

    internal companion object {
        const val TAG = "TotpSecretStore"
        const val PREFS_NAME = "totp_secrets"
        const val IV_SIZE = 12
    }
}
