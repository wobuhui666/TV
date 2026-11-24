package com.fongmi.android.tv.ui.calendar

import android.view.SoundEffectConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.components.tvTouchInput
import com.fongmi.android.tv.ui.custom.LibraryMotion
import com.fongmi.android.tv.ui.theme.JetStreamTheme
import com.fongmi.android.tv.ui.theme.JetStreamThemeController
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import androidx.tv.material3.Surface
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults

private class CalendarFocusRegistry {
    val targets = mutableMapOf<String, FocusRequester>()
    val rows = linkedMapOf<String, LazyListState>()
    val aspects = mutableMapOf<String, Boolean>()
    val lastCards = mutableMapOf<String, String>()
    var lastHeader = "mode:public"
    var focused = ""
    var active = ""
    var focusJob: Job? = null
    var touch = false
    var cancelledToken = -1
    var pendingDate by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AiringCalendarScreen(state: AiringCalendarState, focusRequest: CalendarFocusRequest,
    themeVersion: Int, onAction: (String, String) -> Unit, onFocused: (String) -> Unit) {
    val host = LocalView.current
    val registry = remember { CalendarFocusRegistry() }
    val scope = rememberCoroutineScope()
    val contextKey = "${state.provider}:${state.mode}:${state.weekStart}:${state.selectedDate}"
    val row = remember(contextKey) {
        registry.rows.getOrPut(contextKey) { LazyListState() }.also {
            if (registry.rows.size > 24) {
                val oldest = registry.rows.keys.first()
                registry.rows.remove(oldest)
                registry.aspects.remove(oldest)
                registry.lastCards.remove(oldest)
            }
        }
    }
    // Lock the day's geometry before focus-driven enrichment starts replacing artwork.
    if (state.items.isNotEmpty() && contextKey !in registry.aspects) {
        registry.aspects[contextKey] = state.items.any { it.episodeStill.isNotBlank() || it.backdrop.isNotBlank() }
    }
    val landscape = registry.aspects[contextKey] ?: true
    val motion = remember(themeVersion, JetStreamThemeController.version) { LibraryMotion.enabled(host) }
    val showToday = state.todayDate.isNotBlank() && state.todayDate != state.selectedDate
    val headers = buildList {
        add("mode:public"); add("mode:following")
        add("provider")
        if (state.provider != "tmdb") add("account")
        add("week:previous"); add("week:next")
        if (showToday) add("today")
        if (state.mode == "following" && state.provider == "tmdb") add("keeps")
        if (state.canFollow) add("follow")
    }
    val footers = buildList { if (state.failed) add("retry"); if (state.hasMore) add("loadMore") }
    val date = "date:${state.selectedDate}"
    val firstCard = registry.lastCards[contextKey]?.takeIf { key -> state.items.any { "item:${it.key}" == key } }
        ?: state.items.firstOrNull()?.let { "item:${it.key}" }
    val validKeys = headers + state.days.map { "date:${it.date}" } + state.items.map { "item:${it.key}" } + footers

    suspend fun requestTarget(value: String, allowTouch: Boolean = false): Boolean {
        if (registry.touch && !allowTouch) return false
        val key = if (value == "content") firstCard ?: if (state.loading) return false else date else value
        val index = state.items.indexOfFirst { "item:${it.key}" == key }.takeIf { it >= 0 }
            ?: if (key in footers && state.items.isNotEmpty()) state.items.size else -1
        if (key !in validKeys) return false
        if (index >= 0 && row.layoutInfo.visibleItemsInfo.none { it.index == index }) row.scrollToItem(index)
        repeat(12) {
            withFrameNanos { }
            if (!host.isAttachedToWindow || !host.hasWindowFocus() || (registry.touch && !allowTouch)) return false
            if (registry.active == key) return true
            registry.targets[key]?.let { runCatching { it.requestFocus() } }
        }
        return registry.active == key
    }
    fun navigate(key: String) {
        registry.focusJob?.cancel()
        registry.focusJob = scope.launch { requestTarget(key) }
    }
    DisposableEffect(contextKey) { onDispose { registry.focusJob?.cancel() } }
    val focus: @Composable (String) -> Modifier = { key ->
        val requester = remember(key) { FocusRequester() }
        DisposableEffect(key) {
            registry.targets[key] = requester
            onDispose { registry.targets.remove(key) }
        }
        Modifier.focusRequester(requester).onFocusChanged {
            if (it.isFocused) {
                registry.focused = key
                registry.active = key
                if (key in headers) registry.lastHeader = key
                if (key.startsWith("item:")) registry.lastCards[contextKey] = key
                if (key.startsWith("date:") && key != date && !registry.touch) {
                    registry.pendingDate = null
                    onAction("date", key.removePrefix("date:"))
                }
                onFocused(key)
            } else if (registry.active == key) registry.active = ""
        }.onPreviewKeyEvent { event ->
            if (event.key !in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) false else {
                if (event.type == KeyEventType.KeyDown) {
                    registry.touch = false
                    registry.pendingDate = null
                    if (event.nativeKeyEvent.repeatCount == 0) host.playSoundEffect(when (event.key) {
                        Key.DirectionLeft -> SoundEffectConstants.NAVIGATION_LEFT
                        Key.DirectionRight -> SoundEffectConstants.NAVIGATION_RIGHT
                        Key.DirectionUp -> SoundEffectConstants.NAVIGATION_UP
                        else -> SoundEffectConstants.NAVIGATION_DOWN
                    })
                    if (key.startsWith("date:") && event.key == Key.DirectionDown && firstCard == null && state.loading) {
                        registry.pendingDate = state.selectedDate
                    } else calendarNavigationTarget(key, event.key, state, headers, footers, registry.lastHeader, firstCard)?.let(::navigate)
                }
                true
            }
        }.testTag(key)
    }
    var handledToken by remember { mutableIntStateOf(-1) }
    LaunchedEffect(focusRequest.token, validKeys, state.loading) {
        if (handledToken == focusRequest.token || focusRequest.key.isBlank()) return@LaunchedEffect
        if (registry.cancelledToken == focusRequest.token) { handledToken = focusRequest.token; return@LaunchedEffect }
        val requested = focusRequest.key
        if (requested !in validKeys && state.loading && (requested == "content" || requested.startsWith("item:"))) return@LaunchedEffect
        val target = requested.takeIf { it in validKeys || it == "content" } ?: firstCard ?: date
        if (requestTarget(target)) handledToken = focusRequest.token
    }
    LaunchedEffect(registry.pendingDate, state.selectedDate, state.items.map { it.key }, state.loading) {
        if (registry.pendingDate != state.selectedDate) return@LaunchedEffect
        val target = firstCard ?: if (state.loading) return@LaunchedEffect else date
        if (requestTarget(target, allowTouch = true)) registry.pendingDate = null
    }
    LaunchedEffect(contextKey, validKeys, state.loading) {
        if (!registry.touch && !state.loading && registry.focused.isNotBlank() && registry.focused !in validKeys) {
            requestTarget(firstCard ?: date)
        }
    }
    LaunchedEffect(contextKey, row, state.items.size, state.loading, state.failed, state.hasMore) {
        if (state.loading || state.failed || !state.hasMore || state.items.isEmpty()) return@LaunchedEffect
        snapshotFlow { row.layoutInfo.visibleItemsInfo.any { it.index >= state.items.lastIndex } }
            .filter { it }.take(1).collect { onAction("loadMore", "") }
    }
    JetStreamTheme {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.pointerInput(focusRequest.token) {
                awaitPointerEventScope {
                    while (true) if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed && !it.previousPressed }) {
                        registry.touch = true
                        registry.cancelledToken = focusRequest.token
                        registry.pendingDate = null
                        registry.focusJob?.cancel()
                    }
                }
            }) {
                CalendarBackdrop(state.preview.backdrop, motion, Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp).focusGroup().testTag("calendarPrimaryHeader"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.calendar_title), Modifier.testTag("calendarTitle"),
                            fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                        Row(Modifier.calendarGlass().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CalendarGlassButton(stringResource(R.string.calendar_public), focus("mode:public"), state.mode == "public") {
                                registry.pendingDate = null; onAction("mode", "public")
                            }
                            CalendarGlassButton(stringResource(R.string.calendar_following), focus("mode:following"), state.mode == "following") {
                                registry.pendingDate = null; onAction("mode", "following")
                            }
                        }
                        CalendarGlassButton(when (state.provider) { "trakt" -> "Trakt"; "bangumi" -> "Bangumi"; else -> "TMDB" },
                            focus("provider")) { onAction("provider", "") }
                        if (state.provider != "tmdb") CalendarGlassButton(stringResource(R.string.calendar_account), focus("account"), state.connected) {
                            onAction("account", "")
                        }
                        if (state.accountBusy) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onBackground, strokeWidth = 2.dp)
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.calendarGlass().padding(horizontal = 3.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CalendarGlassButton("‹", focus("week:previous").semantics {
                                contentDescription = host.context.getString(R.string.calendar_previous_week)
                            }) { registry.pendingDate = null; onAction("week", "previous") }
                            Text(state.weekLabel, Modifier.widthIn(max = 180.dp), color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Clip)
                            CalendarGlassButton("›", focus("week:next").semantics {
                                contentDescription = host.context.getString(R.string.calendar_next_week)
                            }) { registry.pendingDate = null; onAction("week", "next") }
                        }
                        if (showToday) CalendarGlassButton(stringResource(R.string.calendar_today), focus("today")) {
                            registry.pendingDate = null; onAction("today", "")
                        }
                        if (state.mode == "following" && state.provider == "tmdb") CalendarGlassButton("☆", focus("keeps").semantics {
                            contentDescription = host.context.getString(R.string.calendar_all_keeps)
                        }) { onAction("keeps", "") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.width(484.dp).height(40.dp).focusGroup().testTag("calendarDateHeader"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.days.forEach { day ->
                                CalendarDateCell(day, day.date == state.selectedDate, Modifier.width(64.dp).then(focus("date:${day.date}"))) {
                                    onAction("date", day.date); registry.pendingDate = day.date
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.canFollow) CalendarGlassButton(stringResource(if (state.followingTitle)
                            if (state.provider == "bangumi") R.string.calendar_pause else R.string.calendar_unfollow
                            else R.string.calendar_follow), focus("follow"), state.followingTitle) {
                            if (!state.accountBusy) onAction("follow", "")
                        }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                        CalendarHero(state.preview, motion, Modifier.fillMaxWidth(.55f)) { onAction("logoFailed", state.preview.key) }
                        if (state.items.isEmpty()) CalendarEmpty(state, focus, onAction,
                            Modifier.fillMaxSize().padding(start = 520.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    LazyRow(state = row, modifier = Modifier.fillMaxWidth().height(if (landscape) 178.dp else 190.dp)
                        .testTag("calendarMediaRow"), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Top) {
                        items(state.items, key = { it.key }) { item ->
                            CalendarMediaCard(item, landscape, motion, focus("item:${item.key}")) { onAction("open", item.key) }
                        }
                        if (state.items.isNotEmpty() && (state.loading || footers.isNotEmpty())) item(key = "calendarFooter") {
                            Column(Modifier.width(120.dp).padding(top = 34.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (state.loading) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onBackground, strokeWidth = 2.dp)
                                if (state.failed) Text(stringResource(R.string.calendar_partial_failed),
                                    color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, maxLines = 2)
                                if (state.failed) CalendarGlassButton(stringResource(R.string.calendar_retry), focus("retry")) { onAction("retry", "") }
                                if (state.hasMore) CalendarGlassButton(stringResource(R.string.calendar_load_more), focus("loadMore")) {
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

@Composable
private fun Modifier.calendarGlass() = background(MaterialTheme.colorScheme.onBackground.copy(alpha = .06f), RoundedCornerShape(22.dp))
    .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = .13f), RoundedCornerShape(22.dp))

@Composable
private fun CalendarGlassButton(label: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    CalendarNavigationSurface(onClick, selected, RoundedCornerShape(18.dp), modifier.height(36.dp)) {
        Box(Modifier.height(36.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, color = LocalContentColor.current, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CalendarDateCell(day: AiringCalendarDay, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val number = runCatching { LocalDate.parse(day.date).dayOfMonth.toString() }.getOrDefault(day.date)
    val dateTextStyle = MaterialTheme.typography.bodySmall.copy(platformStyle = PlatformTextStyle(includeFontPadding = false))
    CalendarNavigationSurface(onClick, selected, RoundedCornerShape(20.dp), modifier.height(40.dp)
        .semantics { contentDescription = "${day.label} ${day.date}"; text = AnnotatedString(number) }, compactDate = true) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            Text(day.label, Modifier.testTag("weekday:${day.date}").clearAndSetSemantics { },
                style = dateTextStyle, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
            Text(number, Modifier.testTag("daynumber:${day.date}").clearAndSetSemantics { },
                style = dateTextStyle, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Shared calendar chrome; compact dates use white capsules with inverse focus text. */
@Composable
private fun CalendarNavigationSurface(onClick: () -> Unit, selected: Boolean,
    shape: RoundedCornerShape, modifier: Modifier, compactDate: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val foreground = MaterialTheme.colorScheme.onBackground
    val background = MaterialTheme.colorScheme.background
    val focusedText = if (compactDate) background else foreground
    Surface(onClick = onClick, modifier = modifier.tvTouchInput(true, onClick, interactionSource = interactions),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) foreground.copy(alpha = if (compactDate) .12f else .10f) else Color.Transparent,
            focusedContainerColor = if (compactDate) foreground else foreground.copy(alpha = if (selected) .12f else .08f),
            pressedContainerColor = if (compactDate) foreground else foreground.copy(alpha = .16f),
            contentColor = if (compactDate && !selected) foreground.copy(alpha = .65f) else foreground,
            focusedContentColor = focusedText, pressedContentColor = focusedText
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = if (compactDate) Border.None else Border(BorderStroke(2.dp, foreground), shape = shape)
        ), interactionSource = interactions) {
        CompositionLocalProvider(LocalContentColor provides androidx.tv.material3.LocalContentColor.current) {
            content()
        }
    }
}

@Composable
private fun CalendarEmpty(state: AiringCalendarState, focus: @Composable (String) -> Modifier,
    onAction: (String, String) -> Unit, modifier: Modifier) {
    Column(modifier.testTag("calendarEmpty"), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.loading) CircularProgressIndicator(Modifier.size(26.dp), color = MaterialTheme.colorScheme.onBackground, strokeWidth = 2.dp)
        else {
            Text(if (state.failed) stringResource(R.string.calendar_load_failed) else state.emptyMessage.ifBlank {
                stringResource(if (state.mode == "following" && !state.hasKeeps) R.string.calendar_empty_following else R.string.calendar_empty_day)
            }, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, maxLines = 2)
            if (state.failed) CalendarGlassButton(stringResource(R.string.calendar_retry), focus("retry")) { onAction("retry", "") }
            if (state.hasMore) CalendarGlassButton(stringResource(R.string.calendar_load_more), focus("loadMore")) { onAction("loadMore", "") }
        }
    }
}

private fun calendarNavigationTarget(key: String, direction: Key, state: AiringCalendarState,
    headers: List<String>, footers: List<String>, lastHeader: String, firstCard: String?): String? {
    val date = "date:${state.selectedDate}"
    val header = headers.indexOf(key)
    if (header >= 0) return when (direction) {
        Key.DirectionLeft -> headers[(header - 1).coerceAtLeast(0)]
        Key.DirectionRight -> headers[(header + 1).coerceAtMost(headers.lastIndex)]
        Key.DirectionDown -> date
        else -> key
    }
    val day = state.days.indexOfFirst { "date:${it.date}" == key }
    if (day >= 0) return when (direction) {
        Key.DirectionLeft -> "date:${state.days[(day - 1).coerceAtLeast(0)].date}"
        Key.DirectionRight -> "date:${state.days[(day + 1).coerceAtMost(state.days.lastIndex)].date}"
        Key.DirectionUp -> lastHeader.takeIf { it in headers } ?: "mode:${state.mode}"
        Key.DirectionDown -> firstCard ?: footers.firstOrNull()?.takeIf { !state.loading } ?: key
        else -> key
    }
    val item = state.items.indexOfFirst { "item:${it.key}" == key }
    if (item >= 0) return when (direction) {
        Key.DirectionLeft -> "item:${state.items[(item - 1).coerceAtLeast(0)].key}"
        Key.DirectionRight -> if (item < state.items.lastIndex) "item:${state.items[item + 1].key}" else footers.firstOrNull() ?: key
        Key.DirectionUp -> date
        else -> key
    }
    val footer = footers.indexOf(key)
    if (footer >= 0) return when (direction) {
        Key.DirectionLeft -> if (footer > 0) footers[footer - 1] else firstCard ?: date
        Key.DirectionRight -> footers[(footer + 1).coerceAtMost(footers.lastIndex)]
        Key.DirectionUp -> date
        else -> key
    }
    return null
}
