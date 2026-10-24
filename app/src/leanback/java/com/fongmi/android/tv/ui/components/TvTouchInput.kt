package com.fongmi.android.tv.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput

/** Add pointer gestures to TV Material's existing focus target and remote-key handlers. */
@Composable
fun Modifier.tvTouchInput(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null
): Modifier {
    val requester = remember { FocusRequester() }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val interactions by rememberUpdatedState(interactionSource)
    return focusRequester(requester).pointerInput(enabled, onLongClick != null) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onPress = { offset ->
                val source = interactions
                val press = PressInteraction.Press(offset)
                source?.tryEmit(press)
                var released = false
                try {
                    released = tryAwaitRelease()
                } finally {
                    source?.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                }
            },
            onTap = {
                requester.requestFocus()
                click()
            },
            onLongPress = if (onLongClick != null) {
                {
                    requester.requestFocus()
                    longClick?.invoke()
                }
            } else null
        )
    }
}
