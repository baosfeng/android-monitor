package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceDao.kt 的 Category 分区；职责：分类表读写、按类型/父子关系查询与分类名迁移更新。

import androidx.core.database.sqlite.transaction
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import android.content.ContentValues
import com.bsfeng.monitor.finance.model.Category

/** 分类与预算分类名数据访问（由 [FinanceDao] 持有并转发） */
internal class FinanceCategoryDao(private val dbHelper: FinanceDatabaseHelper) {

    /**
     * 批量插入分类（去重，事务）。
     *
     * @return 实际新增的分类数量；已存在的同名分类（UNIQUE(name) 冲突）不计入
     */
    fun insertCategories(categories: List<Category>): Int {
        if (categories.isEmpty()) return 0
        val db = dbHelper.writableDatabase
        var inserted = 0
        db.transaction {
            for (cat in categories) {
                try {
                    insertOrThrow(CategoryEntity.TABLE_NAME, null, CategoryEntity.toContentValues(cat))
                    inserted++
                } catch (_: SQLiteConstraintException) {
                    // UNIQUE 冲突忽略
                }
            }
        }
        return inserted
    }

    /** 获取所有分类 */
    fun getAllCategories(): List<Category> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            CategoryEntity.TABLE_NAME, null, null, null, null, null,
            "${CategoryEntity.COL_TYPE} ASC, ${CategoryEntity.COL_SORT_ORDER} ASC"
        )
        return CategoryEntity.listFromCursor(cursor)
    }

    /** 某类型的一级分类（parent_id = -1，按 sort_order） */
    fun getTopLevelCategories(type: Int): List<Category> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            CategoryEntity.TABLE_NAME, null,
            "${CategoryEntity.COL_TYPE} = ? AND ${CategoryEntity.COL_PARENT_ID} < 0",
            arrayOf(type.toString()),
            null, null, "${CategoryEntity.COL_SORT_ORDER} ASC"
        )
        return CategoryEntity.listFromCursor(cursor)
    }

    /** 某一级分类下的二级分类（按 sort_order） */
    fun getChildCategories(parentId: Long): List<Category> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            CategoryEntity.TABLE_NAME, null,
            "${CategoryEntity.COL_PARENT_ID} = ?", arrayOf(parentId.toString()),
            null, null, "${CategoryEntity.COL_SORT_ORDER} ASC"
        )
        return CategoryEntity.listFromCursor(cursor)
    }

    /** 清空分类表（分类体系迁移用，事务内调用） */
    fun clearCategories(): Int {
        val db = dbHelper.writableDatabase
        return db.delete(CategoryEntity.TABLE_NAME, null, null)
    }

    /** 把预算里旧的分类名改到新一级（迁移用） */
    fun updateBudgetCategory(oldName: String, newName: String): Int {
        if (oldName == newName) return 0
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(BudgetEntity.COL_CATEGORY_NAME, newName) }
        return db.update(
            BudgetEntity.TABLE_NAME, values,
            "${BudgetEntity.COL_CATEGORY_NAME} = ?", arrayOf(oldName)
        )
    }

    /** 按类型获取分类 */
    fun getCategoriesByType(type: Int): List<Category> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            CategoryEntity.TABLE_NAME, null,
            "${CategoryEntity.COL_TYPE} = ?", arrayOf(type.toString()),
            null, null, "${CategoryEntity.COL_SORT_ORDER} ASC"
        )
        return CategoryEntity.listFromCursor(cursor)
    }

    /** 插入一个分类并返回新 id（分类体系迁移/种子用；冲突返回 -1） */
    fun insertCategory(category: Category): Long {
        val db = dbHelper.writableDatabase
        return db.insertWithOnConflict(
            CategoryEntity.TABLE_NAME, null,
            CategoryEntity.toContentValues(category),
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /** 预算里出现过的分类名（迁移映射用；总预算空串跳过） */
    fun getDistinctBudgetCategoryNames(): List<String> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT DISTINCT ${BudgetEntity.COL_CATEGORY_NAME} FROM ${BudgetEntity.TABLE_NAME} " +
                "WHERE ${BudgetEntity.COL_CATEGORY_NAME} != ''",
            null
        )
        val names = mutableListOf<String>()
        cursor.use { c ->
            while (c.moveToNext()) names += c.getString(0)
        }
        return names
    }
}
