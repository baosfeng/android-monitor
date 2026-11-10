package com.bsfeng.monitor.totp.ui

// 验证器模块的装配层：六个屏需要的状态与动作集中在这里，屏本身只渲染与上抛。
//
// 三件事只有这里能拍板：
//  1. 倒计时 —— 一个 1s ticker 驱动全列表（每行各起协程会在 20 条账户时留下 20 个循环）；
//  2. 安全三开关 —— 复制走 TotpClipboard、截图保护由宿主落 FLAG_SECURE、后台遮罩见 TotpScreenHost；
//  3. 错误归一 —— 数据层异常一律翻成面向用户的中文提示，界面不读堆栈。

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.totp.TotpBackup
import com.bsfeng.monitor.totp.TotpBackupEntry
import com.bsfeng.monitor.totp.TotpClipboard
import com.bsfeng.monitor.totp.TotpQrDecoder
import com.bsfeng.monitor.totp.TotpRepository
import com.bsfeng.monitor.totp.TotpSettings
import com.bsfeng.monitor.ui.components.FiveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 倒计时刷新间隔：1 秒（与验证码换码窗口同粒度，再快没有意义）。 */
private const val TICK_MS = 1_000L

/** 界面离开后再等 5 秒停止 ticker：旋转 / 短暂切后台不重新起表。 */
private const val TICK_IDLE_MS = 5_000L

/** 单份备份超过这个账户数按分批导入处理（原型 s27-edge 的 1,000 条口径）。 */
internal const val TOTP_BACKUP_BATCH_LIMIT = 1_000

/**
 * 账户详情里正在编辑的字段（id 用于定位）。
 *
 * 参数（算法 / 位数 / 周期 / 类型）与 HOTP 计数器一并进草稿：详情页要能事后修正，
 * 只改名字的话用户仍得重新扫码才能换掉一个错参数。
 */
data class TotpDetailDraft(
    val accountId: Long,
    val issuer: String,
    val accountName: String,
    val params: com.bsfeng.monitor.domain.totp.TotpParams = com.bsfeng.monitor.domain.totp.TotpParams(),
    val counter: Long = 0L,
)

/**
 * 验证器装配层。
 *
 * 依赖一律从构造注入：生产由 [TotpViewModelFactory] 从 `ServiceContainer` 取
 * （装配只发生一次，不在 VM 里现拼数据层），测试直接注入替身。
 * 所有写操作都在 [viewModelScope] 里执行，界面拿到的是不可变快照。
 */
class TotpViewModel internal constructor(
    application: Application,
    private val repository: TotpRepository,
    private val settings: TotpSettings,
    private val clipboard: TotpClipboard,
    private val qrDecoder: TotpQrDecoder,
    private val backup: TotpBackup,
    private val clock: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(application) {

    // ＝＝ 列表 ＝＝

    private val _accounts = MutableStateFlow<List<TotpAccount>>(emptyList())
    val accounts: StateFlow<List<TotpAccount>> = _accounts.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** 密钥不可读（Keystore 重置 / 数据损坏）的账户：列表照常显示，但不算码。 */
    private val _unreadableIds = MutableStateFlow<Set<Long>>(emptySet())
    val unreadableIds: StateFlow<Set<Long>> = _unreadableIds.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 一次性提示（AppToast）：消费后由宿主清空。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /**
     * 全列表共用的时钟。
     *
     * WhileSubscribed：界面一离开就停表，回来立刻续上；旋转不会重置窗口。
     */
    val nowMillis: StateFlow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(TICK_IDLE_MS), clock())

    init {
        refresh()
    }

    /** 读账户 + 读不可读集合；失败保留旧列表并给出错误文案（不清空用户数据）。 */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            runCatching {
                val list = repository.accounts()
                list to repository.unreadableAccountIds().toSet()
            }.onSuccess { (list, unreadable) ->
                _accounts.value = list
                _unreadableIds.value = unreadable
                _loadError.value = null
                _backupScreenState.value = if (list.isEmpty()) FiveState.EMPTY else FiveState.POPULATED
            }.onFailure { error ->
                _loadError.value = error.message ?: "读取账户失败"
            }
            _loading.value = false
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    /**
     * 复制当前验证码。
     *
     * 复制即焚关闭时立刻取消自动清理（copy 内部已按默认 30s 排了清理任务，这里撤销）；
     * 密钥不可读 / 剪贴板不可用都只提示不崩溃。
     */
    fun copyCode(account: TotpAccount): Boolean {
        if (account.secretBase32.isBlank()) {
            _message.value = "密钥不可读，无法生成验证码"
            return false
        }
        val code = deriveCode(account.secretBase32, clock() / 1000, account.params)
        if (code.isEmpty()) {
            _message.value = "验证码生成失败"
            return false
        }
        if (!clipboard.copy(code)) {
            _message.value = "系统剪贴板不可用"
            return false
        }
        if (!settings.clipboardAutoClearEnabled) {
            clipboard.cancelPendingClear()
            _message.value = "已复制"
        } else {
            _message.value = "已复制，30 秒后自动清空"
        }
        return true
    }

    // ＝＝ 三项安全开关（设置页与验证器宿主读同一份）＝＝

    private val _screenshotProtection = MutableStateFlow(settings.screenshotProtectionEnabled)
    val screenshotProtection: StateFlow<Boolean> = _screenshotProtection.asStateFlow()

    private val _clipboardAutoClear = MutableStateFlow(settings.clipboardAutoClearEnabled)
    val clipboardAutoClear: StateFlow<Boolean> = _clipboardAutoClear.asStateFlow()

    private val _backgroundMask = MutableStateFlow(settings.backgroundMaskEnabled)
    val backgroundMask: StateFlow<Boolean> = _backgroundMask.asStateFlow()

    fun setScreenshotProtection(enabled: Boolean) {
        settings.screenshotProtectionEnabled = enabled
        _screenshotProtection.value = enabled
    }

    fun setClipboardAutoClear(enabled: Boolean) {
        settings.clipboardAutoClearEnabled = enabled
        _clipboardAutoClear.value = enabled
        if (!enabled) clipboard.cancelPendingClear()
    }

    fun setBackgroundMask(enabled: Boolean) {
        settings.backgroundMaskEnabled = enabled
        _backgroundMask.value = enabled
    }

    /** 页面退出：撤销尚未触发的剪切板清理，避免后台误清用户后来复制的内容。 */
    fun onScreenClosed() {
        clipboard.cancelPendingClear()
    }

    // ＝＝ 详情（改名 / 排序 / 删除）＝＝

    private val _detail = MutableStateFlow<TotpDetailDraft?>(null)
    val detail: StateFlow<TotpDetailDraft?> = _detail.asStateFlow()

    fun openDetail(account: TotpAccount) {
        _detail.value = TotpDetailDraft(
            accountId = account.id,
            issuer = account.issuer,
            accountName = account.accountName,
            params = account.params,
            counter = account.counter,
        )
    }

    fun setIssuerDraft(value: String) {
        _detail.update { it?.copy(issuer = value) }
    }

    fun setAccountDraft(value: String) {
        _detail.update { it?.copy(accountName = value) }
    }

    /** 详情页参数改动（算法 / 位数 / 周期 / 类型）：只改草稿，保存时才落库。 */
    fun setDetailParams(params: com.bsfeng.monitor.domain.totp.TotpParams) {
        _detail.update { it?.copy(params = params) }
    }

    /** HOTP 计数器在详情页可手改（导入的计数器对不上时，用户只能在这里修正）。 */
    fun setDetailCounter(counter: Long) {
        _detail.update { it?.copy(counter = counter) }
    }

    /** 详情页的账户实体（按 id 从列表取；已被删则 null → 屏走加载态）。 */
    fun accountById(id: Long): TotpAccount? = _accounts.value.firstOrNull { it.id == id }

    /** 「第 N 位 / 共 M 位」：排序说明由列表顺序算，屏不自己数。 */
    fun positionLabel(id: Long): String {
        val list = _accounts.value
        val index = list.indexOfFirst { it.id == id }
        return if (index < 0) "" else "第 " + (index + 1) + " 位 / 共 " + list.size + " 位"
    }

    /** 上移 / 下移：换相邻两位后整表重排（sort_order 以列表下标为准）。 */
    fun move(id: Long, delta: Int) {
        val list = _accounts.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        list.add(target, list.removeAt(index))
        _accounts.value = list
        viewModelScope.launch {
            runCatching { repository.reorder(list.map { it.id }) }
                .onFailure { _message.value = it.message ?: "排序保存失败" }
        }
    }

    /**
     * 保存详情草稿：改名 + 参数 + 计数器一次写回。
     *
     * 校验口径与手动输入一致（平台名/账户名非空、位数 1–10、周期 1–86400），
     * 参数越界时**不落库**——算错码比拒绝保存更糟，用户会以为验证器坏了。
     */
    fun saveDetail(onSaved: () -> Unit) {
        val draft = _detail.value ?: return
        val account = accountById(draft.accountId)
        if (account == null) {
            _message.value = "账户已不存在"
            return
        }
        if (draft.issuer.isBlank()) {
            _message.value = "平台名不能为空"
            return
        }
        if (draft.accountName.isBlank()) {
            _message.value = "账户名不能为空"
            return
        }
        val digitsError = validateDigits(draft.params.digits)
        if (digitsError != null) {
            _message.value = digitsError
            return
        }
        val periodError = validatePeriod(draft.params.periodSec)
        if (periodError != null) {
            _message.value = periodError
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.update(
                    account.copy(
                        issuer = draft.issuer.trim(),
                        accountName = draft.accountName.trim(),
                        params = draft.params,
                        counter = draft.counter,
                    ),
                )
            }.onSuccess {
                _detail.value = null
                _message.value = "已保存"
                refresh()
                onSaved()
            }.onFailure { _message.value = it.message ?: "保存失败" }
        }
    }

    fun deleteAccount(id: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess {
                    _detail.value = null
                    _message.value = "已删除"
                    refresh()
                    onDeleted()
                }
                .onFailure { _message.value = it.message ?: "删除失败" }
        }
    }

    // ＝＝ 手动输入 ＝＝

    private val _manualForm = MutableStateFlow(TotpManualForm())
    val manualForm: StateFlow<TotpManualForm> = _manualForm.asStateFlow()

    private val _manualError = MutableStateFlow<String?>(null)
    val manualError: StateFlow<String?> = _manualError.asStateFlow()

    fun setManualForm(form: TotpManualForm) {
        _manualForm.value = form
        if (_manualError.value != null) _manualError.value = null
    }

    fun resetManualForm() {
        _manualForm.value = TotpManualForm()
        _manualError.value = null
    }

    /** 提交表单：校验通过才落库（校验口径与屏上的实时预演同源，见 TotpFormat）。 */
    fun submitManual(onAdded: () -> Unit) {
        val form = _manualForm.value
        val error = validateManualForm(form)
        if (error != null) {
            _manualError.value = error
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.add(
                    TotpAccount(
                        issuer = form.issuer.trim(),
                        accountName = form.accountName.trim(),
                        secretBase32 = form.secretBase32,
                        params = form.params,
                        // HOTP 的起点计数器：用户从网站拿到的 counter 必须原样带上，
                        // 丢掉它就只能靠「下一码」猜，第一条码一定是错的。
                        counter = if (isHotp(form.params)) form.counter else 0L,
                    ),
                )
            }.onSuccess {
                resetManualForm()
                _message.value = "已添加"
                refresh()
                onAdded()
            }.onFailure { _manualError.value = it.message ?: "保存失败" }
        }
    }

    // ＝＝ 扫码 / 相册 ＝＝

    private val _scanFailure = MutableStateFlow<String?>(null)
    val scanFailure: StateFlow<String?> = _scanFailure.asStateFlow()

    fun clearScanFailure() {
        _scanFailure.value = null
    }

    /**
     * 二维码 / 粘贴文本的统一入口：解析成功即落库并回列表，失败留在扫码页显示原因。
     *
     * 单条 otpauth:// 与 Google 迁移链接（otpauth-migration://，一图多账户）都走
     * [TotpRepository.addBatchFromUri]：扫码端不必先按 scheme 分叉，多账户的结果也
     * 统一成「已添加 N 条」。
     */
    fun addFromPayload(payload: String, onAdded: () -> Unit) {
        viewModelScope.launch {
            val result = runCatching { repository.addBatchFromUri(payload) }.getOrElse { error ->
                _scanFailure.value = error.message ?: "添加失败"
                return@launch
            }
            // 迁移链接里可能整批都失败：此时一条都没入库，原因统一走横幅
            if (result.entries.isEmpty()) {
                _scanFailure.value = result.skipped.firstOrNull() ?: "链接里没有可添加的账户"
                return@launch
            }
            val name = result.entries.first().let { it.issuer.ifBlank { it.account } }
            _scanFailure.value = null
            _message.value = buildString {
                append("已添加")
                if (result.entries.size > 1) append(" ").append(result.entries.size).append(" 条")
                append("「").append(name).append("」")
                if (result.skipped.isNotEmpty()) append("，跳过 ").append(result.skipped.size).append(" 条")
            }
            refresh()
            onAdded()
        }
    }

    /**
     * 从相册选图识别：解码 → 找二维码 → 走同一条添加路径。
     *
     * 失败一律把**中文原因**写进 [scanFailure]：选图动作由添加页发起，失败后用户停在添加页，
     * 那里必须渲染同一条横幅，否则就是「点完相册静默退回」——真机实测过的 P0。
     */
    fun importQrImage(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: return@runCatching null
                    qrDecoder.decode(bitmap)
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                _scanFailure.value = "这张图片里没有识别到二维码，换一张或改手动输入"
                return@launch
            }
            addFromPayload(text) { }
        }
    }

    /** 手动清除相册失败横幅（用户去手动输入或重选时）。 */
    fun clearAlbumFailure() {
        _scanFailure.value = null
    }

    // ＝＝ 行内动作：HOTP 下一码 ＝＝

    /**
     * HOTP：推进一格计数器并刷新列表，随后提示「已生成下一码」。
     *
     * 计数器只增不减（同码不可复用）；账户已被删时只提示，不当作崩溃。
     */
    fun advanceHotp(account: TotpAccount) {
        viewModelScope.launch {
            runCatching { repository.advanceHotpCounter(account.id) }
                .onSuccess { next ->
                    // 详情页开着同一账户时同步草稿计数器，否则顶部码区还按旧计数器算
                    _detail.update { draft ->
                        if (draft != null && draft.accountId == account.id) draft.copy(counter = next) else draft
                    }
                    refresh()
                    _message.value = "已生成下一码"
                }
                .onFailure { error -> _message.value = error.message ?: "生成下一码失败" }
        }
    }

    // ＝＝ 备份与导入 ＝＝

    private val _backupScreenState = MutableStateFlow(FiveState.POPULATED)
    val backupScreenState: StateFlow<FiveState> = _backupScreenState.asStateFlow()

    private val _exportPassword = MutableStateFlow("")
    val exportPassword: StateFlow<String> = _exportPassword.asStateFlow()

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    private val _importFor = MutableStateFlow<String?>(null)
    val importFor: StateFlow<String?> = _importFor.asStateFlow()

    private val _importPassword = MutableStateFlow("")
    val importPassword: StateFlow<String> = _importPassword.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    /** 已生成待写盘的备份字节（选好保存位置后由 [writeExport] 消费）。 */
    private var pendingExport: ByteArray? = null

    /** 待解密的备份正文（本应用加密格式，密码弹窗确认后消费）。 */
    private var pendingImportText: String? = null

    fun setExportPassword(value: String) {
        _exportPassword.value = value
        if (_exportError.value != null) _exportError.value = null
    }

    fun setImportPassword(value: String) {
        _importPassword.value = value
        if (_importError.value != null) _importError.value = null
    }

    fun dismissImport() {
        _importFor.value = null
        _importPassword.value = ""
        _importError.value = null
        pendingImportText = null
    }

    /**
     * 生成加密备份字节；成功返回字节供宿主起系统保存框，失败把原因写进 [exportError]。
     *
     * 导出前重新读一遍账户：列表可能是几分钟前的快照，用旧快照会导出已删除的密钥。
     */
    suspend fun buildExport(): ByteArray? {
        val password = _exportPassword.value
        val passwordError = validateBackupPassword(password)
        if (passwordError != null) {
            _exportError.value = passwordError
            return null
        }
        return runCatching {
            withContext(Dispatchers.IO) {
                val entries = repository.backupEntries()
                backup.export(entries, password)
            }
        }.onSuccess {
            pendingExport = it
            _exportError.value = null
        }.onFailure {
            _exportError.value = it.message ?: "导出失败"
        }.getOrNull()
    }

    /** 写到用户选的 uri；返回是否成功。 */
    fun writeExport(uri: Uri): Boolean {
        val bytes = pendingExport
        if (bytes == null) {
            _exportError.value = "没有可导出的内容"
            return false
        }
        val ok = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } != null
        }.getOrDefault(false)
        if (ok) {
            pendingExport = null
            _message.value = "已导出加密备份"
        } else {
            _exportError.value = "写入文件失败，换个位置再试"
        }
        return ok
    }

    /** 选中备份文件后按内容分流：本应用加密格式要密码，第三方明文直接解析导入。 */
    fun openImportFile(uri: Uri) {
        viewModelScope.launch {
            _backupScreenState.value = FiveState.LOADING
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                _backupScreenState.value = FiveState.ERROR
                _importError.value = "读不到文件内容，换个文件再试"
                return@launch
            }
            if (text.trimStart().startsWith(TOTP_BACKUP_HEADER)) {
                pendingImportText = text
                _importFor.value = "本应用"
                _backupScreenState.value = FiveState.POPULATED
            } else {
                importExternalText(text)
            }
        }
    }

    /** 导入第三方明文（Aegis / 2FAS）。 */
    private suspend fun importExternalText(text: String) {
        runCatching { withContext(Dispatchers.IO) { backup.importExternal(text) } }
            .onSuccess { result -> applyImport(result) }
            .onFailure { error ->
                _backupScreenState.value = FiveState.ERROR
                _importError.value = error.message ?: "导入失败"
            }
    }

    /** 密码弹窗确认：解密本应用备份后入库。 */
    fun confirmImport() {
        val text = pendingImportText
        if (text == null) {
            _importFor.value = null
            return
        }
        viewModelScope.launch {
            _backupScreenState.value = FiveState.LOADING
            val entries = runCatching {
                withContext(Dispatchers.IO) { backup.import(text.toByteArray(Charsets.UTF_8), _importPassword.value) }
            }.getOrElse { error ->
                _backupScreenState.value = FiveState.ERROR
                _importError.value = error.message ?: "导入失败"
                return@launch
            }
            applyImport(com.bsfeng.monitor.totp.TotpImportResult(entries, emptyList()))
            dismissImport()
        }
    }

    /** 导入失败后重试：回到正常态让用户重新选文件（不自动重放同一份坏文件）。 */
    fun retryImport() {
        _importError.value = null
        _backupScreenState.value = if (_accounts.value.isEmpty()) FiveState.EMPTY else FiveState.POPULATED
    }

    private suspend fun applyImport(result: com.bsfeng.monitor.totp.TotpImportResult) {
        if (result.entries.size > TOTP_BACKUP_BATCH_LIMIT) {
            // 超过分批上限：先把文件解析结果摆出来，让用户自己决定是否继续
            _backupScreenState.value = FiveState.EDGE
            return
        }
        val imported = runCatching { repository.importEntries(result.entries) }
            .getOrElse { error ->
                _backupScreenState.value = FiveState.ERROR
                _importError.value = error.message ?: "导入失败"
                return
            }
        val skipped = imported.skipped
        _importError.value = null
        _backupScreenState.value = if (_accounts.value.isEmpty() && imported.entries.isEmpty()) {
            FiveState.EMPTY
        } else {
            FiveState.POPULATED
        }
        _message.value = buildString {
            append("已导入 ")
            append(imported.entries.size)
            append(" 条")
            if (skipped.isNotEmpty()) {
                append("，跳过 ")
                append(skipped.size)
                append(" 条")
            }
        }
        refresh()
    }

    /** 备份页的重试：重新读一遍账户，把错误态清掉。 */
    fun reopenBackup() {
        retryImport()
    }

    private companion object {
        const val TOTP_BACKUP_HEADER = "AMTOTP/1"
    }
}
