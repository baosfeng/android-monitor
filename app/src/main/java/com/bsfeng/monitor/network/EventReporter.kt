package com.bsfeng.monitor.network

import android.content.Context
import com.bsfeng.monitor.AppConfig
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.EventEntity
import com.bsfeng.monitor.event.UnifiedEvent
import com.bsfeng.monitor.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class EventReporter(private val context: Context) {
    private val db = DatabaseHelper.getInstance(context)

    suspend fun uploadBatch(): Result<Int> = withContext(Dispatchers.IO) {
        val events = db.getUnsentEvents(AppConfig.batchMaxSize)
        if (events.isEmpty()) return@withContext Result.success(0)

        val batch = EventsBatch(
            events = events.map { it.toUnifiedEvent() },
            batchId = UUID.randomUUID().toString(),
            deviceId = AppUtils.getDeviceId(),
            batchCreatedAtMs = System.currentTimeMillis(),
            batchSize = events.size
        )

        try {
            val result = ApiService.uploadEvents(batch)
            result.fold(
                onSuccess = { httpResp ->
                    val response = ApiService.parseUploadResponse(httpResp)
                    if (httpResp.code in 200..299 && response.code == 0) {
                        db.markUploaded(events.map { it.eventId })
                        cleanOldEvents()
                        Result.success(events.size)
                    } else {
                        Result.failure(Exception("Upload failed: ${httpResp.code} ${response.message}"))
                    }
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun cleanOldEvents() {
        val before = System.currentTimeMillis() - (AppConfig.cacheMaxDays.toLong() * 86400000L)
        db.cleanOldEvents(before)
    }

    private fun EventEntity.toUnifiedEvent(): UnifiedEvent {
        val extraMap = try {
            JSONObject(extraJson).let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }
        } catch (_: Exception) { emptyMap() }
        return UnifiedEvent(
            eventId = eventId,
            eventType = eventType,
            timestamp = timestampMs,
            deviceId = deviceId,
            extra = extraMap
        )
    }
}
