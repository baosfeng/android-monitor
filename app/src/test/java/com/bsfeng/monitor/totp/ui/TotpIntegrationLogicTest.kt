package com.bsfeng.monitor.totp.ui

import com.bsfeng.monitor.domain.totp.TotpParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 装配层用到的纯逻辑（分组标题 / 手动表单校验）—— 不依赖 Android，直接钉口径。 */
class TotpIntegrationLogicTest {

    @Test
    fun `分组标题按平台名 空平台归入未命名`() {
        assertEquals("GitHub", groupLabelOf("GitHub"))
        assertEquals("腾讯云", groupLabelOf("  腾讯云  "))
        assertEquals("未命名平台", groupLabelOf(""))
        assertEquals("未命名平台", groupLabelOf("   "))
    }

    @Test
    fun `手动表单 合法输入返回 null`() {
        assertNull(
            validateManualForm(
                TotpManualForm(issuer = "GitHub", accountName = "alice@acme.com", secretBase32 = "JBSWY3DPEHPK3PXP"),
            ),
        )
        // 带空格与 8 位码同样合法（高级参数不改变必填口径）
        assertNull(
            validateManualForm(
                TotpManualForm(
                    issuer = "AWS",
                    accountName = "root@acme",
                    secretBase32 = "JBSW Y3DP EHPK 3PXP",
                    params = TotpParams(digits = 8, periodSec = 60),
                ),
            ),
        )
    }

    @Test
    fun `手动表单 缺字段按填写顺序先报最上面一项`() {
        assertEquals(
            "请填写平台名",
            validateManualForm(TotpManualForm(issuer = "", accountName = "", secretBase32 = "")),
        )
        assertEquals(
            "请填写账户",
            validateManualForm(TotpManualForm(issuer = "GitHub", accountName = "  ", secretBase32 = "")),
        )
    }

    @Test
    fun `手动表单 密钥格式错给出可操作提示`() {
        val error = validateManualForm(
            TotpManualForm(issuer = "GitHub", accountName = "alice@acme.com", secretBase32 = "0189!!"),
        )
        assertNotNull(error)
        assertEquals(true, error!!.startsWith("密钥格式不对"))
    }
}
