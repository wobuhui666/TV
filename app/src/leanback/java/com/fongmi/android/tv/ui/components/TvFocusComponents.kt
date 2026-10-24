package com.fongmi.android.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.fongmi.android.tv.ui.rex.RexTheme
import com.fongmi.android.tv.ui.theme.JetStreamAnimations
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.fongmi.android.tv.ui.theme.JetStreamThemeController

/** Neutral focus treatment shared by TV actions; state never changes measured geometry. */
object TvFocusStyle {
    private val light: Boolean
        get() {
            // These getters also style Views, but Compose callers must observe refreshes.
            JetStreamThemeController.version
            return RexTheme.isLight()
        }
    val Container get() = if (light) JetStreamPalette.colorScheme().surfaceContainer else Color(0xFF24262B)
    val SelectedContainer get() = if (light) JetStreamPalette.colorScheme().surfaceContainerHigh else Color(0xFF35383F)
    val FocusedContainer get() = if (light) JetStreamPalette.colorScheme().surfaceContainerHighest else Color(0xFF41454E)
    val PressedContainer get() = if (light) JetStreamPalette.colorScheme().outlineVariant else Color(0xFF505560)
    val Content get() = if (light) JetStreamPalette.colorScheme().onSurface else Color(0xFFF2F2F2)
    val FocusOutline get() = if (light) JetStreamPalette.colorScheme().outline else Color(0xFFC5C8D0)
    val Shape = RoundedCornerShape(10.dp)
}

/**
 * AndroidX TV Button owns D-pad center/Enter, long press, semantics and focus interaction.
 * Keep 6dp of outer space at clipped viewport edges for the focus border. Do not add a
 * second clickable/focusable modifier or nest focusable controls inside this button.
 */
@Composable
fun TvActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = TvFocusStyle.Shape,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    interactionSource: MutableInteractionSource? = null,
    focusedScale: Float = JetStreamAnimations.FocusScaleSmall,
    content: @Composable RowScope.() -> Unit
) {
    val active = enabled && LocalTvContentActive.current
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val background = animatedContainer(interactions, active, selected, TvFocusStyle.Container)
    val outline = animatedOutline(interactions, active)
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.heightIn(min = 40.dp).focusProperties { canFocus = active }
            .tvTouchInput(active, onClick, onLongClick, interactions),
        enabled = active,
        shape = ButtonDefaults.shape(shape = shape),
        scale = ButtonDefaults.scale(focusedScale = focusedScale),
        colors = ButtonDefaults.colors(
            containerColor = background,
            contentColor = TvFocusStyle.Content,
            focusedContainerColor = background,
            focusedContentColor = TvFocusStyle.Content,
            pressedContainerColor = background,
            pressedContentColor = TvFocusStyle.Content,
            disabledContainerColor = TvFocusStyle.Container.copy(alpha = 0.45f),
            disabledContentColor = TvFocusStyle.Content.copy(alpha = 0.38f)
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(2.dp, outline), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, outline), shape = shape),
            focusedDisabledBorder = Border.None
        ),
        contentPadding = contentPadding,
        interactionSource = interactions
    ) {
        // TV Material and Material3 have distinct text-style locals. Children use
        // Material3 Text, so bridge label metrics as well as the content color.
        CompositionLocalProvider(LocalContentColor provides androidx.tv.material3.LocalContentColor.current) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )) {
                content()
            }
        }
    }
}

/**
 * TV Material Surface for a single row/card action. Layout and padding belong to its content.
 * Place secondary actions alongside this surface in a non-focusable Row, never inside it.
 */
@Composable
fun TvFocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = TvFocusStyle.Shape,
    containerColor: Color = TvFocusStyle.Container,
    interactionSource: MutableInteractionSource? = null,
    // Full-width rows keep their bounds; compact navigation can opt into scaling.
    focusedScale: Float = 1f,
    touchEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val active = enabled && LocalTvContentActive.current
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val background = animatedContainer(interactions, active, selected, containerColor)
    val outline = animatedOutline(interactions, active)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.focusProperties { canFocus = active }
            .tvTouchInput(active && touchEnabled, onClick, onLongClick, interactions),
        enabled = active,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = background,
            contentColor = TvFocusStyle.Content,
            focusedContainerColor = background,
            focusedContentColor = TvFocusStyle.Content,
            pressedContainerColor = background,
            pressedContentColor = TvFocusStyle.Content,
            disabledContainerColor = containerColor.copy(alpha = 0.45f),
            disabledContentColor = TvFocusStyle.Content.copy(alpha = 0.38f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, outline), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, outline), shape = shape),
            focusedDisabledBorder = Border.None
        ),
        interactionSource = interactions
    ) {
        CompositionLocalProvider(LocalContentColor provides androidx.tv.material3.LocalContentColor.current) {
            content()
        }
    }
}

@Composable
private fun animatedContainer(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    selected: Boolean,
    containerColor: Color
): Color {
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val color by animateColorAsState(
        targetValue = when {
            !enabled -> containerColor.copy(alpha = 0.45f)
            pressed -> TvFocusStyle.PressedContainer
            focused -> TvFocusStyle.FocusedContainer
            selected -> TvFocusStyle.SelectedContainer
            else -> containerColor
        },
        animationSpec = tween(JetStreamAnimations.DurationFocus),
        label = "tvActionContainer"
    )
    return color
}

@Composable
private fun animatedOutline(interactionSource: MutableInteractionSource, enabled: Boolean): Color {
    val focused by interactionSource.collectIsFocusedAsState()
    val color by animateColorAsState(
        targetValue = if (focused && enabled) TvFocusStyle.FocusOutline else Color.Transparent,
        animationSpec = tween(JetStreamAnimations.DurationFocus),
        label = "tvActionOutline"
    )
    return color
}
