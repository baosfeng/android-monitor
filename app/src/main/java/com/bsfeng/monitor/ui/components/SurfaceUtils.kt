package com.bsfeng.monitor.ui.components

// 从 ui/components/Surfaces.kt 拆出：两个排版小助手 —— HSpacer（固定高度占位）、currentTokens（取当前设计 token）。负责「面」家族的零散公共件。

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 占位空行。 */
@Composable
fun HSpacer(height: Dp) = Spacer(Modifier.height(height))

/** 主题快照便利访问（供非 @Composable 的 draw 逻辑使用）。 */
@Composable
fun currentTokens(): DesignTokens = LocalDesignTokens.current
