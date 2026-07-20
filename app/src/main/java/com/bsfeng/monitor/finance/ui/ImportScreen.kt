package com.bsfeng.monitor.finance.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.finance.PandaImporter
import java.io.File
import java.io.FileOutputStream

/**
 * 数据导入页面 — iOS 风格。
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

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it) ?: "panda_jizhang.db"
            selectedFileName = fileName
            // 复制到缓存目录
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
            .background(iOSColors.systemBackground)
    ) {
        // iOS 导航栏
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(iOSColors.groupedBackground)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("完成", color = iOSColors.blue)
                }
                Text(
                    "导入数据",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = iOSColors.labelPrimary
                )
                // 空白占位对齐
                Spacer(Modifier.width(64.dp))
            }
        }

        // 说明
        item {
            Spacer(Modifier.height(24.dp))
            iOSGroupedList {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "熊猫记账数据导入",
                        fontSize = iOSFontSizes.body,
                        fontWeight = FontWeight.SemiBold,
                        color = iOSColors.labelPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "请选择熊猫记账导出的 .db 数据库文件。\n" +
                                "支持导入：账单记录、分类、账户、预算。\n\n" +
                                "已导入的记录不会重复导入（按原始 ID 去重）。",
                        fontSize = iOSFontSizes.footnote,
                        color = iOSColors.labelSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 文件选择
        item {
            iOSGroupedList {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (selectedFileName.isNotEmpty()) selectedFileName else "选择数据库文件",
                            fontSize = iOSFontSizes.body,
                            color = if (selectedFileName.isNotEmpty()) iOSColors.labelPrimary else iOSColors.labelTertiary
                        )
                        if (selectedFileName.isNotEmpty()) {
                            Text(
                                "已选择",
                                fontSize = iOSFontSizes.caption,
                                color = iOSColors.green
                            )
                        }
                    }
                    TextButton(onClick = { filePickerLauncher.launch("application/octet-stream") }) {
                        Text("选取文件", color = iOSColors.blue)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 导入按钮
        item {
            iOSButton(
                text = if (importing) "导入中..." else "开始导入",
                onClick = {
                    if (selectedFilePath.isNotEmpty()) {
                        onImport(selectedFilePath)
                    }
                },
                enabled = selectedFilePath.isNotEmpty() && !importing
            )
            Spacer(Modifier.height(16.dp))
        }

        // 导入中
        if (importing) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = iOSColors.blue,
                            strokeWidth = 3.dp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "正在导入数据...",
                            fontSize = iOSFontSizes.subhead,
                            color = iOSColors.labelSecondary
                        )
                    }
                }
            }
        }

        // 导入结果
        val result = importResult
        if (result != null && !importing) {
            item {
                Spacer(Modifier.height(8.dp))

                // 结果摘要卡片
                iOSGroupedList {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (result.isSuccess) "✅" else "⚠️",
                            fontSize = 24.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (result.isSuccess) "导入完成" else "导入完成（有错误）",
                                fontSize = iOSFontSizes.body,
                                fontWeight = FontWeight.SemiBold,
                                color = iOSColors.labelPrimary
                            )
                            Text(
                                "共 ${result.totalTransactions} 条记录",
                                fontSize = iOSFontSizes.footnote,
                                color = iOSColors.labelSecondary
                            )
                        }
                    }

                    HorizontalDivider(color = iOSColors.gray6, thickness = 0.5.dp)

                    ImportStatRow("账单导入", "${result.importedTransactions}")
                    ImportStatRow("账单跳过（已存在）", "${result.skippedTransactions}")
                    ImportStatRow("分类导入", "${result.importedCategories}")
                    ImportStatRow("账户导入", "${result.importedAccounts}")
                    ImportStatRow("预算导入", "${result.importedBudgets}")
                }

                // 错误列表
                if (result.hasErrors) {
                    Spacer(Modifier.height(16.dp))
                    iOSGroupedList {
                        Text(
                            "错误信息",
                            fontSize = iOSFontSizes.subhead,
                            fontWeight = FontWeight.SemiBold,
                            color = iOSColors.red,
                            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)
                        )
                        for (error in result.errors) {
                            Text(
                                "⚠ $error",
                                fontSize = iOSFontSizes.footnote,
                                color = iOSColors.red,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 重新导入按钮
                iOSButton(
                    text = "重新导入",
                    onClick = { onClearResult() },
                    color = iOSColors.gray
                )

                Spacer(Modifier.height(32.dp))
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ImportStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = iOSFontSizes.subhead, color = iOSColors.labelPrimary)
        Text(value, fontSize = iOSFontSizes.subhead, color = iOSColors.blue, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = iOSColors.gray6, thickness = 0.5.dp)
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
