package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：磁盘/版本/声音/历史加载/任务行改写等杂项工具。
// 与 TransferEngineTools 的分工：那边是会话与接收链路，这边是任务行与系统能力。

import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.annotation.RequiresApi
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.transfer.service.TransferEngine.Companion.TAG
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal fun TransferEngine.freeBytes(): Long = runCatching {
    StatFs(appContext.cacheDir.absolutePath).availableBytes
}.getOrDefault(Long.MAX_VALUE)

internal fun TransferEngine.appVersion(): String = BuildConfig.VERSION_NAME

internal fun TransferEngine.playSound(event: TransferSoundPlayer.Event) {
    TransferSoundPlayer.play(appContext, event, settings, hapticTarget?.get())
}
internal fun TransferEngine.renameTasks(fromSessionId: String, toSessionId: String) {
    _tasks.update { current ->
        current.map {
            if (it.sessionId == fromSessionId) it.copy(sessionId = toSessionId) else it
        }
    }
}

internal fun TransferEngine.failTask(sessionId: String, fileId: String, code: Int, message: String) {
    patchTask(sessionId, fileId) {
        it.copy(
            status = TransferStatus.FAILED,
            errorCode = code,
            message = message,
            updatedAtMs = System.currentTimeMillis(),
        )
    }
}

internal fun TransferEngine.failAll(sessionId: String, code: Int, message: String) {
    var failedAny = false
    _tasks.update { current ->
        current.map {
            if (it.sessionId == sessionId && it.isActive) {
                failedAny = true
                it.copy(
                    status = TransferStatus.FAILED,
                    errorCode = code,
                    message = message,
                    updatedAtMs = System.currentTimeMillis(),
                )
            } else {
                it
            }
        }
    }
    if (failedAny) playSound(TransferSoundPlayer.Event.FAILURE)
}

internal fun TransferEngine.taskKey(sessionId: String, fileId: String): String = "$sessionId#$fileId"

