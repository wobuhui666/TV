package com.fongmi.android.tv.ui.calendar

import android.view.SoundEffectConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.components.TvFocusableSurface
import com.fongmi.android.tv.ui.library.DetailArtwork
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.fongmi.android.tv.ui.theme.JetStreamTheme
import com.fongmi.android.tv.ui.theme.JetStreamThemeController
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

private const val COLUMNS = 5

private class CalendarFocusRegistry {
    val targets = mutableMapOf<String, FocusRequester>()
    val grids = linkedMapOf<String, LazyGridState>()
    val lastCards = mutableMapOf<String, String>()
    var lastHeader = "mode:public"
    var focused = ""
    var focusJob: Job? = null
    var touch = false
    var cancelledToken = -1
    var pendingGridDate by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AiringCalendarScreen(
    state: AiringCalendarState,
    focusRequest: CalendarFocusRequest,
    themeVersion: Int,
    onAction: (String, String) -> Unit,
    onFocused: (String) -> Unit
) {
    val host = LocalView.current
    val registry = remember { CalendarFocusRegistry() }
    val scope = rememberCoroutineScope()
    val contextKey = "${state.mode}:${state.weekStart}:${state.selectedDate}"
    val grid = remember(contextKey) {
        registry.grids.getOrPut(contextKey) { LazyGridState() }.also {
            if (registry.grids.size > 24) {
                val oldest = registry.grids.keys.first()
                registry.grids.remove(oldest)
                registry.lastCards.remove(oldest)
            }
        }
    }
    val headerKeys = remember(state.mode) {
        buildList {
            add("mode:public")
            add("mode:following")
            if (state.mode == "following") add("keeps")
            add("week:previous")
            add("week:next")
            add("today")
        }
    }
    val dateKey = "date:${state.selectedDate}"
    val firstCard = registry.lastCards[contextKey]?.takeIf { key -> state.items.any { "item:${it.key}" == key } }
        ?: state.items.firstOrNull()?.let { "item:${it.key}" }
    val footerKeys = buildList {
        if (state.failed) add("retry")
        if (state.hasMore) add("loadMore")
    }

    suspend fun requestTarget(key: String, allowTouch: Boolean = false): Boolean {
        if (registry.touch && !allowTouch) return false
        val index = if (key.startsWith("item:")) state.items.indexOfFirst { "item:${it.key}" == key }
            else if (key in footerKeys && state.items.isNotEmpty()) state.items.size else -1
        if (key.startsWith("item:") && index < 0) return false
        if (index >= 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
        repeat(8) {
            withFrameNanos { }
            if (!host.isAttachedToWindow || !host.hasWindowFocus()) return false
            if (registry.touch && !allowTouch) return false
            val requester = registry.targets[key]
            if (requester != null && runCatching { requester.requestFocus() }.isSuccess) return true
        }
        return false
    }

    fun navigate(key: String) {
        registry.focusJob?.cancel()
        registry.focusJob = scope.launch { requestTarget(key) }
    }

    DisposableEffect(contextKey) {
        onDispose { registry.focusJob?.cancel() }
    }

    val focus: @Composable (String) -> Modifier = { key ->
        val requester = remember(key) { FocusRequester() }
        DisposableEffect(key) {
            registry.targets[key] = requester
            onDispose { registry.targets.remove(key) }
        }
        Modifier.focusRequester(requester).onFocusChanged {
            if (it.isFocused) {
                registry.focused = key
                if (key in headerKeys) registry.lastHeader = key
                if (key.startsWith("item:")) {
                    registry.lastCards[contextKey] = key
                    onAction("focusedwork", key.removePrefix("item:"))
                }
                if (key.startsWith("date:") && key != dateKey && !registry.touch) {
                    registry.pendingGridDate = null
                    onAction("date", key.removePrefix("date:"))
                }
                onFocused(key)
            }
        }.onPreviewKeyEvent { event ->
            val arrow = event.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
            if (!arrow) false else {
                if (event.type == KeyEventType.KeyDown) {
                    registry.touch = false
                    registry.pendingGridDate = null
                    val sound = when (event.key) {
                        Key.DirectionLeft -> SoundEffectConstants.NAVIGATION_LEFT
                        Key.DirectionRight -> SoundEffectConstants.NAVIGATION_RIGHT
                        Key.DirectionUp -> SoundEffectConstants.NAVIGATION_UP
                        else -> SoundEffectConstants.NAVIGATION_DOWN
                    }
                    if (event.nativeKeyEvent.repeatCount == 0) host.playSoundEffect(sound)
                    calendarNavigationTarget(key, event.key, state, headerKeys, footerKeys,
                        registry.lastHeader, firstCard)?.let(::navigate)
                }
                true
            }
        }.testTag(key)
    }

    var handledToken by remember { mutableIntStateOf(-1) }
    LaunchedEffect(focusRequest.token, state.days, state.items.map { it.key }, state.loading) {
        if (handledToken == focusRequest.token || focusRequest.key.isBlank()) return@LaunchedEffect
        if (registry.cancelledToken == focusRequest.token) {
            handledToken = focusRequest.token
            return@LaunchedEffect
        }
        if (state.loading && focusRequest.key.startsWith("item:") && state.items.none { "item:${it.key}" == focusRequest.key }) {
            return@LaunchedEffect
        }
        val target = focusRequest.key.takeIf { key ->
            !key.startsWith("item:") || state.items.any { "item:${it.key}" == key }
        } ?: dateKey
        if (requestTarget(target)) handledToken = focusRequest.token
    }
    LaunchedEffect(registry.pendingGridDate, state.selectedDate, state.items.map { it.key }, state.loading) {
        val requested = registry.pendingGridDate ?: return@LaunchedEffect
        if (requested != state.selectedDate) return@LaunchedEffect
        val target = firstCard ?: if (!state.loading) footerKeys.firstOrNull() else null
        if (target != null && requestTarget(target, allowTouch = true)) registry.pendingGridDate = null
        else if (!state.loading && state.items.isEmpty()) registry.pendingGridDate = null
    }
    LaunchedEffect(contextKey, state.items.map { it.key }, footerKeys, state.loading) {
        if (registry.touch || state.loading || registry.focused.isBlank()) return@LaunchedEffect
        val removedCard = registry.focused.startsWith("item:") && state.items.none { "item:${it.key}" == registry.focused }
        val removedFooter = registry.focused in listOf("retry", "loadMore") && registry.focused !in footerKeys
        if (removedCard || removedFooter) requestTarget(firstCard ?: dateKey)
    }
    LaunchedEffect(contextKey, grid, state.items.size, state.loading, state.failed, state.hasMore) {
        if (state.loading || state.failed || !state.hasMore || state.items.isEmpty()) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.any { it.index >= state.items.lastIndex } }
            .filter { it }.take(1).collect { onAction("loadMore", "") }
    }

    // Theme reads stay observable even when the activity updates only its view refresh token.
    val themeToken = JetStreamThemeController.version + themeVersion
    val background = remember(themeToken) { JetStreamPalette.colorScheme().background }
    JetStreamTheme {
        Column(
            Modifier.fillMaxSize().background(background)
                .padding(horizontal = 32.dp, vertical = 20.dp)
                .semantics { testTagsAsResourceId = true }
                .pointerInput(focusRequest.token) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed && !it.previousPressed }) {
                                registry.touch = true
                                registry.cancelledToken = focusRequest.token
                                registry.pendingGridDate = null
                                registry.focusJob?.cancel()
                            }
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.calendar_title), style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth().focusGroup(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarButton(stringResource(R.string.calendar_public), focus("mode:public"), state.mode == "public") {
                    registry.pendingGridDate = null
                    onAction("mode", "public")
                }
                CalendarButton(stringResource(R.string.calendar_following), focus("mode:following"), state.mode == "following") {
                    registry.pendingGridDate = null
                    onAction("mode", "following")
                }
                if (state.mode == "following") {
                    CalendarButton(stringResource(R.string.calendar_all_keeps), focus("keeps")) { onAction("keeps", "") }
                }
                Spacer(Modifier.weight(1f))
                CalendarButton("‹", focus("week:previous").semantics {
                    contentDescription = host.context.getString(R.string.calendar_previous_week)
                }) { registry.pendingGridDate = null; onAction("week", "previous") }
                Text(state.weekLabel, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CalendarButton("›", focus("week:next").semantics {
                    contentDescription = host.context.getString(R.string.calendar_next_week)
                }) { registry.pendingGridDate = null; onAction("week", "next") }
                CalendarButton(stringResource(R.string.calendar_today), focus("today")) {
                    registry.pendingGridDate = null
                    onAction("today", "")
                }
            }
            Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.days.forEach { day ->
                    TvFocusableSurface(onClick = {
                        onAction("date", day.date)
                        registry.pendingGridDate = day.date
                    }, selected = day.date == state.selectedDate,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(52.dp).then(focus("date:${day.date}"))) {
                        Column(Modifier.fillMaxSize().padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.label, fontSize = 13.sp, fontWeight = if (day.today) FontWeight.SemiBold else FontWeight.Medium)
                            Text(calendarShortDate(day.date), fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                CalendarPreview(state.preview, Modifier.weight(.28f).fillMaxHeight()) { onAction("logoFailed", state.preview.key) }
                Box(Modifier.weight(.72f).fillMaxHeight()) {
                    if (state.items.isEmpty()) {
                        CalendarEmpty(state, focus, onAction, Modifier.fillMaxSize())
                    } else {
                        LazyVerticalGrid(columns = GridCells.Fixed(COLUMNS), state = grid,
                            modifier = Modifier.fillMaxSize().testTag("calendarGrid"),
                            contentPadding = PaddingValues(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(state.items, key = { it.key }) { item ->
                                CalendarPoster(item, focus("item:${item.key}")) { onAction("open", item.key) }
                            }
                            if (state.loading || state.failed || state.hasMore) {
                                item(key = "calendarFooter", span = { GridItemSpan(COLUMNS) }) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        if (state.failed) Text(stringResource(R.string.calendar_partial_failed), fontSize = 13.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            if (state.failed) CalendarButton(stringResource(R.string.calendar_retry), focus("retry")) { onAction("retry", "") }
                                            if (state.hasMore) CalendarButton(stringResource(R.string.calendar_load_more), focus("loadMore")) {
                                                if (!state.loading) onAction("loadMore", "")
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
    }
}

@Composable
private fun CalendarButton(label: String, modifier: Modifier, selected: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    TvFocusableSurface(onClick = onClick, selected = selected, enabled = enabled,
        shape = RoundedCornerShape(10.dp), modifier = modifier.height(36.dp)) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CalendarPoster(item: AiringCalendarItem, modifier: Modifier, onClick: () -> Unit) {
    TvFocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp), containerColor = Color.Transparent) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            DetailArtwork(item.poster, null, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
                placeholderColor = MaterialTheme.colorScheme.surfaceContainer)
            Text(item.title, Modifier.padding(horizontal = 3.dp), fontSize = 12.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, Modifier.padding(horizontal = 3.dp),
                fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CalendarPreview(preview: AiringCalendarPreview, modifier: Modifier, onLogoFailed: () -> Unit) {
    var logoStatus by remember(preview.key, preview.logo) { mutableStateOf(if (preview.logo.isBlank()) "unavailable" else "loading") }
    Column(modifier.testTag("calendarPreview"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (preview.title.isNotBlank()) {
            DetailArtwork(preview.backdrop, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
                placeholderColor = MaterialTheme.colorScheme.surfaceContainer)
            Box(Modifier.fillMaxWidth().height(66.dp).testTag("calendarLogo").semantics { stateDescription = logoStatus }) {
                if (preview.logo.isNotBlank() && logoStatus != "unavailable") {
                    DetailArtwork(preview.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart, alphaRim = true,
                        onLoadResult = { loaded ->
                            logoStatus = if (loaded) "loaded" else "unavailable"
                            if (!loaded) onLogoFailed()
                        })
                }
                if (logoStatus != "loaded") {
                    Text(preview.title, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (preview.subtitle.isNotBlank()) Text(preview.subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CalendarEmpty(state: AiringCalendarState, focus: @Composable (String) -> Modifier,
    onAction: (String, String) -> Unit, modifier: Modifier) {
    Column(modifier.testTag("calendarEmpty"), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.loading) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.calendar_loading), style = MaterialTheme.typography.bodyMedium)
        } else {
            val message = if (state.failed) stringResource(R.string.calendar_load_failed) else state.emptyMessage.ifBlank {
                when {
                    state.mode == "following" && !state.hasKeeps -> stringResource(R.string.calendar_empty_following)
                    else -> stringResource(R.string.calendar_empty_day)
                }
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.failed) CalendarButton(stringResource(R.string.calendar_retry), focus("retry")) { onAction("retry", "") }
                if (state.hasMore) CalendarButton(stringResource(R.string.calendar_load_more), focus("loadMore")) { onAction("loadMore", "") }
            }
        }
    }
}

private fun calendarShortDate(value: String): String = runCatching {
    LocalDate.parse(value).let { "${it.monthValue}/${it.dayOfMonth}" }
}.getOrDefault(value)

private fun calendarNavigationTarget(key: String, direction: Key, state: AiringCalendarState,
    headers: List<String>, footers: List<String>, lastHeader: String, firstCard: String?): String? {
    val date = "date:${state.selectedDate}"
    val entry = firstCard ?: footers.firstOrNull()?.takeIf { !state.loading } ?: date
    val headerIndex = headers.indexOf(key)
    if (headerIndex >= 0) return when (direction) {
        Key.DirectionLeft -> headers[(headerIndex - 1).coerceAtLeast(0)]
        Key.DirectionRight -> headers[(headerIndex + 1).coerceAtMost(headers.lastIndex)]
        Key.DirectionDown -> date
        else -> key
    }
    val dayIndex = state.days.indexOfFirst { "date:${it.date}" == key }
    if (dayIndex >= 0) return when (direction) {
        Key.DirectionLeft -> "date:${state.days[(dayIndex - 1).coerceAtLeast(0)].date}"
        Key.DirectionRight -> "date:${state.days[(dayIndex + 1).coerceAtMost(state.days.lastIndex)].date}"
        Key.DirectionUp -> lastHeader.takeIf { it in headers } ?: "mode:${state.mode}"
        Key.DirectionDown -> entry
        else -> key
    }
    val index = state.items.indexOfFirst { "item:${it.key}" == key }
    if (index >= 0) {
        val target = when (direction) {
            Key.DirectionLeft -> if (index % COLUMNS > 0) index - 1 else index
            Key.DirectionRight -> if (index % COLUMNS < COLUMNS - 1 && index < state.items.lastIndex) index + 1 else index
            Key.DirectionUp -> if (index >= COLUMNS) index - COLUMNS else return date
            Key.DirectionDown -> if (index / COLUMNS < state.items.lastIndex / COLUMNS) (index + COLUMNS).coerceAtMost(state.items.lastIndex)
                else return footers.firstOrNull()?.takeIf { !state.loading } ?: key
            else -> index
        }
        return "item:${state.items[target].key}"
    }
    val footerIndex = footers.indexOf(key)
    if (footerIndex >= 0) return when (direction) {
        Key.DirectionUp -> firstCard ?: date
        Key.DirectionLeft -> footers[(footerIndex - 1).coerceAtLeast(0)]
        Key.DirectionRight -> footers[(footerIndex + 1).coerceAtMost(footers.lastIndex)]
        else -> key
    }
    return null
}
