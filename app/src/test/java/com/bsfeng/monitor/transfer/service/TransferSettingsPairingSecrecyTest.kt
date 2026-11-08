package com.bsfeng.monitor.transfer.service

import android.content.Context
import com.bsfeng.monitor.util.FailingSecretCipher
import com.bsfeng.monitor.util.SecretCipher
import com.bsfeng.monitor.util.SoftSecretCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 配对密钥的落盘保密性（SEC-02）。
 *
 * `pairing_keys` 命中即免 PIN 直批（TransferReceiveRoutes），所以「拿到 prefs 文件＝拿到通行证」
 * 必须不成立：落盘是 Keystore 密文、旧明文读一次即升级、解不开按未配对处理。
 * 与 [TransferSettingsTest] 分开：那条测协议口径，这条只测存储保密性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferSettingsPairingSecrecyTest {

    private val app get() = RuntimeEnvironment.getApplication()

    /**
     * 一把固定的软件 cipher：跨实例读回必须是同一把钥匙，
     * 每个用例里随手 new 一个会变成「换钥匙」，把解密失败误判成实现问题。
     */
    private val cipher = SoftSecretCipher()

    private val prefs
        get() = app.getSharedPreferences(TransferSettings.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun settings(): TransferSettings = TransferSettings(app, cipher)

    private fun settings(other: SecretCipher): TransferSettings = TransferSettings(app, other)

    @Test
    fun `保存后落盘不是明文 JSON 且能读回同一密钥`() {
        val key = TransferSettings.generatePairingKey()
        settings().savePairingKey(DEVICE, key)

        val stored = prefs.getString(KEY, null)
        assertTrue("新值必须是 v1: 密文格式", stored != null && stored.startsWith("v1:"))
        assertFalse("落盘不能出现密钥明文", stored!!.contains(key))
        assertFalse("落盘不能出现对端 deviceId 明文", stored.contains(DEVICE))
        assertEquals(key, settings().pairingKey(DEVICE))
    }

    @Test
    fun `旧明文 JSON 读一次即升级为密文`() {
        prefs.edit().putString(KEY, """{"$DEVICE":"$LEGACY_KEY"}""").commit()

        assertEquals(LEGACY_KEY, settings().pairingKey(DEVICE))

        val stored = prefs.getString(KEY, null)
        assertTrue("迁移后应为 v1: 密文", stored != null && stored.startsWith("v1:"))
        assertFalse("迁移后盘上不应再有密钥明文", stored!!.contains(LEGACY_KEY))
        assertEquals("迁移后仍可读回", LEGACY_KEY, settings().pairingKey(DEVICE))
    }

    @Test
    fun `换一把钥匙解不开按未配对处理`() {
        settings().savePairingKey(DEVICE, LEGACY_KEY)

        // 另一把钥匙 = Keystore 被系统重置：fail-closed，绝不用半截数据去判免 PIN
        val other = settings(SoftSecretCipher())
        assertEquals(0, other.pairingKeys().size)
        assertNull(other.pairingKey(DEVICE))
    }

    @Test
    fun `移除最后一条配对后盘上不留空表`() {
        val one = settings()
        one.savePairingKey(DEVICE, LEGACY_KEY)
        one.removePairingKey(DEVICE)

        assertNull(prefs.getString(KEY, null))
        assertEquals(0, one.pairingKeys().size)
    }

    @Test
    fun `加密不可用时既不落明文也不崩`() {
        settings(FailingSecretCipher()).savePairingKey(DEVICE, LEGACY_KEY)

        assertNull(prefs.getString(KEY, null))
        assertEquals(0, settings(FailingSecretCipher()).pairingKeys().size)
    }

    private companion object {
        const val DEVICE = "and-7f3a9c"
        const val KEY = "pairing_keys"

        /** 32 字节 hex 的样例值（不是真实密钥）。 */
        const val LEGACY_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
