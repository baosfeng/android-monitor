package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.AppField
import com.bsfeng.monitor.ui.AppInfo
import com.bsfeng.monitor.ui.AppRow
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.ScreenHead
import com.bsfeng.monitor.ui.SecHead
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * s11 应用白名单「实现真值」导出。
 *
 * 绕开 WhitelistViewModel（它要 PackageManager + SharedPreferences），用 WhitelistScreen 的真实排布
 * （ScreenHead → 有数据态 LazyColumn：搜索区 → 应用列表小节头 → 应用行）按原型 s11:45-107 的同一份
 * 9 个已授权应用组合，几何可与 proto-screens.json 的 s11 直接对照。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S11WhitelistParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    /** 原型 s11 的 9 行：前 4 行默认在白名单里、后 5 行不在。 */
    private fun fakeApps() = listOf(
        AppInfo("com.tencent.mm", "微信", null, true),
        AppInfo("tv.danmaku.bili", "哔哩哔哩", null, true),
        AppInfo("com.taobao.taobao", "淘宝", null, true),
        AppInfo("com.autonavi.minimap", "高德地图", null, true),
        AppInfo("com.zhihu.android", "知乎", null, false),
        AppInfo("com.netease.cloudmusic", "网易云音乐", null, false),
        AppInfo("com.eg.android.AlipayGphone", "支付宝", null, true),
        AppInfo("com.jingdong.app.mall", "京东", null, false),
        AppInfo("com.sina.weibo", "微博", null, false),
    )

    @Test
    fun exportWhitelistS11() {
        val apps = fakeApps()
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                val t = LocalDesignTokens.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .testTag("screen-s11"),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        ScreenHead(
                            title = "应用白名单",
                            meta = "白名单内的应用不会被跳过开屏广告",
                            onBack = {},
                            modifier = Modifier.testTag("s11-head"),
                            consumeTopInset = true,
                        )
                        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                            LazyColumn(
                                // 与 WhitelistScreen 有数据态同构：原型 .content.pad = 8 顶 + 左右 16，
                                // 内边距在锚点外侧（锚点 s11-body 量的是内容列 x=16/w=361）。
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = t.space4, end = t.space4, top = t.space2)
                                    .testTag("s11-body"),
                            ) {
                                item(key = "search") {
                                    SearchBlock(appCount = apps.size)
                                }
                                item(key = "apps-head") {
                                    // 原型 .stack gap 12 在锚点之上，SecHead 自身只再给 bottom 12
                                    SecHead(
                                        "应用列表",
                                        Modifier.testTag("s11-list").padding(top = 12.dp, bottom = 12.dp),
                                        right = "匹配 " + apps.size + " 个",
                                    )
                                }
                                itemsIndexed(items = apps, key = { _, app -> app.packageName }) { index, app ->
                                    val enterMod =
                                        if (index < STAGGER_MAX_STEPS) Modifier.staggerEnterAt(index) else Modifier
                                    val t = LocalDesignTokens.current
                                    StackRow(
                                        first = index == 0,
                                        last = index == apps.lastIndex,
                                        modifier = Modifier.padding(horizontal = t.space4).then(enterMod),
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            AppRow(app = app, onToggle = {})
                                            if (index == apps.lastIndex) Spacer(Modifier.height(t.space1))
                                        }
                                    }
                                }
                                item(key = "bottom-space") {
                                    val t = LocalDesignTokens.current
                                    Spacer(Modifier.height(t.space3 + t.space6))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s11" to items), File("build/parity/impl-s11.json"))
        println("PARITY(s11) tags=" + items.size + " " + items.joinToString { it.id })
    }

    /** 搜索区：输入框 + 4 个快捷 chip + 扫描计数行（与 WhitelistScreen 的 item("search") 同构）。 */
    @Composable
    private fun SearchBlock(appCount: Int) {
        val t = LocalDesignTokens.current
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = t.space3)
                .testTag("s11-search"),
        ) {
            AppField(
                value = "",
                onValueChange = {},
                placeholder = "搜索应用名或包名",
                leading = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = t.muted,
                        modifier = Modifier.size(t.space5).padding(end = t.space2),
                    )
                },
            )
            Spacer(Modifier.height(t.space2))
            Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                listOf("微信", "哔哩哔哩", "淘宝", "重置").forEach { keyword ->
                    Chip(label = keyword, selected = false, onClick = {})
                }
            }
            Spacer(Modifier.height(t.space2))
            Text(
                "已扫描 " + appCount + " 个已授权应用 · 搜索「抖音」可查看无结果空态",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
    }
}
