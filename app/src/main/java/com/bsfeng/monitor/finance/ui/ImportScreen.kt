package com.bsfeng.monitor.finance.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.PandaImporter
import com.bsfeng.monitor.ui.theme.FinanceColors
import com.bsfeng.monitor.ui.theme.IOSColors
import com.bsfeng.monitor.ui.theme.InsetGroupedCard
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.Radius
import java.io.File
import java.io.FileOutputStream

/**
 * 数据导入页面。
 * 选择熊猫记账的 .db 文件并导入。
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
    val context = LocalContext.current
    var selectedFileName by remember { mutableStateOf("") }
    var selectedFilePath by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it) ?: "panda_jizhang.db"
            selectedFileName = fileName
            try {
                val cacheFile = File(context.cacheDir, "panda_import_${System.currentTimeMillis()}.db")
                context.contentResolver.openInputStream(it)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                selectedFilePath = cacheFile.absolutePath
            } catch (e: Exception) {
                selectedFileName = "文件读取失败: ${e.message}"
                selectedFilePath = ""
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // iOS 导航栏
        item(key = "nav") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MonitorSpacing.sm, vertical = MonitorSpacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("完成", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "导入数据",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.width(64.dp))
            }
        }

        // 说明
        item(key = "desc") {
            Spacer(Modifier.height(MonitorSpacing.xxl))
            Text(
                "熊猫记账数据导入",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
            )
        }
        item {
            InsetGroupedCard {
                Text(
                    "请选择熊猫记账导出的 .db 数据库文件。\n" +
                            "支持导入：账单记录、分类、账户、预算。\n\n" +
                            "已导入的记录不会重复导入（按原始 ID 去重）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    modifier = Modifier.padding(MonitorSpacing.lg),
                )
            }
        }

        // 文件选择
        item(key = "file_label") {
            Spacer(Modifier.height(MonitorSpacing.sm))
            Text(
                "选择数据库文件",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
            )
        }
        item {
            InsetGroupedCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MonitorSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (selectedFileName.isNotEmpty()) selectedFileName else "点击选取文件",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selectedFileName.isNotEmpty())
                                MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selectedFileName.isNotEmpty()) {
                            Text(
                                "已选择",
                                style = MaterialTheme.typography.labelMedium,
                                color = FinanceColors.income,
                            )
                        }
                    }
                    Button(onClick = { filePickerLauncher.launch("application/octet-stream") }) {
                        Text("选取")
                    }
                }
            }
        }

        // 导入按钮
        item(key = "import_btn") {
            Spacer(Modifier.height(MonitorSpacing.lg))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MonitorSpacing.lg)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(
                        if (selectedFilePath.isNotEmpty() && !importing)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = {
                        if (selectedFilePath.isNotEmpty()) onImport(selectedFilePath)
                    },
                    enabled = selectedFilePath.isNotEmpty() && !importing,
                ) {
                    Text(
                        if (importing) "导入中..." else "开始导入",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(MonitorSpacing.lg))
        }

        // 导入中
        if (importing) {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(MonitorSpacing.xxxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(MonitorSpacing.md))
                        Text("正在导入数据...",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // 导入结果
        val result = importResult
        if (result != null && !importing) {
            item(key = "result_section") {
                Spacer(Modifier.height(MonitorSpacing.sm))
                Text(
                    "导入结果",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
                )
            }
            item {
                InsetGroupedCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(MonitorSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (result.isSuccess) "✅" else "⚠️",
                            style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(MonitorSpacing.md))
                        Column {
                            Text(
                                if (result.isSuccess) "导入完成" else "导入完成（有错误）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val animTransactions by animateIntAsState(
                                targetValue = result.totalTransactions,
                                animationSpec = tween(durationMillis = 600),
                            )
                            Text(
                                "共 $animTransactions 条记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                InsetGroupedCard {
                    ImportStatRow("账单导入", "${result.importedTransactions}")
                    if (result.importedTransactions > 0) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = MonitorSpacing.lg),
                        )
                    }
                    ImportStatRow("账单跳过（已存在）", "${result.skippedTransactions}")
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = MonitorSpacing.lg),
                    )
                    ImportStatRow("分类导入", "${result.importedCategories}")
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = MonitorSpacing.lg),
                    )
                    ImportStatRow("账户导入", "${result.importedAccounts}")
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = MonitorSpacing.lg),
                    )
                    ImportStatRow("预算导入", "${result.importedBudgets}")
                }
            }

            // 错误列表
            if (result.hasErrors) {
                item {
                    InsetGroupedCard {
                        Text(
                            "错误信息",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(MonitorSpacing.lg, MonitorSpacing.md,
                                MonitorSpacing.lg, MonitorSpacing.xs),
                        )
                        result.errors.forEach { error ->
                            Text(
                                "⚠ $error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.xs),
                            )
                        }
                    }
                }
            }

            // 重新导入按钮
            item(key = "retry") {
                Spacer(Modifier.height(MonitorSpacing.lg))
                OutlinedButton(
                    onClick = { onClearResult() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MonitorSpacing.lg),
                ) {
                    Text("重新导入")
                }
                Spacer(Modifier.height(MonitorSpacing.xxxl))
            }
        }

        item(key = "bottom") { Spacer(Modifier.height(MonitorSpacing.xxxl)) }
    }
}

@Composable
private fun ImportStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm.plus(2.dp)),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold)
    }
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
