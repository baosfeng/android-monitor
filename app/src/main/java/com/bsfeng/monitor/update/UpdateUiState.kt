package com.bsfeng.monitor.update

import java.io.File

/** 设置页「检查更新」的 UI 状态。 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val manifest: UpdateManifest) : UpdateUiState
    data class Downloading(val downloaded: Long, val total: Long) : UpdateUiState
    data class Downloaded(val apk: File) : UpdateUiState
}
