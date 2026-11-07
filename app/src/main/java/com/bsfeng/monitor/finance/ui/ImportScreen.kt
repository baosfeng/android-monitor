package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt；职责：数据导入屏幕装配（文件选择 / 进度 / 结果与五态）。

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.finance.PandaImporter
import com.bsfeng.monitor.finance.importer.BillImportResult
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch
import com.bsfeng.monitor.ui.foundation.pageBackdrop

/**
 * s08 数据导入。
 *
 * 三步说明 + 文件选择 + 进度条 + 结果统计 + 失败明细与重试；五态齐备。
 * 两个来源共用一个结果壳：熊猫记账的 .db 走 [PandaImporter]，支付宝 / 微信账单
 * （.csv / .xlsx / .zip）走 BillImporter —— 分流在这里按扩展名做，调用方只接 [ImportRequest]。
 */
@Composable
internal fun ImportScreen(
    importing: Boolean,
    importResult: PandaImporter.ImportResult?,
    billImportResult: BillImportResult?,
    onImport: (ImportRequest) -> Unit,
    onDismiss: () -> Unit,
    onClearResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val toast = rememberToastHostState()
    val control = rememberFinanceStateControl()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var selectedName by remember { mutableStateOf("") }
    var selectedPath by remember { mutableStateOf("") }
    var selectedBytes by remember { mutableStateOf(0L) }
    // 选文件后落盘的缓存路径 + 归属（决定后续走 PandaImporter 还是 BillImporter）。
    // 账单文件在选中时就一次性读进内存：OpenDocument 给的临时授权只在本次会话内可靠，
    // 而「重试失败项」要能再读一次同一份字节。
    var selectedKind by remember { mutableStateOf(ImportKind.UNKNOWN) }
    var selectedBytesData by remember { mutableStateOf<ByteArray?>(null) }
    var readError by remember { mutableStateOf("") }
    // 连点防护：点击后到 StateFlow 回填 importing 之间可能有一点间隔
    var submitting by remember { mutableStateOf(false) }
    // 重试失败项：结果返回后按「重试前失败数」给 toast（锚点 s08-retry-failed）
    var pendingRetry by remember { mutableStateOf(false) }
    var retryBase by remember { mutableStateOf(0) }
    // 焦点迁移 tick：节点要等重组后才存在，延一帧再 requestFocus
    var pickTick by remember { mutableStateOf(0) }
    var fileTick by remember { mutableStateOf(0) }

    val pickFocus = remember { FocusRequester() }
    val fileFocus = remember { FocusRequester() }
    val resultFocus = remember { FocusRequester() }
    val againFocus = remember { FocusRequester() }
    // 锚点 s08-empty「焦点落标题」/ s08-error「焦点直接落在『重试』」/ s08-fail「落在『重新选择文件』」
    val emptyTitleFocus = remember { FocusRequester() }
    val errorRetryFocus = remember { FocusRequester() }
    val failRetryFocus = remember { FocusRequester() }

    fun dropCache(path: String) {
        if (path.isNotEmpty()) runCatching { File(path).delete() }
    }

    fun clearSelection() {
        dropCache(selectedPath)
        selectedName = ""
        selectedPath = ""
        selectedBytes = 0L
        // 账单把内容读进了内存，一起清掉 —— 否则「移除文件」后点重试仍会拿旧字节再导一遍
        selectedKind = ImportKind.UNKNOWN
        selectedBytesData = null
        readError = ""
    }

    /**
     * 一次性把选中的文件读进缓存：DB 留路径给 PandaImporter 传参，账单文件额外留字节。
     *
     * @return 失败原因；null 表示读取成功。
     */
    fun loadPicked(uri: Uri, fileName: String): String? = try {
        dropCache(selectedPath)
        val kind = ImportKind.of(fileName)
        // 缓存前缀通用，扩展名跟着原文件走 —— 此前写死 panda_import_*.db，
        // 会把 csv 命名成 .db，靠扩展名分流的逻辑（以及睡前调试的肉眼核对）全会错。
        val suffix = fileName.substringAfterLast('.', "").lowercase().ifEmpty { "dat" }
        val cacheFile = File(context.cacheDir, "import_" + System.currentTimeMillis() + "." + suffix)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("文件已被移动")
        stream.use { input ->
            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
        }
        selectedKind = kind
        selectedName = fileName
        selectedPath = cacheFile.absolutePath
        selectedBytes = cacheFile.length()
        selectedBytesData = if (kind == ImportKind.BILL) cacheFile.readBytes() else null
        readError = ""
        onClearResult()
        fileTick++
        null
    } catch (e: Exception) {
        clearSelection()
        "文件读取失败：" + (e.message ?: "未知错误")
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        // GetContent 只接受单个 MIME，而这里要同时放行 csv / xlsx / zip / db；
        // OpenMultipleDocuments 的输入是 MIME 数组，且同样返回 Uri 列表（取第一个）。
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        // 取消返回：不出文件行、不出 toast、卡片原样、滚动位置不变
        val uri = uris.firstOrNull() ?: return@rememberLauncherForActivityResult
        val fileName = getFileName(context, uri) ?: ""
        if (fileName.isEmpty()) {
            readError = "无法读取文件名，请换一个文件"
            return@rememberLauncherForActivityResult
        }
        if (ImportKind.of(fileName) == ImportKind.UNKNOWN) {
            readError = "只支持 .csv / .xlsx / .zip / .db"
            return@rememberLauncherForActivityResult
        }
        val failure = loadPicked(uri, fileName)
        if (failure != null) {
            readError = failure
        } else {
            toast.show("已选择 " + fileName)
        }
    }
    val launchPicker = {
        filePickerLauncher.launch(IMPORT_MIME_TYPES)
    }

    // 账单结果映射成同构的结果对象（记录数 / 成功 / 跳过 / 错误同一套语义），
    // 失败判定、结果卡、焦点迁移因此都不分叉；「更新」是它独有的第 4 格，单独往下传。
    val fromBill = billImportResult != null
    val result = importResult ?: billImportResult?.asPandaResult()
    val updatedCount = billImportResult?.updated ?: 0
    // 自动推断出分类的条数（不算兜底「其他」）：用户最关心「有没有猜对」，结果卡要能回答
    val autoCategorized = billImportResult?.categoriesResolved ?: 0
    val emptyFile = result != null && !importing && result.totalTransactions == 0 &&
        result.importedTransactions == 0 && updatedCount == 0 && result.errors.isEmpty()
    val parseFailed = result != null && !importing && (
        // 熊猫：解析器给的固定前缀；账单：一条都没写进去却带着错误，说明格式没认出来
        result.errors.any { it.startsWith("无法打开数据库文件") } ||
            (fromBill && result.errors.isNotEmpty() && result.importedTransactions == 0 &&
                updatedCount == 0) ||
            emptyFile
        )
    val interrupted = result != null && !importing && result.errors.any { it.contains("已回滚") }
    val failed = parseFailed || interrupted
    val derivedState = when {
        failed -> FiveState.ERROR
        // 选文件页是常态页（原型 populated）；空/加载/边界留给五态演示开关
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState
    val hasResult = result != null && !importing && !failed

    /** 把当前选中的文件打包成导入请求；选中项不完整时给一句提示并返回 null。 */
    fun currentRequest(): ImportRequest? {
        if (selectedPath.isEmpty() || selectedName.isEmpty()) {
            toast.show("请先选择要导入的文件")
            return null
        }
        if (selectedKind != ImportKind.BILL) {
            return ImportRequest.Database(selectedName, selectedPath)
        }
        val bytes = selectedBytesData
        if (bytes == null) {
            toast.show("文件内容已失效，请重新选择")
            return null
        }
        return ImportRequest.Bill(selectedName, bytes)
    }

    // 进度：无真实进度事件，按锚点 120ms 线性步进爬到 92%，结果返回即落 1f
    val progress = remember { Animatable(0f) }
    LaunchedEffect(importing) {
        if (importing) {
            progress.snapTo(0f)
            while (progress.value < PROGRESS_CEILING) {
                progress.animateTo(
                    (progress.value + 0.02f).coerceAtMost(PROGRESS_CEILING),
                    tween(t.durState, easing = LinearEasing),
                )
            }
        } else if (result != null) {
            progress.snapTo(1f)
        }
    }

    // 结果返回：焦点落结果卡标题，列表滚到结果卡顶部（结果态结果卡在列表首位）
    LaunchedEffect(result) {
        if (result != null) {
            val wasRetry = pendingRetry
            val retrySucceeded = wasRetry && !result.hasErrors
            if (wasRetry) {
                pendingRetry = false
                if (result.hasErrors) {
                    toast.show("仍有 " + result.errors.size + " 条无法导入")
                } else {
                    toast.show(retryBase.toString() + " 条失败记录已重试，全部导入成功")
                }
            }
            scope.launch { listState.scrollToItem(0) }
            withFrameNanos { }
            // 重试成功后失败明细整块移除，焦点落到同位置的「再导一个文件」；否则落结果卡
            if (retrySucceeded) {
                runCatching { againFocus.requestFocus() }
            } else {
                runCatching { resultFocus.requestFocus() }
            }
        }
        if (!importing) submitting = false
    }

    LaunchedEffect(pickTick) {
        if (pickTick > 0) {
            withFrameNanos { }
            runCatching { pickFocus.requestFocus() }
        }
    }

    LaunchedEffect(fileTick) {
        if (fileTick > 0) {
            withFrameNanos { }
            runCatching { fileFocus.requestFocus() }
        }
    }

    // 锚点 s08-empty：首次进入页面即为空态，焦点落到空态标题（之后交给 pick/file/result 的焦点迁移）
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { emptyTitleFocus.requestFocus() }
    }

    // 锚点 s08-error / s08-fail：错误卡与中断卡本身不进 Tab 序列，焦点直接落到卡上那颗按钮
    LaunchedEffect(failed, parseFailed) {
        if (!failed) return@LaunchedEffect
        withFrameNanos { }
        runCatching {
            if (parseFailed) errorRetryFocus.requestFocus() else failRetryFocus.requestFocus()
        }
    }

    Box(modifier.fillMaxSize().pageBackdrop().testTag("screen-s08")) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 叠层顶栏的**空隙**也要吞点击（锚点 s05-empty-import · 边界）：本页是压在「记一笔」
                    // 表单之上的兄弟节点，标题与两侧之间的空白原先没有指针处理，点击会穿透到下层
                    // AddTransactionTopBar 的标题 → DEBUG 里静默掀开被遮住的五态条（关掉叠层才看见）。
                    // 用 pointerInput 而不是 clickable：clickable 会让 TalkBack 把整条顶栏读成按钮，
                    // 而它本身不可点（同 AutoBookOverlay「卡内吞点击」的取舍）；子节点自身点击不受影响
                    // —— 子 clickable 先消费 down，这里的 detectTapGestures 只接空隙上的手势。
                    .pointerInput(Unit) { detectTapGestures { } }
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

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("s08-content"),
                state = listState,
            ) {
                if (state == FiveState.LOADING) {
                    item(key = "loading") {
                        StateContainer(
                            state = FiveState.LOADING,
                            modifier = Modifier
                                .padding(horizontal = t.space4, vertical = t.space4)
                                .testTag("s08-loading"),
                        ) {}
                    }
                } else {
                    val shown = if (hasResult) result else null
                    if (shown != null) {
                        item(key = "resulthead") {
                            ImportResultSummaryCard(
                                result = shown,
                                updatedCount = updatedCount,
                                autoCategorized = autoCategorized,
                                modifier = Modifier
                                    .focusRequester(resultFocus),
                            )
                        }
                        if (shown.hasErrors) {
                            item(key = "failrows") {
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn(tween(t.durPage)) + expandVertically(tween(t.durOverlayIn)),
                                    exit = fadeOut(tween(t.durOverlayOut)) + shrinkVertically(tween(t.durOverlayOut)),
                                ) {
                                    ImportFailureListCard(errors = shown.errors)
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
                                if (shown.hasErrors) {
                                    GhostActionButton(
                                        text = if (importing) "重试中…" else "重试失败项",
                                        enabled = !importing && selectedPath.isNotEmpty(),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("s08-retry-failed"),
                                        onClick = {
                                            currentRequest()?.let { request ->
                                                submitting = true
                                                retryBase = result?.errors?.size ?: 0
                                                pendingRetry = true
                                                onImport(request)
                                            }
                                        },
                                    )
                                }
                                GhostActionButton(
                                    text = "再导一个文件",
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(againFocus)
                                        .testTag("s08-again"),
                                    onClick = {
                                        clearSelection()
                                        onClearResult()
                                        scope.launch { listState.scrollToItem(0) }
                                        pickTick++
                                    },
                                )
                            }
                        }
                    }

                    if (failed) {
                        item(key = "failed") {
                            Column(Modifier.padding(horizontal = t.space4, vertical = t.space3)) {
                                if (parseFailed) {
                                    // 文案分来源：熊猫 .db 与支付宝 / 微信账单的「认不出」原因不同，
                                    // 统一说成「熊猫导出文件」会让账单用户以为选错了地方
                                    val badFileText = if (emptyFile) {
                                        if (fromBill) {
                                            "文件里没有可导入的账单记录（可能只导出了表头）。"
                                        } else {
                                            "文件里没有可导入的账目记录，或不是有效的熊猫记账导出文件。"
                                        }
                                    } else {
                                        val reason = result.errors.firstOrNull()?.let { " " + it } ?: ""
                                        if (fromBill) {
                                            "无法识别这个账单文件，请确认是支付宝 / 微信导出的原始 .csv / .xlsx / .zip。" + reason
                                        } else {
                                            "不是有效的熊猫记账导出文件，或文件已损坏。" + reason
                                        }
                                    }
                                    ErrorState(
                                        title = "文件解析失败",
                                        text = badFileText,
                                        modifier = Modifier.testTag("s08-error"),
                                        // 锚点 s08-error：焦点直接落到「重试」上
                                        actionModifier = Modifier
                                            .testTag("s08-error-retry")
                                            .focusRequester(errorRetryFocus),
                                        actionLabel = "重试",
                                        onRetry = {
                                            currentRequest()?.let { request ->
                                                submitting = true
                                                onImport(request)
                                            }
                                        },
                                    )
                                } else {
                                    ErrorState(
                                        title = "导入中断",
                                        text = "本次写入已回滚，本地账目未被修改。" +
                                            (result.errors.firstOrNull()?.let { " " + it } ?: ""),
                                        modifier = Modifier.testTag("s08-fail"),
                                        // 锚点 s08-fail：焦点直接落到「重新选择文件」上
                                        actionModifier = Modifier
                                            .testTag("s08-fail-retry")
                                            .focusRequester(failRetryFocus),
                                        actionLabel = "重新选择文件",
                                        onRetry = launchPicker,
                                    )
                                }
                            }
                        }
                    }

                    if (failed) {
                        item(key = "failactions") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = t.space4),
                            ) {
                                GhostActionButton(
                                    text = "再导一个文件",
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(againFocus)
                                        .testTag("s08-again"),
                                    onClick = {
                                        clearSelection()
                                        onClearResult()
                                        scope.launch { listState.scrollToItem(0) }
                                        pickTick++
                                    },
                                )
                            }
                        }
                    }

                    if (!hasResult && !failed && selectedName.isEmpty()) {
                        item(key = "empty") {
                            Column(Modifier.padding(horizontal = t.space4, vertical = t.space3)) {
                                EmptyState(
                                    title = "还没有选择文件",
                                    text = "支付宝「我的 → 账单 → 开具交易流水证明」或微信「我 → 服务 → 钱包 → " +
                                        "账单 → 常见问题 → 下载账单」，也可以选熊猫记账导出的 .db 文件",
                                    icon = Icons.Filled.Description,
                                    // 锚点 s08-empty：焦点落标题；标题块兼作键盘焦点停位（2dp 环）
                                    modifier = Modifier
                                        .testTag("s08-empty")
                                        .focusRequester(emptyTitleFocus)
                                        .keyboardFocusRing(RoundedCornerShape(t.radiusLg))
                                        .focusable(),
                                    actionLabel = "选择数据文件",
                                    actionModifier = Modifier.testTag("s08-empty-pick"),
                                    onAction = { if (!importing) launchPicker() },
                                )
                            }
                        }
                    }

                    if (!hasResult) {
                        item(key = "steps") { ImportStepsCard() }
                    }

                    if (!importing && !hasResult && !failed && selectedName.isEmpty()) {
                        item(key = "pick") {
                            ImportPickCard(
                                readError = readError,
                                enabled = !importing,
                                modifier = Modifier
                                    .focusRequester(pickFocus)
                                    .testTag("s08-drop"),
                                onPick = launchPicker,
                            )
                        }
                    }

                    item(key = "file") {
                        AnimatedVisibility(
                            visible = selectedName.isNotEmpty() && !failed,
                            enter = fadeIn(tween(t.durPage)) + expandVertically(tween(t.durOverlayIn)),
                            exit = fadeOut(tween(t.durOverlayOut)) + shrinkVertically(tween(t.durOverlayOut)),
                        ) {
                            ImportFileCard(
                                fileName = selectedName,
                                ready = selectedPath.isNotEmpty(),
                                sizeText = formatFileSize(selectedBytes),
                                recordCount = shown?.totalTransactions,
                                importing = importing,
                                modifier = Modifier
                                    .focusRequester(fileFocus)
                                    .testTag("s08-file"),
                                onRemove = {
                                    clearSelection()
                                    onClearResult()
                                    pickTick++
                                },
                            )
                        }
                    }

                    if (importing) {
                        item(key = "progress") { ImportProgressCard(progress = progress.value) }
                    }

                    if (!importing && !hasResult && !failed) {
                        item(key = "go") {
                            Column(Modifier.padding(horizontal = t.space4)) {
                                Spacer(Modifier.height(t.space4))
                                ImportActionButton(
                                    text = "开始导入",
                                    enabled = selectedPath.isNotEmpty() && !submitting,
                                    modifier = Modifier.testTag("s08-go"),
                                    onClick = {
                                        currentRequest()?.let { request ->
                                            submitting = true
                                            onImport(request)
                                        }
                                    },
                                )
                                Spacer(Modifier.height(t.space2))
                                Text(
                                    "导入不会删除已有账目；如需重来，先移除文件再重新选择。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = t.meta,
                                )
                            }
                        }
                    }

                    if (state == FiveState.EDGE) {
                        item(key = "edge") { ImportEdgeCard() }
                    }
                }

                item(key = "bottom") { Spacer(Modifier.height(t.space12)) }
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

