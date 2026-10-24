package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.fongmi.android.tv.ui.components.TvSelectionIndicator
import com.fongmi.android.tv.ui.theme.JetStreamAnimations
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.components.TvFocusableSurface
import com.fongmi.android.tv.ui.theme.JetStreamTheme

class JetStreamHomeNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface Listener {
        fun onNavClick(key: String)
        fun onNavLongClick(key: String)
    }

    data class NavItem(val key: String, val text: String, val drawableRes: Int)

    var listener: Listener? = null
    private val items = mutableStateListOf<NavItem>()
    private val requesters = mutableMapOf<String, FocusRequester>()
    private var currentSelectedKey by mutableStateOf("")
    private var focusedKey by mutableStateOf("")
    private var pointerInputEnabled by mutableStateOf(true)

    init {
        // Retain the View focus entry used by HomeActivity and nextFocusUp. Once
        // entered, TV Material owns focus, D-pad/Enter and long press on each item.
        isFocusable = true
        isFocusableInTouchMode = true
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    fun setItems(newItems: List<NavItem>) {
        val keys = newItems.map { it.key }.toSet()
        requesters.keys.retainAll(keys)
        newItems.forEach { requesters.getOrPut(it.key) { FocusRequester() } }
        items.clear()
        items.addAll(newItems)
        if (focusedKey !in keys) focusedKey = newItems.firstOrNull()?.key.orEmpty()
    }

    fun setSelectedKey(key: String) {
        currentSelectedKey = key
        if (!hasFocus() && requesters.containsKey(key)) focusedKey = key
    }

    /** TV Material handles remote keys; optional poster-home categories also accept touch taps. */
    fun setTouchHandlingEnabled(enabled: Boolean) {
        pointerInputEnabled = enabled
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) post {
            // A delayed View/Compose hand-off must not reclaim focus after the
            // user has already moved elsewhere or entered a different child.
            if (isFocused && isAttachedToWindow && items.isNotEmpty()) {
                requesters[focusedKey]?.requestFocus()
            }
        }
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            val positions = remember { mutableStateMapOf<String, ComposeRect>() }
            val density = LocalDensity.current
            Box(Modifier.fillMaxHeight().padding(horizontal = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxHeight().focusGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEach { item ->
                        val selected = item.key == currentSelectedKey
                        TvFocusableSurface(
                            onClick = { listener?.onNavClick(item.key) },
                            onLongClick = { listener?.onNavLongClick(item.key) },
                            selected = selected,
                            focusedScale = JetStreamAnimations.FocusScaleSmall,
                            touchEnabled = pointerInputEnabled,
                            containerColor = Color.Transparent,
                            shape = RoundedCornerShape(21.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .onGloballyPositioned { positions[item.key] = it.boundsInParent() }
                                .focusRequester(requesters.getValue(item.key))
                                .onFocusChanged { if (it.isFocused) focusedKey = item.key }
                        ) {
                            // The surface lays its content out from the start, so a minimum width on
                            // the surface left the label off-centre inside the selected pill. Keep the
                            // minimum on the label box instead: the pill hugs it and the text centres.
                            Box(
                                Modifier.fillMaxHeight().widthIn(min = 62.dp).padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = item.text,
                                    fontSize = 15.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                positions[currentSelectedKey]?.let { bounds ->
                    TvSelectionIndicator(
                        left = with(density) { bounds.center.x.toDp() } - 8.dp,
                        top = with(density) { bounds.bottom.toDp() } - 6.dp
                    )
                }
            }
        }
    }
}
