package com.bsfeng.monitor.totp

import com.bsfeng.monitor.domain.totp.OtpauthMigration
import com.bsfeng.monitor.domain.totp.OtpauthParseResult
import com.bsfeng.monitor.domain.totp.OtpauthUri
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 验证器数据入口 —— 组合 [TotpAccountDao]（元数据 + 排序）与 [TotpSecretStore]（加密密钥）。
 *
 * 对外只给 domain 的 [TotpAccount]：密钥已解密拼好，UI / ViewModel 不需要知道存储细节。
 * 所有 IO 走 [io]（默认 Dispatchers.IO），调用方在主线程 await 即可。
 *
 * 错误口径：
 * - 参数不合法（位数 / 周期 / 算法 / 密钥格式）→ IllegalArgumentException，消息面向用户；
 * - 密钥解密失败 → 账户仍在列表里，secretBase32 为空串，[unreadableAccountIds] 给出 id，
 *   **不清数据**（唯一一份 2FA 密钥删了不可恢复，让用户自行删除或重装）；
 * - 加账户时密钥写失败 → 回滚刚插入的行，不留半个账户。
 */
class TotpRepository(
    private val dao: TotpAccountDao,
    private val secrets: TotpSecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 全部账户，按 sort_order 升序。 */
    suspend fun accounts(): List<TotpAccount> = withContext(io) {
        dao.listAll().map { row -> row.toAccount(secrets.load(row.id).orEmpty()) }
    }

    /** 密钥不可读（Keystore 重置 / 数据损坏）的账户 id，供 UI 出「密钥不可读」提示。 */
    suspend fun unreadableAccountIds(): List<Long> = withContext(io) {
        dao.listAll().filter { secrets.load(it.id) == null }.map { it.id }
    }

    /**
     * 新增账户，返回入库 id。
     *
     * [TotpAccount.sortOrder] 为 0（默认）时追加到末尾。
     */
    suspend fun add(account: TotpAccount): Long = withContext(io) {
        requireValid(account)
        val now = clock()
        val sortOrder = if (account.sortOrder > 0) account.sortOrder else dao.nextSortOrder()
        val id = dao.insert(TotpEntity.toRow(account, sortOrder = sortOrder, now = now))
        try {
            secrets.save(id, account.secretBase32)
        } catch (e: Exception) {
            // 密钥写失败 → 删掉刚插的行，不留「有账户没密钥」的僵尸
            dao.delete(id)
            throw e
        }
        id
    }

    /** 更新账户（id 必须已入库；密钥变化时重写加密存储）。 */
    suspend fun update(account: TotpAccount) = withContext(io) {
        require(account.id > 0L) { "更新账户缺少 id" }
        requireValid(account)
        val existing = dao.getById(account.id)
            ?: throw NoSuchElementException("账户不存在：" + account.id)
        val row = TotpEntity.toRow(
            account = account,
            sortOrder = account.sortOrder,
            now = clock(),
            createdAt = existing.createdAt,
        )
        if (dao.update(row) == 0) throw NoSuchElementException("账户不存在：" + account.id)
        secrets.save(account.id, account.secretBase32)
    }

    /** 删除账户及其密钥（先删行，再删密钥：行删失败时密钥还在，不会留下解不开的孤儿行）。 */
    suspend fun delete(id: Long) = withContext(io) {
        dao.delete(id)
        secrets.delete(id)
    }

    /** 按给定 id 顺序重排（下标即新 sort_order）。 */
    suspend fun reorder(orderedIds: List<Long>) = withContext(io) {
        dao.reorder(orderedIds)
    }

    /**
     * HOTP 账户前进一格计数器，返回**前进后**的值（UI 拿它算下一码）。
     *
     * 计数只增不减：每验证一次就必须前进一格，否则同一个码可被重复使用。
     * 行不存在抛 [NoSuchElementException]（账户已被删除）。
     */
    suspend fun advanceHotpCounter(id: Long): Long = withContext(io) {
        val row = dao.getById(id) ?: throw NoSuchElementException("账户不存在：" + id)
        val next = if (row.counter < 0L) 1L else row.counter + 1L
        val updated = dao.update(row.copy(counter = next, updatedAt = clock()))
        if (updated == 0) throw NoSuchElementException("账户不存在：" + id)
        next
    }

    /**
     * 扫码 / 粘贴 / 手动输入的统一入口：先走 domain 校验，通过后才落库。
     *
     * 返回 [OtpauthParseResult]：Success 表示已入库，Failure 的 reason 原样交给 UI 翻译。
     * **只处理单条 otpauth URI**；Google 迁移二维码（otpauth-migration://）走 [addBatchFromUri]。
     */
    suspend fun addFromUri(uri: String): OtpauthParseResult {
        val raw = uri.trim()
        // 迁移二维码一图多账户：走批量入口把**全部**账户都落库，再返回第一条给调用方做提示，
        // 不让 OtpauthUri.parse 的「只取第一条」把其余账户静默丢掉。
        if (raw.startsWith(MIGRATION_SCHEME, ignoreCase = true)) return addMigrationFromUri(raw)
        return when (val parsed = OtpauthUri.parse(raw)) {
            is OtpauthParseResult.Success -> {
                add(parsed.account)
                parsed
            }
            is OtpauthParseResult.Failure -> parsed
        }
    }

    /** 迁移链接收敛成单条结果：账户已全部入库，返回第一条已入库账户（无成功项则给整体失败原因）。 */
    private suspend fun addMigrationFromUri(uri: String): OtpauthParseResult {
        val batch = addBatchFromUri(uri)
        val first = batch.entries.firstOrNull()
            ?: return OtpauthParseResult.Failure(OtpauthUri.REASON_MIGRATION_INVALID)
        return OtpauthParseResult.Success(
            TotpAccount(
                issuer = first.issuer,
                accountName = first.account,
                secretBase32 = first.secret,
                params = TotpParams(
                    algorithm = first.algorithm,
                    digits = first.digits,
                    periodSec = first.period,
                    type = first.type,
                ),
                counter = first.counter,
            ),
        )
    }

    /**
     * 批量入口：Google 迁移二维码（otpauth-migration://）一图多账户，逐条入库。
     *
     * 口径与 [importEntries] 一致 —— 单条失败只进 skipped（中文原因），不中断整批；
     * 传进来的若是普通 otpauth:// 链接，就退化成一条的批（成功 1 条或 skipped 1 条），
     * 好让扫码页不必按 scheme 分叉。
     */
    suspend fun addBatchFromUri(uri: String): TotpImportResult {
        val raw = uri.trim()
        if (!raw.startsWith(MIGRATION_SCHEME, ignoreCase = true)) return singleFromUri(raw)
        val parsed = runCatching { OtpauthMigration.parse(raw) }.getOrElse { error ->
            return TotpImportResult(emptyList(), listOf(error.message ?: "迁移二维码解析失败"))
        }
        val accepted = mutableListOf<TotpBackupEntry>()
        val skipped = parsed.skipped.toMutableList()
        parsed.accounts.forEach { account ->
            val entry = account.toBackupEntry()
            runCatching { add(account) }.fold(
                onSuccess = { accepted += entry },
                onFailure = { error ->
                    skipped += labelOf(account) + "：" + (error.message ?: "导入失败")
                },
            )
        }
        return TotpImportResult(accepted, skipped)
    }

    /** 普通 otpauth:// 链接包成一条的批结果。 */
    private suspend fun singleFromUri(uri: String): TotpImportResult = when (val parsed = OtpauthUri.parse(uri)) {
        is OtpauthParseResult.Success -> {
            val entry = parsed.account.toBackupEntry()
            runCatching { add(parsed.account) }.fold(
                onSuccess = { TotpImportResult(listOf(entry), emptyList()) },
                onFailure = { error ->
                    TotpImportResult(emptyList(), listOf(labelOf(parsed.account) + "：" + (error.message ?: "导入失败")))
                },
            )
        }
        is OtpauthParseResult.Failure ->
            TotpImportResult(emptyList(), listOf(OtpauthUri.reasonMessage(parsed.reason)))
    }

    /** 导出用条目（只含密钥可读的账户）。 */
    suspend fun backupEntries(): List<TotpBackupEntry> = withContext(io) {
        dao.listAll().mapNotNull { row ->
            val secret = secrets.load(row.id) ?: return@mapNotNull null
            TotpBackupEntry(
                issuer = row.issuer,
                account = row.accountName,
                secret = secret,
                algorithm = row.algorithm,
                digits = row.digits,
                period = row.periodSec,
                type = row.otpType,
                counter = row.counter,
            )
        }
    }

    /**
     * 批量导入（备份 / Aegis / 2FAS 解析出的条目）。
     *
     * 逐条走 [add] 的同一套校验，失败的进 skipped 并写明原因，不中断整批。
     */
    suspend fun importEntries(entries: List<TotpBackupEntry>): TotpImportResult {
        val accepted = mutableListOf<TotpBackupEntry>()
        val skipped = mutableListOf<String>()
        entries.forEach { entry ->
            val account = TotpAccount(
                issuer = entry.issuer,
                accountName = entry.account,
                secretBase32 = entry.secret,
                params = TotpParams(
                    algorithm = entry.algorithm,
                    digits = entry.digits,
                    periodSec = entry.period,
                    type = entry.type,
                ),
                counter = entry.counter,
            )
            // 走一遍 build → parse 的往返再把结果落库：secret 规范化、Steam 变体推断、
            // HOTP 计数器都只由 domain 决定，备份条目本身没带 variant，不能让 Steam 导入后退化成数字码。
            val normalized = when (val parsed = OtpauthUri.parse(OtpauthUri.build(account))) {
                is OtpauthParseResult.Success -> parsed.account
                is OtpauthParseResult.Failure -> {
                    skipped += entry.issuer + " / " + entry.account + "：" +
                        OtpauthUri.reasonMessage(parsed.reason)
                    return@forEach
                }
            }
            runCatching { add(normalized) }.fold(
                onSuccess = { accepted += entry },
                onFailure = { error ->
                    skipped += entry.issuer + " / " + entry.account + "：" +
                        (error.message ?: "导入失败")
                },
            )
        }
        return TotpImportResult(accepted, skipped)
    }

    /**
     * 账户参数校验 —— 唯一口径是 domain 的 OtpauthUri：build → parse 往返，
     * 不接受就是不接受（不在数据层另写一套位数/周期白名单）。
     */
    private fun requireValid(account: TotpAccount) {
        require(account.accountName.isNotBlank()) { "账户名不能为空" }
        require(account.secretBase32.isNotBlank()) { "密钥不能为空" }
        when (val parsed = OtpauthUri.parse(OtpauthUri.build(account))) {
            is OtpauthParseResult.Success -> Unit
            is OtpauthParseResult.Failure ->
                throw IllegalArgumentException(
                    "账户参数不被支持：" + parsed.reason + "（" + OtpauthUri.reasonMessage(parsed.reason) + "）",
                )
        }
    }

    private fun labelOf(account: TotpAccount): String =
        account.issuer.ifBlank { account.accountName } + " / " + account.accountName

    private companion object {
        const val MIGRATION_SCHEME = "otpauth-migration://"

        /** domain 账户 → 备份线格式（导入结果与导出共用同一口径）。 */
        fun TotpAccount.toBackupEntry(): TotpBackupEntry = TotpBackupEntry(
            issuer = issuer,
            account = accountName,
            secret = secretBase32,
            algorithm = params.algorithm,
            digits = params.digits,
            period = params.periodSec,
            type = params.type,
            counter = counter,
        )
    }
}
