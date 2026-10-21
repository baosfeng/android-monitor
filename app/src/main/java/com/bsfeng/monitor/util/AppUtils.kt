package com.bsfeng.monitor.util

import android.content.Context
import androidx.core.content.edit
import android.content.pm.PackageManager
import com.bsfeng.monitor.MonitorApp
import java.util.UUID

object AppUtils {
    private const val PREFS_NAME = "monitor_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_AUTH_TOKEN = "auth_token"

    private val appContext: Context get() = MonitorApp.instance

    fun getDeviceId(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit { putString(KEY_DEVICE_ID, id) }
            id
        }
    }

    fun getAppName(packageName: String): String {
        return try {
            val pm = appContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun getToken(): String? {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }

    fun saveToken(token: String) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_AUTH_TOKEN, token) }
    }

    fun getDeviceName(): String {
        return android.os.Build.MODEL
    }

    fun getManufacturer(): String {
        return android.os.Build.MANUFACTURER
    }

    fun getAndroidVersion(): String {
        return android.os.Build.VERSION.RELEASE
    }
}
