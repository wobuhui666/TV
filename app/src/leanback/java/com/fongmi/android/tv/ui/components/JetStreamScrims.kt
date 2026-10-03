package com.fongmi.android.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.fongmi.android.tv.ui.theme.JetStreamWallpaper

/**
 * JetStream Scrim Overlays
 * 遮罩层组件，用于创建不同的背景效果
 */

/**
 * 垂直渐变遮罩 - 从上到下
 */
@Composable
fun jetStreamVerticalScrimBrush(
    startColor: Color? = null,
    middleColor: Color? = null,
    endColor: Color? = null,
    startY: Float = 0f,
    endY: Float = Float.POSITIVE_INFINITY
): Brush {
    val colorScheme = MaterialTheme.colorScheme
    val resolvedStartColor = startColor ?: colorScheme.background.copy(alpha = 0.20f)
    val resolvedEndColor = endColor ?: colorScheme.surface.copy(alpha = 0.92f)
    val colors = if (middleColor == null) {
        listOf(resolvedStartColor, resolvedEndColor)
    } else {
        listOf(resolvedStartColor, middleColor, resolvedEndColor)
    }
    return Brush.verticalGradient(colors = colors, startY = startY, endY = endY)
}

@Composable
fun JetStreamVerticalScrim(
    modifier: Modifier = Modifier,
    startColor: Color? = null,
    endColor: Color? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                jetStreamVerticalScrimBrush(startColor = startColor, endColor = endColor)
            )
    ) {
        content()
    }
}

/**
 * 水平渐变遮罩 - 从左到右
 */
@Composable
fun jetStreamHorizontalScrimBrush(
    startColor: Color? = null,
    middleColor: Color? = null,
    endColor: Color? = null
): Brush {
    val colorScheme = MaterialTheme.colorScheme
    val resolvedStartColor = startColor ?: colorScheme.surface.copy(alpha = 0.88f)
    val resolvedMiddleColor = middleColor ?: colorScheme.surface.copy(alpha = 0.70f)
    val resolvedEndColor = endColor ?: colorScheme.background.copy(alpha = 0.94f)
    return Brush.horizontalGradient(listOf(resolvedStartColor, resolvedMiddleColor, resolvedEndColor))
}

@Composable
fun JetStreamHorizontalScrim(
    modifier: Modifier = Modifier,
    startColor: Color? = null,
    middleColor: Color? = null,
    endColor: Color? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                jetStreamHorizontalScrimBrush(startColor, middleColor, endColor)
            )
    ) {
        content()
    }
}

/**
 * 控制面板遮罩 - 用于播放器控制面板
 */
@Composable
fun JetStreamControlScrim(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                jetStreamVerticalScrimBrush(
                    startColor = colorScheme.background.copy(alpha = 0.20f),
                    endColor = colorScheme.surface.copy(alpha = 0.92f)
                )
            )
    ) {
        content()
    }
}

/**
 * 信息遮罩 - 用于显示信息覆盖层
 */
@Composable
fun JetStreamInfoScrim(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                jetStreamVerticalScrimBrush(
                    startColor = colorScheme.background.copy(alpha = 0.66f),
                    middleColor = colorScheme.surface.copy(alpha = 0.14f),
                    endColor = colorScheme.background.copy(alpha = 0.54f)
                )
            )
    ) {
        content()
    }
}

/**
 * 页面遮罩 - 用于设置页面等全屏场景
 */
@Composable
fun JetStreamPageScrim(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val canvas = MaterialTheme.colorScheme.background.copy(alpha = JetStreamWallpaper.canvasAlpha)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                jetStreamVerticalScrimBrush(
                    startColor = canvas,
                    middleColor = canvas,
                    endColor = canvas
                )
            )
    ) {
        content()
    }
}

/**
 * 径向渐变遮罩 - 从中心向外
 *
 * 位置支持两种方式：绝对像素（center/radius）或按画布尺寸的比例
 * （centerFraction/radiusFraction）。优先使用比例，以适配不同分辨率。
 */
@Composable
fun JetStreamRadialScrim(
    modifier: Modifier = Modifier,
    centerColor: Color = Color.Transparent,
    edgeColor: Color? = null,
    center: Offset? = null,
    radius: Float? = null,
    centerFraction: Offset? = null,
    radiusFraction: Float? = null
) {
    val resolvedEdgeColor = edgeColor ?: MaterialTheme.colorScheme.background.copy(alpha = 0.60f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val maxDimension = maxOf(size.width, size.height)
        val centerOffset = when {
            centerFraction != null -> Offset(size.width * centerFraction.x, size.height * centerFraction.y)
            center != null -> center
            else -> Offset(size.width / 2f, size.height / 2f)
        }
        val resolvedRadius = when {
            radiusFraction != null -> maxDimension * radiusFraction
            radius != null -> radius
            else -> maxDimension / 1.5f
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(centerColor, resolvedEdgeColor),
                center = centerOffset,
                radius = resolvedRadius
            ),
            center = centerOffset,
            radius = resolvedRadius
        )
    }
}

/**
 * 底部遮罩 - 用于底部渐隐效果
 */
@Composable
fun JetStreamBottomScrim(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                jetStreamVerticalScrimBrush(
                    startColor = Color.Transparent,
                    middleColor = colorScheme.background.copy(alpha = 0.54f),
                    endColor = colorScheme.surface.copy(alpha = 0.92f)
                )
            )
    ) {
        content()
    }
}

/**
 * 顶部遮罩 - 用于顶部渐隐效果
 */
@Composable
fun JetStreamTopScrim(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                jetStreamVerticalScrimBrush(
                    startColor = colorScheme.surface.copy(alpha = 0.92f),
                    middleColor = colorScheme.background.copy(alpha = 0.54f),
                    endColor = Color.Transparent
                )
            )
    ) {
        content()
    }
}
