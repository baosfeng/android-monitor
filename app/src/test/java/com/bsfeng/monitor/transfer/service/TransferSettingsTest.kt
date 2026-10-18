package com.bsfeng.monitor.transfer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [TransferSettings] / [PairingCodec] / [TransferSoundPlayer] 的本地状态测试（Robolectric）。
 *
 * 覆盖协议 §5（配对按 deviceId 持久化、可移除）与 §7（三项提示音默认值）。
 * 真正跑传输需要真机网络，本类只钉住「设置与配对」这一类纯本地契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferSettingsTest {

    @Before
    fun clearPrefs() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(TransferSettings.PREFS_NAME, 0)
            .edit()
            .clear()
            .commit()
    }

    private fun settings(): TransferSettings = TransferSettings(RuntimeEnvironment.getApplication())

    @Test
    fun `deviceId 稳定且符合 and 前缀规范`() {
        val settings = settings()
        val first = settings.deviceId
        assertTrue(first.startsWith("and-"))
        assertEquals(10, first.length)
        // 同一实例与新建实例都稳定
        assertEquals(first, settings.deviceId)
        assertEquals(first, settings().deviceId)
    }

    @Test
    fun `配对密钥按 deviceId 持久化并可移除`() {
        val settings = settings()
        val key = TransferSettings.generatePairingKey()
        settings.savePairingKey("mac-1", key)

        assertEquals(key, settings.pairingKey("mac-1"))
        assertEquals(setOf("mac-1"), settings().pairingKeys().keys)

        settings.removePairingKey("mac-1")
        assertNull(settings.pairingKey("mac-1"))
        assertEquals(0, settings().pairingKeys().size)
    }

    @Test
    fun `配对密钥为 32 字节 hex PIN 为 4 位数字`() {
        val key = TransferSettings.generatePairingKey()
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })

        repeat(50) {
            val pin = TransferSettings.generatePin()
            assertEquals(4, pin.length)
            assertTrue(pin.all { it.isDigit() })
        }
    }

    @Test
    fun `提示音默认值符合协议第七节`() {
        val settings = settings()
        // 完成开 / 失败关 / 新设备关
        assertTrue(settings.soundOnComplete)
        assertTrue(!settings.soundOnFailure)
        assertTrue(!settings.soundOnNewDevice)

        settings.soundOnComplete = false
        settings.soundOnFailure = true
        assertTrue(!settings().soundOnComplete)
        assertTrue(settings().soundOnFailure)
    }

    @Test
    fun `别名默认取机型且可修改`() {
        val settings = settings()
        val defaultAlias = settings.alias
        assertTrue(defaultAlias.isNotBlank())
        settings.alias = "我的小米"
        assertEquals("我的小米", settings().alias)
        settings.alias = "   "
        assertEquals(defaultAlias, settings().alias)
    }

    @Test
    fun `可被发现默认开启且可持久化关闭`() {
        val settings = settings()
        assertTrue(settings.discoverable)
        settings.discoverable = false
        assertTrue(!settings().discoverable)
        settings().discoverable = true
        assertTrue(settings.discoverable)
    }

    @Test
    fun `后台接收默认关闭且可持久化开启`() {
        val settings = settings()
        assertTrue(!settings.backgroundReceive)
        settings.backgroundReceive = true
        assertTrue(settings().backgroundReceive)
        settings().backgroundReceive = false
        assertTrue(!settings.backgroundReceive)
    }

    @Test
    fun `暂停接收持久化并向通知暴露剩余时间`() {
        val settings = settings()
        assertTrue(!settings.isOffersPaused())
        assertEquals(0L, settings.pauseRemainingMs())

        val now = System.currentTimeMillis()
        settings.pauseOffers(nowMs = now)
        assertTrue(settings().isOffersPaused(now + 1_000L))
        assertEquals(5 * 60_000L - 1_000L, settings().pauseRemainingMs(now + 1_000L))
        // 到期自动恢复
        assertTrue(!settings().isOffersPaused(now + 6 * 60_000L))

        settings().resumeOffers()
        assertTrue(!settings.isOffersPaused(now + 1_000L))
    }

    @Test
    fun `免配对窗口持久化并可判定生效`() {
        val settings = settings()
        assertTrue(!settings.isOpenPairingActive())
        assertEquals(0L, settings.openPairingRemainingMs())

        val now = System.currentTimeMillis()
        settings.startOpenPairingWindow(nowMs = now, minutes = 10)
        // 换实例仍生效（持久化）
        val reloaded = settings()
        assertTrue(reloaded.isOpenPairingActive(now + 1_000L))
        assertEquals(10 * 60_000L - 1_000L, reloaded.openPairingRemainingMs(now + 1_000L))
        assertEquals(10, reloaded.openPairingMinutes)

        reloaded.stopOpenPairingWindow()
        assertTrue(!settings().isOpenPairingActive(now + 1_000L))
    }

    @Test
    fun `免配对窗口到期自动失效`() {
        val settings = settings()
        val now = System.currentTimeMillis()
        settings.startOpenPairingWindow(nowMs = now, minutes = 5)
        // 6 分钟后：开关仍开着，但已过期
        assertTrue(settings.openPairingEnabled)
        assertTrue(!settings.isOpenPairingActive(now + 6 * 60_000L))
        assertEquals(0L, settings.openPairingRemainingMs(now + 6 * 60_000L))
    }

    @Test
    fun `免配对窗口时长只接受 5 10 30`() {
        val settings = settings()
        settings.openPairingMinutes = 7
        assertEquals(10, settings.openPairingMinutes)
        settings.openPairingMinutes = 30
        assertEquals(30, settings.openPairingMinutes)
    }

    @Test
    fun `体积阈值默认关闭且可持久化`() {
        val settings = settings()
        assertEquals(0, settings.autoApproveThresholdMb)
        assertTrue(!settings.shouldAutoApprove(1L))

        settings.autoApproveThresholdMb = 10
        assertEquals(10, settings().autoApproveThresholdMb)
        assertTrue(settings.shouldAutoApprove(10L * 1024 * 1024))
        assertTrue(!settings.shouldAutoApprove(10L * 1024 * 1024 + 1))
        // 负数按关闭处理
        settings.autoApproveThresholdMb = -5
        assertEquals(0, settings.autoApproveThresholdMb)
    }

    @Test
    fun `窗口与阈值任一命中都免确认`() {
        val settings = settings()
        settings.autoApproveThresholdMb = 50
        assertTrue(settings.shouldAutoApprove(20L * 1024 * 1024))
        assertTrue(!settings.shouldAutoApprove(80L * 1024 * 1024))

        settings.startOpenPairingWindow(minutes = 5)
        assertTrue(settings.shouldAutoApprove(80L * 1024 * 1024))
    }

    @Test
    fun `配对表序列化往返`() {
        val map = mapOf("mac-1" to "aaaa", "mac-2" to "bbbb")
        val encoded = PairingCodec.encode(map)
        assertEquals(map, PairingCodec.decode(encoded))
    }

    @Test
    fun `配对表坏数据解为空表`() {
        assertEquals(emptyMap<String, String>(), PairingCodec.decode(null))
        assertEquals(emptyMap<String, String>(), PairingCodec.decode(""))
        assertEquals(emptyMap<String, String>(), PairingCodec.decode("not json"))
        assertEquals(emptyMap<String, String>(), PairingCodec.decode("""{"mac-1":""}"""))
    }

    @Test
    fun `配对表编码跳过空键值`() {
        val encoded = PairingCodec.encode(mapOf("" to "x", "mac-1" to "", "mac-2" to "k"))
        assertEquals(mapOf("mac-2" to "k"), PairingCodec.decode(encoded))
    }
}
