package com.bsfeng.monitor.ui.foundation

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import com.bsfeng.monitor.MonitorApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * 动效强度三档：所有动效基元按档位分支，不再只有「开 / 关」二值。
 *
 * - [FULL]：stagger 逐个延迟 + spring 过冲 + 数字滚动 + 图表动画 + 转场 + 按压缩放（默认档）
 * - [NORMAL]：保留转场 / 数字滚动 / 按压缩放；stagger 步数归零（全部同时入场），spring 换无过冲（临界阻尼）
 * - [REDUCED]：全部直接落终态，不播动画，等价于系统「动画时长缩放 = 0」
 *
 * 优先级：系统「动画时长缩放 = 0」恒为 [REDUCED]（忽略用户选择）；其余情况等于用户选择。
 * 自动降档只走 [FULL] → [NORMAL] 一次，进程内不自动回升；[REDUCED] 只由用户手动选或系统设置触发。
 */
enum class MotionTier {
    FULL,
    NORMAL,
    REDUCED,
}

/** 时长换算系数：`时长 × scale` 得到该档位的实际时长。[REDUCED] 为 0，即不占时长。 */
val MotionTier.scale: Float
    get() = when (this) {
        MotionTier.FULL -> 1f
        MotionTier.NORMAL -> 0.75f
        MotionTier.REDUCED -> 0f
    }

/**
 * 按档位缩放 token 时长：FULL 全长、NORMAL ×0.75、REDUCED 0。
 *
 * 只换时长不换曲线语义（缓动仍取 token 的 --ease-out）；REDUCED 档返回 0 时调用点应走
 * 「直接落终态」分支而不是播一段 0ms 动画。
 */
fun MotionTier.scaleDuration(durationMs: Int): Int =
    (durationMs.coerceAtLeast(0) * scale).roundToInt()

/** [FULL] 档的 stagger 步数上限（24ms × 12 = 288ms）。 */
const val FULL_STAGGER_MAX_STEPS: Int = 12

/**
 * 该档位的 stagger 步数：只有 [FULL] 逐项延迟，其余档位一律 0（全部同时入场）。
 *
 * [maxSteps] 由调用点传入自己的上限，本函数不持有默认上限，避免基元与档位互相绑死。
 */
fun staggerStepsFor(tier: MotionTier, maxSteps: Int = FULL_STAGGER_MAX_STEPS): Int =
    if (tier == MotionTier.FULL) maxSteps.coerceAtLeast(0) else 0

/**
 * 生效档位：系统「动画时长缩放 = 0」时恒为 [REDUCED]，忽略用户选择（用户手动选的档保留在
 * [MotionConfig.userTier]，系统动画恢复后自动还原）。
 */
fun effectiveMotionTier(userTier: MotionTier, animationsAllowed: Boolean): MotionTier =
    if (animationsAllowed) userTier else MotionTier.REDUCED

/**
 * 档位持久化后端。
 *
 * 抽成接口只为纯 JVM 单测能注入内存实现（不引 Robolectric），生产实现是 [PrefsMotionTierStore]。
 */
interface MotionTierStore {
    /** 读用户选的档位；null = 从未选过 → 调用方回落到默认 [MotionTier.FULL]。 */
    fun readTier(): MotionTier?

    fun writeTier(tier: MotionTier)

    /** 上次运行是否因卡顿被自动降档（设置页据此显示「已自动切到常规」）。 */
    fun readAutoDowngraded(): Boolean

    fun writeAutoDowngraded(downgraded: Boolean)
}

/** SharedPreferences 实现：档位存名字串，未知名字串按「未设置」处理（不抛异常）。 */
class PrefsMotionTierStore(context: Context) : MotionTierStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readTier(): MotionTier? = prefs.getString(KEY_TIER, null)
        ?.let { name -> MotionTier.entries.firstOrNull { it.name == name } }

    override fun writeTier(tier: MotionTier) {
        prefs.edit { putString(KEY_TIER, tier.name) }
    }

    override fun readAutoDowngraded(): Boolean = prefs.getBoolean(KEY_AUTO_DOWNGRADED, false)

    override fun writeAutoDowngraded(downgraded: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_DOWNGRADED, downgraded) }
    }

    companion object {
        /** 与 `HapticConfig` / `AutoBookConfig` / `DebugConfig` 共用同一个偏好文件。 */
        const val PREFS_NAME = "monitor_prefs"
        const val KEY_TIER = "motion_tier"
        const val KEY_AUTO_DOWNGRADED = "motion_auto_downgraded"
    }
}

/**
 * 动效档位的唯一入口：默认值 + 当前值 + 读写 + 变更流。
 *
 * 读口径分两种，别混用：
 * - 热路径（每帧 / 每个列表项挂载）用 [current]：只读内存态，不碰磁盘；
 * - Compose 用 [tier]（`collectAsState`）：档位一变界面立即重组。
 *
 * 写入即落盘 + 立即更新内存态，因此「切档 → 生效」在同一帧内完成。
 * 边界：系统「动画时长缩放」按进程缓存（每次读都是 ContentResolver → binder IPC），
 * 用户在系统设置里改动画开关后需要调用 [refreshSystemPolicy] 才生效（Activity onResume 时机）。
 */
class MotionConfig(
    private val store: MotionTierStore,
    private val animationsAllowed: () -> Boolean = { true },
) {

    private val _userTier = MutableStateFlow(store.readTier() ?: DEFAULT_TIER)

    /** 用户选择的档位（不含系统动画开关的覆盖），设置页选中态读它。 */
    val userTier: StateFlow<MotionTier> = _userTier.asStateFlow()

    private val _tier = MutableStateFlow(effectiveMotionTier(_userTier.value, animationsAllowed()))

    /** 生效档位：动效基元订阅它。系统动画缩放为 0 时恒为 [MotionTier.REDUCED]。 */
    val tier: StateFlow<MotionTier> = _tier.asStateFlow()

    private val _autoDowngraded = MutableStateFlow(store.readAutoDowngraded())

    /** 是否因检测到卡顿被自动降档；用户手动选档 / 恢复默认后清除。 */
    val autoDowngraded: StateFlow<Boolean> = _autoDowngraded.asStateFlow()

    /** 热路径同步读：只读内存态，零 IPC、零磁盘。 */
    fun current(): MotionTier = _tier.value

    /** 当前生效档位的时长系数（见 [scale]）。 */
    fun tierScale(): Float = current().scale

    /**
     * 用户选档：落盘 + 立即生效，并清除「自动降档」标记（用户已接管）。
     *
     * @return true = 档位确实变了；false = 重复设置同一档位。
     */
    fun setTier(tier: MotionTier): Boolean {
        if (_userTier.value == tier) {
            clearAutoDowngraded()
            return false
        }
        store.writeTier(tier)
        _userTier.value = tier
        _tier.value = effectiveMotionTier(tier, animationsAllowed())
        clearAutoDowngraded()
        return true
    }

    /** 恢复默认档 [FULL]（设置页「恢复默认」）。 */
    fun resetToDefault() {
        setTier(DEFAULT_TIER)
    }

    /**
     * 卡顿自动降档：只从 [FULL] 降到 [MotionTier.NORMAL] 一次，其余档位不动（只降不升）。
     *
     * @return true = 本次确实降档（调用方据此打点 / 提示）；false = 已是 [MotionTier.NORMAL] /
     *         [MotionTier.REDUCED] 或系统动画已关，无需动作。
     */
    fun autoDowngradeToNormal(): Boolean {
        if (_userTier.value != MotionTier.FULL) return false
        store.writeTier(MotionTier.NORMAL)
        _userTier.value = MotionTier.NORMAL
        _tier.value = effectiveMotionTier(MotionTier.NORMAL, animationsAllowed())
        _autoDowngraded.value = true
        store.writeAutoDowngraded(true)
        return true
    }

    /**
     * 重读系统动画开关（清除进程级缓存）。系统设置里关掉动画后调一次即可让界面整体落到
     * [MotionTier.REDUCED]；用户选择保留不动。
     */
    fun refreshSystemPolicy() {
        resetSystemAnimationsCache()
        _tier.value = effectiveMotionTier(_userTier.value, animationsAllowed())
    }

    private fun clearAutoDowngraded() {
        if (!_autoDowngraded.value) return
        _autoDowngraded.value = false
        store.writeAutoDowngraded(false)
    }

    companion object {
        /** 默认档：[FULL]（性能机不妥协，兜底靠手动降档 + 卡顿自动降档）。 */
        val DEFAULT_TIER: MotionTier = MotionTier.FULL

        /** 进程级缓存：系统「动画时长缩放」只读一次（每次读都是 ContentResolver → binder IPC）。 */
        private val systemAnimationsCache = AtomicReference<Boolean?>(null)

        @Volatile
        private var instance: MotionConfig? = null

        /**
         * 全局单例；首次访问时从 SharedPreferences 装载。
         *
         * 任何能拿到 Context 的地方都用这个入口，保证只有一个实例（单例 → 档位变更只广播一次）。
         */
        fun get(context: Context): MotionConfig =
            instance ?: synchronized(this) {
                instance ?: MotionConfig(PrefsMotionTierStore(context)) {
                    systemAnimationsAllowed(context)
                }.also { instance = it }
            }

        /** 全局单例（[MonitorApp.onCreate] 之后可用）。 */
        fun get(): MotionConfig = get(MonitorApp.instance)

        /**
         * 进程内已初始化的单例；未初始化时返回 null。
         *
         * 给没有 Context 的纯函数 / 基元用（如 `springOf`）：null 表示「还没有人配置过」，按默认档处理。
         */
        fun peek(): MotionConfig? = instance

        /**
         * 系统是否允许播动画：`Settings.Global.ANIMATOR_DURATION_SCALE > 0`。
         * 读取失败按「允许」处理（读不到不该把界面钉死成精简档）。结果按进程缓存。
         */
        fun systemAnimationsAllowed(context: Context): Boolean {
            systemAnimationsCache.get()?.let { return it }
            return runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) > 0f
            }.getOrDefault(true).also { systemAnimationsCache.set(it) }
        }

        /** 清掉系统动画开关的进程级缓存（[MotionConfig.refreshSystemPolicy] 内部调用）。 */
        fun resetSystemAnimationsCache() {
            systemAnimationsCache.set(null)
        }
    }
}
