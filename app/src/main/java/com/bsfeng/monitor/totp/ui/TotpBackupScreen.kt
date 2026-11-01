package com.bsfeng.monitor.totp.ui

// s27 备份与导入：加密导出（AES-256 口径说明）+ Aegis / 2FAS 导入 + 导入失败态 + 超大备份边界态。
// 文件读写、加解密、目录选择全在数据层；本屏只收回调与结果文案。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.AppButton
import com.bsfeng.monitor.ui.AppButtonVariant
import com.bsfeng.monitor.ui.TokenDivider
import com.bsfeng.monitor.ui.TokenRow
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 支持的导入来源（顺序即展示顺序）；不支持的专有格式单独放在最后一行。 */
internal val TOTP_IMPORT_SOURCES = listOf("Aegis", "2FAS")

/**
 * 备份与导入（原型 s27）。
 *
 * @param state LOADING 骨架 / ERROR 导入失败 / EDGE 超大备份 / POPULATED 正常
 * @param importDialogFor 非 null 时打开「导入 <来源>」弹窗（密码在弹窗内输入）
 */
@Composable
fun TotpBackupScreen(
    modifier: Modifier = Modifier,
    state: FiveState = FiveState.POPULATED,
    exportPassword: String = "",
    onExportPasswordChange: (String) -> Unit = {},
    exportError: String? = null,
    importDialogFor: String? = null,
    importPassword: String = "",
    onImportPasswordChange: (String) -> Unit = {},
    importError: String? = null,
    onExport: () -> Unit = {},
    onRequestImport: (String) -> Unit = {},
    onDismissImport: () -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onRetryImport: () -> Unit = {},
    onAddAccount: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    TotpScaffold(title = "备份与导入", testTag = "screen-s27", onBack = onBack, modifier = modifier) {
        when (state) {
            FiveState.LOADING -> {
                AppCard { StateSkeleton(rows = 3) }
                AppCard { StateSkeleton(rows = 2) }
            }
            FiveState.ERROR -> Column {
                ErrorState(
                    title = "导入失败",
                    text = "备份密码不正确，或文件不是完整的 Aegis / 2FAS 导出包。重试不会改动已有账户。",
                    onRetry = onRetryImport,
                    actionLabel = "重新选择备份文件",
                )
            }
            FiveState.EDGE -> BackupOverflowCard()
            FiveState.EMPTY -> Column {
                Text(
                    "还没有可备份的账户。先添加账户，再来导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
                AppButton("添加账户", onAddAccount, block = true)
            }
            FiveState.POPULATED -> {
                Text(
                    "备份文件包含全部密钥，等同账号的第二把钥匙 —— 只放在自己可信的地方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = t.space2),
                )
                AppCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "加密导出",
                            style = MaterialTheme.typography.titleMedium,
                            color = t.fg,
                            modifier = Modifier.weight(1f),
                        )
                        Badge("AES-256")
                    }
                    Spacer(Modifier.height(t.space2))
                    TotpTextField(
                        value = exportPassword,
                        onValueChange = onExportPasswordChange,
                        label = "设置备份密码（至少 8 位）",
                        placeholder = "用于加密备份文件",
                        obscure = true,
                        invalid = exportError != null,
                        keyboardType = KeyboardType.Password,
                    )
                    if (exportError != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = t.space1),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(t.space2),
                        ) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = t.dangerText,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(exportError, style = MaterialTheme.typography.bodySmall, color = t.dangerText)
                        }
                    }
                    Spacer(Modifier.height(t.space2))
                    AppButton(text = "导出加密备份", onClick = onExport, block = true)
                    TotpFine("密码不随文件保存，丢了就解不开；文件写入「下载」目录，同名文件会被覆盖。")
                }

                AppCard(padding = t.space1) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = t.space2, vertical = t.space2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "从其他验证器导入",
                            style = MaterialTheme.typography.titleMedium,
                            color = t.fg,
                            modifier = Modifier.weight(1f),
                        )
                        Text("本机解析", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    }
                    TokenRow(
                        title = "Aegis（JSON 导出）",
                        subtitle = "支持加密与明文导出，密码在导入时输入",
                        modifier = Modifier.padding(horizontal = t.space2),
                        onClick = { onRequestImport("Aegis") },
                        trailing = { Badge("可导入") },
                    )
                    TokenDivider(first = false)
                    TokenRow(
                        title = "2FAS（.2fas 备份）",
                        subtitle = "读取 accounts 数组，忽略应用内设置项",
                        modifier = Modifier.padding(horizontal = t.space2),
                        onClick = { onRequestImport("2FAS") },
                        trailing = { Badge("可导入") },
                    )
                    TokenDivider(first = false)
                    TokenRow(
                        title = "其他专有格式",
                        subtitle = "无公开格式，需改用扫码或手动输入逐条添加",
                        modifier = Modifier.padding(horizontal = t.space2),
                        trailing = { Badge("不支持") },
                    )
                    TotpFine("导入按密钥去重，同名账户保留两条；导入过程不联网，解析失败不会写入任何账户。")
                }

                WarnStrip("导出包请勿放进网盘同步目录或聊天记录；本机卸载 App 会一并删除密钥，届时应删除备份文件本身。")
            }
        }
    }

    if (importDialogFor != null) {
        AppDialog(visible = true, onDismiss = onDismissImport) {
            Text("导入 $importDialogFor 备份", style = MaterialTheme.typography.titleMedium, color = t.fg)
            Spacer(Modifier.height(t.space2))
            Text(
                "输入备份文件的密码；不确定就先用「明文导出」在对方 App 里重做一份。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
            Spacer(Modifier.height(t.space3))
            TotpTextField(
                value = importPassword,
                onValueChange = onImportPasswordChange,
                label = "备份密码",
                placeholder = "备份文件密码",
                obscure = true,
                invalid = importError != null,
                keyboardType = KeyboardType.Password,
            )
            if (importError != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = t.space2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(t.space2),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = t.dangerText,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(importError, style = MaterialTheme.typography.bodySmall, color = t.dangerText)
                }
            }
            Spacer(Modifier.height(t.space3))
            Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                AppButton("取消", onDismissImport, variant = AppButtonVariant.Ghost, modifier = Modifier.weight(1f))
                AppButton("开始导入", onConfirmImport, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 边界态：超过 1000 个账户时分批导入（原型 s27-edge）。 */
@Composable
private fun BackupOverflowCard() {
    val t = LocalDesignTokens.current
    AppCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Badge("5,000 个账户")
            Spacer(Modifier.weight(1f))
            Text("文件 2.4 MB", style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
        Spacer(Modifier.height(t.space2))
        Text("aegis-backup-2025-10-14.json", style = MaterialTheme.typography.titleSmall, color = t.fg)
        Spacer(Modifier.height(t.space1))
        Text(
            "超过 1,000 个账户时分批导入，每批 200 个，中途可取消；已导入的账户保留。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space2))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            AppButton("取消", {}, variant = AppButtonVariant.Ghost, modifier = Modifier.weight(1f))
            AppButton("继续导入", {}, modifier = Modifier.weight(1f))
        }
    }
}

/** 警示条（原型 .s27-warn）：--warn 14% 底 + 30% 描边。 */
@Composable
private fun WarnStrip(text: String) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.warn.copy(alpha = t.warn.alpha * 0.14f))
            .border(1.dp, t.warn.copy(alpha = t.warn.alpha * 0.3f), shape)
            .padding(t.space3),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Outlined.Warning, contentDescription = null, tint = t.warnText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(t.space2))
        Text(text, style = MaterialTheme.typography.bodySmall, color = t.fg2)
    }
}

