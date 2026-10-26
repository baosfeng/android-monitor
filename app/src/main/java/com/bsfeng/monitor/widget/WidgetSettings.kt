package com.bsfeng.monitor.widget

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * 小组件设置（当前只有「隐藏金额」）。
 *
 * 为什么用 SharedPreferences 而不是 Glance 的 updateAppWidgetState（DataStore）：
 * 组件刷新时进程可能刚被拉起，这里要一次同步读取就拿到结论；DataStore 的读是挂起调用 +
 * 首次读要建文件，为了一个布尔值不值得。写频率是人类点击级别，不存在性能问题。
 */
object WidgetSettings {

    private const val TAG = "WidgetSettings"
    private const val PREFS_NAME = "widget_prefs"
    private const val KEY_HIDE_AMOUNTS = "hide_amounts"

    /** 桌面上的金额是否显示为 `¥••••`。默认 false：用户主动添加的组件默认可读。 */
    fun isAmountHidden(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_HIDE_AMOUNTS, false) }
            .getOrDefault(false)

    fun setAmountHidden(context: Context, hidden: Boolean) {
        runCatching { prefs(context).edit { putBoolean(KEY_HIDE_AMOUNTS, hidden) } }
            .onFailure { Log.w(TAG, "写入 hide_amounts 失败", it) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
