package com.bsfeng.monitor.update

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

sealed interface InstallResult {
    data object Started : InstallResult
    data object NeedPermission : InstallResult
    data class Failed(val message: String) : InstallResult
}

/** 通过 FileProvider 拉起系统安装器。 */
object UpdateInstaller {
    fun install(context: Context, apk: File): InstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData("package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(intent)
                InstallResult.NeedPermission
            }.getOrDefault(InstallResult.Failed("无法打开安装权限设置"))
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            InstallResult.Started
        }.getOrDefault(InstallResult.Failed("无法启动安装器"))
    }
}
