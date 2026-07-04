package com.bsfeng.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType

class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        val appName = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { packageName }

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                val isUpdate = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                EventCollector.emit(EventType.APP_INSTALLED, mapOf(
                    "package_name" to packageName,
                    "app_name" to appName,
                    "is_update" to isUpdate.toString()
                ))
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                val dataRemoved = intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)
                EventCollector.emit(EventType.APP_UNINSTALLED, mapOf(
                    "package_name" to packageName,
                    "app_name" to appName,
                    "data_removed" to dataRemoved.toString()
                ))
            }
            Intent.ACTION_PACKAGE_RESTARTED -> {
                EventCollector.emit(EventType.APP_KILLED, mapOf(
                    "package_name" to packageName,
                    "app_name" to appName
                ), summary = "$appName 被强制关闭")
            }
        }
    }
}
