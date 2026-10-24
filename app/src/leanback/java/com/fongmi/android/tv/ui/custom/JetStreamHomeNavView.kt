package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.theme.JetStreamTheme
import com.fongmi.android.tv.ui.theme.JetStreamAnimations
import com.fongmi.android.tv.ui.theme.JetStreamShapes
import com.fongmi.android.tv.ui.theme.JetStreamBorders

class JetStreamHomeNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface Listener {
        fun onNavClick(key: String)
        fun onNavLongClick(key: String)
    }

    data class NavItem(
        val key: String,
        val text: String,
        val drawableRes: Int
    )

    var listener: Listener? = null
    private val items = mutableStateListOf<NavItem>()
    private var currentSelectedKey by mutableStateOf("")
    private var focusedIndex by mutableStateOf(0)
    private var navFocused by mutableStateOf(false)
    private var centerPressed = false
    private var centerLongPressed = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // The host owns focus; each item already draws its own focus treatment.
            defaultFocusHighlightEnabled = false
        }
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    fun setItems(newItems: List<NavItem>) {
        items.clear()
        items.addAll(newItems)
        focusedIndex = if (items.isEmpty()) -1 else focusedIndex.coerceIn(0, items.size - 1)
    }

    fun setSelectedKey(key: String) {
        currentSelectedKey = key
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0 && !navFocused) focusedIndex = index
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        navFocused = gainFocus
        if (gainFocus) normalizeFocus()
        else {
            centerPressed = false
            centerLongPressed = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (items.isEmpty()) return super.dispatchKeyEvent(event)
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (event.action == KeyEvent.ACTION_DOWN) moveFocus(-1) else super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.action == KeyEvent.ACTION_DOWN) moveFocus(1) else super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> handleCenterKey(event)
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun normalizeFocus() {
        if (items.isEmpty()) {
            focusedIndex = -1
            return
        }
        if (focusedIndex in items.indices) return
        val selected = items.indexOfFirst { it.key == currentSelectedKey }
        focusedIndex = if (selected >= 0) selected else 0
    }

    private fun moveFocus(step: Int): Boolean {
        if (items.isEmpty()) return false
        normalizeFocus()
        val next = (focusedIndex + step).coerceIn(0, items.size - 1)
        if (next == focusedIndex) return false
        focusedIndex = next
        return true
    }

    private fun handleCenterKey(event: KeyEvent): Boolean {
        normalizeFocus()
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) centerPressed = true
        if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress && !centerLongPressed) {
            centerLongPressed = true
            clickNav(focusedIndex, true)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (!centerPressed) return true
            centerPressed = false
            if (centerLongPressed) {
                centerLongPressed = false
            } else if (!event.isCanceled) {
                clickNav(focusedIndex, false)
            }
            return true
        }
        return true
    }

    private fun clickNav(index: Int, longClick: Boolean) {
        if (index !in items.indices) return
        focusedIndex = index
        requestFocus()
        val key = items[index].key
        if (longClick) listener?.onNavLongClick(key) else listener?.onNavClick(key)
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items.forEachIndexed { index, item ->
                    NavButton(
                        item = item,
                        selected = item.key == currentSelectedKey,
                        focused = navFocused && index == focusedIndex,
                        onClick = { clickNav(index, false) },
                        onLongClick = { clickNav(index, true) }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun NavButton(item: NavItem, selected: Boolean, focused: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
        val interactionSource = remember { MutableInteractionSource() }
        val scale by animateFloatAsState(
            if (focused) JetStreamAnimations.FocusScaleMedium else 1.0f,
            animationSpec = JetStreamAnimations.ScaleSpring,
            label = "navScale"
        )
        val background by animateColorAsState(
            targetValue = when {
                focused -> MaterialTheme.colorScheme.primaryContainer
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> Color.Transparent
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "navBackground"
        )
        val border by animateColorAsState(
            targetValue = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            animationSpec = JetStreamAnimations.ColorTween,
            label = "navBorder"
        )
        val contentColor by animateColorAsState(
            targetValue = when {
                focused -> MaterialTheme.colorScheme.onPrimaryContainer
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "navContent"
        )

        Row(
            modifier = Modifier
                .height(34.dp)
                .widthIn(min = 54.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(JetStreamShapes.Button)
                .background(background)
                .then(
                    if (focused) Modifier.border(
                        JetStreamBorders.Thin,
                        border,
                        JetStreamShapes.Button
                    )
                    else Modifier
                )
                .focusProperties { canFocus = false }
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconFor(item.drawableRes)),
                contentDescription = item.text,
                modifier = Modifier.size(19.dp),
                tint = contentColor
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.text,
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    companion object {
        @DrawableRes
        private fun iconFor(resId: Int): Int {
            return when (resId) {
                R.drawable.msr_home, R.drawable.msr_movie, R.drawable.msr_search,
                R.drawable.msr_settings, R.drawable.msr_live_tv, R.drawable.msr_bookmark_border,
                R.drawable.msr_cloud_upload -> resId
                R.drawable.ic_home_live -> R.drawable.msr_live_tv
                R.drawable.ic_home_search -> R.drawable.msr_search
                R.drawable.ic_home_keep -> R.drawable.msr_bookmark_border
                R.drawable.ic_home_push -> R.drawable.msr_cloud_upload
                R.drawable.ic_home_setting -> R.drawable.msr_settings
                R.drawable.ic_home_discover -> R.drawable.ic_home_discover
                else -> R.drawable.msr_movie
            }
        }
    }
}
