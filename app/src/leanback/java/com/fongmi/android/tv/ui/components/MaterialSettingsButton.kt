package com.fongmi.android.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.fongmi.android.tv.ui.theme.JetStreamAnimations

enum class MaterialSettingsButtonStyle { Filled, Tonal, Outlined, Text }

/** TV Button owns remote input and long press; Material color roles define action hierarchy. */
@Composable
fun MaterialSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    style: MaterialSettingsButtonStyle = MaterialSettingsButtonStyle.Tonal,
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    interactionSource: MutableInteractionSource? = null,
    focusedScale: Float = JetStreamAnimations.FocusScaleSmall,
    content: @Composable RowScope.() -> Unit
) {
    val active = enabled && LocalTvContentActive.current
    val colors = MaterialTheme.colorScheme
    val container = when {
        selected -> colors.primaryContainer
        style == MaterialSettingsButtonStyle.Filled -> colors.primary
        style == MaterialSettingsButtonStyle.Tonal -> colors.secondaryContainer
        else -> Color.Transparent
    }
    val foreground = when {
        selected -> colors.onPrimaryContainer
        style == MaterialSettingsButtonStyle.Filled -> colors.onPrimary
        style == MaterialSettingsButtonStyle.Tonal -> colors.onSecondaryContainer
        else -> colors.primary
    }
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val background by animateColorAsState(
        if (active && pressed) colors.primaryContainer else if (active && focused) colors.primary else container,
        tween(JetStreamAnimations.DurationFocus), label = "settingsActionContainer"
    )
    val contentColor by animateColorAsState(
        if (active && pressed) colors.onPrimaryContainer else if (active && focused) colors.onPrimary else foreground,
        tween(JetStreamAnimations.DurationFocus), label = "settingsActionContent"
    )
    val outline by animateColorAsState(
        if (active && focused) colors.onSurface else if (style == MaterialSettingsButtonStyle.Outlined && !selected) colors.outline else Color.Transparent,
        tween(JetStreamAnimations.DurationFocus), label = "settingsActionOutline"
    )
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = active,
        modifier = modifier.heightIn(min = 48.dp).focusProperties { canFocus = active }
            .tvTouchInput(active, onClick, onLongClick, interactions).semantics { this.selected = selected },
        shape = ButtonDefaults.shape(shape = shape),
        scale = ButtonDefaults.scale(focusedScale = focusedScale),
        colors = ButtonDefaults.colors(
            containerColor = background,
            contentColor = contentColor,
            focusedContainerColor = background,
            focusedContentColor = contentColor,
            pressedContainerColor = background,
            pressedContentColor = contentColor
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, outline), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, outline), shape = shape)
        ),
        contentPadding = contentPadding,
        interactionSource = interactions
    ) {
        CompositionLocalProvider(LocalContentColor provides androidx.tv.material3.LocalContentColor.current) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )) { content() }
        }
    }
}
