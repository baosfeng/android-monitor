package com.bsfeng.monitor.network

import android.content.Context
import com.bsfeng.monitor.AppConfig
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.record.RecordStore
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DiagnosticCounters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 事件上报器。
 */
class EventReporter(private val context: Context) {
    private val db = DatabaseHelper.getInstance(context)
    private val recordStore = RecordStore(db)

    suspend fun uploadBatch(): Result<Int> = withContext(Dispatchers.IO) {
        DiagnosticCounters.uploadAttemptCount++
        try {
            val token = AppUtils.getToken()
            DiagnosticCounters.isTokenAvailable = !token.isNullOrBlank()
            if (token.isNullOrBlank()) {
                val message = "无 Auth Token，跳过上传"
                DiagnosticCounters.lastUploadResult = message
                DiagnosticCounters.lastUploadError = message
                return@withContext Result.failure(Exception(message))
            }
            val unsent = recordStore.getUnuploaded(AppConfig.batchMaxSize)
            DiagnosticCounters.uploadEventCount = unsent.size
            if (unsent.isEmpty()) {
                val message = "无待上传事件"
                DiagnosticCounters.lastUploadResult = message
                DiagnosticCounters.lastUploadError = ""
                return@withContext Result.success(0)
            }
            val batch = EventsBatch(
                events = unsent,
                batchId = java.util.UUID.randomUUID().toString(),
                deviceId = AppUtils.getDeviceId(),
                batchCreatedAtMs = System.currentTimeMillis(),
                batchSize = unsent.size
            )
            val httpResult = ApiService.uploadEvents(batch)
            httpResult.fold(
                onSuccess = { httpResp ->
                    val response = ApiService.parseUploadResponse(httpResp)
                    if (httpResp.code in 200..299 && response.code == 0) {
                        // 批量 ack：后端返回 code==0 视为整批接受；若后端只提供 acceptedCount，
                        // 也按整批成功标记，因为无法按单条 id 匹配。
                        recordStore.markUploaded(unsent.map { it.id })
                        val cutoff = System.currentTimeMillis() - AppConfig.cacheMaxDays.toLong() * 86_400_000L
                        recordStore.deleteUploadedBefore(cutoff)
                        DiagnosticCounters.uploadSuccessCount++
                        DiagnosticCounters.lastUploadResult = "上传成功 ${unsent.size} 条"
                        DiagnosticCounters.lastUploadError = ""
                        Result.success(unsent.size)
                    } else {
                        DiagnosticCounters.uploadFailCount++
                        val error = "上报失败: HTTP ${httpResp.code}, code=${response.code}, ${response.message}"
                        DiagnosticCounters.lastUploadResult = error
                        DiagnosticCounters.lastUploadError = error
                        Result.failure(Exception(error))
                    }
                },
                onFailure = { error ->
                    DiagnosticCounters.uploadFailCount++
                    val message = error.message ?: error.toString()
                    DiagnosticCounters.lastUploadResult = "上传异常: $message"
                    DiagnosticCounters.lastUploadError = message
                    Result.failure(error)
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticCounters.uploadFailCount++
            val message = e.message ?: e.toString()
            DiagnosticCounters.lastUploadResult = "上传异常: $message"
            DiagnosticCounters.lastUploadError = message
            Result.failure(e)
        }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
