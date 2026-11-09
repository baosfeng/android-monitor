package com.bsfeng.monitor.ui.foundation

// 记账界面「按需开启」三开关（原型 s09 记账功能组）：预算与预警 / 首页记账卡 / 多周期查询入口。
// 与 feature/FeatureFlags 的分工：FeatureFlags 管「一段会真干活的能力」（Tab / 服务 / 扫描），
// 这里管「记账界面上的呈现件」，同一套「缺键 = 用户没选过 = 用默认值」口径。

import android.content.Context
import androidx.core.content.edit
import com.bsfeng.monitor.MonitorApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 记账界面开关。声明顺序 = 设置页行序（预算与预警 → 首页记账卡 → 多周期查询入口）。
 *
 * @param summary 设置页副标题（写「开启后得到什么」，不写当前值）
 * @param storageKey 持久化键，改名会丢用户已存的选择，保持稳定
 * @param defaultOn 缺键时的默认值（原型 s09-finance-budget 的 aria-checked=false 即默认关）
 */
enum class FinanceUiToggle(
    val title: String,
    val summary: String,
    val storageKey: String,
    val defaultOn: Boolean,
) {
    /** 记账页的预算进度环与超支预警；默认关（原型 aria-checked=false）。 */
    // 副标题口径与 s09 其余开关行一致：开启态写「开起来得到什么」，关闭态前面加「已关闭：」。
    // 长度上限按实测倒推：卡片 390dp 宽、subtitle maxLines = 1，前缀 4 字 + 正文超过约 18 字
    // 就被省略号截断（原型那两句 20+ 字的长描述照抄会截在关键词上）。
    BUDGET("预算与预警", "记账页预算进度与预警", "budget_enabled", false),

    /** 概览页的记账图表卡；默认开。 */
    HOME_CARD("首页记账卡", "概览页记账图表卡", "home_card_enabled", true),

    /** 记账页顶部的时间单位切换（日 / 周 / 月 / 年 / 全部）；默认开。 */
    PERIOD_BAR("多周期查询入口", "记账页时间单位切换", "period_bar_enabled", true),
}

/** 记账界面开关的持久化后端（抽接口只为纯 JVM 单测能注入内存实现，与 [HapticStore] 同思路）。 */
interface FinanceUiStore {
    /** 读已存值；null = 用户从未设置过，调用方回落到 [FinanceUiToggle.defaultOn]。 */
    fun read(toggle: FinanceUiToggle): Boolean?

    fun write(toggle: FinanceUiToggle, enabled: Boolean)
}

/** SharedPreferences 实现：一个开关一个布尔键，缺键即「未选择」。 */
class SharedPrefsFinanceUiStore(context: Context) : FinanceUiStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(toggle: FinanceUiToggle): Boolean? =
        if (prefs.contains(toggle.storageKey)) {
            prefs.getBoolean(toggle.storageKey, toggle.defaultOn)
        } else {
            null
        }

    override fun write(toggle: FinanceUiToggle, enabled: Boolean) {
        prefs.edit { putBoolean(toggle.storageKey, enabled) }
    }

    companion object {
        /** 单独一份 prefs：财务界面偏好与 feature_flags / monitor_prefs 各管各的，互不清空。 */
        const val PREFS_NAME = "finance_ui"
    }
}

/**
 * 记账界面开关的唯一入口（设置页、概览卡、记账页都只读这一层，不直接读 SharedPreferences）。
 *
 * 读口径分两种：[isOn] / [values] 给 Compose（collectAsState 后开关一变界面立即重组），
 * [isEnabled] 给非 Compose 的取数侧（VM 决定「关着就不查库」）。写入即落盘 + 立即更新内存态。
 */
object FinanceUiPrefs {

    private val _values = MutableStateFlow(defaults())

    /** 全部开关的当前值；Compose 侧订阅它。 */
    val values: StateFlow<Map<FinanceUiToggle, Boolean>> = _values.asStateFlow()

    /** 热路径读（取数侧判定「关着就不必查库」，不碰磁盘）。 */
    fun isEnabled(toggle: FinanceUiToggle): Boolean = _values.value[toggle] ?: toggle.defaultOn

    /**
     * 改一个开关：落盘 + 更新内存态 + 通知订阅者。
     *
     * @return true = 值确实变了（调用方据此决定是否补提示），false = 重复设置同一值
     */
    fun setEnabled(toggle: FinanceUiToggle, enabled: Boolean): Boolean {
        if (isEnabled(toggle) == enabled) return false
        _values.value = _values.value + (toggle to enabled)
        ensureStore()?.write(toggle, enabled)
        return true
    }

    /** 恢复默认（预算与预警关、首页记账卡与多周期入口开）。 */
    fun resetToDefault() {
        FinanceUiToggle.entries.forEach { toggle -> setEnabled(toggle, toggle.defaultOn) }
    }

    /** 绑定持久化后端并从盘上装载一次（幂等）。拿不到 Context（纯 JVM 测试）时仅内存生效。 */
    fun attach(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store != null) return
            attachStore(SharedPrefsFinanceUiStore(context))
        }
    }

    /** 测试 / 无 Context 场景注入内存实现。 */
    fun attachStore(newStore: FinanceUiStore) {
        synchronized(this) {
            store = newStore
            _values.value = FinanceUiToggle.entries.associateWith { toggle ->
                newStore.read(toggle) ?: toggle.defaultOn
            }
        }
    }

    /** 默认值表（进位图 / 设置页首帧，未 attach 时也可用）。 */
    fun defaults(): Map<FinanceUiToggle, Boolean> =
        FinanceUiToggle.entries.associateWith { it.defaultOn }

    @Volatile
    private var store: FinanceUiStore? = null

    /**
     * 落盘时若尚未绑定后端，尝试用全局 Application 兜底绑定一次
     * （不要求 Application 显式初始化：开关都发生在 UI 之后，此时 MonitorApp.instance 已就绪）。
     */
    private fun ensureStore(): FinanceUiStore? {
        store?.let { return it }
        runCatching { MonitorApp.instance }.getOrNull()?.let { attach(it) }
        return store
    }
}
