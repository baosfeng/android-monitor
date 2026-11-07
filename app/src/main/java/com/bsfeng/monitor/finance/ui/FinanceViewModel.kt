package com.bsfeng.monitor.finance.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.finance.FinanceInsertSignal
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.finance.importer.BillImporter
import com.bsfeng.monitor.finance.PandaImporter
import com.bsfeng.monitor.finance.importer.BillImportResult
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * 记账模块 ViewModel。
 *
 * 管理记账 UI 的所有状态，提供对 FinanceManager 的协程封装。
 */
class FinanceViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = FinanceManager.getInstance(application)
    private val importer = PandaImporter(manager)

    /** 支付宝 / 微信账单导入（.csv / .xlsx / .zip）；与 [importer] 各管一类文件，互不替代。 */
    private val billImporter = BillImporter(manager)

    // ========== 状态 ==========

    private val _currentMonth = MutableStateFlow(currentMonthStr())
    val currentMonth: StateFlow<String> = _currentMonth.asStateFlow()

    private val _monthlyTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val monthlyTransactions: StateFlow<List<Transaction>> = _monthlyTransactions.asStateFlow()

    private val _monthlyStats = MutableStateFlow(FinanceStats())
    val monthlyStats: StateFlow<FinanceStats> = _monthlyStats.asStateFlow()

    private val _categoryBreakdown = MutableStateFlow<Map<String, Double>>(emptyMap())
    val categoryBreakdown: StateFlow<Map<String, Double>> = _categoryBreakdown.asStateFlow()

    private val _budgets = MutableStateFlow<List<Budget>>(emptyList())
    val budgets: StateFlow<List<Budget>> = _budgets.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _availableMonths = MutableStateFlow<List<String>>(emptyList())
    val availableMonths: StateFlow<List<String>> = _availableMonths.asStateFlow()

    /** 月份切换越界提示（一次性消费；原型 s04/s06/s07 月份条到边界时的 toast 口径）。 */
    private val _monthNotice = MutableStateFlow<String?>(null)
    val monthNotice: StateFlow<String?> = _monthNotice.asStateFlow()

    fun consumeMonthNotice() {
        _monthNotice.value = null
    }

    /**
     * 写操作成功提示（一次性；列表页消费后清空）。
     *
     * 文案口径取 s05-save「已保存 · 支出 餐饮 ¥38.00」与 s05-del-confirm「已删除这笔交易」，
     * 1.6s 停留由 `AppToast` 统一；本状态只回答「哪条文案 + 哪种触感」，不参与取数 / 排序 / 筛选。
     */
    private val _actionNotice = MutableStateFlow<FinanceActionNotice?>(null)
    val actionNotice: StateFlow<FinanceActionNotice?> = _actionNotice.asStateFlow()

    fun consumeActionNotice() {
        _actionNotice.value = null
    }

    // 导入状态
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importResult = MutableStateFlow<PandaImporter.ImportResult?>(null)
    val importResult: StateFlow<PandaImporter.ImportResult?> = _importResult.asStateFlow()

    /** 账单导入（支付宝 / 微信）的结果；与 [importResult] 互斥，同一时刻只有一个非空。 */
    private val _billImportResult = MutableStateFlow<BillImportResult?>(null)
    val billImportResult: StateFlow<BillImportResult?> = _billImportResult.asStateFlow()

    // 加载状态
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * 当前月读取是否**真实失败**（异常，不是 DEBUG 演示态）。
     *
     * 列表页据此把「读不出来」渲染成错态卡而不是空态卡（s04-error 前置「本地数据库读取失败」）；
     * 读取本身不抛给调用方 —— 抛出去会打穿 viewModelScope，用户只会看到空白的空态。
     */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** 当前这次加载的协程（新加载会取消它，避免过期结果覆盖新结果） */
    private var loadJob: Job? = null

    /** 加载序号：只有最新一次加载有权写状态、清 loading */
    private val loadSequence = AtomicInteger(0)

    // ========== 初始化 ==========

    init {
        // 种子数据（分类/账户）初始化在后台协程完成后再加载数据，
        // 避免主线程同步建库造成卡顿
        viewModelScope.launch {
            manager.ensureInitialized()
            loadCurrentMonth()
        }
        observeExternalChanges()
    }

    /**
     * 订阅 [FinanceManager.changeVersion]：任何写操作（含自动记账落库、导入、预算）成功后
     * 重新加载当前浏览月。
     *
     * - `drop(1)` 跳过订阅瞬间的版本值 —— 首次加载由上面的 init 协程负责，不必重复读库；
     * - 加载是只读的，不会 bump，所以不存在「加载 → 版本变化 → 再加载」的自激循环；
     * - 重入由 [loadMonth] 里的 job 取消 + token 兜住，连着触发两次也只留最后一次结果。
     */
    private fun observeExternalChanges() {
        viewModelScope.launch {
            manager.changeVersion.drop(1).collect {
                loadMonth(_currentMonth.value)
            }
        }
    }

    fun loadCurrentMonth(onResult: (Result<Unit>) -> Unit = {}) {
        loadMonth(_currentMonth.value, onResult)
    }

    /**
     * 读取某个月的全部页面数据。
     *
     * @param onResult 本次读取的**真实结果**（成功后 [loadFailed] 复位、失败后置位）。重试入口据此决定
     *   「停在错态」还是「回到有数据态」——失败时不得弹成功提示（s04-retry 边界「再次失败保持错态、
     *   不叠加第二个 toast」）。默认空实现，既有调用方行为不变。
     */
    fun loadMonth(yearMonth: String, onResult: (Result<Unit>) -> Unit = {}) {
        _currentMonth.value = yearMonth
        // 同一时刻只保留一次加载：切 Tab / ON_RESUME / 变更通知常在同一批帧里连着触发。
        // token 防止被取消的旧协程把过期结果写回来（跨月切换时尤其明显）。
        val token = loadSequence.incrementAndGet()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            try {
                val transactions = manager.getMonthlyTransactions(yearMonth)
                val stats = manager.getMonthlyStats(yearMonth)
                val breakdown = manager.getCategoryBreakdown(yearMonth)
                val budgets = manager.getMonthlyBudget(yearMonth)
                val categories = manager.getAllCategories()
                val accounts = manager.getAllAccounts()
                val months = manager.getAvailableMonths()
                if (token != loadSequence.get()) return@launch
                _monthlyTransactions.value = transactions
                _monthlyStats.value = stats
                _categoryBreakdown.value = breakdown
                _budgets.value = budgets
                _categories.value = categories
                _accounts.value = accounts
                _availableMonths.value = months
                _loadFailed.value = false
                onResult(Result.success(Unit))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 被新加载取代：这不是失败，不能把错态推给用户
                throw e
            } catch (e: Exception) {
                // 读库异常此前会直接抛穿协程、屏幕只留下空态；改为落到错态并如实回执。
                Log.w("FinanceViewModel", "读取 " + yearMonth + " 记账数据失败", e)
                if (token == loadSequence.get()) {
                    _loadFailed.value = true
                    onResult(Result.failure(e))
                }
            } finally {
                // 只有仍在位的那次加载有权清掉 loading，否则会把新加载的指示器提前灭掉
                if (token == loadSequence.get()) _loading.value = false
            }
        }
    }

    /** 切换到上一个月：已在最早可浏览月时只提示、不改月份（接 [availableMonths] 边界）。 */
    fun previousMonth() {
        val ym = _currentMonth.value
        val earliest = _availableMonths.value.minOrNull()
        if (earliest != null && ym <= earliest) {
            _monthNotice.value = "已经是可浏览的最早月份（" + monthLabelOf(ym) + "）"
            return
        }
        val parts = ym.split("-")
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        val newMonth = if (month == 1) "${year - 1}-12" else String.format(Locale.US, "%04d-%02d", year, month - 1)
        loadMonth(newMonth)
    }

    /** 切换到下一个月：已在最新可浏览月时只提示、不改月份（接 [availableMonths] 边界）。 */
    fun nextMonth() {
        val ym = _currentMonth.value
        val latest = _availableMonths.value.maxOrNull()
        if (latest != null && ym >= latest) {
            _monthNotice.value = "已经是可浏览的最新月份（" + monthLabelOf(ym) + "）"
            return
        }
        val parts = ym.split("-")
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        val newMonth = if (month == 12) "${year + 1}-01" else String.format(Locale.US, "%04d-%02d", year, month + 1)
        loadMonth(newMonth)
    }

    /** 切换到当前月 */
    fun goToCurrentMonth() {
        loadMonth(currentMonthStr())
    }

    // ========== 交易操作 ==========
    //
    // 这里保留写后显式 loadMonth 作为兜底（即使通知订阅出问题，自己的改动也立刻可见）；
    // 通知链路同时会触发一次加载，两者被 [loadMonth] 的 job 取消 + token 合并成一次结果。

    /**
     * 新增一笔交易。
     *
     * 写库异常此前会直接抛穿 viewModelScope（协程里的未捕获异常会崩进程）：用户刚填的
     * 金额 / 分类 / 说明全丢，而且零提示。锚点 s14a-save 的失败路径在实现里根本不存在。
     * 现在失败落到 [actionNotice]（1.6s toast），表单的输入随组合卸载由路由保留，
     * 进程不崩、用户知道「没存上、可以再点一次」。
     *
     * @param onResult 本次写入的**真实结果**。默认空实现，既有调用方行为不变；
     *   需要「失败就停在表单、成功才返回」的调用方（s14a）据此分流。
     */
    fun addTransaction(tx: Transaction, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runFinanceWrite { manager.addTransaction(tx) }
            result.onSuccess { id ->
                // 锚点 s14a-save / s05-save：手动保存的新行也要「就地出现并高亮 1.6s」。
                // 与自动记账共用同一条「刚落库」一次性信号（lead 裁定：口径优先于旧 KDoc），
                // 列表侧消费一次即清；编辑 / 切 Tab / 回前台重载都不点亮。
                FinanceInsertSignal.mark(id)
                _actionNotice.value = FinanceActionNotice(savedNoticeMessage(tx), deleted = false)
                loadMonth(_currentMonth.value)
            }.onFailure { e ->
                Log.w("FinanceViewModel", "新增交易写库失败", e)
                _actionNotice.value = FinanceActionNotice(SAVE_FAILED_NOTICE, deleted = false)
            }
            onResult(result.map { Unit })
        }
    }

    /**
     * 删除一笔交易。失败不再打穿协程，给用户一句「没删掉」而不是整页崩掉。
     *
     * @param onResult 本次删除的真实结果（见 [addTransaction]）。
     */
    fun deleteTransaction(id: Long, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runFinanceWrite { manager.deleteTransaction(id) }
            result.onSuccess {
                _actionNotice.value = FinanceActionNotice(DELETE_NOTICE, deleted = true)
                loadMonth(_currentMonth.value)
            }.onFailure { e ->
                Log.w("FinanceViewModel", "删除交易写库失败", e)
                _actionNotice.value = FinanceActionNotice(DELETE_FAILED_NOTICE, deleted = false)
            }
            onResult(result)
        }
    }

    /**
     * 更新一笔交易。失败同样是可见提示 + 回执，不打穿 viewModelScope。
     *
     * @param onResult 本次更新的真实结果（见 [addTransaction]）。
     */
    fun updateTransaction(tx: Transaction, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runFinanceWrite { manager.updateTransaction(tx) }
            result.onSuccess {
                _actionNotice.value = FinanceActionNotice(savedNoticeMessage(tx), deleted = false)
                loadMonth(_currentMonth.value)
            }.onFailure { e ->
                Log.w("FinanceViewModel", "更新交易写库失败", e)
                _actionNotice.value = FinanceActionNotice(UPDATE_FAILED_NOTICE, deleted = false)
            }
            onResult(result)
        }
    }

    // ========== 预算操作 ==========

    /**
     * 写总预算（class [setCategoryBudget] 同一口径的回执）。
     *
     * @param onResult 写入结果回执：成功 / 失败原因。UI（[BudgetScreen]）据此决定关不关弹窗 ——
     *   s06-dlg-save 边界要求「写入失败时弹窗不关、输入保留」。默认空实现，既有调用方行为不变。
     */
    fun setBudget(categoryName: String, amount: Double, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            try {
                manager.setBudget(_currentMonth.value, categoryName, amount)
                loadMonth(_currentMonth.value)
                onResult(Result.success(Unit))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("FinanceViewModel", "写入总预算失败", e)
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * 修改某条分类预算，并把总预算行同步为「分类预算之和」（对齐原型 s06 的口径：
     * 分类预算合计等于总预算）。写入后重新加载本月数据，总额 / 百分比 / 圆环随之重算。
     *
     * 业务读写仍全部经由 [FinanceManager.setBudget]，未改动任何统计口径。
     */
    fun setCategoryBudget(categoryName: String, amount: Double, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val month = _currentMonth.value
            try {
                manager.setBudget(month, categoryName, amount)
                val sum = manager.getMonthlyBudget(month)
                    .filterNot { it.isOverall }
                    .sumOf { it.budgetAmount }
                manager.setBudget(month, "", sum)
                loadMonth(month)
                onResult(Result.success(Unit))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 两段写入不在同一事务里：第二段失败时分类预算可能已落库。这里不吞错、
                // 也不回滚（回滚同样可能失败），交给 UI 提示重试，用户重试覆盖为期望值。
                Log.w("FinanceViewModel", "写入分类预算失败", e)
                onResult(Result.failure(e))
            }
        }
    }

    // ========== 导入操作 ==========

    fun importFromPanda(dbPath: String): Deferred<PandaImporter.ImportResult> {
        val deferred = viewModelScope.async {
            _importing.value = true
            _importResult.value = null
            // 反向互斥：走熊猫导入时把账单结果清掉，结果卡不会新旧并存
            _billImportResult.value = null
            try {
                val result = importer.import(dbPath)
                _importResult.value = result
                // 导入后刷新
                if (result.importedTransactions > 0) {
                    loadMonth(_currentMonth.value)
                }
                result
            } finally {
                _importing.value = false
            }
        }
        return deferred
    }

    fun clearImportResult() {
        _importResult.value = null
        _billImportResult.value = null
    }

    /**
     * 导入支付宝 / 微信账单文件。
     *
     * 与 [importFromPanda] 同一套生命周期：置 [importing] → 清旧结果 → 跑导入 → 回填结果 →
     * 有写入才刷新当前月 → finally 复位 importing。区别只在结果类型与「有写入」的判据
     * （账单的已存在记录是**更新**而非跳过，所以看 [BillImportResult.hasWrites]）。
     *
     * @param fileName 原始文件名 —— 解析侧靠它识别渠道（alipay / wechat）与容器格式
     * @param bytes 文件原始字节（重试时调用方会再传一次同一份内容）
     */
    fun importBillFile(fileName: String, bytes: ByteArray): Deferred<BillImportResult> {
        val deferred = viewModelScope.async {
            _importing.value = true
            // 两个结果互斥：切到账单导入时，上一次熊猫导入的旧卡片必须先消失
            _importResult.value = null
            _billImportResult.value = null
            try {
                val result = billImporter.import(fileName, bytes)
                _billImportResult.value = result
                // 导入后刷新（失败 / 全跳过时不写库，也就不必重读）
                if (result.hasWrites) {
                    loadMonth(_currentMonth.value)
                }
                result
            } finally {
                _importing.value = false
            }
        }
        return deferred
    }

    fun clearBillImportResult() {
        _billImportResult.value = null
    }

    /**
     * 按请求类型分流到两个 importer 之一。
     *
     * 分流放在这里而不是 MainTabContent：调用点只需要把 UI 组装好的请求原样转交，
     * 不必同时 import 两个 importer 的结果类型，两处调用点（叠层 / 独立路由）也就不用各写一遍 when。
     */
    internal fun dispatchImport(request: ImportRequest) {
        when (request) {
            is ImportRequest.Database -> importFromPanda(request.path)
            is ImportRequest.Bill -> importBillFile(request.fileName, request.bytes)
        }
    }

    // ========== 工具方法 ==========

    /** 按日期分组的交易列表 */
    fun getTransactionsGroupedByDate(transactions: List<Transaction>): Map<String, List<Transaction>> {
        return transactions.groupBy { it.date }
    }

    /** 获取当月中文标题 "2024年5月" */
    fun getMonthDisplayName(yearMonth: String): String {
        val parts = yearMonth.split("-")
        return "${parts[0]}年${parts[1].toInt()}月"
    }

    companion object {
        fun currentMonthStr(): String {
            val now = LocalDate.now()
            return now.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        }
    }
}

/**
 * 把一次写库动作收成 [Result]，并**只吞 Exception、绝不吞取消**。
 *
 * 为什么不用裸 runCatching：协程取消是 CancellationException（Exception 的子类），
 * runCatching 会把它一起捕获 —— 于是一次「切换月份导致的取消」会被当成写失败，
 * 弹一句「保存失败」给用户。这里显式重抛取消（与 [FinanceViewModel.loadMonth] /
 * [FinanceViewModel.setBudget] 的既有口径一致），其余异常落成 failure。
 *
 * 抽成独立函数是为了可单测：写库抛异常时返回失败、不向上抛（此前会打穿 viewModelScope 崩进程）。
 */
internal suspend fun <T> runFinanceWrite(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/**
 * 一次写操作的成功提示：文案 + 触感类别（删除 = REJECT，保存 = SUCCESS）。
 *
 * 为什么由 ViewModel 出文案：它手里有交易本身的类型 / 分类 / 金额；列表页只负责把它渲染成
 * toast（`AppToast`，1.6s 停留）与触感，不再自己拼口径。
 */
data class FinanceActionNotice(
    val message: String,
    val deleted: Boolean,
)

/** 删除成功文案（s05-del-confirm 逐字）。 */
private const val DELETE_NOTICE: String = "已删除这笔交易"

/**
 * 写库失败的三句文案：告诉用户「没成功」+「重试不会写重 / 不会删错」。
 *
 * 不说「请重试」之外的细节（异常类型对用户无意义），也不说「已保存」——失败的路径
 * 绝不能复用成功文案，否则用户以为存上了。
 */
internal const val SAVE_FAILED_NOTICE: String = "保存失败 · 未写入，请重试"
internal const val DELETE_FAILED_NOTICE: String = "删除失败 · 记录还在，请重试"
internal const val UPDATE_FAILED_NOTICE: String = "保存失败 · 修改未写入，请重试"

/**
 * 保存成功文案（s05-save 逐字）：「已保存 · 支出 餐饮 ¥38.00」= 已保存 · 类型 分类 ¥金额。
 *
 * 分类为空（转账不写分类，见 [AddTransactionDraft.build]）时省略该段：「已保存 · 转账 ¥38.00」；
 * 金额取绝对值两位小数，退款（负金额）不显示负号 —— 用户点的是「保存成功」，不是收支方向。
 */
internal fun savedNoticeMessage(tx: Transaction): String {
    val kind = when {
        tx.isTransfer -> "转账"
        tx.isIncome -> "收入"
        tx.isRefund -> "退款"
        else -> "支出"
    }
    val category = tx.categoryName.trim()
    val categoryPart = if (category.isEmpty()) "" else " $category"
    return "已保存 · $kind$categoryPart " + yen(kotlin.math.abs(tx.amount), 2)
}
