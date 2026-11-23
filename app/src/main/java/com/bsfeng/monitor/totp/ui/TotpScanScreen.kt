package com.bsfeng.monitor.totp.ui

// s24 扫码添加：取景框 + 手电筒 + 相册入口 + 识别失败横幅 + 相机权限被拒态。
// 相机 / 相册 / 系统设置全部是回调；本屏不持有 CameraX、不申请权限。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.AppButton
import com.bsfeng.monitor.ui.AppButtonVariant
import com.bsfeng.monitor.ui.TokenRow
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 扫码添加（原型 s24）。
 *
 * @param cameraDenied 相机权限被拒时整屏走错误态（不给假取景框）
 * @param failureMessage 相册解码失败的横幅文案；null 时横幅不渲染
 * @param torchOn 手电筒状态（真机由调用方接 CameraManager）
 */
@Composable
fun TotpScanScreen(
    modifier: Modifier = Modifier,
    cameraDenied: Boolean = false,
    torchOn: Boolean = false,
    failureMessage: String? = null,
    onToggleTorch: () -> Unit = {},
    onPickImage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onManualEntry: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    /** 取景框里的真实相机预览；null = 静态取景框（无相机权限 / 纯视觉场景不传）。 */
    preview: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    TotpScaffold(title = "扫码添加", screenTag = "screen-s24", contentTag = "s24-content", onBack = onBack, modifier = modifier) {
        if (cameraDenied) {
            ErrorState(
                title = "无法使用相机",
                text = "相机权限被拒绝，扫码不可用。可以到系统设置里重新授权，或改用手动输入密钥。",
                onRetry = onOpenSettings,
                actionLabel = "去系统设置",
            )
            AppButton(
                text = "改用手动输入",
                onClick = onManualEntry,
                variant = AppButtonVariant.Ghost,
                block = true,
            )
        } else {
            // 原型 s24-body 包住取景 / 失败横幅 / 工具条 / 手动回退 / 说明；列内间距沿用原来的显式 Spacer，视觉不变
            Column(Modifier.testTag("s24-body")) {
                // 原型 s24-view 包住取景框与「把二维码放进取景框」提示，二者构成一个整块
                Column(Modifier.testTag("s24-view")) {
                    ScanLens(
                        torchOn = torchOn,
                        preview = preview,
                        modifier = Modifier.testTag("s24-lens"),
                    )
                    Text(
                        "把二维码放进取景框 · 识别在本机完成，截图不外传",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.fillMaxWidth().padding(top = t.space3),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                if (failureMessage != null) {
                    ScanFailureSection(message = failureMessage, onRetry = onPickImage, onManualEntry = onManualEntry)
                }
                Spacer(Modifier.height(t.space2))
                Row(Modifier.testTag("s24-tools"), horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                    AppButton(
                        text = "手电筒",
                        onClick = onToggleTorch,
                        variant = if (torchOn) AppButtonVariant.Primary else AppButtonVariant.Ghost,
                        modifier = Modifier.weight(1f).testTag("s24-torch"),
                    )
                    AppButton(
                        text = "从相册选择",
                        onClick = onPickImage,
                        variant = AppButtonVariant.Ghost,
                        modifier = Modifier.weight(1f).testTag("s24-album"),
                    )
                }
                Spacer(Modifier.height(t.space2))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .testTag("s24-fallback")
                        .clip(RoundedCornerShape(t.radiusLg))
                        .background(t.surface)
                        .border(1.dp, t.borderSoft, RoundedCornerShape(t.radiusLg))
                        .padding(horizontal = t.space2, vertical = t.space1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TokenRow(
                        title = "扫码不方便？",
                        subtitle = "网站只给了密钥字符串时可改手动输入",
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        text = "手动输入",
                        onClick = onManualEntry,
                        variant = AppButtonVariant.Ghost,
                        modifier = Modifier.testTag("s24-manual"),
                    )
                }
                TotpFine("识别成功后直接回到账户列表，可再改平台名与账号；二维码解析出的密钥立即用 Keystore 加密，不落明文磁盘。")
            }
        }
    }
}

/** 取景框（原型 .s24-lens）：88% 前景底 + 四角括号 + 中央扫描线。 */
@Composable
private fun ScanLens(torchOn: Boolean, preview: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val lensBg = t.fg.copy(alpha = if (torchOn) 0.62f else 0.88f)
    val markColor = Color.White.copy(alpha = 0.82f)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(t.radiusLg))
            // 有真实预览时不再铺底色：底色会盖在 SurfaceTexture 之上挡住画面
            .then(if (preview == null) Modifier.background(lensBg) else Modifier),
    ) {
        // 相机预览在最底层，四角括号与扫描线叠在它上面
        if (preview != null) {
            Box(Modifier.fillMaxSize()) { preview() }
        }
        Box(Modifier.fillMaxSize().padding(38.dp)) {
            ScanCorner(Alignment.TopStart, markColor, vertical = true)
            ScanCorner(Alignment.TopEnd, markColor, vertical = false)
            ScanCorner(Alignment.BottomStart, markColor, vertical = true)
            ScanCorner(Alignment.BottomEnd, markColor, vertical = false)
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.78f)
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.7f)),
        )
    }
}

/** 单角括号：一横一竖两根 2dp 细条，按角落对齐。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ScanCorner(
    corner: Alignment,
    color: Color,
    vertical: Boolean,
) {
    Box(Modifier.align(corner).size(26.dp)) {
        Box(
            Modifier
                .align(if (vertical) Alignment.TopStart else Alignment.TopEnd)
                .size(width = 26.dp, height = 2.dp)
                .background(color),
        )
        Box(
            Modifier
                .align(if (vertical) Alignment.TopStart else Alignment.TopEnd)
                .size(width = 2.dp, height = 26.dp)
                .background(color),
        )
    }
}

/** 识别失败横幅现已抽到 [TotpScaffold.kt] 的 `ScanFailureBanner`（添加页复用同一组件）。 */
@Composable
private fun ScanFailureSection(message: String, onRetry: () -> Unit, onManualEntry: () -> Unit) {
    ScanFailureBanner(
        message = message,
        primaryLabel = "换一张图片",
        onPrimary = onRetry,
        secondaryLabel = "手动输入",
        onSecondary = onManualEntry,
        modifier = Modifier.testTag("s24-failure"),
    )
}
