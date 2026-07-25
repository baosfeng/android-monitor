package com.bsfeng.monitor.domain.repository

/**
 * 设备仓储接口。
 * 管理设备 ID、Token 等设备级配置。
 */
interface DeviceRepository {

    /** 获取设备唯一标识 */
    fun getDeviceId(): String

    /** 获取设备名称 */
    fun getDeviceName(): String

    /** 获取厂商名 */
    fun getManufacturer(): String

    /** 获取 Android 版本号 */
    fun getAndroidVersion(): String

    /** 获取认证 Token */
    fun getToken(): String?

    /** 保存认证 Token */
    fun saveToken(token: String)
}
