package com.bsfeng.monitor.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import com.bsfeng.monitor.data.DatabaseHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 备份编排的两个端到端不变量。
 *
 * 1. **R6 一致快照**：备份期间另一个线程一直在往 records 里写，包里那份库的行数必须与
 *    manifest 登记的表行数逐字相等 —— 这正是还原后自检用的判据；
 * 2. **SEC-08 不外溢**：备份只落 App 私有目录，公共 Downloads 不再出现本 App 的备份包。
 *
 * 两者都要真库 + 真写，所以整类只放两个用例（DatabaseHelper 是进程单例，多用例会互相干扰）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupSnapshotConsistencyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        // 不删库：DatabaseHelper 是进程单例，deleteDatabase 只会把文件 unlink 掉，
        // 连接仍指向旧 inode —— 之后快照就会在「文件不存在」上失败。用例本身用唯一 id 写入，
        // 断言只比「包内行数 = 清单登记值」，不依赖空库。
        context = RuntimeEnvironment.getApplication()
    }

    private val sequence = java.util.concurrent.atomic.AtomicLong()

    private fun uniqueId(prefix: String): String = prefix + "-" + sequence.incrementAndGet()

    private fun insertRecord(id: String) {
        val db = DatabaseHelper.getInstance(context).writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO records (id, type, action, timestamp, label, detail) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id, "APP", "APP_SWITCH", System.currentTimeMillis(), "某某应用", "{}"),
        )
    }

    private fun recordsInDb(dbFile: File): Long {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        val rows = db.rawQuery("SELECT COUNT(*) FROM records", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        }
        db.close()
        return rows
    }

    /** 把包里的两张库解到临时目录（还原时也是这么解包的），返回目录。 */
    private fun extractDatabases(zip: File, target: File): File {
        target.mkdirs()
        ZipFile(zip).use { z ->
            z.entries().asSequence()
                .filter { it.name.startsWith("${BackupPaths.DB_DIR}/monitor.db") }
                .forEach { entry ->
                    File(target, entry.name.substringAfterLast('/')).outputStream().use { out ->
                        z.getInputStream(entry).use { input -> input.copyTo(out) }
                    }
                }
        }
        return target
    }

    @Test
    fun `备份期间持续写入时包内行数与清单登记一致`() {
        val seedIds = (0 until 100).map { uniqueId("seed") }
        seedIds.forEach { insertRecord(it) }

        val writing = AtomicBoolean(true)
        val writer = thread(name = "record-writer") {
            var i = 0
            while (writing.get()) {
                runCatching { insertRecord(uniqueId("live-" + i++)) }
                // 不要打满：真实写者（切 App / 心跳）也不是每毫秒一条，
                // 而打满会让「拿写闸」变成纯抢锁，测出来的只是锁竞争不是快照一致性
                Thread.sleep(2)
            }
        }

        val outcome = try {
            BackupManager(context).backupNow()
        } finally {
            writing.set(false)
            writer.join(10_000)
        }

        assertTrue("备份应成功：$outcome", outcome is BackupOutcome.Success)
        val success = outcome as BackupOutcome.Success
        val local = File(context.getExternalFilesDir(null), BackupManager.LOCAL_DIR + "/" + success.localName)
        assertTrue("备份包应落在 App 私有目录", local.isFile)

        val packed = extractDatabases(local, File(context.cacheDir, "verify"))
        val packagedRows = recordsInDb(File(packed, "monitor.db"))
        val declared = success.manifest.tables.first { it.table == "records" }.rows

        assertEquals("包内的行数必须等于清单登记值（还原后自检就按这个比）", declared, packagedRows)
        assertTrue("写入是并发的，包内至少要包含种子那 100 行", packagedRows >= 100L)
    }

    @Test
    fun `备份不再写公共 Downloads 目录`() {
        insertRecord(uniqueId("only"))

        val outcome = BackupManager(context).backupNow()

        assertTrue("备份应成功：$outcome", outcome is BackupOutcome.Success)
        val success = outcome as BackupOutcome.Success
        assertNotNull("本地备份仍要生成", success.localName)

        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "行为记录备份",
        )
        val leaked = publicDir.listFiles()
            ?.filter { it.name.startsWith(BackupManager.NAME_PREFIX) }
            ?.map { it.name }
            ?: emptyList()
        assertTrue("加密前的库不该出现在公共目录：" + leaked, leaked.isEmpty())
    }
}
