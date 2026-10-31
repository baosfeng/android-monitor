package com.bsfeng.monitor.ui

// 设置页五态容器 + 二级页整屏骨架。
//
// 五态容器（加载 / 空 / 错 / 边界）在一级页与二级页共用同一份文案与重试动作；
// 二级页骨架只负责「页头 + 五态 + 正文」，各组正文见 ui/SettingsSubPages.kt。
//
// 二级页内**不再写与页头标题重复的 SectionHeader**（契约 s09 页层级）：分组只靠卡间隙区分，
// 页头大标题已经是这一页的名字，正文再来一个同名小标题是同一句话说两遍。

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.foundation.pageBackdrop

/**
 * 设置页五态容器（一级与二级共用）。
 *
 * 空/错态 = 居中列 + 44dp 图标 + 17sp 标题 + 14sp 描述 + CTA，文案取自原型 s09-st-*；
 * 边界态沿用既有超长标题 / 五位计数样例。
 */
@Composable
internal fun SettingsStateContainer(
    rt: SettingsScreenRuntime,
    anchored: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 五态锚点只落在锚点所属页（一级页）：二级页以覆盖层叠加、一级页不卸载，
    // 两处都打同键会出现两个同名节点，机器对账选不中唯一目标。
    val stateModifier = if (!anchored) {
        Modifier
    } else {
        when (rt.effective) {
            FiveState.LOADING -> Modifier.testTag("s09-st-loading")
            FiveState.EMPTY -> Modifier.testTag("s09-st-empty")
            FiveState.ERROR -> Modifier.testTag("s09-st-error")
            FiveState.EDGE -> Modifier.testTag("s09-st-edge")
            FiveState.POPULATED -> Modifier
        }
    }
    StateContainer(
        state = rt.effective,
        modifier = Modifier.fillMaxWidth().then(stateModifier),
        emptyTitle = "暂无可用设置项",
        emptyText = "无障碍服务尚未授权，开启后才能调整监控与开屏跳过选项。",
        emptyActionLabel = "前往系统设置",
        onEmptyAction = {
            rt.context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        errorTitle = "读取配置失败",
        errorText = "本地配置存储被拒绝（EACCES），上次读取 9:40 失败。",
        onRetry = {
            rt.reloadCounters()
            rt.toast.show("正在重试读取配置…")
        },
        edgeText = "边界态：超长应用名 / 10,000+ 条计数",
        edgeContent = {
            TokenRow(
                title = "国家电网掌上电力营业厅客户端（企业内网版）",
                trailing = { TailNum("10,286 条") },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "中国建设银行个人网上银行移动客户端",
                trailing = { TailNum("10,000 条") },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "超长标题：该应用在后台持续拉起前台服务并申请了忽略电池优化的白名单权限",
                trailing = { TailNum("9,999 条") },
            )
        },
        content = content,
    )
}

/**
 * 二级页整屏：页头（带返回）+ 五态容器 + 该组正文。
 *
 * 二级页不常驻，返回即卸载；一级页滚动位置由调用方保留（一级页始终在组合树里，
 * 二级页以覆盖层形式叠加，见 ui/SettingsScreen.kt）。
 */
@Composable
internal fun SettingsSubPageScreen(
    page: SettingsSubPage,
    rt: SettingsScreenRuntime,
    onBack: () -> Unit,
    onOpenSubPage: (SettingsSubPage) -> Unit,
) {
    val t = rt.t
    Column(
        Modifier
            .fillMaxSize()
            .pageBackdrop()
    ) {
        ScreenHead(title = page.title, meta = page.meta, onBack = onBack)
        // 滚动位置按页持有：下钻（功能开关 → 细则）时 page 变而组合位置不变，
        // 直接 rememberScrollState() 会让细则页继承上一页的偏移、一进来就停在半屏。
        val pageScroll = remember(page) { ScrollState(0) }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(pageScroll),
            verticalArrangement = Arrangement.spacedBy(t.space4),
        ) {
            SettingsStateContainer(rt) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(t.space4),
                ) {
                    SettingsSubPageBody(page = page, rt = rt, onOpenSubPage = onOpenSubPage)
                    Spacer(Modifier.height(t.space6))
                }
            }
        }
    }
}
