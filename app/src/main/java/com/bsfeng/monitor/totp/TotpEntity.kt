package com.bsfeng.monitor.totp

import android.content.ContentValues
import android.database.Cursor
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpParams

/**
 * 一行验证器账户 —— 持久化形态，**不含密钥**（密钥在 TotpSecretStore，按 id 关联）。
 *
 * 与 domain 的 TotpAccount 分开是因为后者带 secretBase32 且没有时间戳：
 * 密钥单独加密存、时间戳属存储层职责，硬塞进领域模型会让两边都别扭。
 */
data class TotpAccountRow(
    val id: Long = 0L,
    val issuer: String,
    val accountName: String,
    val algorithm: String = TotpParams.ALGORITHM_SHA1,
    val digits: Int = TotpParams.DEFAULT_DIGITS,
    val periodSec: Int = TotpParams.DEFAULT_PERIOD_SEC,
    val sortOrder: Int = 0,
    val iconKey: String = "",
    val variant: String = TotpAccount.VARIANT_STANDARD,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {

    /** 拼上已解密的密钥，得到 domain 模型。 */
    fun toAccount(secretBase32: String): TotpAccount = TotpAccount(
        id = id,
        issuer = issuer,
        accountName = accountName,
        secretBase32 = secretBase32,
        params = TotpParams(algorithm = algorithm, digits = digits, periodSec = periodSec),
        sortOrder = sortOrder,
        iconKey = iconKey,
        variant = variant,
    )
}

/**
 * TOTP 账户表 —— 独立于 monitor.db / finance.db 的 totp.db。
 *
 * 遵循项目规范：SQLiteOpenHelper + ContentValues / Cursor 手动映射，不用 Room。
 */
object TotpEntity {
    const val TABLE_NAME = "totp_accounts"

    const val COL_ID = "id"
    const val COL_ISSUER = "issuer"
    const val COL_ACCOUNT_NAME = "account_name"
    const val COL_ALGORITHM = "algorithm"
    const val COL_DIGITS = "digits"
    const val COL_PERIOD_SEC = "period_sec"
    const val COL_SORT_ORDER = "sort_order"
    const val COL_ICON_KEY = "icon_key"
    const val COL_VARIANT = "variant"
    const val COL_CREATED_AT = "created_at"
    const val COL_UPDATED_AT = "updated_at"

    /** 建表 SQL。带 IF NOT EXISTS：迁移路径复用时天然幂等。 */
    const val CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS $TABLE_NAME (
            $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COL_ISSUER TEXT NOT NULL DEFAULT '',
            $COL_ACCOUNT_NAME TEXT NOT NULL DEFAULT '',
            $COL_ALGORITHM TEXT NOT NULL DEFAULT 'SHA1',
            $COL_DIGITS INTEGER NOT NULL DEFAULT 6,
            $COL_PERIOD_SEC INTEGER NOT NULL DEFAULT 30,
            $COL_SORT_ORDER INTEGER NOT NULL DEFAULT 0,
            $COL_ICON_KEY TEXT NOT NULL DEFAULT '',
            $COL_VARIANT TEXT NOT NULL DEFAULT 'STANDARD',
            $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
            $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
        )
    """

    /** 列表按 sort_order 全表扫，建索引。 */
    const val CREATE_INDEX = """
        CREATE INDEX IF NOT EXISTS idx_totp_accounts_sort ON $TABLE_NAME($COL_SORT_ORDER)
    """

    /** Row → ContentValues（id 不写：插入用自增，更新靠 WHERE id）。 */
    fun toContentValues(row: TotpAccountRow): ContentValues = ContentValues().apply {
        put(COL_ISSUER, row.issuer)
        put(COL_ACCOUNT_NAME, row.accountName)
        put(COL_ALGORITHM, row.algorithm)
        put(COL_DIGITS, row.digits)
        put(COL_PERIOD_SEC, row.periodSec)
        put(COL_SORT_ORDER, row.sortOrder)
        put(COL_ICON_KEY, row.iconKey)
        put(COL_VARIANT, row.variant)
        put(COL_CREATED_AT, row.createdAt)
        put(COL_UPDATED_AT, row.updatedAt)
    }

    /** Cursor 当前行 → Row（按列名取值，旧版多出来的列自然忽略）。 */
    fun fromCursor(cursor: Cursor): TotpAccountRow = TotpAccountRow(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
        issuer = cursor.getString(cursor.getColumnIndexOrThrow(COL_ISSUER)),
        accountName = cursor.getString(cursor.getColumnIndexOrThrow(COL_ACCOUNT_NAME)),
        algorithm = cursor.getString(cursor.getColumnIndexOrThrow(COL_ALGORITHM)),
        digits = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DIGITS)),
        periodSec = cursor.getInt(cursor.getColumnIndexOrThrow(COL_PERIOD_SEC)),
        sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SORT_ORDER)),
        iconKey = cursor.getString(cursor.getColumnIndexOrThrow(COL_ICON_KEY)),
        variant = cursor.getString(cursor.getColumnIndexOrThrow(COL_VARIANT)),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
    )

    /** Cursor → List<Row>（关游标）。 */
    fun listFromCursor(cursor: Cursor): List<TotpAccountRow> {
        val list = mutableListOf<TotpAccountRow>()
        cursor.use { c ->
            while (c.moveToNext()) list.add(fromCursor(c))
        }
        return list
    }

    /** domain 账户 + 时间戳 → Row。 */
    fun toRow(account: TotpAccount, sortOrder: Int, now: Long, createdAt: Long = now): TotpAccountRow =
        TotpAccountRow(
            id = account.id,
            issuer = account.issuer,
            accountName = account.accountName,
            algorithm = account.params.algorithm,
            digits = account.params.digits,
            periodSec = account.params.periodSec,
            sortOrder = sortOrder,
            iconKey = account.iconKey,
            variant = account.variant,
            createdAt = createdAt,
            updatedAt = now,
        )
}
