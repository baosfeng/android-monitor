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

    /**
     * 包名 → 应用显示名。**带缓存**：前台轮询每秒调用一次，而每次都要两次跨进程
     * （`getApplicationInfo` + `getApplicationLabel`），且它与事件采集、开屏跳过共享主线程。
     *
     * 缓存失效条件：包被安装/卸载/替换时 [invalidateAppName]，由 RecordReceiver 的 PACKAGE_* 分支调用；
     * 未命中时回退到包名本身（与应用商店表现一致，且不会因为查不到而丢记录）。
     */
    fun getAppName(packageName: String): String {
        appNameCache[packageName]?.let { return it }
        return try {
            val pm = appContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString().also { appNameCache.put(packageName, it) }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /** 包被安装 / 卸载 / 替换时清掉它的名字缓存（下次取到的是新版本 label）。 */
    fun invalidateAppName(packageName: String) {
        appNameCache.remove(packageName)
    }

    /** 64 条足够覆盖常用应用；超出后按 LRU 淘汰。安装/卸载清缓存只是加速，不是正确性依赖。 */
    private val appNameCache = android.util.LruCache<String, String>(64)

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
