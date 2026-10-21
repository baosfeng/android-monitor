package com.bsfeng.monitor.ui.theme

import android.content.Context
import androidx.core.content.edit
import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行期主题仓库：唯一主题状态源 + 持久化（杀进程重启后保持）。
 *
 * 程序化切换入口（自验与 W3/s13 共用）：
 * ```
 * ThemeRepository.init(applicationContext)      // 启动时一次
 * ThemeRepository.setTheme("kami")              // 切换 + 持久化；false = 未知主题，状态不变
 * ThemeRepository.themeId.value                 // 当前主题 id
 * ThemeRepository.tokens().accent               // 当前主题的非 Material token
 * ThemeRepository.colorScheme().primary         // 当前主题的 Material ColorScheme
 * ```
 * 非法入参（未知主题 id / 未 init 就读）一律回落到 apple，不抛异常。
 */
object ThemeRepository {

    const val DEFAULT_THEME: String = DesignTokenTable.DEFAULT_THEME
    const val PREFS_NAME: String = "monitor_appearance"
    const val KEY_THEME_ID: String = "theme_id"

    /** 主题 id 顺序（= token 文件顺序）：apple 为默认主题。 */
    val themeIds: List<String> get() = DesignTokenTable.THEME_IDS

    private val displayNames: Map<String, String> = mapOf(
        "apple" to "Apple",
        "glassmorphism" to "玻璃拟态",
        "notion" to "Notion",
        "stripe" to "Stripe",
        "claude" to "Claude",
        "ibm" to "IBM",
        "kami" to "Kami",
        "bento" to "Bento",
    )

    private val themeIdState = MutableStateFlow(DesignTokenTable.DEFAULT_THEME)
    val themeId: StateFlow<String> = themeIdState.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun isKnown(id: String): Boolean = DesignTokenTable.THEME_IDS.contains(id)

    fun displayName(id: String): String = displayNames[id] ?: id

    /** 读取持久化主题；未 init 或值非法时保持 apple。 */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString(KEY_THEME_ID, null)
        themeIdState.value = if (saved != null && isKnown(saved)) saved else DesignTokenTable.DEFAULT_THEME
    }

    /** 切换主题并持久化。返回 false = 未知主题 id，状态不变（fail-closed）。 */
    fun setTheme(id: String): Boolean {
        if (!isKnown(id)) return false
        themeIdState.value = id
        prefs?.edit { putString(KEY_THEME_ID, id) }
        return true
    }

    fun currentThemeId(): String = themeIdState.value

    fun tokens(id: String = themeIdState.value): DesignTokens =
        DesignTokens(if (isKnown(id)) id else DesignTokenTable.DEFAULT_THEME)

    fun colorScheme(id: String = themeIdState.value): ColorScheme =
        GeneratedColorScheme.schemeOf(if (isKnown(id)) id else DesignTokenTable.DEFAULT_THEME)

    /** 运行时读取 token 原值（自验用：非设备路径下也能 dump 最终生效值）。 */
    fun rawToken(id: String, name: String): String = DesignTokenTable.raw(id, name)

    fun tokenColor(id: String, name: String): Color = DesignTokenTable.colorOf(id, name)
}
