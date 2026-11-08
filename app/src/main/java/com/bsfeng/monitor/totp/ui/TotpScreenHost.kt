package com.bsfeng.monitor.totp.ui

// 验证器宿主：把六个屏、路由与系统能力（相机权限 / 相册 / 文件选择 / FLAG_SECURE / 后台遮罩）接起来。
//
// 为什么宿主单独一层：屏是纯渲染件（参数化、可单测），系统交互不该散进六屏里；
// 路由用 TotpRoute + Saver，旋转与进程重建后回到同一屏。

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.totp.TotpSecureScreen
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.launch

/**
 * 验证器整屏入口（概览页右上角图标 push 进来）。
 *
 * [onBack] 只负责「退出整个模块」；模块内部返回由宿主自己处理。
 */
@Composable
fun TotpScreenHost(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    /** 首次组合落在哪一屏（设置卡「备份与导入」直接进备份页）。 */
    startRoute: TotpRoute = TotpRoute.List,
    viewModel: TotpViewModel = viewModel(factory = TotpViewModelFactory),
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }

    var route by rememberSaveable(stateSaver = TotpRouteSaver) {
        mutableStateOf(startRoute)
    }

    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val unreadable by viewModel.unreadableIds.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    // 单一 ticker：全列表 / 详情 / 手动预演共用同一个 nowMillis（不按行各起协程）
    val nowMillis by viewModel.nowMillis.collectAsStateWithLifecycle()

    val screenshotOn by viewModel.screenshotProtection.collectAsStateWithLifecycle()
    val maskOn by viewModel.backgroundMask.collectAsStateWithLifecycle()

    val listState = when {
        loading -> FiveState.LOADING
        loadError != null -> FiveState.ERROR
        accounts.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }

    // 防截屏：进模块加 FLAG_SECURE，离开清掉（只在这里碰窗口 flag）
    DisposableEffect(activity, screenshotOn) {
        if (activity != null) TotpSecureScreen.apply(activity, screenshotOn)
        onDispose { if (activity != null) TotpSecureScreen.apply(activity, false) }
    }
    // 退出模块时撤销未触发的剪贴板清理
    DisposableEffect(Unit) {
        onDispose { viewModel.onScreenClosed() }
    }

    // 后台遮罩：进程切后台时盖一层，回前台还原（多任务缩略图另有 FLAG_SECURE 兜底）
    var masked by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, maskOn) {
        if (!maskOn) {
            masked = false
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> masked = true
                    Lifecycle.Event.ON_START -> masked = false
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // 返回：模块内逐层退，退到列表再交回外壳
    fun back() {
        route = TotpRoute.List
    }
    BackHandler(enabled = route != TotpRoute.List) { back() }

    // ＝＝ 相机权限 / 相册 / 手电筒 ＝＝
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }
    var torchOn by remember { mutableStateOf(false) }

    var failureOverride by remember { mutableStateOf<String?>(null) }
    val scanFailure by viewModel.scanFailure.collectAsStateWithLifecycle()

    // 进扫码页才申请一次相机权限（只申请 CAMERA，不碰存储 / 通知）
    LaunchedEffect(route) {
        if (route == TotpRoute.Scan && !cameraGranted && !cameraRequested) {
            cameraRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    // 离开扫码页释放手电筒，避免摄像头常亮
    DisposableEffect(route) {
        onDispose {
            if (torchOn) {
                setTorch(context, false)
                torchOn = false
            }
        }
    }

    val albumLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importQrImage(uri)
    }
    // 导出：系统保存框选位置（不写死在公共目录里）
    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? -> if (uri != null) viewModel.writeExport(uri) }
    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> if (uri != null) viewModel.openImportFile(uri) }

    Box(modifier.fillMaxSize()) {
        when (val current = route) {
            TotpRoute.List -> TotpScreen(
                accounts = accounts,
                state = listState,
                nowMillis = nowMillis,
                searchQuery = query,
                onSearchQueryChange = viewModel::setQuery,
                pausedIds = unreadable,
                groupOf = { groupLabelOf(it.issuer) },
                onCopy = { viewModel.copyCode(it) },
                onEdit = { account ->
                    viewModel.openDetail(account)
                    route = TotpRoute.Detail(account.id)
                },
                onAddAccount = { route = TotpRoute.Add },
                onOpenBackup = { route = TotpRoute.Backup },
                onRetry = { viewModel.refresh() },
                onBack = onBack,
            )

            TotpRoute.Add -> TotpAddScreen(
                onScan = { route = TotpRoute.Scan },
                onManualEntry = {
                    viewModel.resetManualForm()
                    route = TotpRoute.Manual
                },
                onPickFromAlbum = { albumLauncher.launch("image/*") },
                onBack = { back() },
            )

            TotpRoute.Scan -> TotpScanScreen(
                cameraDenied = !cameraGranted,
                torchOn = torchOn,
                failureMessage = failureOverride ?: scanFailure,
                onToggleTorch = {
                    val next = !torchOn
                    torchOn = setTorch(context, next) && next
                    if (!torchOn) failureOverride = "这台设备的手电筒不可用"
                },
                onPickImage = {
                    failureOverride = null
                    viewModel.clearScanFailure()
                    albumLauncher.launch("image/*")
                },
                onOpenSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    failureOverride = null
                },
                onManualEntry = {
                    viewModel.resetManualForm()
                    route = TotpRoute.Manual
                },
                onBack = { back() },
                // 真实取景预览：解出的文本（不可信）原样上抛，校验与落库都走 addFromUri
                preview = if (cameraGranted) {
                    {
                        TotpCameraPreview(
                            active = true,
                            modifier = Modifier.fillMaxSize(),
                            onDecoded = { payload ->
                                failureOverride = null
                                viewModel.addFromPayload(payload) { route = TotpRoute.List }
                            },
                        )
                    }
                } else {
                    null
                },
            )

            TotpRoute.Manual -> {
                val form by viewModel.manualForm.collectAsStateWithLifecycle()
                val error by viewModel.manualError.collectAsStateWithLifecycle()
                TotpManualEntryScreen(
                    form = form,
                    onFormChange = viewModel::setManualForm,
                    error = error,
                    nowMillis = nowMillis,
                    onSubmit = { viewModel.submitManual { route = TotpRoute.List } },
                    onGoScan = { route = TotpRoute.Scan },
                    onBack = { back() },
                )
            }

            is TotpRoute.Detail -> {
                val draft by viewModel.detail.collectAsStateWithLifecycle()
                val account = viewModel.accountById(current.accountId)
                // 进程重建后 draft 丢失：按 id 重新铺一次草稿（账户已被删则保持加载态）
                LaunchedEffect(current.accountId) {
                    if (draft == null && account != null) viewModel.openDetail(account)
                }
                TotpDetailScreen(
                    account = account,
                    issuerDraft = draft?.issuer ?: account?.issuer.orEmpty(),
                    accountDraft = draft?.accountName ?: account?.accountName.orEmpty(),
                    onIssuerChange = viewModel::setIssuerDraft,
                    onAccountChange = viewModel::setAccountDraft,
                    nowMillis = nowMillis,
                    positionLabel = viewModel.positionLabel(current.accountId),
                    sourceLabel = "本机 · TOTP",
                    onMoveUp = { viewModel.move(current.accountId, -1) },
                    onMoveDown = { viewModel.move(current.accountId, 1) },
                    onSave = { viewModel.saveDetail { route = TotpRoute.List } },
                    onDelete = { viewModel.deleteAccount(current.accountId) { route = TotpRoute.List } },
                    onBack = { back() },
                )
            }

            TotpRoute.Backup -> {
                val backupState by viewModel.backupScreenState.collectAsStateWithLifecycle()
                val exportPassword by viewModel.exportPassword.collectAsStateWithLifecycle()
                val exportError by viewModel.exportError.collectAsStateWithLifecycle()
                val importFor by viewModel.importFor.collectAsStateWithLifecycle()
                val importPassword by viewModel.importPassword.collectAsStateWithLifecycle()
                val importError by viewModel.importError.collectAsStateWithLifecycle()
                TotpBackupScreen(
                    state = backupState,
                    exportPassword = exportPassword,
                    onExportPasswordChange = viewModel::setExportPassword,
                    exportError = exportError,
                    importDialogFor = importFor,
                    importPassword = importPassword,
                    onImportPasswordChange = viewModel::setImportPassword,
                    importError = importError,
                    onExport = {
                        scope.launch {
                            if (viewModel.buildExport() != null) {
                                createDocLauncher.launch("monitor-totp-backup.txt")
                            }
                        }
                    },
                    onRequestImport = { openDocLauncher.launch(arrayOf("*/*")) },
                    onDismissImport = { viewModel.dismissImport() },
                    onConfirmImport = { viewModel.confirmImport() },
                    onRetryImport = { viewModel.retryImport() },
                    onAddAccount = { route = TotpRoute.Add },
                    onBack = { back() },
                )
            }
        }

        AppToast(
            message = message,
            onDismiss = { viewModel.clearMessage() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // 后台遮罩：只在开关打开时覆盖（回到前台自动撤掉）
        if (masked && maskOn) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(t.surface)
                    .testTag("s22-mask"),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("验证器已隐藏", style = MaterialTheme.typography.titleMedium, color = t.fg)
                Text(
                    "回到前台继续查看验证码",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 找宿主 Activity：FLAG_SECURE 只能加在 Activity 窗口上。 */
private fun Context.findActivity(): Activity? {
    var cursor: Context? = this
    while (cursor is ContextWrapper) {
        if (cursor is Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}

/** 开关手电筒（系统 Camera2 API，不引相机预览依赖）；无闪光灯 / 无权限时返回 false。 */
private fun setTorch(context: Context, on: Boolean): Boolean {
    val manager = context.getSystemService(CameraManager::class.java) ?: return false
    val cameraId = runCatching {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull() ?: return false
    return runCatching { manager.setTorchMode(cameraId, on) }.isSuccess
}
