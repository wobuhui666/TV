package com.fongmi.android.tv.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween

/**
 * JetStream Animation System
 * 统一的动画系统，提供流畅的交互体验
 */
object JetStreamAnimations {
    // Duration constants
    const val DurationShort = 180
    const val DurationMedium = 260
    const val DurationLong = 500
    const val DurationPanel = 220
    const val DurationExit = 160
    const val DurationFocus = 90

    // Scale animations - 缩放动画
    val ScaleSpring: AnimationSpec<Float> = tween(durationMillis = DurationFocus)

    val ScaleTween: AnimationSpec<Float> = tween(
        durationMillis = DurationMedium
    )

    // Color animations - 颜色动画
    val ColorTween = tween<androidx.compose.ui.graphics.Color>(
        durationMillis = DurationMedium
    )

    // Size animations - 尺寸动画
    val SizeTween = tween<androidx.compose.ui.unit.Dp>(
        durationMillis = DurationMedium
    )

    // Float animations - 浮点数动画
    val FloatTween = tween<Float>(
        durationMillis = DurationMedium
    )

    // Focus scale values - 聚焦缩放值
    const val FocusScaleSmall = 1.03f
    const val FocusScaleMedium = 1.06f
    const val FocusScaleLarge = 1.08f

    // Pressed scale values - 按下缩放值
    const val PressedScale = 0.96f
}

/**
 * JetStream Alpha Values
 * 统一的透明度值
 */
object JetStreamAlpha {
    // Disabled states
    const val Disabled = 0.38f
    const val DisabledMedium = 0.50f

    // Content alpha
    const val High = 0.92f
    const val Medium = 0.86f
    const val MediumLow = 0.72f
    const val Low = 0.62f
    const val VeryLow = 0.38f

    // Background alpha
    const val BackgroundVeryLight = 0.08f
    const val BackgroundLight = 0.10f
    const val BackgroundLightMedium = 0.14f
    const val BackgroundMedium = 0.18f
    const val BackgroundMediumHigh = 0.22f
    const val BackgroundHigh = 0.26f
    const val BackgroundVeryHigh = 0.30f

    // Scrim alpha
    const val ScrimLight = 0.10f
    const val ScrimMedium = 0.42f
    const val ScrimHeavy = 0.72f
}
