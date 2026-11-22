package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt；职责：s14a「记一笔」屏幕装配（表单状态 / 抽屉互斥 / 保存与删除）。
// 三张卡见 AddTransactionSections，表单行见 AddTransactionFormRows，按钮见 AddTransactionButtons，
// 顶栏与抽屉分发见 AddTransactionChrome，交易组装见 AddTransactionDraft，取值规则见 AddTransactionFormState。
// 锚点：screen-s14a / s14a-content / s14a-actions / s05-loading / s05-empty / s05-empty-import /
// s05-error / s05-retry；行与卡片锚点在 AddTransactionSections，抽屉锚点在 AddTransactionChrome 与 Record*Sheet。

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.time.LocalDate
import java.util.Locale

/**
 * s14a「记一笔」——一屏放得下的表单 + 全抽屉（定版原型 `.open-design/26-a-quan-chouti.html`）。
 *
 * 与旧 s05 的差别：一屏不放滚（分类走抽屉里的两级选择器）、账户不再裸露、时间能选到「昨天 10 点」。
 * 四个字段行（分类 / 账户 / 时间 / 说明）同构：标签 + 值 + ▸，抽屉开合不影响上方表单高度。
 * 「说明」行只存用户手写内容，自动采集的商户 / 商品说明 / 付款方式留在交易的独立字段里。
 *
 * @param editing 非空表示编辑已有交易：表单预填该交易，保存时保留 id/createdAt/source，
 *                并允许通过 [onDelete] 删除；为空表示新增。
 * @param categoryTree 一级/二级分类树：分类抽屉用它做两级同屏选择。
 *                     默认由 [categories] 现构（MainActivity 已一次性提供全量分类）；
 *                     显式传入时以树为准，[categories] 只用于空态判定。
 * @param fromMonth 浏览月（MainActivity 的 FinanceRoute.AddTransaction(fromMonth)）。
 *                  非空且 ≠ 当前系统月时，表单日期默认取该月最后一天，否则取今天——
 *                  否则在历史月里新增的账会落到本月，用户看着像「没保存」。
 * @param onImportClick 空态 CTA「去导入历史账目」的目标；未传时整颗按钮不渲染
 *                      （空态卡是「分类表为空」的唯一自救入口，没接线就不能伪造一个跳转）。
 */
@Composable
fun AddTransactionScreen(
    categories: List<Category>,
    accounts: List<Account>,
    onSave: (Transaction) -> Unit,
    onDismiss: () -> Unit,
    editing: Transaction? = null,
    onDelete: ((Transaction) -> Unit)? = null,
    fromMonth: String? = null,
    onImportClick: (() -> Unit)? = null,
    categoryTree: CategoryTree? = null,
) {
    val t = LocalDesignTokens.current
    val editingId = editing?.id
    val isEditing = editing != null
    val isRefundEntry = editing?.isRefundEntry == true
    val isTransfer = txTypeOfValue(editing) == RecordForm.TYPE_TRANSFER

    var txType by remember(editingId) { mutableStateOf(editing?.type ?: RecordForm.TYPE_EXPENSE) }
    var amountText by remember(editingId) {
        mutableStateOf(
            editing?.let { String.format(Locale.CHINA, "%.2f", kotlin.math.abs(it.amount)) } ?: ""
        )
    }
    var selectedCategory by remember(editingId) { mutableStateOf(editing?.categoryName ?: "") }
    var accountName by remember(editingId) { mutableStateOf(editing?.accountName ?: "") }
    var toAccountName by remember(editingId) { mutableStateOf(editing?.toAccountName ?: "") }
    var remark by remember(editingId) { mutableStateOf(editing?.remark ?: "") }
    val tag = remember(editingId) { editing?.tag ?: "" }
    var parts by remember(editingId) { mutableStateOf(initialTimeParts(editing, fromMonth)) }

    var openSheet by remember { mutableStateOf(RecordSheet.NONE) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 提交态：保存 / 删除后到路由返回之间（MainTabContent 的 FINANCE_BACK_DELAY_MS = 260ms）
    // 表单仍在组合、按钮仍可点 —— 没有这个闸门，双击就写两笔（锚点 s14a-save「连续点只写一次」）。
    // rememberSaveable 让旋转期间也不放行第二笔。
    var submitting by rememberSaveable(editingId) { mutableStateOf(false) }

    // 抽屉关闭后焦点回行（口径「移除/关闭后」列）：四个可开抽屉的行各一个落点
    val catFocus = remember { FocusRequester() }
    val acctFocus = remember { FocusRequester() }
    val timeFocus = remember { FocusRequester() }
    val receiptFocus = remember { FocusRequester() }
    // 删除确认框关闭后的回位落点（锚点 s05-del-dialog / s05-del-cancel · 移除后）
    val deleteFocus = remember { FocusRequester() }
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(pendingFocus, openSheet) {
        val target = pendingFocus
        if (target != null && openSheet == RecordSheet.NONE) {
            pendingFocus = null
            // 目标行可能刚随状态切换而不存在（例如错态未恢复）：请求失败不崩
            runCatching { target.requestFocus() }
        }
    }

    // 锚点 s05-del-dialog / s05-del-cancel · 移除后：确认框关闭后焦点回「删除这笔交易」按钮。
    // 该弹窗是本窗口内的浮层（Dialogs.kt → WindowOverlay.kt 的 if (!visible) return），没有系统级
    // 焦点回退，只能显式请求；等一帧让浮层先卸载，再请求按钮（节点一直在组合里，请求失败也不崩）。
    // ⚠️ 顺序固定「等帧 → 请求 → 复位 key」，复位绝不能挪到 withFrameNanos 之前 ——
    // 复位是快照写，下一帧重组会 forget 本协程；而续体走 AndroidUiDispatcher.dispatch（Handler.post），
    // 排在本帧回调之后 → 取消先落地 → requestFocus 变死代码（batch1 P1-A / batch2 N4 两轮同一个坑）。
    var restoreDeleteFocus by remember { mutableStateOf(false) }
    LaunchedEffect(restoreDeleteFocus) {
        if (!restoreDeleteFocus) return@LaunchedEffect
        restoreFocusAfterFrames(
            frames = 1,
            awaitFrame = { withFrameNanos { } },
            requestFocus = { runCatching { deleteFocus.requestFocus() }.isSuccess },
            reset = { restoreDeleteFocus = false },
        )
    }
    // 锚点 s05-retry · 移除后：重试成功后焦点回分类行。错态卡这一帧还在组合里，
    // 表单要等 loadFailed 复位后才重新挂载 —— 等几帧再请求，仍失败就放弃（不崩、不改状态）。
    // 同一条安全执行序（请求在复位之前，见 restoreFocusAfterFrames）。
    var restoreCatFocus by remember { mutableStateOf(false) }
    LaunchedEffect(restoreCatFocus) {
        if (!restoreCatFocus) return@LaunchedEffect
        restoreFocusAfterFrames(
            frames = CAT_FOCUS_RETRY_FRAMES,
            awaitFrame = { withFrameNanos { } },
            requestFocus = { runCatching { catFocus.requestFocus() }.isSuccess },
            reset = { restoreCatFocus = false },
        )
    }

    fun sheetRowFocus(sheet: RecordSheet): FocusRequester? = when (sheet) {
        RecordSheet.CATEGORY -> catFocus
        RecordSheet.ACCOUNT, RecordSheet.TO_ACCOUNT -> acctFocus
        RecordSheet.TIME -> timeFocus
        RecordSheet.RECEIPT -> receiptFocus
        RecordSheet.NONE -> null
    }

    fun closeSheet(sheet: RecordSheet) {
        pendingFocus = sheetRowFocus(sheet)
        openSheet = RecordSheet.NONE
    }

    val vm = rememberFinanceViewModel()
    val loading = vm?.loading?.collectAsState()?.value == true
    // 真实读取失败（与 s04 / s06 / s07 同一口径）：分类与账户都读不出来时不能再画成
    // 「还没有可用的分类」（那是空态，不是故障），否则用户没有任何重试入口。
    val loadFailed = vm?.loadFailed?.collectAsState()?.value == true
    val control = rememberFinanceStateControl()

    // 分类树：MainActivity 传全量分类（一级 + 二级），这里构造成树供两级选择器使用。
    // 类型校正要按「该类型下所有分类名（一级 + 二级）」判断，否则编辑一笔二级分类的交易
    // （例如「电商」）会被误判成不在当前类型里而被改成第一个分类。
    val localTree = remember(categories) { CategoryTree(categories) }
    val tree = categoryTree ?: localTree
    val typeCategoryNames = remember(tree, txType) {
        tree.topLevel(txType).flatMap { top ->
            listOf(top.name) + tree.childrenOf(top.name).map { it.name }
        }
    }
    val today = LocalDate.now()

    LaunchedEffect(typeCategoryNames, accounts, txType) {
        // 切换支出/收入后原选中项可能不属于当前类型（例如「餐饮」不属于收入）：必须校正，
        // 否则会出现「芯片全未选中却仍能保存支出分类的收入记录」
        if (txType != RecordForm.TYPE_TRANSFER) {
            selectedCategory = RecordForm.correctCategory(selectedCategory, typeCategoryNames)
        }
        if (accountName.isBlank()) accountName = accounts.firstOrNull()?.name ?: ""
        if (txType == RecordForm.TYPE_TRANSFER && toAccountName.isBlank()) {
            toAccountName = accounts.firstOrNull { it.name != accountName }?.name ?: ""
        }
    }

    // 保存校验拆成两档（判定口径在 RecordForm.saveCheck，本屏只决定「怎么反馈」）：
    // - 金额为空 / 为 0 → 可当场纠正的输入错误：按钮**保持可点**，点了抖金额卡 + toast，
    //   而不是把按钮变灰却不说原因（禁用态既点不动、读屏也问不出「还差什么」）。
    //   原型 s04-记账.html:735-749 就是这么做的（抖动 .is-bad + toast「请输入大于 0 的金额」）。
    // - 分类未选 / 转账账户不合法 → 结构性缺失：沿用禁用态（现状不变，原型也不允许存未分类）。
    // - 提交中 → 一律禁用（连点闸门，锚点 s14a-save）。
    // 「禁用态」与「点击反馈」不能共存：clickable(enabled=false) 不再收事件，想要「禁用还解释原因」
    // 只能再套一层点击区，读屏会念「不可用」却又能点，语义更乱 —— 所以按错误能否当场纠正来分流。
    val saveBlock = RecordForm.saveCheck(txType, amountText, selectedCategory, accountName, toAccountName)
    val canSave = saveBlock == null
    val canSubmit = RecordForm.saveButtonEnabled(saveBlock, submitting)
    // 金额校验反馈：每次失败 signal +1 → 金额卡抖一轮 + 一条 toast（原型 s04:738-741）
    var amountShakeSignal by remember { mutableStateOf(0) }
    val amountShake = rememberAmountShake(amountShakeSignal)
    val toast = rememberToastHostState()

    fun buildTransaction(): Transaction = AddTransactionDraft(
        txType = txType,
        amountText = amountText,
        selectedCategory = selectedCategory,
        accountName = accountName,
        toAccountName = toAccountName,
        remark = remark,
        tag = tag,
        parts = parts,
        editing = editing,
        isRefundEntry = isRefundEntry,
    ).build()

    val derivedState = addTransactionState(
        loading = loading,
        loadFailed = loadFailed,
        categoryCount = categories.size,
        accountCount = accounts.size,
    )
    val state = control.value ?: derivedState
    // EMPTY 只作为演示态：内容仍渲染，空态卡画在表单首位（s05-empty；CTA = s05-empty-import）
    val containerState = if (state == FiveState.EMPTY) FiveState.POPULATED else state
    // 错态下图内表单与保存都不可用：只留错态卡 + 唯一出口「重新加载」
    val showError = state == FiveState.ERROR

    // 根锚点：s14a 锚点 screen-s14a（页根容器 / 表单滚动区）
    Box(Modifier.fillMaxSize().pageBackdrop().testTag("screen-s14a")) {
        // imePadding：项目已 edge-to-edge（窗口不再自动 resize），键盘弹起时整页上抬，
        // 说明输入行与吸底「保存」都不被遮挡（锚点 screen-s14a 边界）；滚动区变矮但仍可滚到底。
        Column(Modifier.fillMaxSize().imePadding()) {
            // 顶栏：关闭 / 标题（s14a 顶栏不承载保存 —— 它吸在底部，永远可点）
            AddTransactionTopBar(
                isEditing = isEditing,
                onDismiss = onDismiss,
                onToggleStateBar = { control.toggleBar() },
            )

            when {
                state == FiveState.LOADING -> LoadingBlock(Modifier.weight(1f))
                showError -> ErrorBlock(onRetry = {
                    // 锚点 s05-retry：立即重查，**按真实结果**决定走向 ——
                    // 成功才清演示态 + 焦点回分类行；再次失败时 loadFailed 保持置位
                    // （错态卡原地保留），不弹成功提示、也不把用户留在出不去的错态里。
                    vm?.loadCurrentMonth { result ->
                        if (result.isSuccess) {
                            control.select(null)
                            restoreCatFocus = true
                        }
                    }
                }, modifier = Modifier.weight(1f))
                // 边界演示态（锚点 s05-edge）：只在 DEBUG 五态条切「边界」时出现 ——
                // 自动态的 addTransactionState 不产 EDGE，真实数据路径永远走不到这里。
                // 原型 s05:454 的保存条 data-state-hide 含 edge，所以这里同样不渲染表单与保存条。
                state == FiveState.EDGE -> AddTransactionEdgeBlock(Modifier.weight(1f))
                else -> Box(Modifier.weight(1f).fillMaxSize()) {
                    // 原型 .content{padding:8px 0 24px}：**没有**左右内边距 —— 左右 16 由每个 .pad 块自带。
                    // 这里同样不给：卡片级锚点 s14a-amount / -fields / -receipt 挂在 .pad 上（x=0、w=393），
                    // 给在容器上会把三个锚点整体缩进 16、宽少 32。
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .testTag("s14a-content"),
                    ) {
                        if (state == FiveState.EMPTY) {
                            // 空态卡自带左右 16（原型 .st-empty 的 padding:space-8 space-4），
                            // 容器既然不再统一给左右内边距，这里就不再加一层，否则内缩变 32。
                            EmptyState(
                                text = "分类数据为空，先到「设置 → 记账」里创建分类，或导入账单补齐",
                                modifier = Modifier.testTag("s05-empty"),
                                title = "还没有可用的分类",
                                icon = Icons.Filled.Description,
                                // 未接线（onImportClick = null）时整颗 CTA 不渲染：不能把「去导入」偷换成「关页面」
                                actionLabel = if (onImportClick != null) "去导入历史账目" else null,
                                onAction = onImportClick,
                                actionModifier = Modifier.testTag("s05-empty-import"),
                            )
                            Spacer(Modifier.height(t.space3))
                        } else {
                            // 原型 .content 的 padding-top 8（--space-2）+ 首块 .pad 的 mt-3 12 = 20
                            Spacer(Modifier.height(t.space2 + t.space3))
                        }

                        // ① 类型 + 金额（s14a-amount / s14a-kind / s14a-kind-expense|income|transfer）
                        AddTransactionAmountCard(
                            txType = txType,
                            amountText = amountText,
                            onTypeChange = { txType = it },
                            onAmountChange = { amountText = it },
                            isRefundEntry = isRefundEntry,
                            // 金额校验失败时本卡抖一轮（原型 .s05-input.is-bad）
                            shakeOffset = amountShake.value,
                        )

                        // ② 字段卡：分类 / 账户（转账为转出+转入）/ 时间 / 说明 —— 四行同构，与原型一致。
                        // 原型块间距 = .content 的 gap 12 + 每个 .pad 的 mt-3 12 = 24
                        Spacer(Modifier.height(t.space3 + t.space3))
                        AddTransactionFieldCard(
                            txType = txType,
                            selectedCategory = selectedCategory,
                            accountName = accountName,
                            toAccountName = toAccountName,
                            timeText = RecordForm.timeRowText(parts, today),
                            remark = remark,
                            onOpenSheet = { openSheet = it },
                            onRemarkChange = { remark = it },
                            catFocus = catFocus,
                            acctFocus = acctFocus,
                            timeFocus = timeFocus,
                        )

                        // ③ 单据卡：只有自动记账记下的交易才带单据，手动录入不摆空行
                        // 卡片不渲染时不占位：ReceiptCard 内部提前 return，这里的间距由卡内高度 0 抵消
                        if (RecordForm.hasReceipt(editing)) {
                            Spacer(Modifier.height(t.space3 + t.space3))
                        }
                        AddTransactionReceiptCard(
                            editing = editing,
                            onOpenSheet = { openSheet = it },
                            receiptFocus = receiptFocus,
                        )
                        // 给吸底保存条让位（原型 .s14a-actions 是 sticky，这里用等高的留白）
                        Spacer(Modifier.height(t.space12))
                    }

                    // ④ 保存吸底（s14a-actions；主按钮 = s14a-save）：表单怎么滚，它都在
                    AddTransactionActionBar(
                        // 可用态 = 结构性条件 && 未提交：金额无效时按钮仍可点，点了给抖动 + 提示
                        canSave = canSubmit,
                        showDelete = isEditing && onDelete != null,
                        // 保存闸门：金额无效 → 抖动 + toast 停在本页；条件满足且未提交才放行，
                        // 并当场置 submitting —— 260ms 返回窗口内的补点（含状态还没重组完的那次）一律挡掉
                        onSave = {
                            when (saveBlock) {
                                SaveBlock.AMOUNT -> {
                                    amountShakeSignal++
                                    toast.show(SAVE_AMOUNT_HINT)
                                }
                                else -> if (acceptSubmit(canSave, submitting)) {
                                    submitting = true
                                    onSave(buildTransaction())
                                }
                            }
                        },
                        onDeleteClick = { if (!submitting) showDeleteConfirm = true },
                        modifier = Modifier.align(Alignment.BottomCenter).testTag("s14a-actions"),
                        deleteFocus = deleteFocus,
                    )
                }
            }
        }

        // 四个抽屉（互斥；分类 / 账户选中即关，时间由「确定」关，单据由「关闭」关）
        AddTransactionSheets(
            openSheet = openSheet,
            tree = tree,
            accounts = accounts,
            txType = txType,
            editing = editing,
            selectedCategory = selectedCategory,
            accountName = accountName,
            toAccountName = toAccountName,
            parts = parts,
            onClose = { closeSheet(openSheet) },
            onPickCategory = {
                selectedCategory = it
                closeSheet(RecordSheet.CATEGORY)
            },
            onPickAccount = {
                accountName = it
                // 转出与转入撞车时自动改掉转入，避免「同一账户进出」这种无意义的转账
                if (toAccountName == it) {
                    toAccountName = accounts.firstOrNull { acc -> acc.name != it }?.name ?: ""
                }
                closeSheet(RecordSheet.ACCOUNT)
            },
            onPickToAccount = {
                toAccountName = it
                closeSheet(RecordSheet.TO_ACCOUNT)
            },
            // 时间抽屉「确定」：只有走到这里才把草稿回写表单；点遮罩 / 返回键不经过本回调（整份丢弃）
            onConfirmTime = {
                parts = it
                closeSheet(RecordSheet.TIME)
            },
        )

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )

        // 删除确认（s05-del-dialog / s05-del-cancel / s05-del-confirm）
        AddTransactionDeleteDialog(
            visible = showDeleteConfirm,
            editing = editing,
            // 删除与保存共用同一提交闸门：确认后立刻进入提交态，重复点确认也不会删两次
            onDelete = { tx ->
                if (!submitting) {
                    submitting = true
                    showDeleteConfirm = false
                    onDelete?.invoke(tx)
                }
            },
            // 取消 / 点遮罩 / 返回键都走这里：关框后把焦点送回「删除这笔交易」（表单不重置）
            onDismiss = {
                showDeleteConfirm = false
                restoreDeleteFocus = true
            },
        )

        // 校验反馈的 toast 宿主：贴底部浮起，但要抬到吸底保存条之上（锚点 toast-host 由 AppToast 自带）。
        // 保存条高 ≈ 内容 44(tapMin) + 上下 padding（space3 / space5），所以落点取 space12 + tapMin；
        // 只给 space12 时提示会压在「保存」按钮上，正好挡住用户要点的那个东西。
        // 本页不是 WindowOverlay 弹窗，页面级宿主就能看见；抽屉打开时不会有校验提示（按钮在抽屉下）。
        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = t.space12 + t.tapMin),
        )
    }
}

/**
 * 记一笔的五态推导（纯函数，可 JVM 单测）。
 *
 * 读库失败时：**无缓存**（分类与账户两表都空）才进错态卡 —— 此时若画成「还没有可用的分类」，
 * 用户看不到故障也拿不到唯一的自救入口「重新加载」。有缓存时照常出表单：缓存里的分类与账户
 * 能读能存，这时封锁表单才是真的丢数据（用户填的内容无处可存），与 s06/s07「有缓存时错误只贴
 * 一条、下方数值仍可用」同一取舍。
 */
internal fun addTransactionState(
    loading: Boolean,
    loadFailed: Boolean,
    categoryCount: Int,
    accountCount: Int,
): FiveState {
    val noCache = categoryCount == 0 && accountCount == 0
    return when {
        loading && noCache -> FiveState.LOADING
        loadFailed && noCache -> FiveState.ERROR
        noCache -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
}

/**
 * 提交守卫（纯函数，可 JVM 单测）：表单条件满足且不在提交中才放行一次提交。
 *
 * 放行后调用方必须**同步**把 submitting 置 true；保存成功要等 260ms 才返回列表
 * （MainTabContent 的 FINANCE_BACK_DELAY_MS），这个窗口里的每次补点都读同一个状态，
 * 因此第二次起一律 false —— 连点 N 次只写 1 笔（锚点 s14a-save）。
 */
internal fun acceptSubmit(canSubmit: Boolean, submitting: Boolean): Boolean = canSubmit && !submitting

/** 重试成功后「焦点回分类行」最多等几帧：表单随错态卡卸载后重挂，一帧未必已进焦点树。 */
private const val CAT_FOCUS_RETRY_FRAMES = 3

/**
 * 一次性焦点回位的安全执行序：**等帧 → 请求 → 复位 key**（挂起函数，可 JVM 单测）。
 *
 * 为什么顺序不能反：key 复位是快照写 → 下一次重组时 `LaunchedEffect(key)` 换成新实例，
 * 旧协程被 forget（`job.cancel()`）；而 `withFrameNanos` 的续体恢复走 `AndroidUiDispatcher.dispatch`
 * （`Handler.post`，排在本帧回调之后），同一帧里 Recomposer 又先 `sendFrame` 再重组 ——
 * 复位若在挂起点之前，续体恢复时 job 已被取消，`requestFocus()` 永不执行
 * （batch1 P1-A、batch2 N4 连续两轮同一个坑）。
 *
 * 复位放在请求之后，且**一定会执行**（即使一次都没请求成功）：否则同一个一次性信号再次置 true
 * 时值没变化，LaunchedEffect 不会重启，回位就永久失效。
 *
 * @param frames 最多等几帧重试（落点可能刚随状态切换重挂，一帧未必进焦点树）
 * @param awaitFrame 等一帧（生产实现 = `withFrameNanos { }`，单测传可控闸门）
 * @param requestFocus 请求焦点并返回是否成功（不得抛出）
 * @param reset 复位一次性信号（触发重组 → 取消本协程，**必须在请求之后**）
 * @return 是否请求成功
 */
internal suspend fun restoreFocusAfterFrames(
    frames: Int,
    awaitFrame: suspend () -> Unit,
    requestFocus: () -> Boolean,
    reset: () -> Unit,
): Boolean {
    var focused = false
    var waited = 0
    while (!focused && waited < frames) {
        waited++
        awaitFrame()
        focused = requestFocus()
    }
    reset()
    return focused
}

/** 加载骨架（锚点 s05-loading）：只有「加载中且分类与账户两表皆空」才出，已有数据时换月不闪骨架。 */
@Composable
private fun LoadingBlock(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    StateSkeleton(
        modifier = modifier
            .testTag("s05-loading")
            .padding(horizontal = t.space4, vertical = t.space2),
        rows = 4,
    )
}

/** 错态卡（锚点 s05-error / s05-retry）：标题、说明逐字照抄原型；唯一出口「重新加载」。 */
@Composable
private fun ErrorBlock(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    ErrorState(
        text = "无法读取本地分类表，暂时不能保存新的交易",
        modifier = modifier
            .testTag("s05-error")
            .padding(horizontal = t.space4),
        title = "分类与账户加载失败",
        onRetry = onRetry,
        actionLabel = "重新加载",
        actionModifier = Modifier.testTag("s05-retry"),
    )
}
