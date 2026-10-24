package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.components.JetStreamGlassCard
import com.fongmi.android.tv.ui.theme.JetStreamAnimations
import com.fongmi.android.tv.ui.theme.JetStreamShapes
import com.fongmi.android.tv.ui.theme.JetStreamSpacing
import com.fongmi.android.tv.ui.theme.JetStreamTheme

class JetStreamVodDetailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface Listener {
        fun onSummary()
        fun onKeep()
        fun onChange()
        fun onFocusVideo()
        fun onFocusList()
    }

    private data class ActionSpec(
        val action: DetailAction,
        val label: String,
        @param:DrawableRes val icon: Int,
        val enabled: Boolean,
        val selected: Boolean = false
    )

    private enum class DetailAction {
        SUMMARY,
        KEEP,
        CHANGE
    }

    private var listener: Listener? = null
    private var title by mutableStateOf("")
    private var logoUrl by mutableStateOf("")
    private var remark by mutableStateOf<CharSequence>("")
    private var tmdbRating by mutableStateOf<CharSequence>("")
    private var doubanRating by mutableStateOf<CharSequence>("")
    private var site by mutableStateOf<CharSequence>("")
    private var year by mutableStateOf<CharSequence>("")
    private var area by mutableStateOf<CharSequence>("")
    private var type by mutableStateOf<CharSequence>("")
    private var director by mutableStateOf<CharSequence>("")
    private var actor by mutableStateOf<CharSequence>("")
    private var summaryEnabled by mutableStateOf(false)
    private var keepSelected by mutableStateOf(false)
    private var selectedAction by mutableStateOf(0)
    private var detailFocused by mutableStateOf(false)
    private var logoLoadFailed by mutableStateOf(false)
    private var centerPressed = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            DetailSurface()
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        detailFocused = gainFocus
        if (gainFocus) normalizeSelectedAction()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return handleCenterKey(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (moveSelection(-1)) true
                else listener?.let {
                    it.onFocusVideo()
                    true
                } ?: super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveSelection(1) || super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                listener?.let {
                    it.onFocusList()
                    true
                } ?: super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_DPAD_UP -> super.dispatchKeyEvent(event)
            else -> super.dispatchKeyEvent(event)
        }
    }

    // ACTION_UP 触发，吞掉长按 repeat，避免焦点停在“收藏”上长按 OK 时反复开关收藏。
    private fun handleCenterKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) centerPressed = true
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (!centerPressed) return true
            centerPressed = false
            performSelectedAction()
            return true
        }
        return true
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setTitle(title: CharSequence?) {
        this.title = title?.toString().orEmpty()
    }

    fun setLogoUrl(url: CharSequence?) {
        val next = url?.toString().orEmpty()
        if (logoUrl != next) logoLoadFailed = false
        logoUrl = next
    }

    fun setMetadata(
        tmdbRating: CharSequence?,
        doubanRating: CharSequence?,
        site: CharSequence?,
        year: CharSequence?,
        area: CharSequence?,
        type: CharSequence?,
        director: CharSequence?,
        actor: CharSequence?,
        remark: CharSequence?
    ) {
        this.tmdbRating = tmdbRating ?: ""
        this.doubanRating = doubanRating ?: ""
        this.site = site ?: ""
        this.year = year ?: ""
        this.area = area ?: ""
        this.type = type ?: ""
        this.director = director ?: ""
        this.actor = actor ?: ""
        this.remark = remark ?: ""
    }

    fun setActions(summaryEnabled: Boolean, keepSelected: Boolean) {
        this.summaryEnabled = summaryEnabled
        this.keepSelected = keepSelected
        normalizeSelectedAction()
    }

    @Composable
    private fun DetailSurface() {
        JetStreamGlassCard(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = JetStreamSpacing.CardPaddingLarge, vertical = 16.dp)
            ) {
                TitleBlock()
                Spacer(Modifier.height(8.dp))
                MetadataRow()
                Spacer(Modifier.height(8.dp))
                PeopleBlock()
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(10.dp))
                ActionRow()
            }
        }
    }

    @Composable
    private fun TitleBlock() {
        if (logoUrl.isNotEmpty() && !logoLoadFailed) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                factory = { context ->
                    AppCompatImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_START
                    }
                },
                update = { image ->
                    val requestedLogo = logoUrl
                    if (image.tag == requestedLogo) return@AndroidView
                    image.tag = requestedLogo
                    Glide.with(image)
                        .load(requestedLogo)
                        .fitCenter()
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                if (image.tag == requestedLogo) logoLoadFailed = true
                                return false
                            }

                            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                if (image.tag == requestedLogo) logoLoadFailed = false
                                return false
                            }
                        })
                        .into(image)
                }
            )
        } else {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (remark.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = remark.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun MetadataRow() {
        val items = listOf(tmdbRating, doubanRating, site, year, area, type).filter { it.isNotBlank() }
        if (items.isEmpty()) return
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { MetadataChip(it.toString()) }
        }
    }

    @Composable
    private fun MetadataChip(text: String) {
        val colorScheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(colorScheme.secondaryContainer.copy(alpha = 0.72f))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = colorScheme.onSecondaryContainer,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun PeopleBlock() {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ClickableInfoText(director, 1)
            ClickableInfoText(actor, 1)
        }
    }

    @Composable
    private fun ClickableInfoText(text: CharSequence, maxLines: Int) {
        if (text.isBlank()) return
        val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
        val linkColor = MaterialTheme.colorScheme.primary.toArgb()
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                TextView(context).apply {
                    setTextColor(textColor)
                    setLinkTextColor(linkColor)
                    textSize = 15f
                    includeFontPadding = false
                    highlightColor = android.graphics.Color.TRANSPARENT
                    movementMethod = LinkMovementMethod.getInstance()
                    setLineSpacing(2f, 1.0f)
                }
            },
            update = { view ->
                view.maxLines = maxLines
                view.ellipsize = android.text.TextUtils.TruncateAt.END
                view.text = text
                view.setTextColor(textColor)
                view.setLinkTextColor(linkColor)
                view.movementMethod = if (text is Spanned) LinkMovementMethod.getInstance() else null
            }
        )
    }

    @Composable
    private fun ActionRow() {
        val specs = actions()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            specs.forEachIndexed { index, spec ->
                ActionButton(
                    spec = spec,
                    active = detailFocused && selectedAction == index,
                    onClick = {
                        selectedAction = index
                        requestFocus()
                        performAction(spec.action)
                    }
                )
            }
        }
    }

    @Composable
    private fun ActionButton(spec: ActionSpec, active: Boolean, onClick: () -> Unit) {
        val colorScheme = MaterialTheme.colorScheme
        val interactionSource = remember { MutableInteractionSource() }
        val scale by animateFloatAsState(
            if (active) JetStreamAnimations.FocusScaleMedium else 1.0f,
            animationSpec = JetStreamAnimations.ScaleSpring,
            label = "detailActionScale"
        )
        val background by animateColorAsState(
            targetValue = when {
                !spec.enabled -> colorScheme.surfaceVariant.copy(alpha = 0.42f)
                active -> colorScheme.primaryContainer
                spec.selected -> colorScheme.secondaryContainer
                else -> colorScheme.surfaceVariant.copy(alpha = 0.78f)
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "detailActionBackground"
        )
        val contentColor by animateColorAsState(
            targetValue = when {
                !spec.enabled -> colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                active -> colorScheme.onPrimaryContainer
                spec.selected -> colorScheme.onSecondaryContainer
                else -> colorScheme.onSurfaceVariant
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "detailActionContent"
        )
        Row(
            modifier = Modifier
                .requiredHeight(48.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(JetStreamShapes.Button)
                .background(background)
                .border(1.dp, if (active) colorScheme.primary else colorScheme.outlineVariant.copy(alpha = 0.36f), JetStreamShapes.Button)
                .focusProperties { canFocus = false }
                .clickable(
                    enabled = spec.enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(start = JetStreamSpacing.ButtonHorizontalPadding, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = spec.icon),
                contentDescription = spec.label,
                modifier = Modifier.requiredSize(20.dp),
                tint = contentColor
            )
            Spacer(Modifier.width(JetStreamSpacing.IconPadding))
            Text(
                text = spec.label,
                color = contentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }

    private fun performSelectedAction() {
        actions().getOrNull(selectedAction)?.let { performAction(it.action) }
    }

    private fun moveSelection(step: Int): Boolean {
        val specs = actions()
        var index = selectedAction + step
        while (index in specs.indices) {
            if (specs[index].enabled) {
                selectedAction = index
                return true
            }
            index += step
        }
        return false
    }

    private fun normalizeSelectedAction() {
        val specs = actions()
        selectedAction = selectedAction.coerceIn(0, specs.lastIndex)
        if (specs[selectedAction].enabled) return
        selectedAction = specs.indexOfFirst { it.enabled }.takeIf { it >= 0 } ?: 0
    }

    private fun performAction(action: DetailAction) {
        val spec = actions().firstOrNull { it.action == action } ?: return
        if (!spec.enabled) return
        when (spec.action) {
            DetailAction.SUMMARY -> listener?.onSummary()
            DetailAction.KEEP -> listener?.onKeep()
            DetailAction.CHANGE -> listener?.onChange()
        }
    }

    private fun actions(): List<ActionSpec> {
        return listOf(
            ActionSpec(DetailAction.SUMMARY, context.getString(R.string.detail_desc), R.drawable.msr_info, summaryEnabled),
            ActionSpec(
                action = DetailAction.KEEP,
                label = context.getString(R.string.keep),
                icon = if (keepSelected) R.drawable.msr_bookmark else R.drawable.msr_bookmark_border,
                enabled = true,
                selected = keepSelected
            ),
            ActionSpec(DetailAction.CHANGE, context.getString(R.string.play_change), R.drawable.msr_swap_horiz, true)
        )
    }
}
