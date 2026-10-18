package com.bsfeng.monitor.transfer.service

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 本机对外展示名（`alias`）解析（纯函数 + 一次容错读取）。
 *
 * 取值链（逐级回落，任何异常 / 空串都走下一级，绝不抛）：
 * 1. `Settings.Global.DEVICE_NAME`（API 25+，用户在「关于手机 → 设备名称」里起的名字）；
 * 2. `Build.MANUFACTURER + " " + Build.MODEL`（如 `Xiaomi 23127PN0CC`）；
 * 3. `Build.MODEL`；
 * 4. 兜底常量 `Android`。
 *
 * 这样对端看到的是「小米14」这类真实名称，而不是型号代号。
 */
object DeviceAliasResolver {

    /** 三级回落都拿不到时的兜底名。 */
    const val FALLBACK_ALIAS: String = "Android"

    /**
     * 纯函数版解析。
     *
     * @param deviceName `Settings.Global.DEVICE_NAME` 的读取结果（可能为 null / 空串）。
     * @param manufacturer `Build.MANUFACTURER`。
     * @param model `Build.MODEL`。
     * @param deviceCode `Build.DEVICE`（系统内部设备代号，用于识别「系统名就是型号代号」的机型）。
     */
    fun resolve(
        deviceName: String?,
        manufacturer: String?,
        model: String?,
        fallback: String = FALLBACK_ALIAS,
        deviceCode: String? = null,
    ): String {
        val maker = manufacturer?.trim().orEmpty()
        val deviceModel = model?.trim().orEmpty()
        val systemName = deviceName?.trim().orEmpty()
        // 很多 ROM 的 DEVICE_NAME 直接就是型号代号（如 `23127PN0CC`）。
        // 这种「名字」对用户没有信息量，降级到「厂商 + 型号」至少能看出是哪家设备。
        val nameIsModelCode = systemName.isNotBlank() && (
            systemName.equals(deviceModel, ignoreCase = true) ||
                systemName.equals(deviceCode?.trim(), ignoreCase = true)
            )
        if (systemName.isNotBlank() && !nameIsModelCode) return systemName
        if (maker.isNotBlank() && deviceModel.isNotBlank()) return "$maker $deviceModel"
        if (deviceModel.isNotBlank()) return deviceModel
        if (systemName.isNotBlank()) return systemName
        if (maker.isNotBlank()) return maker
        return fallback
    }

    /** 从容错角度读取系统设备名；不可读 / 为空时返回 null。 */
    fun readDeviceName(context: Context): String? = runCatching {
        Settings.Global.getString(context.applicationContext.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 当前设备的展示名（读取系统名 → 厂商 + 型号 → 型号 → 兜底）。 */
    fun resolveDeviceAlias(context: Context): String =
        resolve(
            deviceName = readDeviceName(context),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            deviceCode = Build.DEVICE,
        )
}
