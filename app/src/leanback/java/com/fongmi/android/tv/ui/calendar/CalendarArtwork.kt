@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.fongmi.android.tv.ui.calendar

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.fongmi.android.tv.ui.components.TvFocusableSurface
import com.fongmi.android.tv.ui.library.DetailArtwork
import com.fongmi.android.tv.ui.theme.JetStreamAnimations
import com.fongmi.android.tv.ui.theme.RexTheme
import com.fongmi.android.tv.utils.ImgUtil
import kotlin.math.roundToInt

/** Keep the previous frame until new artwork is decoded, then fade two cached image layers. */
@Composable
internal fun CalendarBackdrop(url: String, motion: Boolean, modifier: Modifier) {
    val background = MaterialTheme.colorScheme.background
    val scrim = if (RexTheme.isLight) background else Color.Black
    var displayed by remember { mutableStateOf("") }
    var status by remember(url) {
        mutableStateOf(when (url) { "" -> "unavailable"; displayed -> "loaded"; else -> "loading" })
    }
    val fade = remember(url) { Animatable(if (url == displayed) 1f else 0f) }
    LaunchedEffect(url, status, motion) {
        if (status == "loaded") {
            if (motion && displayed.isNotEmpty() && displayed != url) fade.animateTo(1f, tween(350)) else fade.snapTo(1f)
            displayed = url
        } else if (status == "unavailable") displayed = ""
    }
    Box(modifier.background(background).testTag("calendarBackdrop").semantics {
        contentDescription = url
        stateDescription = status
    }) {
        // Keep the previous target alive during the fade. The incoming target becomes the
        // displayed one with the same key, so its decoded image never needs a second request.
        val layers = listOf(displayed, url).filter { it.isNotBlank() }.distinct()
        for (artwork in layers) key(artwork) {
            DetailArtwork(artwork, null, Modifier.fillMaxSize().graphicsLayer {
                alpha = if (artwork == displayed) 1f else fade.value
            }, onLoadResult = { loaded ->
                if (artwork == url) status = if (loaded) "loaded" else "unavailable"
            })
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            // Strong scrims keep foreground text readable over the brightest source images.
            val vertical = Brush.verticalGradient(
                0f to scrim.copy(alpha = .90f),
                .18f to scrim.copy(alpha = .90f),
                .48f to scrim.copy(alpha = .42f),
                .72f to scrim.copy(alpha = .82f),
                1f to scrim.copy(alpha = .98f)
            )
            val horizontal = Brush.horizontalGradient(
                0f to scrim.copy(alpha = .94f),
                .32f to scrim.copy(alpha = .86f),
                .56f to scrim.copy(alpha = .62f),
                1f to scrim.copy(alpha = .18f)
            )
            onDrawBehind { drawRect(vertical); drawRect(horizontal) }
        })
    }
}

@Composable
internal fun CalendarHero(preview: AiringCalendarPreview, motion: Boolean, modifier: Modifier,
    onLogoFailed: () -> Unit) {
    val foreground = MaterialTheme.colorScheme.onBackground
    var logoStatus by remember(preview.key, preview.logo) {
        mutableStateOf(if (preview.logo.isBlank()) "unavailable" else "loading")
    }
    val status = when (preview.airingStatus) {
        "aired" -> stringResource(R.string.calendar_status_aired)
        "today" -> stringResource(R.string.calendar_status_today)
        "upcoming" -> stringResource(R.string.calendar_status_upcoming)
        "weekly" -> stringResource(R.string.calendar_weekly)
        else -> ""
    }
    val chips = (listOf(status) + preview.metadata).filter(String::isNotBlank).distinct()
    Column(modifier.testTag("calendarPreview"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (preview.title.isNotBlank()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 42.dp).testTag("calendarLogo").semantics {
                stateDescription = logoStatus
            }) {
                if (preview.logo.isNotBlank() && logoStatus != "unavailable") {
                    DetailArtwork(preview.logo, null, Modifier.fillMaxWidth().height(42.dp), contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart, alphaRim = true, onLoadResult = { loaded ->
                            logoStatus = if (loaded) "loaded" else "unavailable"
                            if (!loaded) onLogoFailed()
                        })
                }
                if (logoStatus != "loaded") CalendarHeroTitle(preview.title, motion)
            }
            if (chips.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().testTag("calendarPreviewMetadata"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxLines = 2) {
                chips.forEachIndexed { index, chip ->
                    androidx.compose.foundation.layout.Row(Modifier.background(foreground.copy(alpha = .09f), RoundedCornerShape(7.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (chip == status) Box(Modifier.size(4.dp).background(foreground, CircleShape))
                        Text(chip, Modifier.testTag("calendarPreviewMetadataText:$index"),
                            color = foreground, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                    }
                }
            }
            if (preview.overview.isNotBlank()) Text(preview.overview,
                Modifier.fillMaxWidth().testTag("calendarPreviewOverview"), color = foreground.copy(alpha = .93f),
                fontSize = 14.sp, lineHeight = 18.sp, maxLines = if (motion) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CalendarHeroTitle(title: String, motion: Boolean) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
        platformStyle = PlatformTextStyle(includeFontPadding = false))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = with(density) { maxWidth.toPx() }
        // Do not remember fallback-font metrics: the resolver can finish loading MiSans later.
        val measured = measurer.measure(AnnotatedString(title), style, softWrap = false,
            maxLines = 1, overflow = TextOverflow.Visible).multiParagraph.maxIntrinsicWidth
        val fitted = (32f * (available / measured.coerceAtLeast(1f)).coerceAtMost(1f)).coerceAtLeast(24f)
        var corrected by remember(title, maxWidth, density.fontScale, motion) { mutableStateOf(32f) }
        val font = minOf(fitted, corrected).sp
        Text(title,
            Modifier.fillMaxWidth().testTag("calendarPreviewTitle").then(if (motion) Modifier.basicMarquee(
                iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 2500, velocity = 18.dp
            ) else Modifier),
            color = MaterialTheme.colorScheme.onBackground, style = style.copy(fontSize = font, lineHeight = (font.value * 1.13f).sp),
            maxLines = if (motion) 1 else 2, softWrap = !motion, overflow = TextOverflow.Clip,
            onTextLayout = { result ->
                if (!motion && (result.hasVisualOverflow || result.lineCount > 1) && font.value > 24f) {
                    corrected = (font.value - 1f).coerceAtLeast(24f)
                }
            })
    }
}

@Composable
internal fun CalendarMediaCard(item: AiringCalendarItem, landscape: Boolean, motion: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val foreground = MaterialTheme.colorScheme.onBackground
    val background = MaterialTheme.colorScheme.background
    val interactions = remember(item.key) { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused && motion) 1.06f else 1f,
        animationSpec = if (motion) JetStreamAnimations.ScaleSpring else snap(),
        label = "calendarCardScale"
    )
    val image = if (landscape) item.episodeStill.ifBlank { item.backdrop.ifBlank { item.poster } } else item.poster
    val width = if (landscape) 232.dp else 96.dp
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        TvFocusableSurface(onClick = onClick, focusedScale = 1f, interactionSource = interactions,
            modifier = modifier.fillMaxWidth()
                .semantics { contentDescription = item.title + if (item.subtitle.isBlank()) "" else " · ${item.subtitle}" }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (focused) 16.dp.toPx() else 0f
                    shape = RoundedCornerShape(12.dp)
                    clip = false
                },
            shape = RoundedCornerShape(12.dp), containerColor = Color.Transparent) {
            Box(Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9f else 2f / 3f).drawWithCache {
                val strokeWidth = 2.dp.toPx()
                val inset = strokeWidth / 2f
                val radius = (12.dp.toPx() - inset).coerceAtLeast(0f)
                onDrawWithContent {
                    drawContent()
                    // Draw after the artwork using the Surface's actual interaction.
                    if (focused) drawRoundRect(foreground, topLeft = Offset(inset, inset),
                        size = Size((size.width - strokeWidth).coerceAtLeast(0f), (size.height - strokeWidth).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(radius, radius), style = Stroke(strokeWidth))
                }
            }) {
                CalendarRailArtwork(image, width, if (landscape) 16f / 9f else 2f / 3f,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).testTag("calendarArtwork:${item.key}"))
                if (item.subtitle.isNotBlank()) Text(item.subtitle,
                    Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp), color = Color.White,
                    fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        Text(item.title, Modifier.fillMaxWidth().then(if (focused && motion) Modifier.basicMarquee(
            iterations = Int.MAX_VALUE, initialDelayMillis = 2200, repeatDelayMillis = 2200, velocity = 16.dp
        ) else Modifier).drawWithCache {
            val fade = Brush.horizontalGradient(.85f to Color.Transparent, 1f to background.copy(alpha = .3f))
            onDrawWithContent { drawContent(); if (!focused) drawRect(fade) }
        }.testTag("calendarCardTitle:${item.key}"), color = foreground, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** Keep rail artwork inside Compose's transform/clip tree alongside its focus border. */
@Composable
private fun CalendarRailArtwork(url: String, width: androidx.compose.ui.unit.Dp, aspect: Float, modifier: Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    val pixelWidth = with(density) { width.roundToPx().coerceAtLeast(1) }
    val pixelHeight = (pixelWidth / aspect).roundToInt().coerceAtLeast(1)
    var bitmap by remember(url, pixelWidth, pixelHeight) { mutableStateOf<Bitmap?>(null) }
    val manager = remember(view) { Glide.with(view) }
    DisposableEffect(manager, url, pixelWidth, pixelHeight) {
        var active = true
        val target = object : CustomTarget<Bitmap>(pixelWidth, pixelHeight) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (active) bitmap = resource
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (active) bitmap = null
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                bitmap = null
            }
        }
        if (url.isNotBlank()) {
            try {
                manager.asBitmap().load(ImgUtil.getUrl(url))
                    .override(pixelWidth, pixelHeight).downsample(DownsampleStrategy.AT_MOST)
                    .dontTransform().dontAnimate().disallowHardwareConfig().into(target)
            } catch (error: RuntimeException) {
                // Legacy source poster URLs can contain malformed header suffixes.
                bitmap = null
            }
        }
        onDispose {
            active = false
            bitmap = null
            manager.clear(target)
        }
    }
    val loaded = bitmap
    if (loaded == null) Box(modifier.background(Color(0xFF20232A)))
    else Image(remember(loaded) { loaded.asImageBitmap() }, contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Crop)
}
