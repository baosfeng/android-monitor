package com.bsfeng.monitor.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import kotlinx.coroutines.Dispatchers
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

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        filterApps()
    }

    /** 清空搜索（s11 无结果空态的「清空搜索」动作） */
    fun clearSearch() {
        updateSearchQuery("")
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
        prefs.edit().putStringSet("whitelist", whitelist).apply()
    }

    fun refresh() {
        loadInstalledApps()
    }
}
