package com.fongmi.android.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface

/** Neutral focus treatment shared by TV actions; state never changes measured geometry. */
object TvFocusStyle {
    val Container = Color(0xFF24262B)
    val SelectedContainer = Color(0xFF35383F)
    val FocusedContainer = Color(0xFF41454E)
    val PressedContainer = Color(0xFF505560)
    val Content = Color(0xFFF2F2F2)
    val FocusOutline = Color(0xFFC5C8D0)
    val Shape = RoundedCornerShape(10.dp)
}

/**
 * AndroidX TV Button owns D-pad center/Enter, long press, semantics and focus interaction.
 * Keep 4dp of outer space at clipped viewport edges for the focus border. Do not add a
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
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.heightIn(min = 40.dp).focusProperties { canFocus = enabled },
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) TvFocusStyle.SelectedContainer else TvFocusStyle.Container,
            contentColor = TvFocusStyle.Content,
            focusedContainerColor = TvFocusStyle.FocusedContainer,
            focusedContentColor = TvFocusStyle.Content,
            pressedContainerColor = TvFocusStyle.PressedContainer,
            pressedContentColor = TvFocusStyle.Content,
            disabledContainerColor = TvFocusStyle.Container.copy(alpha = 0.45f),
            disabledContentColor = TvFocusStyle.Content.copy(alpha = 0.38f)
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(BorderStroke(1.dp, TvFocusStyle.FocusOutline), shape = shape),
            focusedDisabledBorder = Border.None
        ),
        contentPadding = contentPadding,
        interactionSource = interactionSource
    ) {
        // Existing pages use Compose Material Text/Icon. Bridge only content color;
        // keep their established MiSans typography and application theme intact.
        CompositionLocalProvider(LocalContentColor provides androidx.tv.material3.LocalContentColor.current) {
            content()
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
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.focusProperties { canFocus = enabled },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) TvFocusStyle.SelectedContainer else containerColor,
            contentColor = TvFocusStyle.Content,
            focusedContainerColor = TvFocusStyle.FocusedContainer,
            focusedContentColor = TvFocusStyle.Content,
            pressedContainerColor = TvFocusStyle.PressedContainer,
            pressedContentColor = TvFocusStyle.Content,
            disabledContainerColor = containerColor.copy(alpha = 0.45f),
            disabledContentColor = TvFocusStyle.Content.copy(alpha = 0.38f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.dp, TvFocusStyle.FocusOutline), shape = shape),
            focusedDisabledBorder = Border.None
        ),
        interactionSource = interactionSource
    ) {
        CompositionLocalProvider(LocalContentColor provides androidx.tv.material3.LocalContentColor.current) {
            content()
        }
    }
}
