package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt（经 SettingsFeatureSection 二次拆分）拆出：设置「功能 · 位置采集」卡。
// 负责位置开关的三态权限引导：未申请先弹运行时权限、缺「始终允许」单独引导、少数 ROM 的兜底入口。

import android.Manifest
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import kotlinx.coroutines.launch
import android.content.Context
import android.view.View
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.theme.DesignTokens

/**
 * 位置采集卡（默认关闭）：打开必须先走系统运行时权限申请，
 * 系统设置只承担「始终允许」这一步（Android 11+ 后台定位无法在弹窗里给）。
 */
@Composable
internal fun SettingsLocationCard(
    t: DesignTokens,
    context: Context,
    view: View,
    toast: ToastHostState,
    locationOn: Boolean,
    locationPermission: LocationPermissionStatus,
    locationLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    backgroundLauncher: ManagedActivityResultLauncher<String, Boolean>,
    setFeature: (Feature, Boolean) -> Unit,
    openLocationAppSettings: () -> Unit,
) {
// 位置采集（默认关闭）：打开必须先走系统运行时权限申请，
// 系统设置只承担「始终允许」这一步（Android 11+ 后台定位无法在弹窗里给）
AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
    TokenRow(
        title = "位置采集",
        subtitle = if (locationOn) {
            "移动时约 10 秒、静止时约 3 分钟记录一次（3 位小数）"
        } else {
            "已关闭：不读位置、不落坐标"
        },
        icon = Icons.Outlined.LocationOn,
        trailing = {
            AppSwitch(locationOn, { enabled ->
                if (!enabled) {
                    setFeature(Feature.LOCATION, false)
                } else {
                    when (LocationCollector.get(context).status()) {
                        // 从未申请过 → 先弹系统权限申请，回调里再决定开关
                        LocationPermissionStatus.NOT_GRANTED ->
                            locationLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        // 前台已授权：直接开；缺「始终允许」由下面那行引导
                        else -> setFeature(Feature.LOCATION, true)
                    }
                }
            })
        },
    )
    TokenDivider(first = false)
    if (locationOn) {
        TokenRow(
            title = "定位权限",
            subtitle = when (locationPermission) {
                LocationPermissionStatus.GRANTED -> "已授权：后台也能取到位置"
                LocationPermissionStatus.MISSING_BACKGROUND -> "缺「始终允许」：切后台读不到位置"
                LocationPermissionStatus.NOT_GRANTED -> "未授权：暂不记录位置"
            },
            icon = Icons.Outlined.Shield,
            trailing = {
                when (locationPermission) {
                    // 已齐全：不再给按钮，避免多余的一次跳转
                    LocationPermissionStatus.GRANTED -> Unit
                    // 缺后台定位：单独申请 ACCESS_BACKGROUND_LOCATION，
                    // 系统会直接打开「允许在后台访问位置」页（才有「始终允许」）
                    LocationPermissionStatus.MISSING_BACKGROUND -> AppButton(
                        text = "去开启始终允许",
                        onClick = {
                            backgroundLauncher.launch(
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            )
                        },
                        variant = AppButtonVariant.Ghost,
                    )
                    // 前台权限被拒/被撤：重新走运行时申请（系统弹窗或「不再询问」）
                    LocationPermissionStatus.NOT_GRANTED -> AppButton(
                        text = "申请权限",
                        onClick = {
                            locationLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        },
                        variant = AppButtonVariant.Ghost,
                    )
                }
            },
        )
        // 兜底入口：少数 ROM 的 launcher 不跳转（或跳了仍无「始终允许」），
        // 这里保留手动到「应用信息 → 权限」的路径，不作为主入口
        if (locationPermission == LocationPermissionStatus.MISSING_BACKGROUND) {
            TokenDivider(first = false)
            TokenRow(
                title = "仍看不到「始终允许」？",
                subtitle = "打开应用信息，手动进 权限 → 位置",
                icon = Icons.Outlined.Settings,
                onClick = { openLocationAppSettings() },
                trailing = { TailChevron() },
            )
        }
    } else {
        TokenRow(
            title = "定位权限",
            subtitle = FeatureCatalog.offHint(Feature.LOCATION),
            icon = Icons.Outlined.Shield,
        )
    }
    Spacer(Modifier.height(t.space1))
}
}
