package com.bsfeng.monitor.finance.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.finance.PandaImporter
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 记账模块 ViewModel。
 *
 * 管理记账 UI 的所有状态，提供对 FinanceManager 的协程封装。
 */
class FinanceViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = FinanceManager.getInstance(application)
    private val importer = PandaImporter(manager)

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

    // 导入状态
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importResult = MutableStateFlow<PandaImporter.ImportResult?>(null)
    val importResult: StateFlow<PandaImporter.ImportResult?> = _importResult.asStateFlow()

    // 加载状态
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ========== 初始化 ==========

    init {
        loadCurrentMonth()
    }

    fun loadCurrentMonth() {
        loadMonth(_currentMonth.value)
    }

    fun loadMonth(yearMonth: String) {
        _currentMonth.value = yearMonth
        viewModelScope.launch {
            _loading.value = true
            try {
                _monthlyTransactions.value = manager.getMonthlyTransactions(yearMonth)
                _monthlyStats.value = manager.getMonthlyStats(yearMonth)
                _categoryBreakdown.value = manager.getCategoryBreakdown(yearMonth)
                _budgets.value = manager.getMonthlyBudget(yearMonth)
                _categories.value = manager.getAllCategories()
                _accounts.value = manager.getAllAccounts()
                _availableMonths.value = manager.getAvailableMonths()
            } finally {
                _loading.value = false
            }
        }
    }

    /** 切换到上一个月 */
    fun previousMonth() {
        val ym = _currentMonth.value
        val parts = ym.split("-")
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        val newMonth = if (month == 1) "${year - 1}-12" else String.format("%04d-%02d", year, month - 1)
        loadMonth(newMonth)
    }

    /** 切换到下一个月 */
    fun nextMonth() {
        val ym = _currentMonth.value
        val parts = ym.split("-")
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        val newMonth = if (month == 12) "${year + 1}-01" else String.format("%04d-%02d", year, month + 1)
        loadMonth(newMonth)
    }

    /** 切换到当前月 */
    fun goToCurrentMonth() {
        loadMonth(currentMonthStr())
    }

    // ========== 交易操作 ==========

    fun addTransaction(tx: Transaction) {
        viewModelScope.launch {
            manager.addTransaction(tx)
            loadMonth(_currentMonth.value)
        }
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            manager.deleteTransaction(id)
            loadMonth(_currentMonth.value)
        }
    }

    fun updateTransaction(tx: Transaction) {
        viewModelScope.launch {
            manager.updateTransaction(tx)
            loadMonth(_currentMonth.value)
        }
    }

    // ========== 预算操作 ==========

    fun setBudget(categoryName: String, amount: Double) {
        viewModelScope.launch {
            manager.setBudget(_currentMonth.value, categoryName, amount)
            loadMonth(_currentMonth.value)
        }
    }

    // ========== 导入操作 ==========

    fun importFromPanda(dbPath: String): Deferred<PandaImporter.ImportResult> {
        val deferred = viewModelScope.async {
            _importing.value = true
            _importResult.value = null
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
