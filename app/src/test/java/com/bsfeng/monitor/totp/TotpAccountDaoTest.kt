package com.bsfeng.monitor.totp

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [TotpAccountDao] + [TotpDatabaseHelper] 单元测试（Robolectric 跑真实 SQLite）。
 *
 * 覆盖：CRUD、按 sort_order 排序、重排事务、空库计数、迁移重复执行的幂等性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpAccountDaoTest {

    private lateinit var context: Context
    private lateinit var helper: TotpDatabaseHelper
    private lateinit var dao: TotpAccountDao

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TotpDatabaseHelper.resetForTesting()
        context.deleteDatabase(TotpDatabaseHelper.DATABASE_NAME)
        helper = TotpDatabaseHelper(context)
        dao = TotpAccountDao(helper)
    }

    @After
    fun tearDown() {
        helper.close()
        TotpDatabaseHelper.resetForTesting()
        context.deleteDatabase(TotpDatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun `增删改查：插入后可读回全部字段`() {
        val id = dao.insert(
            testRow(
                issuer = "腾讯云",
                accountName = "ops@acme.cn",
                algorithm = "SHA256",
                digits = 8,
                periodSec = 60,
                sortOrder = 3,
            ),
        )
        assertTrue(id > 0)

        val row = dao.getById(id)
        assertNotNull(row)
        assertEquals("腾讯云", row!!.issuer)
        assertEquals("ops@acme.cn", row.accountName)
        assertEquals("SHA256", row.algorithm)
        assertEquals(8, row.digits)
        assertEquals(60, row.periodSec)
        assertEquals(3, row.sortOrder)
        assertEquals(1, dao.count())

        val updated = row.copy(issuer = "阿里云", sortOrder = 0)
        assertEquals(1, dao.update(updated))
        assertEquals("阿里云", dao.getById(id)!!.issuer)

        assertEquals(1, dao.delete(id))
        assertNull(dao.getById(id))
        assertEquals(0, dao.count())
    }

    @Test
    fun `排序：按 sort_order 升序返回，重排后顺序跟随给定 id`() {
        val a = dao.insert(testRow(accountName = "a", sortOrder = 2))
        val b = dao.insert(testRow(accountName = "b", sortOrder = 0))
        val c = dao.insert(testRow(accountName = "c", sortOrder = 1))

        assertEquals(listOf("b", "c", "a"), dao.listAll().map { it.accountName })
        assertEquals(3, dao.nextSortOrder())

        dao.reorder(listOf(a, b, c))
        val names = dao.listAll().map { it.accountName }
        assertEquals(listOf("a", "b", "c"), names)
        assertEquals(listOf(0, 1, 2), dao.listAll().map { it.sortOrder })
    }

    @Test
    fun `迁移幂等：onUpgrade 重复执行不抛异常且老数据不丢`() {
        val id = dao.insert(testRow(accountName = "keep-me"))

        helper.onUpgrade(helper.writableDatabase, 0, TotpDatabaseHelper.DATABASE_VERSION)
        helper.onUpgrade(helper.writableDatabase, 0, TotpDatabaseHelper.DATABASE_VERSION)

        assertEquals(1, dao.count())
        assertEquals("keep-me", dao.getById(id)!!.accountName)
    }

    @Test
    fun `空库：计数为 0 且 nextSortOrder 从 0 开始`() {
        assertEquals(0, dao.count())
        assertEquals(0, dao.nextSortOrder())
        assertTrue(dao.listAll().isEmpty())
    }
}
