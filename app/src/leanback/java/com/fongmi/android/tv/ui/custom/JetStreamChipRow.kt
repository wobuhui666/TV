package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.components.TvActionButton
import com.fongmi.android.tv.ui.theme.JetStreamTheme

class JetStreamChipRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface OnChipClickListener {
        fun onChipClick(position: Int)
    }

    interface OnChipLongClickListener {
        fun onChipLongClick(position: Int)
    }

    private val items = mutableStateListOf<String>()
    private var selectedIndex by mutableIntStateOf(-1)
    private var focusedIndex by mutableIntStateOf(-1)
    private var entryFocusToken by mutableStateOf(0L)
    private var clickListener: ((Int) -> Unit)? = null
    private var longClickListener: ((Int) -> Unit)? = null

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setItems(texts: List<String>, selected: Int) {
        val previousFocus = focusedIndex
        items.clear()
        items.addAll(texts)
        selectedIndex = if (selected in items.indices) selected else -1
        focusedIndex = when {
            items.isEmpty() -> -1
            selectedIndex in items.indices -> selectedIndex
            else -> 0
        }
        if (focusedIndex != previousFocus && hasFocus()) entryFocusToken++
    }

    fun setItems(texts: List<String>) {
        setItems(texts, -1)
    }

    fun getSelectedPosition(): Int = selectedIndex

    fun getFocusedPosition(): Int = focusedIndex

    fun setSelectedPosition(position: Int) {
        selectedIndex = if (position in items.indices) position else -1
        setFocusedPosition(selectedIndex)
    }

    fun setFocusedPosition(position: Int) {
        focusedIndex = when {
            items.isEmpty() -> -1
            position in items.indices -> position
            focusedIndex in items.indices -> focusedIndex
            else -> 0
        }
        if (hasFocus()) entryFocusToken++
    }

    fun setNextFocusUp(id: Int) {
        nextFocusUpId = id
    }

    fun setNextFocusDown(id: Int) {
        nextFocusDownId = id
    }

    fun setOnChipClickListener(listener: (Int) -> Unit) {
        clickListener = listener
    }

    fun setOnChipClickListener(listener: OnChipClickListener) {
        clickListener = { pos -> listener.onChipClick(pos) }
    }

    fun setOnChipLongClickListener(listener: (Int) -> Unit) {
        longClickListener = listener
    }

    fun setOnChipLongClickListener(listener: OnChipLongClickListener) {
        longClickListener = { pos -> listener.onChipLongClick(pos) }
    }

    fun clearListeners() {
        clickListener = null
        longClickListener = null
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) {
            normalizeFocus()
            entryFocusToken++
        }
    }

    private fun moveViewFocus(direction: Int): Boolean {
        val targetId = if (direction == View.FOCUS_UP) nextFocusUpId else nextFocusDownId
        val explicitTarget = if (targetId != View.NO_ID) rootView.findViewById<View>(targetId) else null
        val next = explicitTarget ?: focusSearch(direction)
        return next != null && next !== this && next.isShown && next.isEnabled && next.requestFocus()
    }

    private fun normalizeFocus() {
        if (items.isEmpty()) {
            focusedIndex = -1
            return
        }
        if (focusedIndex in items.indices) return
        focusedIndex = if (selectedIndex in items.indices) selectedIndex else 0
    }

    private fun clickChip(index: Int) {
        if (index in items.indices) clickListener?.invoke(index)
    }

    private fun longClickChip(index: Int) {
        if (index in items.indices) longClickListener?.invoke(index)
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            val listState = rememberLazyListState()
            val snapshot = items.toList()
            val requesters = remember(snapshot) { List(snapshot.size) { FocusRequester() } }
            LaunchedEffect(entryFocusToken, snapshot) {
                val token = entryFocusToken
                val target = focusedIndex
                if (token > 0L && hasFocus() && target in snapshot.indices) {
                    listState.scrollToItem(target)
                    // Scrolling can suspend while the user leaves this View or new items arrive.
                    if (hasFocus() && entryFocusToken == token && items.toList() == snapshot) {
                        requesters[target].requestFocus()
                    }
                }
            }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxHeight().onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> moveViewFocus(View.FOCUS_UP)
                        KeyEvent.KEYCODE_DPAD_DOWN -> moveViewFocus(View.FOCUS_DOWN)
                        else -> false
                    }
                },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(snapshot) { index, text ->
                    TvActionButton(
                        onClick = { clickChip(index) },
                        onLongClick = longClickListener?.let { { longClickChip(index) } },
                        selected = index == selectedIndex,
                        modifier = Modifier.widthIn(max = 280.dp).focusRequester(requesters[index])
                            .onFocusChanged { if (it.isFocused) focusedIndex = index },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = text,
                            fontSize = 14.sp,
                            fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Medium,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
        }
    }
}
