package com.bsfeng.monitor.core.di

import android.app.Application
import com.bsfeng.monitor.domain.quota.QuotaProviderRegistry
import com.bsfeng.monitor.quota.QuotaAlertNotifier
import com.bsfeng.monitor.quota.QuotaCredentialStore
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.quota.QuotaSnapshotStore
import com.bsfeng.monitor.quota.provider.DeepSeekQuotaProvider
import com.bsfeng.monitor.quota.provider.OllamaQuotaProvider
import com.bsfeng.monitor.quota.provider.OpenDesignQuotaProvider
import com.bsfeng.monitor.quota.provider.OpencodeGoQuotaProvider
import com.bsfeng.monitor.quota.provider.QuotaHttp
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.repository.SkipRuleRepositoryImpl
import com.bsfeng.monitor.domain.repository.SkipRuleRepository
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.record.RecordCollector
import com.bsfeng.monitor.record.RecordHandlerRegistry
import com.bsfeng.monitor.record.RecordStore
import com.bsfeng.monitor.record.UsageTracker
import com.bsfeng.monitor.skip.SkipRuleEngine
import com.bsfeng.monitor.skip.SkipRuleManager
import com.bsfeng.monitor.totp.TotpAccountDao
import com.bsfeng.monitor.totp.TotpDatabaseHelper
import com.bsfeng.monitor.totp.TotpRepository
import com.bsfeng.monitor.totp.TotpSecretStore
import com.bsfeng.monitor.transfer.service.TransferEngine
import com.bsfeng.monitor.transfer.service.TransferEngineHolder
import com.bsfeng.monitor.util.DebugConfig
import android.util.Log

/**
 * 手动 DI 容器。
 *
 * 所有依赖通过懒加载创建，按需初始化。
 */
class ServiceContainer(private val app: Application) {

    // ========== 基础设施 ==========

    val databaseHelper: DatabaseHelper by lazy { DatabaseHelper.getInstance(app) }

    // ========== Record 系统 ==========

    /** 只被 [recordCollector] 用来校验/派生记录，不是对外契约（外部要的是 collector）。 */
    private val recordHandlerRegistry: RecordHandlerRegistry by lazy {
        RecordHandlerRegistry.createDefault()
    }

    val recordStore: RecordStore by lazy { RecordStore(databaseHelper) }

    /**
     * UsageTracker 构造时传空 lambda，init 块中通过 setOnRecordCallback 设置实际回调。
     */
    val usageTracker: UsageTracker by lazy { UsageTracker { } }

    val recordCollector: RecordCollector by lazy {
        RecordCollector(recordStore, usageTracker, recordHandlerRegistry)
    }

    // ========== 跳过规则 ==========

    val skipRuleManager: SkipRuleManager by lazy { SkipRuleManager(app) }

    val skipRuleEngine: SkipRuleEngine by lazy { SkipRuleEngine(skipRuleManager) }

    // ========== 额度监控 ==========

    /** 凭据存储（Android Keystore AES-GCM）。 */
    val quotaCredentials: QuotaCredentialStore by lazy { QuotaCredentialStore(app) }

    /** provider 注册表 —— 新增 provider 只在这里加一行。 */
    /** 所有 provider 共用一条 HTTP 通道；日志走 logcat（QuotaHttp 里不直接依赖 android.util.Log）。 */
    private val quotaHttp: QuotaHttp by lazy {
        QuotaHttp(log = { message -> android.util.Log.i("QuotaHttp", message) })
    }

    val quotaRegistry: QuotaProviderRegistry by lazy {
        QuotaProviderRegistry(
            listOf(
                OllamaQuotaProvider(http = quotaHttp),
                DeepSeekQuotaProvider(http = quotaHttp),
                OpenDesignQuotaProvider(http = quotaHttp),
                OpencodeGoQuotaProvider(http = quotaHttp),
            ),
        )
    }

    /** 阈值提醒（通知渠道 + 已提醒状态持久化）。 */
    val quotaNotifier: QuotaAlertNotifier by lazy {
        QuotaAlertNotifier(app) { MainActivity.quotaTabIntent(app) }
    }

    /** 额度状态单一数据源：拉取 / 节流 / 退避 / 预警。 */
    val quotaRepository: QuotaRepository by lazy {
        QuotaRepository(
            registry = quotaRegistry,
            credentials = quotaCredentials,
            notifier = quotaNotifier,
            // 失败原因写 logcat（tag=Quota），排查「查询失败」时 adb logcat -s Quota 就能看到
            logger = { message -> android.util.Log.w("Quota", message) },
            cache = QuotaSnapshotStore(app),
        )
    }

    // ========== 三条主链路（记账 / 互传 / 验证器）==========

    /**
     * 记账：FinanceManager 自己持进程级单例（组件、无障碍浮窗、心跳都要取它），
     * 容器这里只是**统一取用点** —— 新代码从这里取，不再直接 FinanceManager.getInstance(...)。
     * 身份仍是那个单例：container.financeManager === FinanceManager.getInstance(app)。
     */
    val financeManager: FinanceManager by lazy { FinanceManager.getInstance(app) }

    /**
     * 互传引擎：HTTP 端口与 UDP 发现必须进程级唯一，单例由 TransferEngineHolder 持有；
     * 容器镜像同一实例，供只读状态的调用方（桌面组件）使用。
     *
     * 单例生命周期仍归 holder（resetForTesting 清的是它），容器不参与重建。
     */
    val transferEngine: TransferEngine by lazy { TransferEngineHolder.get(app) }

    /**
     * 验证器数据层：账户表 + Keystore 密钥存储。
     *
     * 装配只在这里发生，UI 走 TotpViewModelFactory（CreationExtras → 容器），
     * 不再在 ViewModel 里现拼 TotpRepository(TotpAccountDao(TotpDatabaseHelper.getInstance()))——
     * 那样每次新建都绕过容器，也说不清「一份数据源还是几份」。
     */
    val totpRepository: TotpRepository by lazy {
        TotpRepository(
            dao = TotpAccountDao(TotpDatabaseHelper.getInstance(app)),
            secrets = TotpSecretStore(app),
        )
    }

    // ========== Repository ==========

    val skipRuleRepository: SkipRuleRepository by lazy { SkipRuleRepositoryImpl(skipRuleManager) }

    /**
     * APP_USAGE 落库通道：回调在主线程触发（无障碍 1 秒轮询），写库必须挪到 IO。
     *
     * 口径见 [UsageRecordSink]：入队即返回、单并发保序、异常不外抛。
     * `UsageTracker` 本身仍保持同步回调语义（它的 9 个纯 JVM 用例同步断言 records）。
     */
    private val usageRecordSink: UsageRecordSink by lazy {
        UsageRecordSink(
            write = { record -> recordStore.insertOrMerge(record) },
            onError = { e -> Log.w("ServiceContainer", "APP_USAGE 落库失败（跳过本条，不影响采集）", e) },
        )
    }

    init {
        DebugConfig.init()
        // 解决 UsageTracker 与 RecordCollector 的循环依赖：
        // UsageTracker 生成 APP_USAGE 记录后，通过回调写入 RecordStore
        // 同 App 5 分钟内多次使用合并为一条（累加 duration）。
        usageTracker.setOnRecordCallback { record -> usageRecordSink.submit(record) }
    }
}

/**
 * 从任意 Context 获取 ServiceContainer 的扩展属性。
 */
val android.content.Context.serviceContainer: ServiceContainer
    get() = (applicationContext as com.bsfeng.monitor.MonitorApp).container
