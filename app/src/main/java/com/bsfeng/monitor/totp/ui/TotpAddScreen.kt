package com.bsfeng.monitor.totp.ui

// s23 添加账户：三个入口（扫码 / 手动输入 / 从相册识别）+「支持的参数」说明卡。
// 入口只上抛回调，不在这里申请相机权限或读文件。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.TokenDivider
import com.bsfeng.monitor.ui.TokenRow
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

/** 添加账户（原型 s23）。[state] 只区分加载与正常，其余态由入口按下后的扫码 / 手动屏承担。 */
@Composable
fun TotpAddScreen(
    modifier: Modifier = Modifier,
    state: FiveState = FiveState.POPULATED,
    onScan: () -> Unit = {},
    onManualEntry: () -> Unit = {},
    onPickFromAlbum: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    TotpScaffold(title = "添加账户", testTag = "screen-s23", onBack = onBack, modifier = modifier) {
        if (state == FiveState.LOADING) {
            AppCard { StateSkeleton(rows = 4) }
        } else {
            Text(
                "选择一种方式添加 TOTP 账户 · 密钥只保存在本机",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                modifier = Modifier.padding(top = t.space2),
            )
            AppCard(padding = t.space1) {
                TotpEntryRow(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = "扫码添加",
                    subtitle = "对准网站出示的 otpauth 二维码",
                    onClick = onScan,
                )
                TokenDivider(first = false)
                TotpEntryRow(
                    icon = Icons.Outlined.Edit,
                    title = "手动输入",
                    subtitle = "粘贴 Base32 密钥，可调算法与周期",
                    onClick = onManualEntry,
                )
                TokenDivider(first = false)
                TotpEntryRow(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = "从相册识别",
                    subtitle = "选择二维码截图，本机离线解码",
                    onClick = onPickFromAlbum,
                )
            }
            Spacer(Modifier.height(t.space2))
            AppCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "支持的参数",
                        style = MaterialTheme.typography.titleMedium,
                        color = t.fg,
                        modifier = Modifier.weight(1f),
                    )
                    Badge("RFC 6238")
                }
                Spacer(Modifier.height(t.space2))
                TotpParamRow("算法", "SHA1 / SHA256 / SHA512")
                TotpHairline()
                TotpParamRow("位数", "6 / 8 位")
                TotpHairline()
                TotpParamRow("周期", "30 / 60 秒")
                TotpFine("扫码优先读 otpauth 里的参数；二维码里没有的项，先按 SHA1 / 6 位 / 30 秒计算，之后可在账户详情里改。")
            }
            TotpFine("密钥以 Android Keystore 加密后只存本机，不进备份、不写日志、不上传；卸载 App 即随之删除。")
        }
    }
}

/** 入口行（原型 .s23-entry）：44dp 图标 + 标题/副标题 + 右箭头。 */
@Composable
private fun TotpEntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    TokenRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = t.space2),
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = t.muted,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/** 参数键值行（原型 .s23-kv）：左 muted 标签、右等宽值。 */
@Composable
internal fun TotpParamRow(label: String, value: String) {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = monitorMonoMeta, color = t.fg2)
    }
}
