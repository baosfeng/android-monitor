package com.bsfeng.monitor.util

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * 测试替身：JVM 内存密钥的 AES-256-GCM，格式与 [KeystoreAesGcmCipher] 一致（iv12 + ciphertext）。
 *
 * Robolectric 沙箱里没有 AndroidKeyStore provider，生产实现的密钥只能在真机生成；
 * 本替身用来验证「落盘是密文 / 读回是原文 / 旧明文迁移 / 坏数据兜底」这些与密钥来源无关的口径。
 */
class SoftSecretCipher : SecretCipher {

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

/** 加密一律失败（Keystore 不可用 / 密钥被重置）的替身：用来钉住「失败也不退回明文」。 */
class FailingSecretCipher : SecretCipher {

    override fun encrypt(plain: ByteArray): ByteArray = throw IllegalStateException("keystore 不可用")

    override fun decrypt(blob: ByteArray): ByteArray = throw IllegalStateException("keystore 不可用")
}
