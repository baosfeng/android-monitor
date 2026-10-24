package com.bsfeng.monitor.ui

// 从 ui/SettingsScreenRuntime.kt 拆出：定位权限的两条申请通道（前台 FINE/COARSE 一批、后台单独一批）。
// 只做「申请 → 回读真实状态 → 决定开关」这一件事，状态读写通过参数传入。

import android.Manifest
import android.content.Context
import android.view.View
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.performConfirmHaptic

/** 定位权限申请的两个 launcher。 */
internal class LocationLaunchers(
    val locationLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    val backgroundLauncher: ManagedActivityResultLauncher<String, Boolean>,
)

/**
 * 定位权限申请（运行时授权）。
 *
 * 为什么必须先申请：Android 上**从未 requestPermissions 过**的权限不会出现在
 * 「应用信息 → 权限」列表里，只跳系统设置用户根本找不到该权限项 —— 真机缺陷的根因。
 *
 * FINE 与 COARSE 必须同批申请：Android 12+ 只申请 FINE 会被系统直接忽略。
 * 后台定位（ACCESS_BACKGROUND_LOCATION）不进这一批：Android 11+ 要求单独引导，
 * 只能由用户在系统设置里选「始终允许」，所以回调后按三态决定是否露出那条引导行。
 */
@Composable
internal fun rememberLocationLaunchers(
    context: Context,
    view: View,
    toast: ToastHostState,
    featureFlags: FeatureFlags,
    locationPermission: MutableState<LocationPermissionStatus>,
    setFeature: (Feature, Boolean) -> Unit,
): LocationLaunchers {
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 先回读真实授权状态，再决定开关：不按「点了同意」假设已授权
        val status = LocationCollector.get(context).status()
        locationPermission.value = status
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            status != LocationPermissionStatus.NOT_GRANTED
        if (!granted) {
            // 分支①：都拒绝（含「不再询问」）→ 开关保持/回到关闭，如实说明未授权。
            // 这里不用 setFeature：开关此刻在 UI 上仍是关，setFeature 会因「值没变」提前 return，
            // 提示就弹不出来（用户会以为点了没反应）。
            if (featureFlags.setEnabled(Feature.LOCATION, false)) view.performConfirmHaptic()
            toast.show("未授权，无法记录位置 · 可在系统设置开启")
        } else {
            // 分支②③：只给「仅使用期间」或已满足 → 开关打开；缺后台定位时引导行自动出现
            setFeature(Feature.LOCATION, true)
        }
    }

    /**
     * 后台定位单独申请（只有这一条路能让系统弹出「始终允许」）。
     *
     * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 落到的是应用信息**主页**，位置项里只有
     * 「仅在使用中允许」；Android 11+ 必须**单独一次** requestPermissions(ACCESS_BACKGROUND_LOCATION)，
     * 系统才会打开「允许在后台访问位置」那个专门页面（始终 / 仅使用中 / 拒绝三选项）。
     *
     * ⚠️ 不能和 FINE/COARSE 同批：混在一起时系统会直接忽略后台这一项。
     */
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 不按回调布尔值下结论：部分 ROM 对「仅本次允许」也回 true。回读系统真实状态。
        val status = LocationCollector.get(context).status()
        locationPermission.value = status
        if (status != LocationPermissionStatus.GRANTED) {
            toast.show("仍缺「始终允许」：设置 → 应用 → 权限 → 位置 → 始终允许")
        }
        // 已授予时不做额外动作：状态刷新后该行自然变成「已授权」，无需再提示
    }

    return LocationLaunchers(locationLauncher, backgroundLauncher)
}
