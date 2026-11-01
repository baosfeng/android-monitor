package com.bsfeng.monitor.domain

import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `records.type` 的序列化口径。
 *
 * 回归背景：`toString` 曾用 `type::class.simpleName`，release 构建被 R8 重命名成
 * `qg1`/`ig1`/`kg1` 等短名落库，读回 `fromString` 不认识 → 广告页
 * `type='SCREEN' AND action='AD_SKIPPED'` 漏掉近 7 天里最新的 8 条。
 * 这里把「固定字符串、混淆名可救、未知值不静默」三条钉死。
 */
class RecordTypeTest {

    private val allTypes = listOf(
        RecordType.SCREEN, RecordType.CHARGING, RecordType.APP_USAGE, RecordType.WIFI,
        RecordType.AUDIO, RecordType.BATTERY, RecordType.PACKAGE, RecordType.POWER_SAVE,
        RecordType.AIRPLANE, RecordType.PHONE_STATE, RecordType.LOCATION,
    )

    // ─────────────────────────── 正常路径：全类型往返 ───────────────────────────

    @Test
    fun `所有类型名称与类型往返一致`() {
        assertEquals(allTypes.size, RecordType.CANONICAL_NAMES.size)
        allTypes.zip(RecordType.CANONICAL_NAMES).forEach { (type, name) ->
            assertEquals("规范名顺序与类型清单不一致", name, RecordType.Companion.toString(type))
            assertEquals("fromString(\"$name\") 应能还原类型", type, RecordType.fromString(name))
        }
    }

    @Test
    fun `规范名是固定字符串，不是类名`() {
        // 类名在 R8 下会被改名；这里断言的是字面量口径本身
        assertEquals(
            listOf(
                "SCREEN", "CHARGING", "APP_USAGE", "WIFI", "AUDIO", "BATTERY",
                "PACKAGE", "POWER_SAVE", "AIRPLANE", "PHONE_STATE", "LOCATION",
            ),
            RecordType.CANONICAL_NAMES,
        )
        allTypes.forEach { type ->
            val name = RecordType.Companion.toString(type)
            assertEquals("规范名只由大写字母和下划线组成：$name", name, name.uppercase())
            assertTrue("规范名非空：$type", name.isNotEmpty())
        }
    }

    // ──────────────────────────── 边界：大小写 / 空白 ────────────────────────────

    @Test
    fun `fromString 忽略前后空白与大小写`() {
        assertEquals(RecordType.SCREEN, RecordType.fromString("  screen "))
        assertEquals(RecordType.LOCATION, RecordType.fromString("Location"))
        assertEquals(RecordType.APP_USAGE, RecordType.fromString("app_usage"))
    }

    // ─────────────────── 历史混淆名（R8 重命名 data object 类名）───────────────────

    @Test
    fun `本机实测的 10 个混淆名全部还原为规范类型`() {
        val expected = mapOf(
            "qg1" to RecordType.SCREEN,     // 198 条
            "bg1" to RecordType.SCREEN,     // 5 条
            "ig1" to RecordType.APP_USAGE,  // 458 条
            "zf1" to RecordType.APP_USAGE,  // 10 条
            "tf1" to RecordType.APP_USAGE,  // 8 条
            "z41" to RecordType.APP_USAGE,  // 2 条
            "kg1" to RecordType.BATTERY,    // 97 条
            "lg1" to RecordType.CHARGING,   // 11 条
            "cg1" to RecordType.CHARGING,   // 2 条
            "rg1" to RecordType.WIFI,       // 3 条
        )
        assertEquals(expected.keys, RecordType.LEGACY_ALIASES.keys)
        expected.forEach { (alias, type) ->
            assertEquals("混淆名 $alias", type, RecordType.fromString(alias))
            assertEquals("混淆名大写形式 $alias", type, RecordType.fromString(alias.uppercase()))
        }
    }

    @Test
    fun `别名表指向的目标都有规范名（迁移 UPDATE 不会写出空串）`() {
        RecordType.LEGACY_ALIASES.forEach { (alias, type) ->
            val name = RecordType.Companion.toString(type)
            assertTrue("别名 $alias 的目标类型有规范名", name in RecordType.CANONICAL_NAMES)
        }
    }

    // ─────────────────────── 异常：未知值不静默回落 SCREEN ───────────────────────

    @Test
    fun `未知值与空值返回 null 而不是 SCREEN`() {
        assertNull(RecordType.fromString("NOT_A_TYPE"))
        assertNull(RecordType.fromString(""))
        assertNull(RecordType.fromString("   "))
        // 混淆名形状但不在表里：不能猜，必须是 null
        assertNull(RecordType.fromString("qq9"))
    }

    // ─────────────────────────── action 反推（读库兜底）───────────────────────────

    @Test
    fun `唯一归属的 action 能反推类型`() {
        assertEquals(RecordType.SCREEN, RecordType.inferFromAction("UNLOCKED"))
        assertEquals(RecordType.SCREEN, RecordType.inferFromAction("AD_SKIPPED"))
        assertEquals(RecordType.APP_USAGE, RecordType.inferFromAction("USAGE"))
        assertEquals(RecordType.APP_USAGE, RecordType.inferFromAction("CLOSED"))
        assertEquals(RecordType.CHARGING, RecordType.inferFromAction("STARTED"))
        assertEquals(RecordType.CHARGING, RecordType.inferFromAction("STOPPED"))
        assertEquals(RecordType.WIFI, RecordType.inferFromAction("CONNECTED"))
        assertEquals(RecordType.BATTERY, RecordType.inferFromAction("LOW"))
        assertEquals(RecordType.PHONE_STATE, RecordType.inferFromAction("RINGING"))
        assertEquals(RecordType.LOCATION, RecordType.inferFromAction("COORDINATE"))
        // 页面轨迹下线后 ENTER 不再是合法 action：反推必须返回 null，不能凭空造出类型
        assertNull(RecordType.inferFromAction("ENTER"))
    }

    @Test
    fun `歧义与未知 action 不猜（OFF、ON、CHANGED、未知）`() {
        // OFF 同时属于 SCREEN / POWER_SAVE / AIRPLANE；CHANGED 只能属于 BATTERY
        assertNull(RecordType.inferFromAction("OFF"))
        assertNull(RecordType.inferFromAction("ON"))
        assertNull(RecordType.inferFromAction("CHANGED"))
        assertNull(RecordType.inferFromAction("WHAT"))
        assertNull(RecordType.inferFromAction(""))
    }

    // ───────────────────────── fromStored：读库三级兜底 ─────────────────────────

    @Test
    fun `fromStored 优先按名字，其次按 action，全不认识返回 null`() {
        assertEquals(RecordType.SCREEN, RecordType.fromStored("SCREEN", "OFF"))
        assertEquals(RecordType.SCREEN, RecordType.fromStored("qg1", "OFF"))
        // 名字坏了但 action 还能说明问题
        assertEquals(RecordType.APP_USAGE, RecordType.fromStored("zz9", "USAGE"))
        assertNull(RecordType.fromStored("zz9", "WHAT"))
        assertNull(RecordType.fromStored(null, null))
        assertNull(RecordType.fromStored("", ""))
    }

    @Test
    fun `fromStored 对 AD_SKIPPED 这类 SCREEN 事件能救回广告页数据`() {
        // 混淆名 + AD_SKIPPED：广告页查询依赖它被认成 SCREEN
        val resolved = RecordType.fromStored("qg1", "AD_SKIPPED")
        assertNotNull(resolved)
        assertEquals(RecordType.SCREEN, resolved)
    }
}
