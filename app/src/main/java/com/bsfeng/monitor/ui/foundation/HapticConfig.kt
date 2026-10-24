package com.bsfeng.monitor.ui.foundation

// 从 ui/foundation/Haptics.kt 拆出：触觉配置的持久化与应用内开关 —— HapticStore 接口、
// SharedPrefsHapticStore 落盘实现、HapticConfig 单例（设置页只绑这一层，不直接读 SharedPreferences）。

import android.content.Context
import androidx.core.content.edit
import com.bsfeng.monitor.MonitorApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 触觉偏好的持久化后端（抽接口只为纯 JVM 单测能注入内存实现，不引 Robolectric）。 */
interface HapticStore {
    /** 读已存值；null = 用户从未设置过，调用方回落到默认值。 */
    fun readEnabled(): Boolean?

    fun readStrength(): HapticStrength?

    fun writeEnabled(enabled: Boolean)

    fun writeStrength(strength: HapticStrength)
}

/** SharedPreferences 实现：与 `AutoBookConfig` / `DebugConfig` 共用 `monitor_prefs`。 */
class SharedPrefsHapticStore(context: Context) : HapticStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readEnabled(): Boolean? =
        if (prefs.contains(KEY_ENABLED)) prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED) else null

    override fun readStrength(): HapticStrength? =
        if (prefs.contains(KEY_STRENGTH)) {
            prefs.getString(KEY_STRENGTH, null)?.let { name ->
                runCatching { HapticStrength.valueOf(name) }.getOrNull()
            }
        } else {
            null
        }

    override fun writeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    override fun writeStrength(strength: HapticStrength) {
        prefs.edit { putString(KEY_STRENGTH, strength.name) }
    }

    companion object {
        const val PREFS_NAME = "monitor_prefs"
        const val KEY_ENABLED = "haptic_enabled"
        const val KEY_STRENGTH = "haptic_strength"

        /** 用户「性能机不妥协」的默认：触觉开启。 */
        const val DEFAULT_ENABLED = true
    }
}

/**
 * 应用内触觉配置的唯一入口（设置页 T4 绑这层，不直接读 SharedPreferences）。
 *
 * 默认值按用户决策拉满：[enabled] = true、[strength] = [HapticStrength.NORMAL]。
 * 读口径分两种：[isEnabled] / [currentStrength] 给点击热路径（只读内存，零 IPC）；
 * [enabled] / [strength] 给 Compose（`collectAsState()`，设置页改完立即生效）。
 *
 * 落盘策略：首次碰触觉时 [attach] 读一次盘（幂等），此后 [setEnabled] / [setStrength]
 * 写盘 + 立即更新内存态，因此「改设置 → 生效」在同一帧内完成。
 */
object HapticConfig {

    private val _enabled = MutableStateFlow(SharedPrefsHapticStore.DEFAULT_ENABLED)
    private val _strength = MutableStateFlow(HapticStrength.NORMAL)

    /** 应用内触觉开关；默认 true。 */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 触觉强度档；默认 [HapticStrength.NORMAL]。 */
    val strength: StateFlow<HapticStrength> = _strength.asStateFlow()

    /** 热路径读（点击回调里用，不碰磁盘）。 */
    val isEnabled: Boolean get() = _enabled.value

    /** 热路径读强度档。 */
    val currentStrength: HapticStrength get() = _strength.value

    @Volatile
    private var store: HapticStore? = null

    /**
     * 绑定持久化后端并从盘上装载一次（幂等：第二次起只做一次 volatile 读）。
     *
     * 不要求 Application 显式初始化：触觉都发生在 UI 之后，此时 `MonitorApp.instance` 已就绪；
     * 万一拿不到实例（例如纯 JVM 测试）就保持默认值，仅内存生效。
     */
    fun attach(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store != null) return
            val loaded = SharedPrefsHapticStore(context)
            store = loaded
            _enabled.value = loaded.readEnabled() ?: SharedPrefsHapticStore.DEFAULT_ENABLED
            _strength.value = loaded.readStrength() ?: HapticStrength.NORMAL
        }
    }

    /** 测试 / 无 Context 场景注入内存实现。 */
    fun attachStore(newStore: HapticStore) {
        synchronized(this) {
            store = newStore
            _enabled.value = newStore.readEnabled() ?: SharedPrefsHapticStore.DEFAULT_ENABLED
            _strength.value = newStore.readStrength() ?: HapticStrength.NORMAL
        }
    }

    /**
     * 改开关：落盘 + 立即生效。
     *
     * @return true = 值确实变了（调用方据此决定是否补一次轻触），false = 重复设置同一值。
     */
    fun setEnabled(enabled: Boolean): Boolean {
        if (_enabled.value == enabled) return false
        _enabled.value = enabled
        ensureStore()?.writeEnabled(enabled)
        return true
    }

    /** 改强度档：落盘 + 立即生效；返回 true 表示值确实变了。 */
    fun setStrength(strength: HapticStrength): Boolean {
        if (_strength.value == strength) return false
        _strength.value = strength
        ensureStore()?.writeStrength(strength)
        return true
    }

    /** 恢复默认（设置页「恢复默认」用）：开启 + NORMAL + 落盘。 */
    fun resetToDefault() {
        setEnabled(SharedPrefsHapticStore.DEFAULT_ENABLED)
        setStrength(HapticStrength.NORMAL)
    }

    /** 落盘时若尚未绑定后端，尝试用全局 Application 兜底绑定一次。 */
    private fun ensureStore(): HapticStore? {
        store?.let { return it }
        runCatching { MonitorApp.instance }.getOrNull()?.let { attach(it) }
        return store
    }
}
