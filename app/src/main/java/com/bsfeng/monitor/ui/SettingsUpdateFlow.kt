package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页的应用内更新编排 —— 检查到新版本后的「下载进度 → 安装」流程与取消。
// 负责「点了检查更新之后发生什么」；对话框外观见 SettingsSupport.kt 的 UpdateDialog。

import com.bsfeng.monitor.update.ApkDownloader
import com.bsfeng.monitor.update.InstallResult
import com.bsfeng.monitor.update.UpdateChecker
import com.bsfeng.monitor.update.UpdateInstaller
import com.bsfeng.monitor.update.UpdateUiState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import androidx.compose.runtime.Composable
import com.bsfeng.monitor.ui.components.ToastHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 应用内更新的下载 / 安装编排：状态写入 [updateState]，失败只弹 Toast 不打断页面。
 */
@Composable
internal fun SettingsUpdateFlowDialogs(
    currentUpdate: UpdateUiState,
    context: Context,
    toast: ToastHostState,
    scope: CoroutineScope,
    updateState: MutableStateFlow<UpdateUiState>,
) {
        UpdateDialog(
            state = currentUpdate,
            onDismiss = { updateState.value = UpdateUiState.Idle },
            onDownload = { manifest ->
                updateState.value = UpdateUiState.Downloading(0, 0)
                scope.launch {
                    val checker = UpdateChecker()
                    val dir = File(context.cacheDir, "updates")
                    val result = withContext(Dispatchers.IO) {
                        ApkDownloader().download(
                            sources = checker.downloadSources(manifest),
                            destDir = dir,
                            expectedSha256 = manifest.apkSha256,
                        ) { downloaded, total ->
                            updateState.value = UpdateUiState.Downloading(downloaded, total)
                        }
                    }
                    result.fold(
                        onSuccess = { updateState.value = UpdateUiState.Downloaded(it) },
                        onFailure = {
                            toast.show("下载失败：${it.message ?: "网络不可用"}")
                            updateState.value = UpdateUiState.Idle
                        },
                    )
                }
            },
            onInstall = { apk ->
                when (val result = UpdateInstaller.install(context, apk)) {
                    is InstallResult.Started -> updateState.value = UpdateUiState.Idle
                    is InstallResult.NeedPermission -> toast.show("请允许安装未知来源应用后重试")
                    is InstallResult.Failed -> toast.show(result.message)
                }
            },
        )

}
