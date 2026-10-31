package com.bsfeng.monitor.ui

// 功能细则页「位置采集」的卡：开关 + 三态权限引导。
// 负责位置开关的三态权限引导：未申请先弹运行时权限、缺「始终允许」单独引导、少数 ROM 的兜底入口。
// 引导卡（location-guide）按契约 s09 的 11 个锚点落地：摘要行可折叠、三步清单、自检行、
// 「重新检测」「去系统设置」两个动作；三态文案与原型逐字一致。

import android.Manifest
import android.content.Context
import android.view.View
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationGateState
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 位置采集卡（默认关闭）：打开必须先走系统运行时权限申请，
 * 系统设置只承担「始终允许」这一步（Android 11+ 后台定位无法在弹窗里给）。
 *
 * 引导卡默认展开、折叠状态不写盘（离开页面再回来回到展开）。
 */
@Composable
internal fun SettingsLocationCard(
    t: DesignTokens,
    context: Context,
    view: View,
    toast: ToastHostState,
    locationOn: Boolean,
    locationPermissionState: MutableState<LocationPermissionStatus>,
    locationLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    backgroundLauncher: ManagedActivityResultLauncher<String, Boolean>,
    setFeature: (Feature, Boolean) -> Unit,
    openLocationAppSettings: () -> Unit,
) {
    val locationPermission = locationPermissionState.value
    // 文案由 LocationHealthText 统一生成（纯逻辑、纯 JVM 单测覆盖），这里只负责渲染
    val copy = LocationHealthText.locationCopyOf(locationPermission)
    // 默认展开；折叠状态不持久化（契约 s09-loc-summary）
    var expanded by remember { mutableStateOf(true) }
    var rechecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    val summaryInteraction = remember { MutableInteractionSource() }
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(t.durState),
        label = "locChevron",
    )

    fun runRecheck() {
        // 检测期间连点不叠加请求：按钮同时禁用
        if (rechecking) return
        scope.launch {
            rechecking = true
            delay(800)
            val result = runCatching { LocationCollector.get(context).status() }
            result.fold(
                onSuccess = { status ->
                    locationPermissionState.value = status
                    toast.show("自检：" + LocationHealthText.locationCopyOf(status).badge)
                },
                // 读取失败保持原状态，只提示（不把三处文案改成半新半旧）
                onFailure = { toast.show("自检失败：系统未返回定位状态") },
            )
            rechecking = false
        }
    }

    AppCard(
        Modifier
            .padding(horizontal = t.space4)
            .testTag("s09-location"),
        padding = 0.dp,
    ) {
        // 位置采集开关（源码现状：开关 + 功能说明，契约未覆盖但保留为产品基准）
        TokenRow(
            title = "位置采集",
            subtitle = if (locationOn) {
                LocationHealthText.PACE_SUMMARY
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
                            // 前台已授权：直接开；缺「始终允许」由下面引导卡承担
                            else -> setFeature(Feature.LOCATION, true)
                        }
                    }
                })
            },
        )
        TokenDivider(first = false)
        // 引导卡容器（location-guide）：容器自身不折叠，折叠只由摘要行触发
        Column(Modifier.fillMaxWidth().testTag("location-guide")) {
            SettingsRow(
                title = "定位权限",
                modifier = Modifier
                    .testTag("s09-loc-summary")
                    .pressFeedback(summaryInteraction)
                    .clickable(
                        interactionSource = summaryInteraction,
                        indication = LocalIndication.current,
                    ) {
                        haptic(HapticTone.LIGHT)
                        expanded = !expanded
                    },
                subtitle = copy.summary,
                subtitleModifier = Modifier.testTag("s09-loc-summary-sub"),
                icon = Icons.Outlined.Shield,
                trailing = {
                    Badge(
                        text = copy.badge,
                        modifier = Modifier
                            .widthIn(min = 64.dp)
                            .testTag("s09-loc-badge"),
                        tone = copy.tone,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = t.muted,
                        modifier = Modifier.size(t.space5).rotate(chevronAngle),
                    )
                },
            )
            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = t.space4, end = t.space4, bottom = t.space3),
                    verticalArrangement = Arrangement.spacedBy(t.space2),
                ) {
                    // 展开区上方 1px 分隔线
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(t.borderSoft)
                    )
                    Text(
                        copy.stepsHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                    // 只给大致位置时先解释后果：不是「掉线」，是权限口径不对（原型里那条 warn 底板）。
                    if (locationPermission == LocationPermissionStatus.COARSE_ONLY) {
                        CoarseLocationNotice(t)
                    }
                    // 三步清单（s09-loc-steps）：步骤本身不可点、不勾选；总数恒为 3
                    Column(
                        Modifier.fillMaxWidth().testTag("s09-loc-steps"),
                        verticalArrangement = Arrangement.spacedBy(t.space3),
                    ) {
                        LocationStep(1, "开启定位服务", "系统设置 › 位置信息 › 打开定位开关", copy.doneSteps >= 1)
                        LocationStep(2, copy.step2Title, copy.step2Desc, copy.doneSteps >= 2)
                        LocationStep(3, copy.step3Title, copy.step3Desc, copy.doneSteps >= 3)
                    }
                    // 采集健康：答案「过一会就掉线」到底是不是在掉 —— 上次成功距今多久、订阅在不在、
                    // 上一次失败是什么原因。文案由 LocationHealthText 统一生成（纯函数、有单测）。
                    // 门禁态一并交给文案层：门禁 SLEEPING 时「30 分钟前最后一点」是待机而非断档，
                    // 不传它就会出现「静止一天、徽标常年红」的假掉线。
                    val gate = LocationGateState.state
                    val health = remember(locationOn, rechecking, locationPermission, gate) {
                        runCatching { LocationCollector.get(context).health() }.getOrNull()
                    }
                    if (locationOn) {
                        SettingsRow(
                            title = "采集状态",
                            modifier = Modifier.testTag("s09-loc-health"),
                            subtitle = LocationHealthText.describe(health, gate),
                            subtitleModifier = Modifier.testTag("s09-loc-health-sub"),
                            trailing = {
                                Badge(
                                    text = LocationHealthText.badge(health, gate),
                                    modifier = Modifier.widthIn(min = 64.dp),
                                    tone = LocationHealthText.toneOf(health, gate),
                                )
                            },
                        )
                    }
                    // 自检行：不可点，只由「重新检测」刷新；与摘要行同源同色
                    SettingsRow(
                        title = "自检状态",
                        modifier = Modifier.testTag("s09-loc-check"),
                        subtitle = copy.check,
                        subtitleModifier = Modifier.testTag("s09-loc-check-sub"),
                        trailing = {
                            Badge(
                                text = copy.badge,
                                modifier = Modifier
                                    .widthIn(min = 64.dp)
                                    .testTag("s09-loc-check-badge"),
                                tone = copy.tone,
                            )
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(t.space2),
                    ) {
                        AppButton(
                            text = if (rechecking) "检测中…" else "重新检测",
                            onClick = { runRecheck() },
                            modifier = Modifier.weight(1f).testTag("s09-loc-recheck"),
                            enabled = !rechecking,
                            variant = AppButtonVariant.Ghost,
                        )
                        AppButton(
                            // 只给大致位置时按钮改成「去改权限」：目标是本应用权限页里的
                            // 「使用精确位置」开关，不是后台定位那条路（锚点仍复用 s09-loc-open）。
                            text = if (locationPermission == LocationPermissionStatus.COARSE_ONLY) {
                                "去改权限"
                            } else {
                                "去系统设置"
                            },
                            onClick = {
                                // 缺后台定位时直达「允许在后台访问位置」页（才能选「始终允许」）；
                                // 其余态（含 COARSE_ONLY）走本应用权限页，失败由 openLocationAppSettings 兜底
                                if (locationPermission == LocationPermissionStatus.MISSING_BACKGROUND) {
                                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                } else {
                                    openLocationAppSettings()
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("s09-loc-open"),
                        )
                    }
                    // 兜底入口（少数 ROM 的 launcher 不跳转或跳了仍无「始终允许」）
                    if (locationPermission == LocationPermissionStatus.MISSING_BACKGROUND) {
                        TokenRow(
                            title = "仍看不到「始终允许」？",
                            subtitle = "打开应用信息，手动进 权限 → 位置",
                            icon = Icons.Outlined.Settings,
                            onClick = { openLocationAppSettings() },
                            trailing = { TailChevron() },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(t.space1))
    }
}

/**
 * 「仅大致位置」的说明底板（原型那条 warn 9% 底）。
 *
 * 必须说清因果：不是定位掉线，而是系统只肯给基站级位置 —— 用户才会照着第 2 步去开精确位置。
 */
@Composable
private fun CoarseLocationNotice(t: DesignTokens) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.warn.copy(alpha = 0.09f))
            .padding(t.space3),
    ) {
        Text(
            "行程会很粗糙。",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = t.fg,
        )
        Text(
            "系统只会返回基站级位置（通常 500 米以上），轨迹会大幅漂移，" +
                "很多点还会因精度不足被丢弃。这不是定位掉线，是权限口径不对。",
            style = MaterialTheme.typography.bodySmall,
            color = t.fg2,
        )
    }
}

/** 三步清单的一步：序号圆 24dp，已完成的序号变 success 16% 底 + success 文字色。 */
@Composable
private fun LocationStep(no: Int, title: String, desc: String, done: Boolean) {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (done) t.successText.copy(alpha = 0.16f) else t.fgFade(8)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                no.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (done) t.successText else t.fg2,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = t.fg,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
    }
}