package com.bsfeng.monitor.data.repository

import com.bsfeng.monitor.domain.repository.DeviceRepository
import com.bsfeng.monitor.util.AppUtils

/**
 * 设备仓储实现 — 委托给 AppUtils。
 */
class DeviceRepositoryImpl : DeviceRepository {

    override fun getDeviceId(): String = AppUtils.getDeviceId()

    override fun getDeviceName(): String = AppUtils.getDeviceName()

    override fun getManufacturer(): String = AppUtils.getManufacturer()

    override fun getAndroidVersion(): String = AppUtils.getAndroidVersion()

    override fun getToken(): String? = AppUtils.getToken()

    override fun saveToken(token: String) = AppUtils.saveToken(token)
}
