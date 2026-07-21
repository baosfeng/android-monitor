package com.bsfeng.monitor.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================
// 分类颜色（仍被 FinanceMainScreen / AddTransactionScreen 引用）
// =============================================

val categoryPalette = listOf(
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00),
    Color(0xFF34C759), Color(0xFF5AC8FA), Color(0xFF007AFF),
    Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFF5856D6),
    Color(0xFF00C7BE), Color(0xFFFF6482), Color(0xFF6EA8FF),
    Color(0xFFA2845E), Color(0xFF8E8E93), Color(0xFFFFD60A)
)

object FinanceColors {
    val expense = Color(0xFFFF3B30)
    val income = Color(0xFF34C759)
    val budget = Color(0xFF007AFF)
}

/** 分类图标圆点 */
@Composable
fun CategoryDot(
    categoryName: String,
    color: Color,
    size: Int = 32,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp / 2))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = categoryName.take(1),
            color = Color.White,
            fontSize = (size / 2).sp,
            fontWeight = FontWeight.Bold
        )
    }
}
