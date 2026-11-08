package com.bsfeng.monitor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordHandlerTest {

    @Test
    fun `ScreenHandler checks`() {
        val h = ScreenHandler()
        assertEquals(RecordType.SCREEN, h.type)
        for (a in listOf("OFF", "UNLOCKED", "AD_SKIPPED", "AD_SKIP_UNCONFIRMED")) assertTrue(h.validate(a, null))
        assertFalse(h.validate("FOO", null))
        assertEquals("屏幕熄灭", h.createLabel("OFF", null))
        assertEquals("解锁成功", h.createLabel("UNLOCKED", null))
        assertEquals("跳过 X 广告", h.createLabel("AD_SKIPPED", mapOf("app_name" to "X")))
        assertEquals("跳过广告", h.createLabel("AD_SKIPPED", null))
        assertEquals("点击 X 跳过按钮（未确认）", h.createLabel("AD_SKIP_UNCONFIRMED", mapOf("app_name" to "X")))
        assertEquals("点击跳过按钮（未确认）", h.createLabel("AD_SKIP_UNCONFIRMED", null))
        assertEquals("锁屏", h.createLabel("OTHER", null))
        assertFalse(h.needsDuration("OFF"))
    }

    @Test
    fun `ChargingHandler checks`() {
        val h = ChargingHandler()
        assertEquals(RecordType.CHARGING, h.type)
        assertTrue(h.validate("STARTED", null))
        assertTrue(h.validate("STOPPED", null))
        assertFalse(h.validate("X", null))
        assertEquals("交流充电充电（电量 80%）", h.createLabel("STARTED", mapOf("type" to "交流充电", "level" to "80")))
        assertEquals("充电", h.createLabel("STARTED", null))
        assertEquals("停止充电（电量 80%）", h.createLabel("STOPPED", mapOf("level" to "80")))
        assertEquals("停止充电", h.createLabel("STOPPED", null))
        assertFalse(h.needsDuration("STARTED"))
    }

    @Test
    fun `AppUsageHandler checks`() {
        val h = AppUsageHandler()
        assertEquals(RecordType.APP_USAGE, h.type)
        for (a in listOf("OPENED", "CLOSED", "FOREGROUND")) assertTrue(h.validate(a, null))
        assertFalse(h.validate("X", null))
        assertEquals("打开微信", h.createLabel("OPENED", mapOf("app_name" to "微信")))
        assertEquals("关闭微信", h.createLabel("CLOSED", mapOf("app_name" to "微信")))
        assertEquals("微信", h.createLabel("FOREGROUND", mapOf("app_name" to "微信")))
        assertTrue(h.needsDuration("CLOSED"))
        assertFalse(h.needsDuration("OPENED"))
    }

    @Test
    fun `WifiHandler checks`() {
        val h = WifiHandler()
        assertEquals(RecordType.WIFI, h.type)
        assertTrue(h.validate("CONNECTED", null))
        assertTrue(h.validate("DISCONNECTED", null))
        assertFalse(h.validate("X", null))
        assertEquals("连接 WiFi：home", h.createLabel("CONNECTED", mapOf("ssid" to "home")))
        assertEquals("连接 WiFi", h.createLabel("CONNECTED", null))
        assertEquals("断开 WiFi：home", h.createLabel("DISCONNECTED", mapOf("ssid" to "home")))
        assertFalse(h.needsDuration("CONNECTED"))
    }

    @Test
    fun `AudioHandler checks`() {
        val h = AudioHandler()
        assertEquals(RecordType.AUDIO, h.type)
        for (a in listOf("PLUGGED", "UNPLUGGED", "BLUETOOTH_CONNECTED", "BLUETOOTH_DISCONNECTED")) assertTrue(h.validate(a, null))
        assertFalse(h.validate("X", null))
        assertEquals("插入耳机", h.createLabel("PLUGGED", null))
        assertEquals("拔出耳机", h.createLabel("UNPLUGGED", null))
        assertEquals("蓝牙连接：AirPods", h.createLabel("BLUETOOTH_CONNECTED", mapOf("device" to "AirPods")))
        assertEquals("蓝牙连接", h.createLabel("BLUETOOTH_CONNECTED", null))
        assertEquals("蓝牙断开：AirPods", h.createLabel("BLUETOOTH_DISCONNECTED", mapOf("device" to "AirPods")))
        assertFalse(h.needsDuration("PLUGGED"))
    }

    @Test
    fun `BatteryHandler checks`() {
        val h = BatteryHandler()
        assertEquals(RecordType.BATTERY, h.type)
        for (a in listOf("CHANGED", "LOW", "OKAY", "SAVE_MODE_ON", "SAVE_MODE_OFF")) assertTrue(h.validate(a, null))
        assertFalse(h.validate("X", null))
        assertEquals("电量 50%", h.createLabel("CHANGED", mapOf("level" to "50")))
        assertEquals("低电量警告（10%）", h.createLabel("LOW", mapOf("level" to "10")))
        assertEquals("电量恢复正常（90%）", h.createLabel("OKAY", mapOf("level" to "90")))
        assertEquals("开启省电模式", h.createLabel("SAVE_MODE_ON", null))
        assertEquals("关闭省电模式", h.createLabel("SAVE_MODE_OFF", null))
        assertFalse(h.needsDuration("LOW"))
    }

    @Test
    fun `PackageHandler checks`() {
        val h = PackageHandler()
        assertEquals(RecordType.PACKAGE, h.type)
        for (a in listOf("ADDED", "REMOVED", "REPLACED")) assertTrue(h.validate(a, null))
        assertFalse(h.validate("X", null))
        assertEquals("安装了 App", h.createLabel("ADDED", mapOf("app_name" to "App")))
        assertEquals("卸载了 pkg", h.createLabel("REMOVED", mapOf("package_name" to "pkg")))
        assertEquals("更新了 ", h.createLabel("REPLACED", null))
        assertFalse(h.needsDuration("ADDED"))
    }

    @Test
    fun `other handlers checks`() {
        val p = PowerSaveHandler()
        assertEquals(RecordType.POWER_SAVE, p.type)
        assertTrue(p.validate("ON", null)); assertTrue(p.validate("OFF", null)); assertFalse(p.validate("X", null))
        assertEquals("开启省电模式", p.createLabel("ON", null))
        assertEquals("关闭省电模式", p.createLabel("OFF", null))

        val a = AirplaneHandler()
        assertEquals(RecordType.AIRPLANE, a.type)
        assertTrue(a.validate("ON", null)); assertTrue(a.validate("OFF", null))
        assertEquals("开启飞行模式", a.createLabel("ON", null))
        assertEquals("关闭飞行模式", a.createLabel("OFF", null))

        val ph = PhoneStateHandler()
        assertEquals(RecordType.PHONE_STATE, ph.type)
        for (x in listOf("RINGING", "OFFHOOK", "IDLE")) assertTrue(ph.validate(x, null))
        assertFalse(ph.validate("X", null))
        assertEquals("来电", ph.createLabel("RINGING", null))
        assertEquals("通话中", ph.createLabel("OFFHOOK", null))
        assertEquals("挂断", ph.createLabel("IDLE", null))
        assertTrue(ph.needsDuration("OFFHOOK"))
        assertFalse(ph.needsDuration("RINGING"))

        val l = LocationHandler()
        assertEquals(RecordType.LOCATION, l.type)
        for (x in listOf("COORDINATE", "ENTER", "EXIT")) assertTrue(l.validate(x, null))
        assertFalse(l.validate("X", null))
        assertEquals("北京", l.createLabel("COORDINATE", mapOf("address" to "北京")))
        assertEquals("1.0,2.0", l.createLabel("COORDINATE", mapOf("lat" to "1.0", "lng" to "2.0")))
        assertEquals("位置", l.createLabel("COORDINATE", null))
        assertFalse(l.needsDuration("COORDINATE"))
    }
}
