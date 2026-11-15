package com.bsfeng.monitor.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * 返回键 / 返回手势的唯一分发入口。
 *
 * 为什么要它：每个覆盖层、每个子页各自注册 `BackHandler` 时，「谁先收到返回」由**组合顺序**
 * 决定，而组合顺序会随重组、页面进出而变 —— 结果是规则管理页明明在最上层，返回键却关掉了
 * 它下面的设置子页（现象：画面停在原页，再按一次连退两层）。这里改成显式登记 + 显式排序，
 * 一次返回只弹一层，且弹的永远是用户眼里最上面那层。
 *
 * 排序规则：先比 [BackLayer.priority]（页面类型决定层级），同层再比打开序号（后开的先退）。
 *
 * 只在 [com.bsfeng.monitor.MainContent] 装配一次；未装配时（预览、单独测试）各处退回各自的
 * `BackHandler` 兜底，行为与改造前一致。
 */
@Stable
class BackRouter {

    private data class Entry(
        val key: String,
        val layer: BackLayer,
        val order: Long,
        val onBack: () -> Unit,
    )

    private val entries = mutableStateListOf<Entry>()
    private var nextOrder = 0L

    /** 栈是否非空：外壳据此决定要不要接管返回键（可以返回时才接管，不能返回时交还系统）。 */
    var canGoBack: Boolean by mutableStateOf(false)
        private set

    /**
     * 登记一层可返回的界面。
     *
     * @param key 稳定标识（同一屏多次登记会替换而不是叠加）
     * @return 注销函数，调用方放进 `DisposableEffect` 的 onDispose
     */
    fun register(key: String, layer: BackLayer, onBack: () -> Unit): () -> Unit {
        val entry = Entry(key, layer, nextOrder++, onBack)
        entries.removeAll { it.key == key }
        entries += entry
        refresh()
        return {
            entries.removeAll { it.key == key }
            refresh()
        }
    }

    /**
     * 退一层；返回是否真的消费了这次返回。
     *
     * 只调用栈顶那层的回调，**不主动移除登记**：登记由各层自己随可见性注销（`DisposableEffect`）。
     * 这样「同一容器内的多层页面」（设置子页下钻、记账子路由、验证器内部路由）不必重复登记，
     * 每次返回拿到的最新回调自然只退一层 —— 主动移除会让容器留着但栈里已空，下一次返回越层。
     */
    fun pop(): Boolean {
        val top = entries.maxWithOrNull(compareBy({ it.layer.priority }, { it.order })) ?: return false
        top.onBack()
        return true
    }

    private fun refresh() {
        canGoBack = entries.isNotEmpty()
    }
}

/**
 * 返回层级：数字越大越靠上，返回键先退大的。
 *
 * 取值口径 —— 越"临时 / 越靠用户当前注意力"的层越大：
 * - [PAGE_TAB]：Tab 内的二级视图（记账「统计汇总」子 Tab 这类，不算真页面但用户预期先退）
 * - [PAGE_ROUTE]：Tab 内部的页面路由（记账表单 / 统计 / 预算 / 导入）
 * - [PAGE_SUB]：设置页二级 / 三级子页
 * - [OVERLAY]：与 Tab 同级、铺满全屏的覆盖层（规则 / 白名单 / 额度设置 / 验证器 / 行程 / 外观）
 * - [INNER]：覆盖层或页面**内部**的临时屏（验证器详情 / 扫码这类同一覆盖层里的子路由）
 *
 * 同一层内的先后由打开顺序决定：后打开的在上。
 */
enum class BackLayer(val priority: Int) {
    PAGE_TAB(10),
    PAGE_ROUTE(20),
    PAGE_SUB(30),
    OVERLAY(40),
    INNER(50),
}

/** 装配点注入的返回分发器；未装配时为 null（各处走 BackHandler 兜底）。 */
val LocalBackRouter = compositionLocalOf<BackRouter?> { null }

/**
 * 把一层界面登记进返回栈。
 *
 * [enabled] 为 false 或不在 [BackRouter] 里时什么都不做（调用方仍可用 `BackHandler` 兜底）。
 */
@Composable
fun BackLayerRegistration(
    key: String,
    layer: BackLayer,
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    val router = LocalBackRouter.current
    // 回调随重组更新：容器留在栈里、内部页面下钻时，注册进去的必须是**最新**的返回动作，
    // 否则第二次返回会重放第一次的闭包（页面上看起来像「没退」或「一次退两层」）。
    val currentOnBack = rememberUpdatedState(onBack)
    DisposableEffect(router, key, layer, enabled) {
        if (router == null || !enabled) {
            onDispose { }
        } else {
            val dispose = router.register(key, layer) { currentOnBack.value() }
            onDispose { dispose() }
        }
    }
}
