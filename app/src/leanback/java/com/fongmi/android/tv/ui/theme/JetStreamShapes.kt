package com.fongmi.android.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * JetStream Shape System
 * 统一的形状和圆角系统，遵循 Material Design 3 规范
 */
object JetStreamShapes {
    // Extra Small - 用于小型 Chip、Badge
    val ExtraSmall = RoundedCornerShape(4.dp)

    // Small - 用于按钮、小卡片
    val Small = RoundedCornerShape(8.dp)

    // Medium - 用于中等卡片、对话框
    val Medium = RoundedCornerShape(12.dp)

    // Large - 用于大型卡片
    val Large = RoundedCornerShape(16.dp)

    // Extra Large - 用于全屏卡片、底部表单
    val ExtraLarge = RoundedCornerShape(28.dp)

    // Circular - 用于圆形按钮、头像
    val Circle = RoundedCornerShape(50)

    // Chip Shape - 用于 Chip 组件
    val Chip = RoundedCornerShape(8.dp)

    // Button Shape - 用于按钮组件
    val Button = RoundedCornerShape(12.dp)

    // Card Shape - 用于卡片组件
    val Card = Small

    // Dialog Shape - 用于对话框
    val Dialog = Large

    // Bottom Sheet Shape - 用于底部表单
    val BottomSheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
}

/**
 * JetStream Elevation System
 * 统一的阴影和层级系统
 */
object JetStreamElevation {
    val Level0 = 0.dp
    val Level1 = 1.dp
    val Level2 = 3.dp
    val Level3 = 6.dp
    val Level4 = 8.dp
    val Level5 = 12.dp
}
