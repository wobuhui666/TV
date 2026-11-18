package com.fongmi.android.tv.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import androidx.compose.ui.platform.LocalView
import android.view.SoundEffectConstants
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.components.TvActionButton
import com.fongmi.android.tv.ui.components.TvFocusableSurface
import com.fongmi.android.tv.ui.components.tvTouchInput
import com.fongmi.android.tv.ui.rex.RexTheme
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.fongmi.android.tv.ui.theme.JetStreamTheme
import com.fongmi.android.tv.ui.theme.JetStreamThemeController
import com.fongmi.android.tv.ui.theme.JetStreamTypography
import kotlin.math.min

private const val HERO = "hero"
private const val EPISODES = "episodes"
private const val RESOURCES = "resources"
private const val INFORMATION = "information"
private const val CAST = "cast"
private const val GENRES = "genres"
private const val TRAILERS = "trailers"
private const val STILLS = "stills"
private const val SIMILAR = "similar"
private const val REFRESH = "refresh"
private val DetailCardShape = RoundedCornerShape(12.dp)

private class DetailFocusRegistry {
    val targets = mutableMapOf<String, FocusRequester>()
    val rows = mutableMapOf<String, LazyListState>()
    val lastFocused = mutableMapOf<String, String>()
    var focusJob: Job? = null
    var topInsetPx = 0
}

/** TV focus scrolling uses stable geometry; no focus animation changes a measured size. */
@OptIn(ExperimentalFoundationApi::class)
private class DetailBringIntoView(private val safePadding: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val safe = min(safePadding, containerSize * .12f)
        return when {
            offset < safe -> offset - safe
            offset + size > containerSize - safe -> offset + size - containerSize + safe
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun LibraryDetailScreen(
    state: LibraryDetailState,
    focusRequest: DetailFocusRequest,
    themeVersion: Int,
    onAction: (String, String) -> Unit,
    onFocused: (String) -> Unit
) {
    val hostView = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bringIntoView = remember(density) { with(density) { DetailBringIntoView(36.dp.toPx()) } }
    val themeToken = JetStreamThemeController.version + themeVersion
    val light = remember(themeToken) { RexTheme.isLight() }
    var dominantColor by remember(state.backdrop) { mutableStateOf<Int?>(null) }
    val artworkColors = remember(dominantColor, light, themeToken) {
        detailArtworkColors(dominantColor, light)
    }
    val baseScheme = remember(themeToken) { JetStreamPalette.colorScheme() }
    val colors = remember(baseScheme, artworkColors, light) {
        if (light) lightColorScheme(
            primary = artworkColors.accent, onPrimary = baseScheme.onPrimary,
            primaryContainer = baseScheme.primaryContainer, onPrimaryContainer = baseScheme.onPrimaryContainer,
            background = artworkColors.background, onBackground = baseScheme.onBackground,
            surface = artworkColors.background, onSurface = baseScheme.onSurface,
            surfaceVariant = baseScheme.surfaceVariant, onSurfaceVariant = baseScheme.onSurfaceVariant
        ) else darkColorScheme(
            primary = artworkColors.accent, onPrimary = baseScheme.onPrimary,
            primaryContainer = baseScheme.primaryContainer, onPrimaryContainer = baseScheme.onPrimaryContainer,
            background = artworkColors.background, onBackground = baseScheme.onBackground,
            surface = artworkColors.background, onSurface = baseScheme.onSurface,
            surfaceVariant = baseScheme.surfaceVariant, onSurfaceVariant = baseScheme.onSurfaceVariant
        )
    }
    val typography = remember { detailTypography() }
    val listState = rememberLazyListState()
    val registry = remember { DetailFocusRegistry() }
    val scope = rememberCoroutineScope()
    registry.topInsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.roundToPx() }
    val sections = remember(state) { detailSections(state) }
    val focusModifier: @Composable (String) -> Modifier = { key ->
        val requester = remember(key) { FocusRequester() }
        DisposableEffect(key) {
            registry.targets[key] = requester
            if (key == "seasonSelector") registry.targets["season"] = requester
            onDispose {
                registry.targets.remove(key)
                if (key == "seasonSelector") registry.targets.remove("season")
            }
        }
        Modifier.focusRequester(requester).onFocusChanged {
            if (it.isFocused) {
                detailFocusLocation(key, state, sections)?.takeIf { it.second >= 0 }?.let { location ->
                    registry.lastFocused[sections[location.first]] = key
                }
                onFocused(key)
            }
        }.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                val sound = when (event.key) {
                    Key.DirectionUp -> SoundEffectConstants.NAVIGATION_UP
                    Key.DirectionDown -> SoundEffectConstants.NAVIGATION_DOWN
                    Key.DirectionLeft -> SoundEffectConstants.NAVIGATION_LEFT
                    Key.DirectionRight -> SoundEffectConstants.NAVIGATION_RIGHT
                    else -> null
                }
                sound?.let(hostView::playSoundEffect)
            }
            val down = event.key == Key.DirectionDown
            val up = event.key == Key.DirectionUp
            if (!down && !up) false else {
                val next = detailVerticalTarget(key, down, state, sections, registry)
                if (next == null) false else {
                    if (event.type == KeyEventType.KeyDown) {
                        registry.focusJob?.cancel()
                        registry.focusJob = scope.launch { focusDetailTarget(next, state, sections, listState, registry) }
                    }
                    true
                }
            }
        }.testTag(key)
    }
    var handledFocusToken by remember(state.identity) { mutableIntStateOf(-1) }
    LaunchedEffect(state.identity, focusRequest.token, state.resources.size, state.episodes.size) {
        if (handledFocusToken == focusRequest.token) return@LaunchedEffect
        val key = if (focusRequest.key == "play" && !state.playEnabled) "source" else focusRequest.key
        if (focusDetailTarget(key, state, sections, listState, registry)) handledFocusToken = focusRequest.token
    }
    LaunchedEffect(state.identity) {
        snapshotFlow { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > registry.topInsetPx }
            .filter { it }.take(1).collect { onAction("loadExtras", "") }
    }
    JetStreamTheme {
        MaterialTheme(colorScheme = colors, typography = typography) {
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides bringIntoView,
                androidx.tv.material3.LocalContentColor provides colors.onSurface
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize().background(colors.background)
                        .semantics { testTagsAsResourceId = true }
                ) {
                    val heroHeight = (maxHeight * .64f).coerceIn(320.dp, 430.dp)
                    val heroPx = with(androidx.compose.ui.platform.LocalDensity.current) { heroHeight.toPx() }
                    HeroBackdropLayer(
                        backdropUrl = state.backdrop,
                        backgroundColor = colors.background,
                        scrollFraction = {
                            if (listState.firstVisibleItemIndex > 0) 1f
                            else (listState.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxSize().testTag("backdrop").semantics {
                            stateDescription = if (dominantColor == null) "loading" else "loaded"
                        },
                        onDominantColor = { dominantColor = it }
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("detailContent"),
                        contentPadding = PaddingValues(bottom = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        items(sections, key = { it }, contentType = { it }) { section ->
                            when (section) {
                                HERO -> HeroInformation(state, heroHeight, focusModifier, onAction)
                                EPISODES -> EpisodeSection(state, registry, focusModifier, onAction)
                                RESOURCES -> ResourceSection(state, registry, focusModifier, onAction)
                                INFORMATION -> MediaInformationSection(state, focusModifier, onAction)
                                CAST -> PeopleSection(state.cast, registry, focusModifier, onAction)
                                GENRES -> GenreSection(state.genres, registry, focusModifier, onAction)
                                TRAILERS -> ArtworkSection(TRAILERS, stringResource(R.string.library_trailers),
                                    state.trailers, registry, focusModifier, onAction)
                                STILLS -> ArtworkSection(STILLS, stringResource(R.string.library_images),
                                    state.stills, registry, focusModifier, onAction)
                                SIMILAR -> SimilarSection(state.similar, registry, focusModifier, onAction)
                                REFRESH -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    TvActionButton(onClick = { onAction("refresh", "refresh") },
                                        modifier = focusModifier("refresh"), shape = CircleShape) {
                                        Icon(painterResource(R.drawable.msr_refresh), null, Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.setting_refresh), style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun detailSections(state: LibraryDetailState) = buildList {
    add(HERO)
    if (!state.movie || state.episodes.isNotEmpty() || state.episodesLoading) add(EPISODES)
    add(RESOURCES)
    if (state.mediaInformation.isNotBlank() || state.productionLogos.isNotEmpty()) add(INFORMATION)
    if (state.cast.isNotEmpty()) add(CAST)
    if (state.genres.isNotEmpty()) add(GENRES)
    if (state.trailers.isNotEmpty()) add(TRAILERS)
    if (state.stills.isNotEmpty()) add(STILLS)
    if (state.similar.isNotEmpty()) { add(SIMILAR); add(REFRESH) }
}

private fun detailFocusLocation(key: String, state: LibraryDetailState, sections: List<String>): Pair<Int, Int>? {
    val fixed = when (key) {
        "play", "source", "keep", "more", "summary" -> HERO
        "season", "seasonSelector", "episodePicker", "episodesLoading" -> EPISODES
        "resourceStatus" -> RESOURCES
        "mediaInformation" -> INFORMATION.takeIf { state.mediaInformation.isNotBlank() }
        "refresh" -> REFRESH
        else -> null
    }
    if (fixed != null) return sections.indexOf(fixed).takeIf { it >= 0 }?.let { it to -1 }
    val lists = listOf(EPISODES to state.episodes, RESOURCES to state.resources, CAST to state.cast,
        TRAILERS to state.trailers, STILLS to state.stills, SIMILAR to state.similar)
    for ((section, values) in lists) {
        val index = values.indexOfFirst { it.key == key }
        if (index >= 0) return sections.indexOf(section).takeIf { it >= 0 }?.let { it to index }
    }
    if (state.genres.any { it.key == key }) return sections.indexOf(GENRES).takeIf { it >= 0 }?.let { it to state.genres.indexOfFirst { it.key == key } }
    if (state.ranges.any { it.key == key }) return sections.indexOf(EPISODES).takeIf { it >= 0 }?.let { it to -1 }
    if (state.resourceFilters.any { it.key == key }) return sections.indexOf(RESOURCES).takeIf { it >= 0 }?.let { it to -1 }
    return null
}

private suspend fun focusDetailTarget(key: String, state: LibraryDetailState, sections: List<String>,
    listState: LazyListState, registry: DetailFocusRegistry): Boolean {
    val location = detailFocusLocation(key, state, sections) ?: return false
    // Keep the hero in place when moving among its controls. Other sections align safely.
    if (location.first == 0) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) listState.scrollToItem(0)
    } else if (listState.layoutInfo.visibleItemsInfo.none { it.key == sections[location.first] }) {
        listState.scrollToItem(location.first, -registry.topInsetPx)
    }
    repeat(8) {
        withFrameNanos { }
        val row = registry.rows[sections[location.first]]
        val rangeIndex = state.ranges.indexOfFirst { it.key == key }
        val activeRow = if (rangeIndex >= 0) registry.rows["episodeRanges"] else row
        val itemIndex = if (rangeIndex >= 0) rangeIndex else location.second
        if (activeRow != null && itemIndex >= 0 && activeRow.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) {
            activeRow.scrollToItem(itemIndex)
        }
        val target = registry.targets[key]
        if (target != null && runCatching { target.requestFocus() }.isSuccess) return true
    }
    return false
}

private fun detailVerticalTarget(key: String, down: Boolean, state: LibraryDetailState,
    sections: List<String>, registry: DetailFocusRegistry): String? {
    val selectedEpisode = state.episodes.firstOrNull { it.selected }?.key ?: state.episodes.firstOrNull()?.key
    val rememberedEpisode = registry.lastFocused[EPISODES]?.takeIf { focused -> state.episodes.any { it.key == focused } }
        ?: selectedEpisode
    val selectedRange = state.ranges.firstOrNull { it.selected }?.key ?: state.ranges.firstOrNull()?.key
    val resourceCard = registry.lastFocused[RESOURCES]?.takeIf { focused -> state.resources.any { it.key == focused } }
        ?: state.resources.firstOrNull()?.key ?: "resourceStatus"
    val resourceFilter = state.resourceFilters.firstOrNull { it.selected }?.key
        ?: state.resourceFilters.firstOrNull { it.key == "filter:all" }?.key
        ?: state.resourceFilters.firstOrNull()?.key
    val resourceEntry = resourceFilter ?: resourceCard
    val aboveResources = selectedRange ?: rememberedEpisode
        ?: if (sections.contains(EPISODES)) "seasonSelector" else "source"
    val firstEpisode = selectedEpisode ?: if (sections.contains(EPISODES)) "seasonSelector" else resourceEntry

    if (key == "play") return if (down) "source" else null
    if (key in listOf("source", "keep", "more")) return if (down) firstEpisode else "play"
    if (key == "summary") return if (down) firstEpisode else null
    if (key == "season" || key == "seasonSelector" || key == "episodePicker") {
        return if (down) selectedEpisode ?: selectedRange ?: resourceEntry else "play"
    }
    if (state.episodes.any { it.key == key }) return if (down) selectedRange ?: resourceEntry else "seasonSelector"
    if (state.ranges.any { it.key == key }) return if (down) resourceEntry else rememberedEpisode ?: "seasonSelector"
    if (state.resourceFilters.any { it.key == key }) return if (down) resourceCard else aboveResources
    if (!down && (key == "resourceStatus" || state.resources.any { it.key == key })) {
        return resourceFilter ?: aboveResources
    }

    val location = detailFocusLocation(key, state, sections) ?: return null
    var nextIndex = location.first + if (down) 1 else -1
    while (nextIndex in sections.indices) {
        val section = sections[nextIndex]
        val items = when (section) {
            EPISODES -> state.episodes
            RESOURCES -> state.resources
            CAST -> state.cast
            TRAILERS -> state.trailers
            STILLS -> state.stills
            SIMILAR -> state.similar
            else -> emptyList()
        }
        val target = when (section) {
            HERO -> if (state.playEnabled) "play" else "source"
            EPISODES -> selectedRange ?: rememberedEpisode ?: "seasonSelector"
            RESOURCES -> resourceEntry
            INFORMATION -> "mediaInformation".takeIf { state.mediaInformation.isNotBlank() }
            GENRES -> registry.lastFocused[GENRES]?.takeIf { focused -> state.genres.any { it.key == focused } }
                ?: state.genres.firstOrNull()?.key
            REFRESH -> "refresh"
            else -> registry.lastFocused[section]?.takeIf { focused -> items.any { it.key == focused } }
                ?: items.firstOrNull()?.key
        }
        if (target != null) return target
        // A production-logo row is informational; D-pad continues to the next real action.
        nextIndex += if (down) 1 else -1
    }
    return null
}

@Composable
private fun HeroInformation(
    state: LibraryDetailState,
    height: androidx.compose.ui.unit.Dp,
    focus: @Composable (String) -> Modifier,
    onAction: (String, String) -> Unit
) {
    var logoStatus by remember(state.logo) { mutableStateOf(if (state.logo.isBlank()) "unavailable" else "loading") }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = height).padding(start = 48.dp, end = 48.dp, top = 36.dp, bottom = 24.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        Column(Modifier.weight(.65f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 96.dp).testTag("logo").semantics { stateDescription = logoStatus }) {
                if (state.logo.isNotBlank() && logoStatus != "unavailable") {
                    DetailArtwork(state.logo, null, Modifier.widthIn(max = 440.dp).fillMaxWidth().height(80.dp),
                        contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, alphaRim = true,
                        onLoadResult = { loaded ->
                            logoStatus = if (loaded) "loaded" else "unavailable"
                            if (!loaded) onAction("logoFailed", state.logo)
                        })
                }
                if (logoStatus != "loaded") {
                    Text(state.title, style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.metadata.isNotBlank()) Text(state.metadata, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .74f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.ratings.isNotEmpty() || state.formats.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    state.ratings.take(4).forEach { badge ->
                        Row(Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(badge.label, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                            Text(badge.value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    state.formats.take(3).forEach { format ->
                        Text(format, Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (state.seriesStatus.isNotBlank()) Text(state.seriesStatus, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .74f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.overview.isNotBlank()) {
                TvFocusableSurface(onClick = { onAction("summary", "summary") },
                    modifier = focus("summary").fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp), containerColor = Color.Transparent, focusedScale = 1f) {
                    Text(state.overview, Modifier.padding(4.dp), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .84f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.weight(.35f), contentAlignment = Alignment.BottomEnd) {
            Column(Modifier.width(220.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PrimaryPlayButton(state, focus("play")) { onAction("play", "play") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (state.followable) 8.dp else 12.dp, Alignment.CenterHorizontally)) {
                    DetailIconAction("source", stringResource(R.string.library_sources_action), R.drawable.msr_swap_horiz,
                        focus("source"), onAction = onAction)
                    if (state.followable) DetailFollowAction(state.kept, focus("keep"), onAction)
                    else DetailIconAction("keep", stringResource(R.string.keep), if (state.kept) R.drawable.msr_favorite else R.drawable.msr_favorite_border,
                        focus("keep"), selected = state.kept, onAction = onAction)
                    if (state.showMoreAction) DetailIconAction("more", stringResource(R.string.library_more),
                        R.drawable.msr_info, focus("more"), onAction = onAction)
                }
            }
        }
    }
}

@Composable
private fun PrimaryPlayButton(state: LibraryDetailState, modifier: Modifier, onClick: () -> Unit) {
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Button(onClick = onClick, enabled = state.playEnabled,
        modifier = modifier.width(220.dp).height(52.dp)
            .tvTouchInput(state.playEnabled, onClick, null, interactions),
        shape = ButtonDefaults.shape(shape = CircleShape),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = .85f), contentColor = Color.Black,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f)),
        border = ButtonDefaults.border(border = Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = CircleShape)),
        glow = ButtonDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = .22f), 8.dp)),
        interactionSource = interactions,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (state.selecting) R.drawable.msr_pause else R.drawable.msr_play_arrow), null, Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.playLabel.ifBlank { stringResource(R.string.library_play) },
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.playHint.isNotBlank()) Text(state.playHint,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DetailIconAction(key: String, label: String, icon: Int, modifier: Modifier,
    selected: Boolean = false, onAction: (String, String) -> Unit) {
    TvActionButton(onClick = { onAction(key, key) }, modifier = modifier.size(44.dp).semantics { contentDescription = label; this.selected = selected },
        selected = selected, shape = CircleShape, contentPadding = PaddingValues(10.dp), focusedScale = 1.04f) {
        Icon(painterResource(icon), null, Modifier.size(22.dp))
    }
}

@Composable
private fun DetailFollowAction(selected: Boolean, modifier: Modifier, onAction: (String, String) -> Unit) {
    val label = stringResource(if (selected) R.string.library_detail_following else R.string.library_detail_follow)
    TvActionButton(onClick = { onAction("keep", "keep") },
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
            this.selected = selected
        }.width(116.dp).height(44.dp),
        selected = selected, shape = CircleShape, contentPadding = PaddingValues(horizontal = 12.dp), focusedScale = 1.04f) {
        Icon(painterResource(if (selected) R.drawable.msr_favorite else R.drawable.msr_favorite_border), null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, modifier = Modifier.testTag("followLabel"), style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .92f))
}

@Composable
private fun rememberDetailRow(key: String, registry: DetailFocusRegistry): LazyListState {
    val state = rememberLazyListState()
    DisposableEffect(key) { registry.rows[key] = state; onDispose { registry.rows.remove(key) } }
    return state
}

@Composable
private fun EpisodeSection(state: LibraryDetailState, registry: DetailFocusRegistry,
    focus: @Composable (String) -> Modifier, onAction: (String, String) -> Unit) {
    val row = rememberDetailRow(EPISODES, registry)
    val headerStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
    SectionColumn {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.library_episodes), style = headerStyle, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .92f))
            TvActionButton(onClick = { onAction("season", "seasonSelector") }, modifier = focus("seasonSelector").height(44.dp),
                shape = CircleShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                Text(state.seasonLabel.ifBlank { stringResource(R.string.library_episodes) }, style = headerStyle)
                Spacer(Modifier.width(6.dp))
                Icon(painterResource(R.drawable.msr_expand_more), null, Modifier.size(18.dp))
            }
            TvActionButton(onClick = { onAction("episodePicker", "episodePicker") }, modifier = focus("episodePicker").height(44.dp),
                shape = CircleShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.library_all_episodes), style = headerStyle)
            }
            Spacer(Modifier.weight(1f))
            if (state.episodeStatus.isNotBlank()) Text(state.episodeStatus,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        }
        if (state.episodesLoading) {
            Text(stringResource(R.string.library_episodes_loading), Modifier.testTag("episodesLoading").padding(vertical = 20.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (state.episodes.isEmpty()) {
            Text(state.episodeStatus.ifBlank { stringResource(R.string.library_episodes_empty) }, Modifier.padding(vertical = 20.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(state = row, modifier = Modifier.fillMaxWidth().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp)) {
                items(state.episodes, key = { it.key }, contentType = { "episode" }) { item ->
                    EpisodeCard(item, focus(item.key).width(240.dp)) { onAction("episode", item.key) }
                }
            }
        }
        if (state.ranges.isNotEmpty()) {
            val rangeRow = rememberDetailRow("episodeRanges", registry)
            LazyRow(state = rangeRow, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
                items(state.ranges, key = { it.key }) { range ->
                    TvActionButton(onClick = { onAction("range", range.key) }, modifier = focus(range.key).semantics { selected = range.selected },
                        selected = range.selected, shape = CircleShape) {
                        Text(range.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(item: LibraryDetailItem, modifier: Modifier, onAction: () -> Unit) {
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val onClick = { if (item.enabled) onAction() }
    Card(onClick = onClick,
        modifier = modifier.tvTouchInput(true, onClick, null, interactions).semantics {
            contentDescription = listOf(item.title, item.airDate, item.subtitle, item.badge)
                .filter { it.isNotBlank() }.joinToString(" · ")
            selected = item.selected
        },
        shape = CardDefaults.shape(shape = RoundedCornerShape(0.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        colors = CardDefaults.colors(containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
            pressedContainerColor = Color.Transparent),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        interactionSource = interactions
    ) {
        EpisodeArtwork(item, focused)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (item.airDate.isNotBlank()) Text(item.airDate, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (item.title.isNotBlank()) Text(item.title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.badge.isNotBlank()) Text(item.badge, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.overview.isNotBlank()) Text(item.overview,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EpisodeArtwork(item: LibraryDetailItem, focused: Boolean) {
    val outline = when {
        focused -> MaterialTheme.colorScheme.primary
        item.selected -> MaterialTheme.colorScheme.primary.copy(alpha = .45f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .1f)
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(DetailCardShape)
        .border(BorderStroke(if (focused) 2.dp else 1.dp, outline), DetailCardShape)) {
        if (item.image.isNotBlank()) {
            DetailArtwork(item.image, null, Modifier.fillMaxSize(), placeholderColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.msr_movie), null, Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
            }
        }
        if (item.progress > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(item.progress.coerceIn(0f, 1f))
            .height(3.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun ResourceSection(state: LibraryDetailState, registry: DetailFocusRegistry,
    focus: @Composable (String) -> Modifier, onAction: (String, String) -> Unit) {
    val row = rememberDetailRow(RESOURCES, registry)
    SectionColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.library_resources))
            state.resourceFilters.forEach { choice ->
                TvActionButton(onClick = { onAction("resourceFilter", choice.key) }, modifier = focus(choice.key).semantics { selected = choice.selected },
                    selected = choice.selected, shape = CircleShape) { Text(choice.label, style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (state.resources.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 156.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .055f), DetailCardShape)) {
                Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.msr_movie), null, Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
                    Text(state.resourceStatus.ifBlank { stringResource(R.string.library_no_resources) },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TvActionButton(onClick = { onAction("keep", "resourceStatus") },
                        modifier = focus("resourceStatus").semantics { selected = state.kept },
                        selected = state.kept, shape = CircleShape) {
                        Icon(painterResource(if (state.kept) R.drawable.msr_favorite else R.drawable.msr_favorite_border), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.followable) {
                            if (state.kept) R.string.library_detail_following else R.string.library_detail_follow
                        } else if (state.kept) R.string.library_detail_kept else R.string.library_detail_save), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else {
            LazyRow(state = row, modifier = Modifier.fillMaxWidth().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp)) {
                items(state.resources, key = { it.key }, contentType = { "resource" }) { item ->
                    DetailCard(item, focus(item.key).width(240.dp).heightIn(min = 100.dp),
                        onAction = { onAction("resource", item.key) },
                        onLongAction = { onAction("resourceDetails", item.key) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.badge.isNotBlank()) Text(item.badge, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaInformationSection(state: LibraryDetailState, focus: @Composable (String) -> Modifier,
    onAction: (String, String) -> Unit) {
    SectionColumn {
        if (state.mediaInformation.isNotBlank()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TvActionButton(onClick = { onAction("mediaInformation", "mediaInformation") }, modifier = focus("mediaInformation"), shape = CircleShape) {
                Icon(painterResource(R.drawable.msr_info), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.library_detail_media_information), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (state.productionLogos.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            state.productionLogos.take(5).forEach { url ->
                DetailArtwork(url, null, Modifier.height(28.dp).width(112.dp).padding(horizontal = 12.dp),
                    contentScale = ContentScale.Fit, alphaRim = true)
            }
        }
    }
}

@Composable
private fun PeopleSection(values: List<LibraryDetailItem>, registry: DetailFocusRegistry,
    focus: @Composable (String) -> Modifier, onAction: (String, String) -> Unit) {
    val row = rememberDetailRow(CAST, registry)
    SectionColumn {
        SectionTitle(stringResource(R.string.library_cast))
        LazyRow(state = row, modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(8.dp)) {
            items(values, key = { it.key }, contentType = { "person" }) { item ->
                Column(Modifier.width(104.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    TvFocusableSurface(onClick = { onAction("cast", item.key) },
                        modifier = focus(item.key).size(76.dp).semantics {
                            contentDescription = listOf(item.title, item.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
                        }, containerColor = Color.Transparent, shape = CircleShape, focusedScale = 1.08f) {
                        DetailArtwork(item.image, null, Modifier.fillMaxSize().clip(CircleShape),
                            placeholderColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                    Text(item.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun GenreSection(values: List<LibraryDetailChoice>, registry: DetailFocusRegistry,
    focus: @Composable (String) -> Modifier, onAction: (String, String) -> Unit) {
    val row = rememberDetailRow(GENRES, registry)
    SectionColumn {
        SectionTitle(stringResource(R.string.library_genres))
        LazyRow(state = row, modifier = Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(6.dp)) {
            items(values, key = { it.key }) { item ->
                TvActionButton(onClick = { onAction("genre", item.key) }, modifier = focus(item.key), shape = CircleShape) {
                    Text(item.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ArtworkSection(section: String, title: String, values: List<LibraryDetailItem>, registry: DetailFocusRegistry,
    focus: @Composable (String) -> Modifier, onAction: (String, String) -> Unit) {
    val row = rememberDetailRow(section, registry)
    SectionColumn {
        SectionTitle(title)
        LazyRow(state = row, modifier = Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(8.dp)) {
            items(values, key = { it.key }, contentType = { section }) { item ->
                DetailCard(item, focus(item.key).width(300.dp), onAction = { onAction(if (section == TRAILERS) "trailer" else "still", item.key) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        DetailArtwork(item.image, null, Modifier.fillMaxSize(), placeholderColor = MaterialTheme.colorScheme.surfaceVariant)
                        if (section == TRAILERS) Box(Modifier.align(Alignment.Center).size(48.dp)
                            .background(Color.Black.copy(alpha = .45f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.msr_play_arrow), null, Modifier.size(30.dp), tint = Color.White)
                        }
                    }
                    if (section == TRAILERS) Text(item.title, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SimilarSection(values: List<LibraryDetailItem>, registry: DetailFocusRegistry,
    focus: @Composable (String) -> Modifier, onAction: (String, String) -> Unit) {
    val row = rememberDetailRow(SIMILAR, registry)
    SectionColumn {
        SectionTitle(stringResource(R.string.library_similar))
        LazyRow(state = row, modifier = Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(8.dp)) {
            items(values, key = { it.key }, contentType = { "similar" }) { item ->
                DetailCard(item, focus(item.key).width(360.dp).height(150.dp), onAction = { onAction("similar", item.key) }) {
                    Row(Modifier.fillMaxSize()) {
                        DetailArtwork(item.image, null, Modifier.width(100.dp).height(150.dp), placeholderColor = MaterialTheme.colorScheme.surfaceVariant)
                        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.overview.isNotBlank()) Text(item.overview, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(item: LibraryDetailItem, modifier: Modifier, onAction: () -> Unit,
    onLongAction: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val onClick = { if (item.enabled) onAction() }
    val shape = DetailCardShape
    Card(onClick = onClick, onLongClick = onLongAction,
        modifier = modifier.tvTouchInput(true, onClick, onLongAction, interactions).semantics {
            contentDescription = listOf(item.title, item.subtitle, item.badge).filter { it.isNotBlank() }.joinToString(" · ")
            selected = item.selected
        },
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1.035f),
        colors = CardDefaults.colors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.selected) .13f else .07f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .14f),
            focusedContentColor = MaterialTheme.colorScheme.onSurface),
        border = CardDefaults.border(border = Border(BorderStroke(1.dp,
            if (item.selected) MaterialTheme.colorScheme.primary.copy(alpha = .55f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .1f)), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = shape)),
        interactionSource = interactions,
        content = content
    )
}

private fun detailTypography(): Typography {
    val t = JetStreamTypography
    return Typography(displayLarge = t.displayLarge, displayMedium = t.displayMedium, displaySmall = t.displaySmall,
        headlineLarge = t.headlineLarge, headlineMedium = t.headlineMedium, headlineSmall = t.headlineSmall,
        titleLarge = t.titleLarge, titleMedium = t.titleMedium, titleSmall = t.titleSmall,
        bodyLarge = t.bodyLarge, bodyMedium = t.bodyMedium, bodySmall = t.bodySmall,
        labelLarge = t.labelLarge, labelMedium = t.labelMedium, labelSmall = t.labelSmall)
}
