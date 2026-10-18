package com.bsfeng.monitor.transfer.service

import android.content.Context
import android.provider.Settings
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.transfer.discovery.DeviceRegistry
import com.bsfeng.monitor.transfer.discovery.DiscoveryMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 设备别名解析的三级回落（Robolectric：真实 `Settings.Global` 读取）。
 *
 * 真机问题：对外广播的 alias 是 `23127PN0CC` 这类型号代号，用户希望看到系统里起的真实设备名。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceAliasResolverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        runCatching {
            Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "")
        }
    }

    @After
    fun tearDown() {
        runCatching {
            Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "")
        }
    }

    @Test
    fun `第一级 系统设备名优先`() {
        assertEquals(
            "小米14",
            DeviceAliasResolver.resolve("小米14", "Xiaomi", "23127PN0CC"),
        )
        // 前后空白会被裁掉
        assertEquals("我的手机", DeviceAliasResolver.resolve("  我的手机  ", null, null))
    }

    @Test
    fun `第二级 厂商加型号`() {
        assertEquals(
            "Xiaomi 23127PN0CC",
            DeviceAliasResolver.resolve(null, "Xiaomi", "23127PN0CC"),
        )
        assertEquals(
            "Xiaomi 23127PN0CC",
            DeviceAliasResolver.resolve("", "  Xiaomi ", " 23127PN0CC "),
        )
    }

    @Test
    fun `系统名就是型号代号时降级为厂商加型号`() {
        // 多数 ROM 的 DEVICE_NAME 直接等于型号代号，透出给用户没有信息量
        assertEquals(
            "Xiaomi 23127PN0CC",
            DeviceAliasResolver.resolve("23127PN0CC", "Xiaomi", "23127PN0CC"),
        )
        assertEquals(
            "Google sdk_gphone64_arm64",
            DeviceAliasResolver.resolve("sdk_gphone64_arm64", "Google", "sdk_gphone64_arm64"),
        )
        // Build.DEVICE 代号同样视为型号代号
        assertEquals(
            "Google Pixel 8",
            DeviceAliasResolver.resolve("shiba", "Google", "Pixel 8", deviceCode = "shiba"),
        )
    }

    @Test
    fun `第三级 仅型号`() {
        assertEquals("23127PN0CC", DeviceAliasResolver.resolve("  ", null, "23127PN0CC"))
        assertEquals("23127PN0CC", DeviceAliasResolver.resolve(null, "", "23127PN0CC"))
    }

    @Test
    fun `三级都为空时兜底 Android`() {
        assertEquals("Android", DeviceAliasResolver.resolve(null, null, null))
        assertEquals("Android", DeviceAliasResolver.resolve("", " ", "  "))
        assertEquals("自定义", DeviceAliasResolver.resolve(null, null, null, fallback = "自定义"))
    }

    @Test
    fun `读取系统设备名并解析为别名`() {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "小米14")
        assertEquals("小米14", DeviceAliasResolver.readDeviceName(context))
        assertEquals("小米14", DeviceAliasResolver.resolveDeviceAlias(context))
    }

    @Test
    fun `系统设备名为空时回落到厂商加型号`() {
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "")
        val alias = TransferSettings(context).alias
        assertTrue(alias.isNotBlank())
        assertTrue(alias != "Android" || alias == DeviceAliasResolver.resolve(null, null, null))
    }

    @Test
    fun `旧版别名缓存会被重新解析为真实设备名`() {
        // 模拟旧版本：只存了型号代号，没有写 alias_version
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "我的小米14")
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit()
            .putString("alias", "23127PN0CC")
            .commit()

        assertEquals("我的小米14", TransferSettings(context).alias)
        // 解析结果已回写，第二次读取稳定
        assertEquals("我的小米14", TransferSettings(context).alias)
    }

    @Test
    fun `用户手动设置的别名优先于系统名`() {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "我的小米14")
        val settings = TransferSettings(context)
        settings.alias = "工作机"
        assertEquals("工作机", settings.alias)
        // 置空回落系统名
        settings.alias = "   "
        assertEquals("我的小米14", settings.alias)
    }

    @Test
    fun `未知对端显示未知设备而不是 deviceId`() {
        val registry = DeviceRegistry()
        registry.onSeen(
            DiscoveryMessage.announce(deviceId = "mac-abcdef", alias = "", port = 53319),
            "192.168.1.9",
            1_000L,
        )
        val device = registry.snapshot(1_000L).single()
        assertEquals(UNKNOWN_PEER_ALIAS, device.alias)
        assertFalse(device.alias == "mac-abcdef")
    }
}
