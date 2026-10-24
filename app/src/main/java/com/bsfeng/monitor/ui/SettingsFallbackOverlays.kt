package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页的两条屏内兜底桥 —— 外观与主题、组件与状态演示。
// 导航任务接线之前，设置页的 setter 回调为 null 时由这里自行托管，保证两个入口在真机上真的能进。

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import com.bsfeng.monitor.ui.components.AppearanceScreen
import com.bsfeng.monitor.ui.components.ComponentGalleryScreen
import com.bsfeng.monitor.ui.foundation.AnimatedScreenOverlay

/**
 * 外观（s13）与组件展厅（s12）的屏内兜底入口。
 *
 * 接线后宿主导航会先接管（回调非 null），这两条桥自然不再触发。
 */
@Composable
internal fun SettingsFallbackOverlays(
    localAppearanceState: MutableState<Boolean>,
    localGalleryState: MutableState<Boolean>,
) {
    AnimatedScreenOverlay(
        visible = localAppearanceState.value,
        onBack = { localAppearanceState.value = false },
    ) {
        AppearanceScreen(onBack = { localAppearanceState.value = false })
    }

    AnimatedScreenOverlay(
        visible = localGalleryState.value,
        onBack = { localGalleryState.value = false },
    ) {
        ComponentGalleryScreen(onBack = { localGalleryState.value = false })
    }
}
