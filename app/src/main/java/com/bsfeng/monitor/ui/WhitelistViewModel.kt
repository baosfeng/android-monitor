package com.bsfeng.monitor.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
 */
class WhitelistViewModel : ViewModel() {

    private val prefs = MonitorApp.instance.getSharedPreferences("skip_whitelist", Context.MODE_PRIVATE)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _filteredApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val filteredApps: StateFlow<List<AppInfo>> = _filteredApps.asStateFlow()

    init {
        loadInstalledApps()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        filterApps()
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = MonitorApp.instance.packageManager
                val whitelist = getWhitelist()
                val installedApps = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
                    .mapNotNull { pkg ->
                        val info = pkg.applicationInfo ?: return@mapNotNull null
                        if ((info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
                        if (pkg.packageName == MonitorApp.instance.packageName) return@mapNotNull null
                        pkg to info
                    }
                    .sortedBy { (_, info) -> pm.getApplicationLabel(info).toString().lowercase() }
                    .map { (pkg, info) ->
                        AppInfo(
                            packageName = pkg.packageName,
                            appName = pm.getApplicationLabel(info).toString(),
                            icon = info.loadIcon(pm),
                            isInWhitelist = pkg.packageName in whitelist
                        )
                    }
                _apps.value = installedApps
                filterApps()
            } catch (_: Exception) {}
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
