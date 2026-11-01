package com.bsfeng.monitor.ui

// 功能细则页「验证器」：功能开关 + 两个入口（账户管理 / 备份与导入）+ 三项安全开关。
//
// 安全三项默认全开且**真接线**：截图保护写 TotpSettings → 验证器宿主加 FLAG_SECURE；
// 复制即焚 → TotpClipboard 的 30 秒清理；后台遮罩 → 宿主的前后台遮罩层。
// 本卡只改开关，不做任何「演示」行为。

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.totp.TotpSettings
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch

/**
 * 设置「功能 · 验证器」卡。
 *
 * 关闭态只留一行说明：开关管「概览入口出不出现」，账户与密钥都保留（关掉不是删数据）。
 */
@Composable
internal fun SettingsTotpFeatureCard(rt: SettingsScreenRuntime) {
    val t = rt.t
    val settings = remember(rt.context) { TotpSettings(rt.context) }
    var screenshot by remember { mutableStateOf(settings.screenshotProtectionEnabled) }
    var clipboardClear by remember { mutableStateOf(settings.clipboardAutoClearEnabled) }
    var backgroundMask by remember { mutableStateOf(settings.backgroundMaskEnabled) }

    AppCard(Modifier.padding(horizontal = t.space4).testTag("s09-totp"), padding = 0.dp) {
        // 副标题锚点单独打：TokenRow 拿不到 subtitle 节点
        SettingsRow(
            title = "验证器（TOTP）",
            subtitle = if (rt.totpOn) {
                "二次验证码 · 概览页右上角图标进入"
            } else {
                "已关闭：概览入口收起，本地密钥保留"
            },
            subtitleModifier = Modifier.testTag("s09-totp-sub"),
            icon = Icons.Outlined.Security,
            trailing = {
                AppSwitch(
                    checked = rt.totpOn,
                    onCheckedChange = { rt.setFeature(Feature.TOTP, it) },
                    modifier = Modifier.testTag("s09-totp-switch"),
                    contentDescription = "验证器",
                )
            },
        )
        if (rt.totpOn) {
            TokenDivider(first = false)
            TokenRow(
                title = "管理账户",
                subtitle = "平台分组 · 搜索 · 拖到列表末尾就是最后一位",
                icon = Icons.Outlined.List,
                onClick = { rt.onNavigateToTotp(false) },
                trailing = { TailChevron() },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "备份与导入",
                subtitle = "加密导出 · 从 Aegis / 2FAS 导入",
                icon = Icons.Outlined.Save,
                onClick = { rt.onNavigateToTotp(true) },
                trailing = { TailChevron() },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "禁止截屏",
                subtitle = if (screenshot) "验证码页不可截图、不可录屏" else "已关闭：验证码可能出现在截图里",
                icon = Icons.Outlined.Block,
                trailing = {
                    AppSwitch(
                        checked = screenshot,
                        onCheckedChange = {
                            screenshot = it
                            settings.screenshotProtectionEnabled = it
                        },
                        modifier = Modifier.testTag("s09-totp-flag-secure"),
                        contentDescription = "禁止截屏",
                    )
                },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "复制即焚",
                subtitle = if (clipboardClear) "复制 30 秒后自动清空剪贴板" else "已关闭：复制的验证码会留在剪贴板",
                icon = Icons.Outlined.ContentCopy,
                trailing = {
                    AppSwitch(
                        checked = clipboardClear,
                        onCheckedChange = {
                            clipboardClear = it
                            settings.clipboardAutoClearEnabled = it
                        },
                        modifier = Modifier.testTag("s09-totp-clip-clear"),
                        contentDescription = "复制即焚",
                    )
                },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "后台遮罩",
                subtitle = if (backgroundMask) "切后台时遮住验证码内容" else "已关闭：多任务卡片可能露出验证码",
                icon = Icons.Outlined.Security,
                trailing = {
                    AppSwitch(
                        checked = backgroundMask,
                        onCheckedChange = {
                            backgroundMask = it
                            settings.backgroundMaskEnabled = it
                        },
                        modifier = Modifier.testTag("s09-totp-blur"),
                        contentDescription = "后台遮罩",
                    )
                },
            )
        } else {
            TokenDivider(first = false)
            TokenRow(
                title = "验证器设置",
                subtitle = FeatureCatalog.offHint(Feature.TOTP) + "：账户管理、备份、三项安全开关",
                icon = Icons.Outlined.Security,
            )
        }
    }
}
