package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleEngine.kt 的白名单与屏幕状态判定；职责：读白名单 prefs 与电源状态（异常一律按“允许/亮屏”兜底）。

import android.content.Context
import android.os.PowerManager
import com.bsfeng.monitor.MonitorApp

/** 引擎运行环境查询（白名单 / 屏幕状态），由 [SkipRuleEngine] 调用 */
internal class SkipEngineEnvironment {

    /** 白名单 App 是否跳过全部处理（读失败时按“不在白名单”处理） */
    fun isWhitelisted(packageName: String): Boolean = try {
        val prefs = MonitorApp.instance.getSharedPreferences(WHITELIST_PREFS, Context.MODE_PRIVATE)
        packageName in (prefs.getStringSet(WHITELIST_KEY, emptySet()) ?: emptySet())
    } catch (_: Exception) {
        false
    }

    /** 屏幕是否点亮（取不到电源服务时按亮屏处理，避免整机不跳过） */
    fun defaultScreenInteractive(): Boolean = try {
        val manager = MonitorApp.instance.getSystemService(Context.POWER_SERVICE) as? PowerManager
        manager?.isInteractive ?: true
    } catch (_: Exception) {
        true
    }

    companion object {
        const val WHITELIST_PREFS = "skip_whitelist"
        const val WHITELIST_KEY = "whitelist"
    }
}
