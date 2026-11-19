package com.fongmi.android.tv.ui.library

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

/** Activity-owned data/actions with a single lifecycle-aware TV Compose surface. */
class LibraryDetailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {
    interface Listener {
        fun onAction(action: String, key: String)
        fun onFocused(key: String)
    }

    private var listener: Listener? = null
    private var state by mutableStateOf(LibraryDetailState())
    private var themeVersion by mutableIntStateOf(0)
    private var focusRequest by mutableStateOf(DetailFocusRequest("play", 0))
    private var lastFocusedKey = "play"

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun setListener(value: Listener?) { listener = value }
    fun submit(value: LibraryDetailState.Builder) { submit(value.build()) }
    fun submit(value: LibraryDetailState) { state = value }
    fun focusedKey(): String = lastFocusedKey
    fun currentState(): LibraryDetailState = state
    fun refreshTheme() { themeVersion++ }

    fun focusKey(key: String) {
        if (key.isBlank()) return
        requestFocus()
        focusRequest = DetailFocusRequest(key, focusRequest.token + 1)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) focusRequest = DetailFocusRequest(lastFocusedKey, focusRequest.token + 1)
    }

    @Composable
    override fun Content() {
        LibraryDetailScreen(
            state = state,
            focusRequest = focusRequest,
            themeVersion = themeVersion,
            onAction = { action, key -> listener?.onAction(action, key) },
            onFocused = { key -> lastFocusedKey = key; listener?.onFocused(key) }
        )
    }
}

internal data class DetailFocusRequest(val key: String, val token: Int)
