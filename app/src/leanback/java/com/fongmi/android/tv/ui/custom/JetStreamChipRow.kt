package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.fongmi.android.tv.ui.theme.JetStreamAnimations
import com.fongmi.android.tv.ui.theme.JetStreamShapes
import com.fongmi.android.tv.ui.theme.JetStreamBorders
import com.fongmi.android.tv.ui.theme.JetStreamSpacing
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
    private var rowFocused by mutableStateOf(false)
    private var clickListener: ((Int) -> Unit)? = null
    private var longClickListener: ((Int) -> Unit)? = null
    private var centerPressed = false
    private var centerLongPressed = false

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isFocusable = true
        isFocusableInTouchMode = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // The host owns focus; each item already draws its own focus treatment.
            defaultFocusHighlightEnabled = false
        }
    }

    fun setItems(texts: List<String>, selected: Int) {
        items.clear()
        items.addAll(texts)
        selectedIndex = if (selected in items.indices) selected else -1
        focusedIndex = when {
            items.isEmpty() -> -1
            selectedIndex in items.indices -> selectedIndex
            else -> 0
        }
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
        rowFocused = gainFocus
        if (gainFocus) normalizeFocus()
        else {
            centerPressed = false
            centerLongPressed = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isCenterKey(event.keyCode)) return handleCenterKey(event)
        if (event.action == KeyEvent.ACTION_DOWN && handleKeyDown(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (isCenterKey(keyCode)) return handleCenterKey(event)
        return event.action == KeyEvent.ACTION_DOWN && handleKeyDown(keyCode) || super.onKeyPreIme(keyCode, event)
    }

    private fun isCenterKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    }

    // 与 JetStreamHomeNavView 一致：ACTION_UP 触发、isLongPress 走长按，避免长按 OK 连发点击。
    private fun handleCenterKey(event: KeyEvent): Boolean {
        normalizeFocus()
        if (focusedIndex !in items.indices) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                centerPressed = true
                // 消费 DOWN 后系统不会自动跟踪长按，需显式开启 isLongPress 才会置位
                event.startTracking()
            }
            if (event.isLongPress && !centerLongPressed && longClickListener != null) {
                centerLongPressed = true
                longClickChip(focusedIndex)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (!centerPressed) return true
            centerPressed = false
            if (centerLongPressed) centerLongPressed = false
            else if (!event.isCanceled) clickChip(focusedIndex)
            return true
        }
        return true
    }

    private fun handleKeyDown(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveFocus(-1)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveFocus(1)
            }
            KeyEvent.KEYCODE_DPAD_UP -> moveViewFocus(View.FOCUS_UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveViewFocus(View.FOCUS_DOWN)
            else -> false
        }
    }

    private fun moveFocus(step: Int): Boolean {
        if (items.isEmpty()) return false
        normalizeFocus()
        val next = (focusedIndex + step).coerceIn(0, items.size - 1)
        if (next == focusedIndex) return false
        focusedIndex = next
        return true
    }

    private fun moveViewFocus(direction: Int): Boolean {
        val next = focusSearch(direction)
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
        if (index !in items.indices) return
        focusedIndex = index
        requestFocus()
        clickListener?.invoke(index)
    }

    private fun longClickChip(index: Int) {
        if (index !in items.indices) return
        focusedIndex = index
        requestFocus()
        longClickListener?.invoke(index)
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            val listState = rememberLazyListState()

            LaunchedEffect(focusedIndex) {
                if (focusedIndex in 0 until items.size) {
                    listState.animateScrollToItem(focusedIndex)
                }
            }

            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown && handleKeyDown(event.nativeKeyEvent.keyCode)
                    },
                contentPadding = PaddingValues(horizontal = JetStreamSpacing.ExtraLarge),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(items) { index, text ->
                    Chip(
                        text = text,
                        focused = rowFocused && index == focusedIndex,
                        selected = index == selectedIndex,
                        onClick = { clickChip(index) },
                        onLongClick = longClickListener?.let { { longClickChip(index) } }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Chip(
        text: String,
        focused: Boolean,
        selected: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val scale by animateFloatAsState(
            if (focused) JetStreamAnimations.FocusScaleMedium else 1.0f,
            animationSpec = JetStreamAnimations.ScaleSpring,
            label = "chipScale"
        )
        val background by animateColorAsState(
            targetValue = when {
                focused -> MaterialTheme.colorScheme.primaryContainer
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "chipBackground"
        )
        val border by animateColorAsState(
            targetValue = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            animationSpec = JetStreamAnimations.ColorTween,
            label = "chipBorder"
        )
        val textColor by animateColorAsState(
            targetValue = when {
                focused -> MaterialTheme.colorScheme.onPrimaryContainer
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "chipText"
        )

        Box(
            modifier = Modifier
                .height(36.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(JetStreamShapes.Chip)
                .background(background)
                .then(
                    if (focused) Modifier.border(JetStreamBorders.Medium, border, JetStreamShapes.Chip)
                    else Modifier
                )
                .focusProperties { canFocus = false }
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = JetStreamSpacing.ChipHorizontalPadding, vertical = JetStreamSpacing.ChipVerticalPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}
