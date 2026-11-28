package com.bsfeng.monitor.ui.quota

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.bsfeng.monitor.parity.RegisterComponentActivityRule
import com.bsfeng.monitor.ui.theme.MonitorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * s19 账号行标题的 UI 断言：行内可见标题必须带 provider 显示名。
 *
 * 纯函数 [accountRowTitle] 已有单测；这里锁的是「行里真的把标题渲染出来」——
 * 只测纯函数的话，调用点忘了替换仍然全绿（真机验收踩过：两行都只剩「账号 1 / 账号 2」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class QuotaSettingsAccountRowTitleTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private fun row(info: QuotaProviderInfo, suffix: String) {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                AccountRow(
                    info = info,
                    suffix = suffix,
                    topBorder = false,
                    status = accountStatusText(info, null, 0),
                    expanded = false,
                    configured = false,
                    saving = false,
                    saveError = null,
                    onToggle = {},
                    onSave = {},
                    onClear = {},
                    onToast = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun second_account_title_contains_provider_name() {
        row(
            QuotaProviderInfo(
                instanceId = "ollama#2",
                providerTypeId = "ollama",
                displayName = "账号 2",
                typeDisplayName = "Ollama",
                multiAccount = true,
            ),
            "ollama2",
        )
        compose.onNodeWithTag("s19-acc-ollama2")
            .assert(hasText("Ollama · 账号 2", substring = true))
    }

    @Test
    fun single_account_does_not_duplicate_provider_name() {
        row(
            QuotaProviderInfo(
                instanceId = "deepseek",
                providerTypeId = "deepseek",
                displayName = "DeepSeek",
                typeDisplayName = "DeepSeek",
            ),
            "ds1",
        )
        compose.onNodeWithTag("s19-acc-ds1")
            .assert(hasText("DeepSeek", substring = true))
            .assert(hasText("DeepSeek · DeepSeek", substring = true).not())
    }

    @Test
    fun renamed_account_gets_provider_prefix() {
        row(
            QuotaProviderInfo(
                instanceId = "opendesign",
                providerTypeId = "opendesign",
                displayName = "工作区 A",
                typeDisplayName = "Open Design",
            ),
            "od-a",
        )
        compose.onNodeWithTag("s19-acc-od-a")
            .assert(hasText("Open Design · 工作区 A", substring = true))
    }
}
