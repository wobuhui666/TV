package com.fongmi.android.tv.ui.calendar

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/** A lifecycle-owned Compose surface with no network or playback side effects. */
class AiringCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {
    interface Listener {
        fun onAction(action: String, key: String)
        fun onFocused(key: String)
    }

    private var listener: Listener? = null
    private var state by mutableStateOf(AiringCalendarState())
    private var themeVersion by mutableIntStateOf(0)
    private var focusRequest by mutableStateOf(CalendarFocusRequest("", 0))
    private var lastFocusedKey = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun setListener(value: Listener?) { listener = value }
    fun submit(value: AiringCalendarState.Builder) { submit(value.build()) }
    fun submit(value: AiringCalendarState) { state = value }
    fun currentState(): AiringCalendarState = state
    fun focusedKey(): String = lastFocusedKey
    fun refreshTheme() { themeVersion++ }

    fun focusKey(key: String) {
        if (key.isBlank()) return
        requestFocus()
        focusRequest = CalendarFocusRequest(key, focusRequest.token + 1)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) {
            val key = lastFocusedKey.ifBlank { "date:${state.selectedDate}" }
            if (state.selectedDate.isNotBlank()) focusRequest = CalendarFocusRequest(key, focusRequest.token + 1)
        }
    }

    @Composable
    override fun Content() {
        AiringCalendarScreen(state, focusRequest, themeVersion,
            onAction = { action, key -> listener?.onAction(action, key) },
            onFocused = { key -> lastFocusedKey = key; listener?.onFocused(key) })
    }
}

internal data class CalendarFocusRequest(val key: String, val token: Int)
