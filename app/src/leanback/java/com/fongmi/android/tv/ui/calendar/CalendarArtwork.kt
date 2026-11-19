@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.fongmi.android.tv.ui.calendar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.components.TvFocusableSurface
import com.fongmi.android.tv.ui.library.DetailArtwork

/** Keep the previous frame until new artwork is decoded, then fade two cached image layers. */
@Composable
internal fun CalendarBackdrop(url: String, motion: Boolean, modifier: Modifier) {
    var displayed by remember { mutableStateOf("") }
    var status by remember(url) { mutableStateOf(if (url.isBlank()) "unavailable" else "loading") }
    LaunchedEffect(url) { if (url.isBlank()) displayed = "" }
    Box(modifier.background(Color(0xFF101114)).testTag("calendarBackdrop").semantics {
        contentDescription = url
        stateDescription = status
    }) {
        if (url.isNotBlank()) DetailArtwork(url, null, Modifier.fillMaxSize().alpha(0f),
            onLoadResult = { loaded ->
                status = if (loaded) "loaded" else "unavailable"
                displayed = if (loaded) url else ""
            })
        Crossfade(displayed, Modifier.fillMaxSize(), animationSpec = tween(if (motion) 350 else 0), label = "calendarBackdrop") {
            if (it.isNotBlank()) DetailArtwork(it, null, Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            // Strong scrims keep foreground text readable over the brightest source images.
            val vertical = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .90f),
                .18f to Color.Black.copy(alpha = .90f),
                .48f to Color.Black.copy(alpha = .42f),
                .72f to Color.Black.copy(alpha = .82f),
                1f to Color.Black.copy(alpha = .98f)
            )
            val horizontal = Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = .94f),
                .32f to Color.Black.copy(alpha = .86f),
                .56f to Color.Black.copy(alpha = .62f),
                1f to Color.Black.copy(alpha = .18f)
            )
            onDrawBehind { drawRect(vertical); drawRect(horizontal) }
        })
    }
}

@Composable
internal fun CalendarHero(preview: AiringCalendarPreview, motion: Boolean, modifier: Modifier,
    onLogoFailed: () -> Unit) {
    var logoStatus by remember(preview.key, preview.logo) {
        mutableStateOf(if (preview.logo.isBlank()) "unavailable" else "loading")
    }
    val status = when (preview.airingStatus) {
        "aired" -> stringResource(R.string.calendar_status_aired)
        "today" -> stringResource(R.string.calendar_status_today)
        "upcoming" -> stringResource(R.string.calendar_status_upcoming)
        else -> ""
    }
    val chips = (listOf(status) + preview.metadata).filter(String::isNotBlank).distinct()
    Column(modifier.testTag("calendarPreview"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (preview.title.isNotBlank()) {
            Box(Modifier.fillMaxWidth().height(if (motion) 42.dp else 58.dp).testTag("calendarLogo").semantics {
                stateDescription = logoStatus
            }) {
                if (preview.logo.isNotBlank() && logoStatus != "unavailable") {
                    DetailArtwork(preview.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
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
                    androidx.compose.foundation.layout.Row(Modifier.background(Color.White.copy(alpha = .13f), RoundedCornerShape(7.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (chip == status) Box(Modifier.size(4.dp).background(Color.White, CircleShape))
                        Text(chip, Modifier.testTag("calendarPreviewMetadataText:$index"),
                            color = Color.White, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                    }
                }
            }
            if (preview.overview.isNotBlank()) Text(preview.overview,
                Modifier.fillMaxWidth().testTag("calendarPreviewOverview"), color = Color.White.copy(alpha = .93f),
                fontSize = 14.sp, lineHeight = 18.sp, maxLines = if (motion) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CalendarHeroTitle(title: String, motion: Boolean) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = with(density) { maxWidth.toPx() }
        val measured = remember(title, style) { measurer.measure(AnnotatedString(title), style, maxLines = 1).size.width }
        val font = (32f * (available / measured.coerceAtLeast(1)).coerceAtMost(1f)).coerceAtLeast(24f).sp
        Text(title,
            Modifier.fillMaxWidth().testTag("calendarPreviewTitle").then(if (motion) Modifier.basicMarquee(
                iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 2500, velocity = 18.dp
            ) else Modifier),
            color = Color.White, style = style.copy(fontSize = font, lineHeight = (font.value * 1.13f).sp),
            maxLines = if (motion) 1 else 2, overflow = TextOverflow.Clip)
    }
}

@Composable
internal fun CalendarMediaCard(item: AiringCalendarItem, landscape: Boolean, motion: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    var focused by remember(item.key) { mutableStateOf(false) }
    val scale = if (motion) 1.06f else 1f
    val image = if (landscape) item.episodeStill.ifBlank { item.backdrop.ifBlank { item.poster } } else item.poster
    val width = if (landscape) 232.dp else 96.dp
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        TvFocusableSurface(onClick = onClick, focusedScale = scale,
            modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                .graphicsLayer { shadowElevation = if (focused) 16.dp.toPx() else 0f; shape = RoundedCornerShape(12.dp) },
            shape = RoundedCornerShape(12.dp), containerColor = Color.Transparent) {
            Box(Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9f else 2f / 3f)) {
                DetailArtwork(image, null, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                    .testTag("calendarArtwork:${item.key}").semantics {
                        contentDescription = image
                        stateDescription = "aspect=${if (landscape) "16:9" else "2:3"};focusedScale=${if (focused) scale else 1f}"
                    }, placeholderColor = Color(0xFF20232A))
                if (focused) Box(Modifier.fillMaxSize().border(2.dp, Color.White, RoundedCornerShape(12.dp)))
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
            val fade = Brush.horizontalGradient(.85f to Color.Transparent, 1f to Color(0xFF101114).copy(alpha = .3f))
            onDrawWithContent { drawContent(); if (!focused) drawRect(fade) }
        }, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Clip)
    }
}
