package com.bsfeng.monitor.finance.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.finance.PandaImporter
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.animatedCount
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentSoftFill
import java.io.File
import java.io.FileOutputStream
import com.bsfeng.monitor.ui.foundation.pageBackdrop

/**
 * s08 数据导入。
 *
 * 三步说明 + 文件选择 + 进度条 + 结果统计 + 失败明细与重试；五态齐备。
 * 真实导入走 [PandaImporter]（熊猫记账导出的数据库文件），业务口径未改动。
 */
@Composable
fun ImportScreen(
    importing: Boolean,
    importResult: PandaImporter.ImportResult?,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
    onClearResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val toast = rememberToastHostState()
    val control = rememberFinanceStateControl()

    var selectedFileName by remember { mutableStateOf("") }
    var selectedFilePath by remember { mutableStateOf("") }
    var readError by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it) ?: "panda_jizhang.db"
            selectedFileName = fileName
            try {
                val cacheFile = File(context.cacheDir, "panda_import_" + System.currentTimeMillis() + ".db")
                context.contentResolver.openInputStream(it)?.use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                }
                selectedFilePath = cacheFile.absolutePath
                readError = ""
                toast.show("已选择 " + fileName)
            } catch (e: Exception) {
                readError = "文件读取失败：" + (e.message ?: "未知错误")
                selectedFileName = ""
                selectedFilePath = ""
            }
        }
    }

    val result = importResult
    val derivedState = when {
        importing -> FiveState.LOADING
        result != null && !result.isSuccess && result.importedTransactions == 0 -> FiveState.ERROR
        // 选文件页是常态页（原型 populated）；空态留给五态演示开关
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState
    // 导入中断（原型 s08-fail）：文件卡与结果卡保持可见，另加显式「已回滚」提示卡；
    // 空态同样画在列表里（带「选择数据文件」CTA）
    val containerState = when (state) {
        FiveState.ERROR, FiveState.EMPTY -> FiveState.POPULATED
        else -> state
    }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(importing) {
        if (importing) {
            progress.snapTo(0f)
            progress.animateTo(PROGRESS_CEILING, tween(t.durChart + t.durChart, easing = t.easeOut))
        } else if (result != null) {
            progress.snapTo(1f)
        }
    }

    Box(Modifier.fillMaxSize().pageBackdrop()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.space2, vertical = t.space2),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("完成", style = MaterialTheme.typography.titleSmall, color = t.accent)
                }
                Text(
                    "导入数据",
                    style = MaterialTheme.typography.titleLarge,
                    color = t.fg,
                    modifier = Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { control.toggleBar() },
                )
                Spacer(Modifier.width(t.tapMin))
            }

            StateContainer(
                state = containerState,
                modifier = Modifier.weight(1f),
                emptyText = "先在熊猫记账里「我的 → 导出账单」，再回到这里选择该文件",
                errorText = "不是有效的熊猫记账导出文件，或文件已损坏",
                edgeText = "边界态：超长文件名 / 128,430 条记录 / 超过 20 MB 建议上限",
                onRetry = {
                    if (selectedFilePath.isNotEmpty()) onImport(selectedFilePath) else toast.show("请先选择要导入的文件")
                },
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (state == FiveState.ERROR) {
                        item(key = "rollback") {
                            Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
                                FinanceEmptyState(
                                    title = "导入中断",
                                    desc = "本次写入已回滚，本地账目未被修改。" +
                                        (result?.errors?.firstOrNull()?.let { " " + it } ?: ""),
                                    icon = Icons.Filled.Warning,
                                    actionText = "重试",
                                    onAction = {
                                        if (selectedFilePath.isNotEmpty()) {
                                            onImport(selectedFilePath)
                                        } else {
                                            toast.show("请先选择要导入的文件")
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (state == FiveState.EMPTY) {
                        item(key = "empty") {
                            Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
                                FinanceEmptyState(
                                    title = "还没有选择文件",
                                    desc = "先在熊猫记账里导出账单数据文件，再回到这里选择该文件",
                                    icon = Icons.Filled.Description,
                                    actionText = "选择数据文件",
                                    onAction = { filePickerLauncher.launch("application/octet-stream") },
                                )
                            }
                        }
                    }

                    item(key = "steps") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            Spacer(Modifier.height(t.space3))
                            Text(
                                "从熊猫记账导出的数据文件导入历史账目。导入前会先预检：重复记录自动跳过，" +
                                    "无法识别的分类落到「未分类」。",
                                style = MaterialTheme.typography.labelSmall,
                                color = t.meta,
                            )
                            Spacer(Modifier.height(t.space3))
                            listOf(
                                "选择熊猫记账导出的数据文件",
                                "预检并预览：导入 / 跳过 / 失败 三类记录",
                                "写入本地数据库并重建预算与统计",
                            ).forEachIndexed { index, step ->
                                Row(
                                    modifier = Modifier.padding(vertical = t.space1),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(t.space6)
                                            .clip(CircleShape)
                                            .background(t.surfaceWarm),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            (index + 1).toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = t.accent,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    Spacer(Modifier.width(t.space2))
                                    Text(
                                        step,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = t.fg2,
                                    )
                                }
                            }
                        }
                    }

                    item(key = "pick") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            Spacer(Modifier.height(t.space3))
                            AppCard(
                                onClick = { filePickerLauncher.launch("application/octet-stream") },
                                padding = t.space6,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(t.tapMin)
                                            .clip(CircleShape)
                                            .background(t.accentSoftFill()),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Upload,
                                            contentDescription = null,
                                            tint = t.accent,
                                            modifier = Modifier.size(t.iconSize),
                                        )
                                    }
                                    Spacer(Modifier.height(t.space3))
                                    Text(
                                        "选择数据文件",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = t.fg,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(t.space1))
                                    Text(
                                        "熊猫记账导出的 .db 文件",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = t.muted,
                                    )
                                }
                            }
                            if (readError.isNotEmpty()) {
                                Spacer(Modifier.height(t.space2))
                                Text(
                                    readError,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = t.dangerText,
                                )
                            }
                        }
                    }

                    if (selectedFileName.isNotEmpty()) {
                        item(key = "file") {
                            Column(Modifier.padding(horizontal = t.space4)) {
                                Spacer(Modifier.height(t.space3))
                                AppCard(padding = t.space1) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = t.space3, vertical = t.space3),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Description,
                                            contentDescription = null,
                                            tint = t.muted,
                                            modifier = Modifier.size(t.iconSize),
                                        )
                                        Spacer(Modifier.width(t.space3))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                selectedFileName,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = t.fg,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                if (selectedFilePath.isNotEmpty()) "已就绪 · 可开始导入" else "读取失败",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (selectedFilePath.isNotEmpty()) t.incomeText else t.dangerText,
                                            )
                                        }
                                        FinanceIconButton(
                                            onClick = {
                                                selectedFileName = ""
                                                selectedFilePath = ""
                                                onClearResult()
                                            },
                                            contentDescription = "移除文件",
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = null,
                                                tint = t.muted,
                                                modifier = Modifier.size(t.iconSize),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (importing) {
                        item(key = "progress") {
                            Column(Modifier.padding(horizontal = t.space4)) {
                                Spacer(Modifier.height(t.space3))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(t.space2)
                                        .clip(RoundedCornerShape(t.radiusPill))
                                        .background(t.borderSoft),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(progress.value)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(t.radiusPill))
                                            // B1：进度条用 chart-2（不占每屏强调色预算）
                                            .background(t.chart2),
                                    )
                                }
                                Spacer(Modifier.height(t.space3))
                                Text(
                                    progressLabel(progress.value),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = t.meta,
                                )
                            }
                        }
                    }

                    item(key = "go") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            Spacer(Modifier.height(t.space4))
                            ImportActionButton(
                                text = if (importing) "导入中…" else "开始导入",
                                enabled = selectedFilePath.isNotEmpty() && !importing,
                                onClick = { if (selectedFilePath.isNotEmpty()) onImport(selectedFilePath) },
                            )
                            Spacer(Modifier.height(t.space2))
                            Text(
                                "导入不会删除已有账目；如需重来，先移除文件再重新选择。",
                                style = MaterialTheme.typography.labelSmall,
                                color = t.meta,
                            )
                        }
                    }

                    if (result != null && !importing) {
                        item(key = "resulthead") {
                            Column(Modifier.padding(horizontal = t.space4)) {
                                Spacer(Modifier.height(t.space4))
                                GlassCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (result.hasErrors) "导入完成（有错误）" else "导入完成",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = t.fg,
                                        )
                                        Badge(
                                            text = formatCount(result.totalTransactions),
                                            tone = if (result.hasErrors) BadgeTone.Warn else BadgeTone.Success,
                                        )
                                    }
                                    Spacer(Modifier.height(t.space3))
                                    Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                                        ResultTile(
                                            label = "成功",
                                            value = formatCount(animatedCount(result.importedTransactions)),
                                            color = t.incomeText,
                                            modifier = Modifier.weight(1f),
                                        )
                                        ResultTile(
                                            label = "跳过",
                                            value = formatCount(animatedCount(result.skippedTransactions)),
                                            color = t.fg,
                                            modifier = Modifier.weight(1f),
                                        )
                                        ResultTile(
                                            label = "失败",
                                            value = formatCount(animatedCount(result.errors.size)),
                                            color = if (result.hasErrors) t.expenseText else t.fg,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Spacer(Modifier.height(t.space3))
                                    Text(
                                        "共 " + formatCount(result.totalTransactions) + " 条记录 · 新增 " +
                                            result.importedCategories + " 个分类 · " +
                                            result.importedAccounts + " 个账户 · " +
                                            result.importedBudgets + " 条预算",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = t.meta,
                                    )
                                }
                            }
                        }

                        if (result.hasErrors) {
                            item(key = "failrows") {
                                Column(Modifier.padding(horizontal = t.space4)) {
                                    Spacer(Modifier.height(t.space3))
                                    AppCard(padding = t.space1) {
                                        result.errors.forEach { error ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = t.space3, vertical = t.space3),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    Icons.Filled.Warning,
                                                    contentDescription = null,
                                                    tint = t.warnText,
                                                    modifier = Modifier.size(t.iconSize),
                                                )
                                                Spacer(Modifier.width(t.space3))
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        error,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = t.fg,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        "该条已跳过，可点「重试失败项」再次导入",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = t.meta,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item(key = "resultactions") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = t.space4, vertical = t.space3),
                                horizontalArrangement = Arrangement.spacedBy(t.space3),
                            ) {
                                GhostActionButton(
                                    text = "重试失败项",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (selectedFilePath.isNotEmpty()) {
                                            onImport(selectedFilePath)
                                        } else {
                                            toast.show("请先重新选择文件")
                                        }
                                    },
                                )
                                GhostActionButton(
                                    text = "再导一个文件",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onClearResult()
                                        selectedFileName = ""
                                        selectedFilePath = ""
                                    },
                                )
                            }
                        }
                    }

                    item(key = "bottom") { Spacer(Modifier.height(t.space12)) }
                }
            }
        }

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = t.fabSize),
        )
    }
}

@Composable
private fun ResultTile(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(t.space1))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ImportActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusLg))
            .background(if (enabled) t.accent else t.accent.copy(alpha = 0.45f))
            .clickable { if (enabled) onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = t.accentOn,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GhostActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surfaceWarm)
            .clickable { onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = t.fg2)
    }
}

/** 进度文案按阶段推进（与原型的三段提示一致）。 */
private fun progressLabel(value: Float): String {
    val percent = (value * 100).toInt().coerceIn(0, 100)
    val stage = when {
        value < 0.45f -> "正在解析文件… "
        value < 0.9f -> "正在写入本地数据库… "
        else -> "正在重建预算与统计… "
    }
    return stage + percent + "%"
}

private fun getFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

/** 进度条封顶值：导入没有真实进度事件，动画停在 90% 直到结果返回。 */
private const val PROGRESS_CEILING = 0.92f
