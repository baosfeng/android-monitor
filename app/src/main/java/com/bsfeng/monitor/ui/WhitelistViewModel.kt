package com.bsfeng.monitor.ui

import android.content.Context
import androidx.core.content.edit
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.ui.components.FiveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 已安装应用信息。
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isInWhitelist: Boolean = false
)

/**
 * 广告跳过白名单管理 ViewModel。
 *
 * 获取手机中所有已安装的非系统应用，用户可以搜索并切换白名单状态。
 * [isLoading] / [errorMessage] 供 s11 的五态（loading / error）使用，均为真实加载状态。
 */
class WhitelistViewModel : ViewModel() {

    private val prefs = MonitorApp.instance.getSharedPreferences("skip_whitelist", Context.MODE_PRIVATE)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _filteredApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val filteredApps: StateFlow<List<AppInfo>> = _filteredApps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadInstalledApps()
    }

    /** 防抖中的过滤任务（每敲一个字就取消上一个，停手 [SEARCH_DEBOUNCE_MS] 才真正过滤）。 */
    private var filterJob: Job? = null

    /**
     * 搜索框输入（锚点 s11-search 输入行）：即时回显 + 停手 300ms 后过滤。
     *
     * 防抖期间列表保持上一次结果、不出 loading；关键词超 [SEARCH_MAX_CHARS] 截断不报错。
     */
    fun updateSearchQuery(query: String) {
        val clamped = query.take(SEARCH_MAX_CHARS)
        if (clamped == _searchQuery.value) return
        _searchQuery.value = clamped
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            filterApps()
        }
    }

    /** 立即过滤（chip 快捷筛选 / 重置 / 清空搜索：交互口径要求点下去同帧生效，不等防抖）。 */
    fun applySearchNow(query: String) {
        filterJob?.cancel()
        _searchQuery.value = query.take(SEARCH_MAX_CHARS)
        filterApps()
    }

    /** 清空搜索（s11 无结果空态的「清空搜索」动作 / 快捷筛选「重置」） */
    fun clearSearch() {
        applySearchNow("")
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun filterApps() {
        val query = _searchQuery.value.trim().lowercase()
        _filteredApps.value = if (query.isEmpty()) {
            _apps.value
        } else {
            _apps.value.filter { app ->
                app.appName.lowercase().contains(query) ||
                app.packageName.lowercase().contains(query)
            }
        }
    }

    private fun loadInstalledApps() {
        _isLoading.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = MonitorApp.instance.packageManager
                val whitelist = getWhitelist()
                // 只取 ApplicationInfo：带上 GET_ACTIVITIES 会让 400+ 应用的返回体超过 Binder 事务上限（约 1MB）
                val installedApps = pm.getInstalledApplications(0)
                    .filter { info ->
                        (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                            info.packageName != MonitorApp.instance.packageName
                    }
                    .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                    .map { info ->
                        AppInfo(
                            packageName = info.packageName,
                            appName = pm.getApplicationLabel(info).toString(),
                            icon = info.loadIcon(pm),
                            isInWhitelist = info.packageName in whitelist
                        )
                    }
                _apps.value = installedApps
                filterApps()
                _isLoading.value = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WhitelistViewModel", "加载已安装应用失败", e)
                _isLoading.value = false
                _errorMessage.value = "PackageManager 拒绝查询：" + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun toggleWhitelist(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val whitelist = getWhitelist().toMutableSet()
            if (packageName in whitelist) {
                whitelist.remove(packageName)
            } else {
                whitelist.add(packageName)
            }
            saveWhitelist(whitelist)
            // 更新本地状态
            _apps.value = _apps.value.map { app ->
                if (app.packageName == packageName) app.copy(isInWhitelist = packageName in whitelist)
                else app
            }
            filterApps()
        }
    }

    private fun getWhitelist(): Set<String> {
        return prefs.getStringSet("whitelist", emptySet()) ?: emptySet()
    }

    private fun saveWhitelist(whitelist: Set<String>) {
        prefs.edit { putStringSet("whitelist", whitelist) }
    }

    fun refresh() {
        loadInstalledApps()
    }

    private companion object {
        /** 搜索防抖窗口（锚点 s11-search 输入行：停手 300ms 后过滤）。 */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** 关键词上限（锚点 s11-search 输入行边界：超 60 字符截断不报错）。 */
        const val SEARCH_MAX_CHARS = 60
    }
}

/**
 * 系统「使用情况访问」设置页的 action。
 *
 * 锚点 s11-st-empty：点「去授权」必须**真跳转**系统「使用情况访问」设置页（只弹 toast 不跳转 = 假跳转）。
 * 常量放在 ViewModel 文件里而不是 WhitelistScreen.kt：后者是纯 Composable 文件，不会被 JVM 单测加载
 * （见 app/build.gradle.kts 的覆盖率排除清单），可单测的普通声明不放进去。
 */
internal val USAGE_ACCESS_SETTINGS_ACTION: String =
    android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS

/** s11 一次性焦点回位的决策：请求 / 等状态落定 / 作废。 */
internal enum class WhitelistFocusDecision { REQUEST, WAIT, DROP }

/**
 * 空态「去授权」从系统设置返回后的焦点决策（锚点 s11-st-empty · 返回本页时焦点回「去授权」）。
 *
 * 返回后会重扫一次：扫描中（LOADING）等待，仍落在空态才请求焦点；扫描出结果、空态不再呈现时
 * 作废信号，避免在别的状态抢焦点。
 */
internal fun emptyCtaFocusDecision(state: FiveState): WhitelistFocusDecision = when (state) {
    FiveState.EMPTY -> WhitelistFocusDecision.REQUEST
    FiveState.LOADING -> WhitelistFocusDecision.WAIT
    else -> WhitelistFocusDecision.DROP
}

/**
 * 错态「重新扫描」后的焦点决策：仍失败（ERROR）→ 焦点回「重新扫描」按钮；扫描中等待；
 * 成功退出错态（按钮随之卸载）→ 作废。
 */
internal fun retryFocusDecision(state: FiveState): WhitelistFocusDecision = when (state) {
    FiveState.ERROR -> WhitelistFocusDecision.REQUEST
    FiveState.LOADING -> WhitelistFocusDecision.WAIT
    else -> WhitelistFocusDecision.DROP
}
