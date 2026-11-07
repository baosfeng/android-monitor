package com.bsfeng.monitor.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍服务启用状态判定。
 *
 * ## 为什么需要三重判定
 * 真机（小米 14 / HyperOS，2026-10-13 实测）出现「`dumpsys accessibility` 显示服务已绑定、
 * 引擎日志正常，但设置页显示 ❌ 未开启」：
 * - 只看 [AccessibilityManager.getEnabledAccessibilityServiceList]：该列表在部分 ROM 上会滞后
 *   （服务刚绑定或系统 UI 重建期间返回空），导致误报"未开启"；
 * - 只看 [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES]：反映的是用户勾选状态，
 *   与服务进程是否真的连上没有必然关系（本次 install -r 后就有短暂不一致）。
 *
 * 因此：**用户已勾选（设置项/列表任一命中）或 服务实例已连接**，都算"已开启"。
 */
object AccessibilityStatus {

    /**
     * 无障碍服务类名（与 `AndroidManifest.xml` 中 service 声明一致）。
     *
     * ⚠️ 这里是**伪装身份**的类名，不是 `MonitorAccessibilityService`：manifest 注册的是它在
     * `com.google.android.accessibility.selecttospeak` 包下的子类（微信白名单名）。
     * 原因、证据与风险见该子类的 KDoc 与 docs/记账/微信支付页识别调研.md。
     */
    internal const val SERVICE_CLASS = "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    /**
     * 无障碍服务是否已开启。
     *
     * @param context 任意 Context
     * @param serviceRunning [com.bsfeng.monitor.service.MonitorAccessibilityService.isRunning]
     */
    fun isEnabled(context: Context, serviceRunning: Boolean): Boolean =
        serviceRunning || isEnabledInSettings(context) || isEnabledInManager(context)

    /** [isEnabledCached] 的默认缓存窗口：3s 级轮询下不必每次跨进程重读。 */
    const val DEFAULT_CACHE_TTL_MS: Long = 30_000L

    private var cachedAtMs = 0L
    private var cachedSettingsOrManager = false

    /**
     * 带 TTL 的判定，**仅供轮询路径**使用：命中缓存时不再走
     * [Settings.Secure] 跨进程读与 [AccessibilityManager] 的服务列表解析。
     *
     * 用户操作触发的刷新（如设置页 [androidx.lifecycle.LifecycleResumeEffect]）
     * 必须走 [isEnabledFresh]（实时值 + 回写缓存），否则从系统设置页返回时会看到最多一个 TTL 的旧值。
     */
    @Synchronized
    fun isEnabledCached(
        context: Context,
        serviceRunning: Boolean,
        ttlMs: Long = DEFAULT_CACHE_TTL_MS,
    ): Boolean {
        if (serviceRunning) return true
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAtMs <= ttlMs) return cachedSettingsOrManager
        val fresh = isEnabled(context, serviceRunning = false)
        cachedAtMs = now
        cachedSettingsOrManager = fresh
        return fresh
    }

    /**
     * 实时判定（语义同 [isEnabled]）**并把结果回写缓存**。
     *
     * 用户操作触发的刷新必须走这里：只走 [isEnabled] 不回写，随后的轮询路径
     * （[isEnabledCached]）会拿旧的 `false` 覆盖实时值，界面最长闪回一个 TTL 的「未开启」。
     */
    @Synchronized
    fun isEnabledFresh(context: Context, serviceRunning: Boolean): Boolean {
        val fromSystem = isEnabledInSettings(context) || isEnabledInManager(context)
        cachedAtMs = SystemClock.elapsedRealtime()
        cachedSettingsOrManager = fromSystem
        return serviceRunning || fromSystem
    }

    /** [isDisabledConfirmed] 的默认阈值：轮询每分钟一次，连续 2 次 ≈ 2 分钟。 */
    const val DISABLED_STREAK_THRESHOLD: Int = 2

    private var disabledStreak = 0

    /**
     * 轮询路径专用：**连续 [threshold] 次**判定为未开启才返回 true。
     *
     * 为什么需要这一层：这里只用于「告警」与「拉前台」这类有副作用的动作，而单次判定会受两类
     * 假阴性影响 —— 服务刚绑定/系统 UI 重建期间 [AccessibilityManager] 返回空列表；[Settings.Secure]
     * 那次跨进程读偶发超时。任一次抖动就告警，用户会被反复打扰，久而久之把通知关掉，真故障反而看不见。
     *
     * 不用「异常时当作正常」兜底（旧实现的写法）：ROM 拒绝返回列表恰恰是要提示的那种故障，
     * 静默通过等于把故障藏起来。
     */
    @Synchronized
    fun isDisabledConfirmed(
        context: Context,
        serviceRunning: Boolean,
        threshold: Int = DISABLED_STREAK_THRESHOLD,
    ): Boolean {
        if (isEnabled(context, serviceRunning)) {
            disabledStreak = 0
            return false
        }
        disabledStreak += 1
        return disabledStreak >= threshold
    }

    /** 清空 [isDisabledConfirmed] 的连续计数（用户从系统设置页回来时调用，避免残留计数抢先触发告警）。 */
    @Synchronized
    fun resetDisabledStreak() {
        disabledStreak = 0
    }

    /**
     * 系统设置里是否勾选了本服务（权威来源，`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`）。
     *
     * 该设置项的格式为 `包名/类名:包名/类名`，类名可能是相对写法（`.service.Xxx`）。
     */
    internal fun isEnabledInSettings(context: Context): Boolean = try {
        val flatten = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        flatten?.split(':')?.any { entry ->
            // ComponentName.unflattenFromString 在类名为相对写法时也能解析出完整类名
            ComponentName.unflattenFromString(entry.trim())?.let { matches(it, context.packageName) } == true
        } == true
    } catch (_: Exception) {
        false
    }

    /**
     * [AccessibilityManager] 报告的服务列表里是否有本服务。
     *
     * 用 [AccessibilityServiceInfo.FEEDBACK_ALL_MASK] 而不是 `FEEDBACK_GENERIC`：
     * 后者会把反馈类型声明不一致的服务过滤掉（本项目声明的是 feedbackGeneric，但仍按全类型取，避免 ROM 差异）。
     */
    internal fun isEnabledInManager(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName } == true
    } catch (_: Exception) {
        false
    }

    /** 组件是否指向本应用的无障碍服务（兼容类名的相对写法） */
    private fun matches(component: ComponentName, packageName: String): Boolean {
        if (component.packageName != packageName) return false
        val className = component.className.removePrefix(".")
        return className == SERVICE_CLASS || SERVICE_CLASS.endsWith(".$className")
    }
}
