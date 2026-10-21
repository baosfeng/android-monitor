package com.bsfeng.monitor.feature

import android.content.Context
import androidx.core.content.edit
import com.bsfeng.monitor.MonitorApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 功能开关的持久化后端。
 *
 * 抽成接口只为单测能在纯 JVM 里注入内存实现（不引 Robolectric）；
 * 生产实现是 [SharedPrefsFeatureStore]，与仓库既有 SharedPreferences 模式一致。
 */
interface FeatureStore {
    /** 读已存的值；null = 用户从未选过，调用方回落到默认值。 */
    fun read(feature: Feature): Boolean?

    fun write(feature: Feature, enabled: Boolean)
}

/** SharedPreferences 实现：一个功能一个布尔键，缺键即「未选择」（用默认值）。 */
class SharedPrefsFeatureStore(context: Context) : FeatureStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(feature: Feature): Boolean? =
        if (prefs.contains(feature.storageKey)) {
            prefs.getBoolean(feature.storageKey, FeatureCatalog.defaultOf(feature))
        } else {
            null
        }

    override fun write(feature: Feature, enabled: Boolean) {
        prefs.edit { putBoolean(feature.storageKey, enabled) }
    }

    companion object {
        const val PREFS_NAME = "feature_flags"
    }
}

/**
 * 功能开关的唯一入口：默认值 + 当前值 + 读写 + 变更流。
 *
 * 读口径分两种，别混用：
 * - 热路径（无障碍事件、服务启停判定）用 [isEnabled] / [snapshot]：只读内存里的 StateFlow，不碰磁盘；
 * - Compose 用 [enabled]（`collectAsState`）：开关一变界面立即重组，不需要重启 App。
 *
 * 写入即落盘 + 立即更新内存态，因此「切换 → 生效」在同一帧内完成。
 */
class FeatureFlags(private val store: FeatureStore) {

    private val _enabled = MutableStateFlow(load())

    /** 全部功能的当前值；Compose 侧订阅它。 */
    val enabled: StateFlow<Map<Feature, Boolean>> = _enabled.asStateFlow()

    fun isEnabled(feature: Feature): Boolean = _enabled.value[feature] == true

    /** 第一次读也走内存态，不触发 SharedPreferences 读取（热路径专用）。 */
    fun snapshot(): Map<Feature, Boolean> = _enabled.value

    /**
     * 改一个开关：落盘 + 更新内存态 + 通知订阅者。
     *
     * @return true = 值确实变了（调用方据此决定是否弹提示 / 停服务），false = 重复设置同一值。
     */
    fun setEnabled(feature: Feature, enabled: Boolean): Boolean {
        if (isEnabled(feature) == enabled) return false
        store.write(feature, enabled)
        _enabled.value = _enabled.value + (feature to enabled)
        return true
    }

    /** 当前可见 Tab 列表。 */
    fun visibleTabs(): List<AppTab> = FeatureCatalog.visibleTabs(::isEnabled)

    /** 深链落点（目标功能关闭 → 概览）。 */
    fun resolveTab(tab: AppTab): AppTab = FeatureCatalog.resolveTab(tab, ::isEnabled)

    private fun load(): Map<Feature, Boolean> = Feature.entries.associateWith { feature ->
        store.read(feature) ?: FeatureCatalog.defaultOf(feature)
    }

    companion object {
        @Volatile
        private var instance: FeatureFlags? = null

        /** 首次访问时从 SharedPreferences 装载（服务 / 磁贴可能在 Activity 之前就要读）。 */
        fun get(context: Context): FeatureFlags =
            instance ?: synchronized(this) {
                instance ?: FeatureFlags(SharedPrefsFeatureStore(context)).also { instance = it }
            }

        /** 全局单例（[MonitorApp.onCreate] 已设好 instance）；进程内任何地方都可用。 */
        fun get(): FeatureFlags = get(MonitorApp.instance)
    }
}
