package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.quota.QuotaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 聚合视图的 ViewModel 侧逻辑：状态推导 + 聚合模式下的刷新。
 *
 * 为什么从 [QuotaViewModel] 拆出来：那个文件已经 273 行，再塞聚合会越过 300 行硬线；
 * 而且这部分的输入只有 `repository` 一件事，独立成类后单家视图的代码一行都不用动。
 */
internal class QuotaAggregateDelegate(
    private val repository: QuotaRepository,
    private val scope: CoroutineScope,
) {

    /**
     * 一屏所需的聚合数据。
     *
     * 只订阅 `states`：provider 注册表是启动时装配好的静态集合，真正会变的是各家状态，
     * 所以每次重算时现读 `providers()` 即可，不需要再订阅一遍注册表。
     */
    val snapshot: StateFlow<QuotaAggregateSnapshot> = repository.states
        .map { states -> buildAggregateSnapshot(repository.providers(), states) }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            buildAggregateSnapshot(repository.providers(), repository.states.value),
        )

    /**
     * 聚合模式下的刷新：所有已配置的家一起拉。
     *
     * 走 `refreshAll()`（非 force）而不是逐个 force：一次刷新把 N 个接口同时打满会撞
     * opencode-go / Ollama 各自的限流；节流交给数据层，用户要强制单家时再走单家刷新。
     */
    fun refreshAll() {
        scope.launch(Dispatchers.IO) { repository.refreshAll() }
    }
}
