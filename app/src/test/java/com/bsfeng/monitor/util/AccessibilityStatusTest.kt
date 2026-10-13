package com.bsfeng.monitor.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [AccessibilityStatus] 单元测试。
 *
 * 覆盖 P1-6（设置页误报"未开启"）修复后的判定规则：
 * 系统设置项 / 服务实例任一命中即视为已开启。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityStatusTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication() as Context
        putEnabledServices("")
    }

    private fun putEnabledServices(value: String) {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            value
        )
    }

    private fun component(className: String, packageName: String = context.packageName): String =
        ComponentName(packageName, className).flattenToString()

    // ==================== 正常路径 ====================

    @Test
    fun `设置项里勾选了本服务时视为已开启`() {
        putEnabledServices(component(AccessibilityStatus.SERVICE_CLASS))
        assertTrue(AccessibilityStatus.isEnabledInSettings(context))
        assertTrue(AccessibilityStatus.isEnabled(context, serviceRunning = false))
    }

    @Test
    fun `服务实例已连接时即使设置项为空也视为已开启`() {
        putEnabledServices("")
        assertFalse(AccessibilityStatus.isEnabledInSettings(context))
        assertTrue(AccessibilityStatus.isEnabled(context, serviceRunning = true))
    }

    // ==================== 边界条件 ====================

    @Test
    fun `类名为相对写法时仍能识别`() {
        putEnabledServices(component(".service.MonitorAccessibilityService"))
        assertTrue(AccessibilityStatus.isEnabledInSettings(context))
    }

    @Test
    fun `多个服务用冒号分隔时能挑出本服务`() {
        val other = component("com.other.Service", "com.other.app")
        putEnabledServices(other + ":" + component(AccessibilityStatus.SERVICE_CLASS))
        assertTrue(AccessibilityStatus.isEnabledInSettings(context))
    }

    // ==================== 异常场景 ====================

    @Test
    fun `只启用了别的应用的服务时不算已开启`() {
        putEnabledServices(component("com.other.Service", "com.other.app"))
        assertFalse(AccessibilityStatus.isEnabledInSettings(context))
        assertFalse(AccessibilityStatus.isEnabled(context, serviceRunning = false))
    }

    @Test
    fun `其他应用的同名类不会误判为本应用`() {
        putEnabledServices(component(AccessibilityStatus.SERVICE_CLASS, "com.other.app"))
        assertFalse(AccessibilityStatus.isEnabledInSettings(context))
    }

    @Test
    fun `设置项为空字符串时不算已开启`() {
        putEnabledServices("")
        assertFalse(AccessibilityStatus.isEnabledInSettings(context))
        assertFalse(AccessibilityStatus.isEnabled(context, serviceRunning = false))
    }

    @Test
    fun `设置项为无效内容时不抛异常且判定为未开启`() {
        putEnabledServices("这不是组件名:::乱码")
        assertFalse(AccessibilityStatus.isEnabledInSettings(context))
    }
}
