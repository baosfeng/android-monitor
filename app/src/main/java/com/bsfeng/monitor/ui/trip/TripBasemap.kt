package com.bsfeng.monitor.ui.trip

// 轨迹底图开关的持久化。
//
// 默认关：开启后 App 会把「轨迹所在区域的瓦片编号」发给瓦片服务商（OpenStreetMap）。
// 关着的时候轨迹是完全本地的自绘，一个字节都不出网。

import android.content.Context
import androidx.core.content.edit

/** 轨迹底图开关；与 Feature 开关分开存，它只影响「轨迹卡怎么画」，不是一段独立功能。 */
object TripBasemap {

    const val PREFS_NAME = "trip_map"
    const val KEY_ENABLED = "basemap_enabled"

    /** 默认关：底图要联网，且会把区域瓦片编号发给第三方。 */
    const val DEFAULT_ENABLED = false

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
