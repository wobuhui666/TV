package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.fongmi.android.tv.ui.components.TvActionButton
import com.fongmi.android.tv.ui.theme.JetStreamTheme

class JetStreamVodDetailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface Listener {
        fun onWatch()
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
        WATCH,
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
    private var entryFocusToken by mutableStateOf(0L)
    private var logoLoadFailed by mutableStateOf(false)

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
        if (gainFocus) {
            normalizeSelectedAction()
            entryFocusToken++
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun restoreActionFocus() {
        if (!isShown || !isEnabled || !requestFocus()) return
        normalizeSelectedAction()
        // The saved Android focus may be the Compose host rather than its button.
        // Restore the remembered action after making the detail surface visible.
        entryFocusToken++
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
        val previousAction = selectedAction
        this.summaryEnabled = summaryEnabled
        this.keepSelected = keepSelected
        normalizeSelectedAction()
        if (previousAction != selectedAction && hasFocus()) entryFocusToken++
    }

    @Composable
    private fun DetailSurface() {
        Column(modifier = Modifier.fillMaxSize()) {
            TitleBlock()
            Spacer(Modifier.height(6.dp))
            MetadataRow()
            Spacer(Modifier.height(6.dp))
            PeopleBlock()
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(6.dp))
            ActionRow()
        }
    }

    @Composable
    private fun TitleBlock() {
        if (logoUrl.isNotEmpty() && !logoLoadFailed) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
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
                fontSize = 24.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (remark.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = remark.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun MetadataRow() {
        val items = listOf(year, type, area, tmdbRating, doubanRating, site).filter { it.isNotBlank() }
        if (items.isEmpty()) return
        Text(
            text = items.joinToString("  ·  "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun PeopleBlock() {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
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
                    textSize = 13f
                    includeFontPadding = false
                    applyJetStreamTypeface()
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
        val requesters = remember { List(4) { FocusRequester() } }
        LaunchedEffect(entryFocusToken) {
            if (entryFocusToken > 0L && hasFocus()) requesters[selectedAction].requestFocus()
        }
        fun actionModifier(index: Int, modifier: Modifier): Modifier = modifier
            .focusRequester(requesters[index])
            .onFocusChanged { if (it.isFocused) selectedAction = index }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (index <= 1 || specs.subList(1, index).none { it.enabled }) {
                            listener?.onFocusVideo()
                            true
                        } else false
                    }
                    Key.DirectionDown -> {
                        if (index > 0) {
                            listener?.onFocusList()
                            true
                        } else false
                    }
                    else -> false
                }
            }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(specs.first(), actionModifier(0, Modifier.fillMaxWidth())) {
                performAction(DetailAction.WATCH)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                specs.drop(1).forEachIndexed { index, spec ->
                    ActionButton(spec, actionModifier(index + 1, Modifier.weight(1f))) {
                        performAction(spec.action)
                    }
                }
            }
        }
    }

    @Composable
    private fun ActionButton(spec: ActionSpec, modifier: Modifier, onClick: () -> Unit) {
        val primary = spec.action == DetailAction.WATCH
        TvActionButton(
            onClick = onClick,
            modifier = modifier.height(40.dp),
            enabled = spec.enabled,
            selected = spec.selected,
            contentPadding = PaddingValues(horizontal = if (primary) 16.dp else 8.dp, vertical = 6.dp)
        ) {
            Icon(
                painter = painterResource(id = spec.icon),
                contentDescription = null,
                modifier = Modifier.requiredSize(if (primary) 20.dp else 16.dp)
            )
            Spacer(Modifier.width(if (primary) 8.dp else 5.dp))
            Text(
                text = spec.label,
                fontSize = if (primary) 15.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
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
            DetailAction.WATCH -> listener?.onWatch()
            DetailAction.SUMMARY -> listener?.onSummary()
            DetailAction.KEEP -> listener?.onKeep()
            DetailAction.CHANGE -> listener?.onChange()
        }
    }

    private fun actions(): List<ActionSpec> {
        return listOf(
            ActionSpec(DetailAction.WATCH, context.getString(R.string.playback_watch_fullscreen), R.drawable.ic_playback_fullscreen, true),
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
