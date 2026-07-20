package com.bsfeng.monitor.finance

import android.content.ContentValues
import android.database.Cursor
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction

/**
 * 交易实体 — 数据库行与 Transaction 数据类之间的转换层。
 *
 * 遵循项目规范：不使用 Room，通过 ContentValues / Cursor 手动映射。
 */
object TransactionEntity {
    // 表名
    const val TABLE_NAME = "finance_transactions"

    // 字段名
    const val COL_ID = "id"
    const val COL_TYPE = "type"
    const val COL_CATEGORY_NAME = "category_name"
    const val COL_AMOUNT = "amount"
    const val COL_ACCOUNT_NAME = "account_name"
    const val COL_REMARK = "remark"
    const val COL_TAG = "tag"
    const val COL_DATE = "date"
    const val COL_DATE_TIME = "date_time"
    const val COL_CREATED_AT = "created_at"
    const val COL_UPDATED_AT = "updated_at"
    const val COL_SOURCE = "source"
    const val COL_SOURCE_ID = "source_id"

    /** 建表 SQL */
    const val CREATE_TABLE = """
        CREATE TABLE $TABLE_NAME (
            $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COL_TYPE INTEGER NOT NULL DEFAULT 0,
            $COL_CATEGORY_NAME TEXT NOT NULL DEFAULT '',
            $COL_AMOUNT REAL NOT NULL DEFAULT 0.0,
            $COL_ACCOUNT_NAME TEXT NOT NULL DEFAULT '',
            $COL_REMARK TEXT NOT NULL DEFAULT '',
            $COL_TAG TEXT NOT NULL DEFAULT '',
            $COL_DATE TEXT NOT NULL DEFAULT '',
            $COL_DATE_TIME TEXT NOT NULL DEFAULT '',
            $COL_CREATED_AT INTEGER NOT NULL,
            $COL_UPDATED_AT INTEGER NOT NULL,
            $COL_SOURCE TEXT NOT NULL DEFAULT 'manual',
            $COL_SOURCE_ID TEXT NOT NULL DEFAULT ''
        )
    """

    /** Transaction → ContentValues */
    fun toContentValues(tx: Transaction): ContentValues = ContentValues().apply {
        put(COL_TYPE, tx.type)
        put(COL_CATEGORY_NAME, tx.categoryName)
        put(COL_AMOUNT, tx.amount)
        put(COL_ACCOUNT_NAME, tx.accountName)
        put(COL_REMARK, tx.remark)
        put(COL_TAG, tx.tag)
        put(COL_DATE, tx.date)
        put(COL_DATE_TIME, tx.dateTime)
        put(COL_CREATED_AT, tx.createdAt)
        put(COL_UPDATED_AT, tx.updatedAt)
        put(COL_SOURCE, tx.source)
        put(COL_SOURCE_ID, tx.sourceId)
    }

    /** Cursor → Transaction */
    fun fromCursor(cursor: Cursor): Transaction = Transaction(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
        type = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TYPE)),
        categoryName = cursor.getString(cursor.getColumnIndexOrThrow(COL_CATEGORY_NAME)),
        amount = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_AMOUNT)),
        accountName = cursor.getString(cursor.getColumnIndexOrThrow(COL_ACCOUNT_NAME)),
        remark = cursor.getString(cursor.getColumnIndexOrThrow(COL_REMARK)),
        tag = cursor.getString(cursor.getColumnIndexOrThrow(COL_TAG)),
        date = cursor.getString(cursor.getColumnIndexOrThrow(COL_DATE)),
        dateTime = cursor.getString(cursor.getColumnIndexOrThrow(COL_DATE_TIME)),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
        source = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE)),
        sourceId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE_ID))
    )

    /** Cursor → List<Transaction> */
    fun listFromCursor(cursor: Cursor): List<Transaction> {
        val list = mutableListOf<Transaction>()
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(fromCursor(c))
            }
        }
        return list
    }
}

/**
 * 分类实体。
 */
object CategoryEntity {
    const val TABLE_NAME = "finance_categories"

    const val COL_ID = "id"
    const val COL_NAME = "name"
    const val COL_TYPE = "type"
    const val COL_ICON_NAME = "icon_name"
    const val COL_COLOR = "color"
    const val COL_PARENT_ID = "parent_id"
    const val COL_SORT_ORDER = "sort_order"

    const val CREATE_TABLE = """
        CREATE TABLE $TABLE_NAME (
            $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COL_NAME TEXT NOT NULL UNIQUE,
            $COL_TYPE INTEGER NOT NULL DEFAULT 0,
            $COL_ICON_NAME TEXT NOT NULL DEFAULT '',
            $COL_COLOR TEXT NOT NULL DEFAULT '',
            $COL_PARENT_ID INTEGER NOT NULL DEFAULT -1,
            $COL_SORT_ORDER INTEGER NOT NULL DEFAULT 0
        )
    """

    fun toContentValues(cat: Category): ContentValues = ContentValues().apply {
        put(COL_NAME, cat.name)
        put(COL_TYPE, cat.type)
        put(COL_ICON_NAME, cat.iconName)
        put(COL_COLOR, cat.color)
        put(COL_PARENT_ID, cat.parentId)
        put(COL_SORT_ORDER, cat.sortOrder)
    }

    fun fromCursor(cursor: Cursor): Category = Category(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
        name = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)),
        type = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TYPE)),
        iconName = cursor.getString(cursor.getColumnIndexOrThrow(COL_ICON_NAME)),
        color = cursor.getString(cursor.getColumnIndexOrThrow(COL_COLOR)),
        parentId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_PARENT_ID)),
        sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SORT_ORDER))
    )

    fun listFromCursor(cursor: Cursor): List<Category> {
        val list = mutableListOf<Category>()
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(fromCursor(c))
            }
        }
        return list
    }
}

/**
 * 账户实体。
 */
object AccountEntity {
    const val TABLE_NAME = "finance_accounts"

    const val COL_ID = "id"
    const val COL_NAME = "name"
    const val COL_TYPE = "type"
    const val COL_BALANCE = "balance"
    const val COL_SORT_ORDER = "sort_order"

    const val CREATE_TABLE = """
        CREATE TABLE $TABLE_NAME (
            $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COL_NAME TEXT NOT NULL UNIQUE,
            $COL_TYPE INTEGER NOT NULL DEFAULT 2,
            $COL_BALANCE REAL NOT NULL DEFAULT 0.0,
            $COL_SORT_ORDER INTEGER NOT NULL DEFAULT 0
        )
    """

    fun toContentValues(acc: Account): ContentValues = ContentValues().apply {
        put(COL_NAME, acc.name)
        put(COL_TYPE, acc.type)
        put(COL_BALANCE, acc.balance)
        put(COL_SORT_ORDER, acc.sortOrder)
    }

    fun fromCursor(cursor: Cursor): Account = Account(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
        name = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)),
        type = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TYPE)),
        balance = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_BALANCE)),
        sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SORT_ORDER))
    )

    fun listFromCursor(cursor: Cursor): List<Account> {
        val list = mutableListOf<Account>()
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(fromCursor(c))
            }
        }
        return list
    }
}

/**
 * 预算实体。
 */
object BudgetEntity {
    const val TABLE_NAME = "finance_budgets"

    const val COL_ID = "id"
    const val COL_MONTH = "month"
    const val COL_CATEGORY_NAME = "category_name"
    const val COL_BUDGET_AMOUNT = "budget_amount"
    const val COL_SPENT_AMOUNT = "spent_amount"

    const val CREATE_TABLE = """
        CREATE TABLE $TABLE_NAME (
            $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COL_MONTH TEXT NOT NULL,
            $COL_CATEGORY_NAME TEXT NOT NULL DEFAULT '',
            $COL_BUDGET_AMOUNT REAL NOT NULL DEFAULT 0.0,
            $COL_SPENT_AMOUNT REAL NOT NULL DEFAULT 0.0,
            UNIQUE($COL_MONTH, $COL_CATEGORY_NAME)
        )
    """

    fun toContentValues(budget: Budget): ContentValues = ContentValues().apply {
        put(COL_MONTH, budget.month)
        put(COL_CATEGORY_NAME, budget.categoryName)
        put(COL_BUDGET_AMOUNT, budget.budgetAmount)
        put(COL_SPENT_AMOUNT, budget.spentAmount)
    }

    fun fromCursor(cursor: Cursor): Budget = Budget(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
        month = cursor.getString(cursor.getColumnIndexOrThrow(COL_MONTH)),
        categoryName = cursor.getString(cursor.getColumnIndexOrThrow(COL_CATEGORY_NAME)),
        budgetAmount = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_BUDGET_AMOUNT)),
        spentAmount = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_SPENT_AMOUNT))
    )

    fun listFromCursor(cursor: Cursor): List<Budget> {
        val list = mutableListOf<Budget>()
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(fromCursor(c))
            }
        }
        return list
    }
}

/**
 * 标签实体。
 */
object TagEntity {
    const val TABLE_NAME = "finance_tags"

    const val COL_ID = "id"
    const val COL_NAME = "name"
    const val COL_TYPE = "type"

    const val CREATE_TABLE = """
        CREATE TABLE $TABLE_NAME (
            $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COL_NAME TEXT NOT NULL UNIQUE,
            $COL_TYPE INTEGER NOT NULL DEFAULT 0
        )
    """
}
