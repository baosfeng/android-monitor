package com.bsfeng.monitor.network

import android.content.Context
import com.bsfeng.monitor.AppConfig
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DiagnosticCounters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 事件上报器。
 *
 * ⚠️ 二期实现：当前仅保留注册设备功能，上传功能待 Record 系统稳定后接入。
 */
class EventReporter(private val context: Context) {
    private val db = DatabaseHelper.getInstance(context)

    suspend fun uploadBatch(): Result<Int> = withContext(Dispatchers.IO) {
        DiagnosticCounters.lastUploadResult = "上传暂不可用（二期实现）"
        Result.success(0)
    }

    suspend fun registerDevice(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = DeviceRegisterRequest(
                deviceId = AppUtils.getDeviceId(),
                deviceName = AppUtils.getDeviceName(),
                manufacturer = AppUtils.getManufacturer(),
                model = android.os.Build.MODEL,
                androidVersion = AppUtils.getAndroidVersion(),
                appVersion = BuildConfig.VERSION_NAME,
                appBuild = BuildConfig.VERSION_CODE
            )
            val result = ApiService.registerDevice(request)
            result.fold(
                onSuccess = { httpResp ->
                    val response = ApiService.parseDeviceRegisterResponse(httpResp)
                    if (httpResp.code in 200..299 && response.code == 0) {
                        val token = response.data?.token ?: return@withContext Result.failure(Exception("No token"))
                        AppUtils.saveToken(token)
                        Result.success(token)
                    } else {
                        Result.failure(Exception("Register failed: ${httpResp.code}"))
                    }
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
