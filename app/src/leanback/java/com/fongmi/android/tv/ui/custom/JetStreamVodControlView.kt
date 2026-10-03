package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.components.JetStreamControlScrim
import com.fongmi.android.tv.ui.components.JetStreamInfoScrim
import com.fongmi.android.tv.ui.components.TvActionButton
import com.fongmi.android.tv.ui.components.TvFocusStyle
import com.fongmi.android.tv.ui.components.TvFocusableSurface
import com.fongmi.android.tv.ui.theme.JetStreamAnimations
import com.fongmi.android.tv.ui.theme.JetStreamTheme
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class JetStreamVodControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface Listener {
        fun onPlayPause()
        fun onPrevious()
        fun onNext()
        fun onRepeat()
        fun onCommand(key: String)
        fun onCommandLongClick(key: String)
        fun onSeekTo(positionMs: Long)
        fun onShowControls()
    }

    private data class CommandState(
        val title: String,
        val label: String,
        val visible: Boolean,
        val selected: Boolean
    )

    private data class CommandGroupState(
        val key: String,
        @param:DrawableRes val icon: Int,
        @param:StringRes val labelRes: Int,
        val customLabel: String,
        val visible: Boolean,
        val commandKeys: List<String>
    )

    private var listener: Listener? = null
    private var mediaPlayer by mutableStateOf<Player?>(null)
    private var mediaTitle by mutableStateOf("")
    private var secondaryText by mutableStateOf("")
    private var tertiaryText by mutableStateOf("")
    private var playing by mutableStateOf(false)
    private var livePlayback by mutableStateOf(false)
    private var repeating by mutableStateOf(false)
    private var previousVisible by mutableStateOf(true)
    private var nextVisible by mutableStateOf(true)
    private var repeatVisible by mutableStateOf(true)
    private var controlPanelVisible by mutableStateOf(false)
    private var topInfoVisible by mutableStateOf(false)
    private var centerInfoVisible by mutableStateOf(false)
    private var showTopInfoSubtitle by mutableStateOf(false)
    private var infoSize by mutableStateOf("")
    private var infoClock by mutableStateOf("")
    private var infoAction by mutableStateOf(ACTION_PLAY)
    private var infoPosition by mutableStateOf("")
    private var infoDuration by mutableStateOf("")
    private var activeGroup by mutableStateOf<String?>(null)
    private var controlFocused by mutableStateOf(false)
    private var settingsCloseFocus by mutableLongStateOf(0L)
    private var drawerFocusKey by mutableStateOf<String?>(null)
    private val commandGroups = mutableStateListOf(
        CommandGroupState(GROUP_PLAYLIST, R.drawable.msr_auto_awesome_motion, R.string.vod_control_group_playlist, "", true, PLAYLIST_COMMANDS),
        CommandGroupState(GROUP_CAPTIONS, R.drawable.msr_closed_caption, R.string.vod_control_group_captions, "", true, CAPTION_COMMANDS),
        CommandGroupState(GROUP_SETTINGS, R.drawable.msr_settings, R.string.vod_control_group_settings, "", true, SETTINGS_COMMANDS)
    )
    private val commands = mutableStateMapOf<String, CommandState>()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        JetStreamControls()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        controlFocused = gainFocus
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setPlayer(player: Player?) {
        mediaPlayer = player
    }

    fun setMediaTitle(title: CharSequence?, secondary: CharSequence?, tertiary: CharSequence?) {
        mediaTitle = title?.toString().orEmpty()
        secondaryText = secondary?.toString().orEmpty()
        tertiaryText = tertiary?.toString().orEmpty()
    }

    fun setLiveMode(live: Boolean) {
        livePlayback = live
    }

    fun setPlaybackState(isPlaying: Boolean, isRepeating: Boolean) {
        playing = isPlaying
        repeating = isRepeating
    }

    fun setTransportActions(previous: Boolean, next: Boolean, repeat: Boolean) {
        previousVisible = previous
        nextVisible = next
        repeatVisible = repeat
    }

    fun setTopActions(playlist: Boolean, captions: Boolean, settings: Boolean) {
        setCommandGroupVisibility(GROUP_PLAYLIST, playlist)
        setCommandGroupVisibility(GROUP_CAPTIONS, captions)
        setCommandGroupVisibility(GROUP_SETTINGS, settings)
    }

    fun setTopInfoSubtitleVisible(visible: Boolean) {
        showTopInfoSubtitle = visible
    }

    fun setControlsVisible(visible: Boolean) {
        controlPanelVisible = visible
    }

    fun isControlsVisible(): Boolean {
        return controlPanelVisible
    }

    fun setInfoState(
        topVisible: Boolean,
        centerVisible: Boolean,
        size: CharSequence?,
        clock: CharSequence?,
        action: String,
        position: CharSequence?,
        duration: CharSequence?
    ) {
        topInfoVisible = topVisible
        centerInfoVisible = centerVisible
        infoSize = size?.toString().orEmpty()
        infoClock = clock?.toString().orEmpty()
        infoAction = action
        infoPosition = position?.toString().orEmpty()
        infoDuration = duration?.toString().orEmpty()
    }

    fun isInfoVisible(): Boolean {
        return topInfoVisible || centerInfoVisible
    }

    fun isCenterInfoVisible(): Boolean {
        return centerInfoVisible
    }

    fun setCommand(key: String, label: CharSequence?, visible: Boolean, selected: Boolean) {
        setCommand(key, null, label, visible, selected)
    }

    fun setCommand(key: String, title: CharSequence?, label: CharSequence?, visible: Boolean, selected: Boolean) {
        commands[key] = CommandState(title?.toString().orEmpty(), label?.toString().orEmpty(), visible, selected)
        validateActiveGroup()
    }

    fun setCommandGroup(key: String, @DrawableRes icon: Int, label: CharSequence?, visible: Boolean, vararg commandKeys: String) {
        val group = CommandGroupState(key, icon, 0, label?.toString().orEmpty(), visible, commandKeys.toList())
        val index = commandGroups.indexOfFirst { it.key == key }
        if (index >= 0) commandGroups[index] = group else commandGroups.add(group)
        if (activeGroup == key) validateActiveGroup()
    }

    @JvmOverloads
    fun showGroup(group: String?, focusKey: String? = null) {
        drawerFocusKey = focusKey
        activeGroup = group?.takeIf { hasVisibleCommands(it) }
    }

    private fun triggerCommand(key: String, longClick: Boolean) {
        if (!canTriggerCommand(key)) return
        if (longClick) listener?.onCommandLongClick(key) else listener?.onCommand(key)
    }

    private fun canTriggerCommand(key: String): Boolean {
        val group = activeGroup ?: return false
        val groupState = commandGroups.firstOrNull { it.key == group && it.visible && key in it.commandKeys } ?: return false
        val command = commands[key] ?: return false
        return groupState.commandKeys.contains(key) && command.visible && command.label.isNotEmpty()
    }

    @Composable
    private fun JetStreamControls() {
        val player = mediaPlayer
        var positionMs by remember(player) { mutableLongStateOf(normalize(player?.currentPosition)) }
        var durationMs by remember(player) { mutableLongStateOf(normalize(player?.duration)) }
        var polledPlaying by remember(player, playing) { mutableStateOf(playing) }

        // 仅在控制条或信息层可见时轮询，隐藏后停表避免常驻唤醒
        LaunchedEffect(player, controlPanelVisible, topInfoVisible, centerInfoVisible) {
            if (!controlPanelVisible && !isInfoVisible()) return@LaunchedEffect
            while (isActive) {
                positionMs = normalize(player?.currentPosition)
                durationMs = normalize(player?.duration)
                polledPlaying = player?.isPlaying ?: playing
                delay(300)
            }
        }

        JetStreamTheme {
            Box(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = controlPanelVisible,
                    enter = fadeIn(tween(JetStreamAnimations.DurationPanel)) + slideInVertically(
                        animationSpec = tween(JetStreamAnimations.DurationPanel),
                        initialOffsetY = { it / 5 }
                    ),
                    exit = fadeOut(tween(JetStreamAnimations.DurationExit)) + slideOutVertically(
                        animationSpec = tween(JetStreamAnimations.DurationExit),
                        targetOffsetY = { it / 6 }
                    )
                ) {
                    JetStreamControlScrim(modifier = Modifier.fillMaxSize()) {
                        InfoOverlay()
                        ControlPanel(polledPlaying, positionMs, durationMs)
                    }
                }
                AnimatedVisibility(
                    visible = !controlPanelVisible && isInfoVisible(),
                    enter = fadeIn(tween(JetStreamAnimations.DurationShort)) + scaleIn(
                        animationSpec = tween(JetStreamAnimations.DurationShort),
                        initialScale = 0.98f
                    ),
                    exit = fadeOut(tween(JetStreamAnimations.DurationExit)) + scaleOut(
                        animationSpec = tween(JetStreamAnimations.DurationExit),
                        targetScale = 0.98f
                    )
                ) {
                    JetStreamInfoScrim(modifier = Modifier.fillMaxSize()) {
                        InfoOverlay()
                    }
                }
                SettingsDrawer()
            }
        }
    }

    @Composable
    private fun BoxScope.ControlPanel(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .padding(top = 8.dp, bottom = 36.dp)
        ) {
            MediaTitle(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            SeekerRow(
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs
            )
            Spacer(Modifier.height(14.dp))
            HeaderRow(isPlaying)
        }
    }

    @Composable
    private fun BoxScope.InfoOverlay() {
        if (topInfoVisible) TopInfo()
        if (centerInfoVisible) CenterInfo()
    }

    @Composable
    private fun BoxScope.TopInfo() {
        val colorScheme = MaterialTheme.colorScheme
        val subtitle = subtitleText()
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 32.dp)
            ) {
                Text(
                    text = mediaTitle,
                    color = colorScheme.onSurface,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showTopInfoSubtitle && subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (infoSize.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = infoSize,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (infoClock.isNotEmpty()) {
                Text(
                    text = infoClock,
                    color = colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun BoxScope.CenterInfo() {
        val colorScheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp))
                .background(colorScheme.surface.copy(alpha = 0.88f))
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = centerInfoIcon()),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = infoPosition,
                    color = colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = " / ",
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
                    fontSize = 18.sp,
                    maxLines = 1
                )
                Text(
                    text = infoDuration,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 18.sp,
                    maxLines = 1
                )
            }
        }
    }

    @DrawableRes
    private fun centerInfoIcon(): Int {
        return when (infoAction) {
            ACTION_FORWARD -> R.drawable.msr_fast_forward
            ACTION_REWIND -> R.drawable.msr_fast_rewind
            else -> R.drawable.msr_play_arrow
        }
    }

    @Composable
    private fun HeaderRow(isPlaying: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (previousVisible) {
                    ControlIcon(R.drawable.msr_skip_previous, isPlaying, true, false, stringResource(R.string.play_prev)) {
                        listener?.onPrevious()
                    }
                }
                if (nextVisible) {
                    ControlIcon(R.drawable.msr_skip_next, isPlaying, true, false, stringResource(R.string.play_next)) {
                        listener?.onNext()
                    }
                }
                if (repeatVisible) {
                    ControlIcon(
                        icon = if (repeating) R.drawable.msr_repeat_one else R.drawable.msr_repeat,
                        isPlaying = isPlaying,
                        enabled = true,
                        selected = repeating,
                        contentDescription = stringResource(R.string.play_repeat)
                    ) {
                        listener?.onRepeat()
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                commandGroups.filter { hasVisibleCommands(it.key) }.forEach { group ->
                    ControlIcon(group.icon, isPlaying, true, activeGroup == group.key, groupLabel(group), showLabel = true) {
                        toggleGroup(group.key)
                    }
                }
            }
        }
    }

    @Composable
    private fun MediaTitle(modifier: Modifier) {
        val colorScheme = MaterialTheme.colorScheme
        val subtitle = subtitleText()
        Column(modifier = modifier.padding(end = 24.dp)) {
            Text(
                text = mediaTitle,
                color = colorScheme.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun SeekerRow(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        val playFocusRequester = remember { FocusRequester() }
        var seekActive by remember { mutableStateOf(false) }
        var seekFraction by remember { mutableStateOf(0f) }
        LaunchedEffect(controlPanelVisible, controlFocused, activeGroup) {
            if (controlPanelVisible && controlFocused && activeGroup == null) runCatching { playFocusRequester.requestFocus() }
        }
        LaunchedEffect(settingsCloseFocus) {
            if (settingsCloseFocus > 0L) runCatching { playFocusRequester.requestFocus() }
        }
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ControlIcon(
                    icon = if (isPlaying) R.drawable.msr_pause else R.drawable.msr_play_arrow,
                    isPlaying = isPlaying,
                    enabled = true,
                    selected = false,
                    contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                    focusRequester = playFocusRequester
                ) { listener?.onPlayPause() }
                Spacer(Modifier.width(12.dp))
                if (livePlayback && durationMs <= 0) {
                    Text(
                        text = stringResource(R.string.playback_live_now),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    Spacer(Modifier.weight(1f))
                } else {
                    ControllerText(formatTime(if (seekActive) (durationMs * seekFraction).roundToLong() else positionMs))
                    ControllerIndicator(
                        progress = progress(positionMs, durationMs),
                        durationMs = durationMs,
                        selected = seekActive,
                        seekProgress = seekFraction,
                        onSelectedChange = { seekActive = it },
                        onSeekProgressChange = { seekFraction = it },
                        modifier = Modifier.weight(1f)
                    )
                    ControllerText(formatTime(durationMs))
                }
            }
            Box(Modifier.height(24.dp)) {
                if (seekActive) Text(
                    text = stringResource(R.string.playback_seek_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 56.dp, top = 8.dp)
                )
            }
        }
    }

    @Composable
    private fun BoxScope.SettingsDrawer() {
        val group = activeGroup
        val groupState = commandGroups.firstOrNull { it.key == group && it.visible }
        val visibleCommands = groupState?.commandKeys?.mapNotNull { key ->
            commands[key]?.takeIf { it.visible && it.label.isNotEmpty() }?.let { key to it }
        }.orEmpty()
        val open = group != null && visibleCommands.isNotEmpty()
        var focusedIndex by remember(group) { mutableStateOf(0) }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(JetStreamAnimations.DurationPanel)) + slideInHorizontally(
                animationSpec = tween(JetStreamAnimations.DurationPanel),
                initialOffsetX = { it }
            ),
            exit = fadeOut(tween(JetStreamAnimations.DurationExit)) + slideOutHorizontally(
                animationSpec = tween(JetStreamAnimations.DurationExit),
                targetOffsetX = { it }
            ),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            val colorScheme = MaterialTheme.colorScheme
            val keys = visibleCommands.map { it.first }
            val requesters = remember(keys) { List(keys.size) { FocusRequester() } }
            val listState = rememberLazyListState()
            LaunchedEffect(group, keys) {
                if (open && keys.isNotEmpty()) {
                    val requested = keys.indexOf(drawerFocusKey)
                    val selected = visibleCommands.indexOfFirst { it.second.selected }
                    focusedIndex = when {
                        requested >= 0 -> requested
                        selected >= 0 -> selected
                        else -> 0
                    }
                    drawerFocusKey = null
                    listState.scrollToItem(focusedIndex)
                    requesters[focusedIndex].requestFocus()
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(368.dp)
                    .padding(vertical = 48.dp, horizontal = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colorScheme.surface.copy(alpha = 0.96f))
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft, Key.Back -> { closeSettingsDrawer(); true }
                            Key.DirectionUp -> focusedIndex == 0
                            Key.DirectionDown -> focusedIndex == visibleCommands.lastIndex
                            Key.DirectionRight -> true
                            else -> false
                        }
                    }
                    .padding(vertical = 18.dp)
            ) {
                Text(
                    text = groupState?.let { groupLabel(it) }.orEmpty(),
                    color = colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(visibleCommands, key = { _, item -> item.first }) { index, item ->
                        TvFocusableSurface(
                            onClick = { triggerCommand(item.first, false) },
                            onLongClick = { triggerCommand(item.first, true) },
                            selected = item.second.selected,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).focusRequester(requesters[index])
                                .onFocusChanged { if (it.isFocused) focusedIndex = index }
                        ) {
                            val title = commandTitle(item.first, item.second)
                            val value = item.second.label.trim()
                            val placeholder = when (item.first) {
                                "opening" -> stringResource(R.string.play_op)
                                "ending" -> stringResource(R.string.play_ed)
                                else -> ""
                            }
                            val showValue = title.isNotEmpty() && value != title && value != placeholder
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = title.ifEmpty { value },
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = if (item.second.selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = if (showValue) 1 else 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (showValue) Text(
                                    text = value,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun commandTitle(key: String, command: CommandState): String {
        if (command.title.isNotBlank()) return command.title.trim()
        val titleRes = when (key) {
            "speed" -> R.string.playback_command_speed
            "scale" -> R.string.playback_command_scale
            "player" -> R.string.playback_command_player
            "decode" -> R.string.playback_command_decode
            "opening" -> R.string.playback_command_opening
            "ending" -> R.string.playback_command_ending
            "edition" -> R.string.play_edition
            "chapter" -> R.string.play_chapter
            "text" -> R.string.play_track_text
            "audio" -> R.string.play_track_audio
            "video" -> R.string.play_track_video
            "parse" -> R.string.parse
            else -> return ""
        }
        return stringResource(titleRes)
    }

    private fun closeSettingsDrawer() {
        activeGroup = null
        settingsCloseFocus++
        listener?.onShowControls()
    }

    @Composable
    private fun groupLabel(group: CommandGroupState): String {
        return if (group.labelRes != 0) stringResource(group.labelRes) else group.customLabel
    }

    @Composable
    private fun ControlIcon(
        @DrawableRes icon: Int,
        isPlaying: Boolean,
        enabled: Boolean,
        selected: Boolean,
        contentDescription: String,
        focusRequester: FocusRequester? = null,
        showLabel: Boolean = false,
        onClick: () -> Unit
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        TvActionButton(
            onClick = { listener?.onShowControls(); onClick() },
            enabled = enabled,
            selected = selected,
            interactionSource = interactionSource,
            shape = if (showLabel) TvFocusStyle.Shape else CircleShape,
            modifier = Modifier
                .then(if (showLabel) Modifier.height(40.dp) else Modifier.size(44.dp))
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            contentPadding = PaddingValues(horizontal = if (showLabel) 12.dp else 0.dp, vertical = 6.dp)
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = if (showLabel) null else contentDescription,
                modifier = Modifier.size(if (showLabel) 18.dp else 24.dp)
            )
            if (showLabel) {
                Spacer(Modifier.width(8.dp))
                Text(contentDescription, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LaunchedEffect(focused, isPlaying) {
            if (focused && isPlaying) listener?.onShowControls()
        }
    }

    @Composable
    private fun ControllerText(text: String, color: Color? = null) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp),
            color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            maxLines = 1
        )
    }

    @Composable
    private fun ControllerIndicator(
        progress: Float,
        durationMs: Long,
        selected: Boolean,
        seekProgress: Float,
        onSelectedChange: (Boolean) -> Unit,
        onSeekProgressChange: (Float) -> Unit,
        modifier: Modifier
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        val height by animateDpAsState(if (focused) 8.dp else 4.dp, animationSpec = tween(JetStreamAnimations.DurationFocus), label = "indicatorHeight")
        val colorScheme = MaterialTheme.colorScheme
        val progressColor = if (selected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.92f)
        val trackColor = colorScheme.outlineVariant.copy(alpha = 0.72f)
        val displayProgress = if (selected) seekProgress else progress

        LaunchedEffect(progress, selected) {
            if (!selected) onSeekProgressChange(progress)
        }
        LaunchedEffect(selected) {
            if (selected) listener?.onShowControls()
        }
        // 焦点移开时丢弃未确认的拖动值，避免进度条冻结在待定位置
        LaunchedEffect(focused) {
            if (!focused && selected) {
                onSelectedChange(false)
                onSeekProgressChange(progress)
            }
        }

        Canvas(
            modifier = modifier
                .height(height)
                .padding(horizontal = 4.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || durationMs <= 0) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            if (event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true
                            if (selected) listener?.onSeekTo((durationMs * seekProgress).roundToLong())
                            else onSeekProgressChange(progress)
                            onSelectedChange(!selected)
                            listener?.onShowControls()
                            true
                        }

                        Key.DirectionLeft -> {
                            onSelectedChange(true)
                            onSeekProgressChange((seekProgress - seekStepFraction(event, durationMs)).coerceAtLeast(0f))
                            listener?.onShowControls()
                            true
                        }

                        Key.DirectionRight -> {
                            onSelectedChange(true)
                            onSeekProgressChange((seekProgress + seekStepFraction(event, durationMs)).coerceAtMost(1f))
                            listener?.onShowControls()
                            true
                        }

                        Key.Back -> {
                            // 拖动中按返回：只取消待定 seek，不关闭控制条
                            if (selected) {
                                onSelectedChange(false)
                                onSeekProgressChange(progress)
                                true
                            } else {
                                false
                            }
                        }

                        else -> false
                    }
                }
                .focusable(interactionSource = interactionSource)
        ) {
            val y = size.height / 2f
            drawLine(
                color = trackColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = size.height,
                cap = StrokeCap.Round
            )
            drawLine(
                color = progressColor,
                start = Offset(0f, y),
                end = Offset(size.width * displayProgress.coerceIn(0f, 1f), y),
                strokeWidth = size.height,
                cap = StrokeCap.Round
            )
        }
    }

    private fun toggleGroup(group: String) {
        activeGroup = if (activeGroup == group || !hasVisibleCommands(group)) null else group
        listener?.onShowControls()
    }

    /**
     * 进度条步进：单击跳转 10 秒，按住连发后跳转 30 秒。
     */
    private fun seekStepFraction(event: androidx.compose.ui.input.key.KeyEvent, durationMs: Long): Float {
        if (durationMs <= 0) return 0f
        val stepMs = if (event.nativeKeyEvent.repeatCount < 5) 10_000L else 30_000L
        return stepMs.toFloat() / durationMs.toFloat()
    }

    private fun setCommandGroupVisibility(key: String, visible: Boolean) {
        val index = commandGroups.indexOfFirst { it.key == key }
        if (index < 0) return
        val group = commandGroups[index]
        commandGroups[index] = group.copy(visible = visible)
        if (activeGroup == key) validateActiveGroup()
    }

    private fun validateActiveGroup() {
        val group = activeGroup ?: return
        if (hasVisibleCommands(group)) return
        activeGroup = null
        settingsCloseFocus++
    }

    private fun hasVisibleCommands(group: String): Boolean {
        val groupState = commandGroups.firstOrNull { it.key == group && it.visible } ?: return false
        return groupState.commandKeys.any { key ->
            commands[key]?.let { it.visible && it.label.isNotEmpty() } == true
        }
    }

    private fun subtitleText(): String {
        return buildString {
            append(secondaryText)
            if (secondaryText.isNotEmpty() && tertiaryText.isNotEmpty()) append(" • ")
            append(tertiaryText)
        }
    }

    private fun normalize(value: Long?): Long {
        val time = value ?: 0L
        return if (time == C.TIME_UNSET || time < 0L) 0L else time
    }

    private fun progress(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    companion object {
        const val GROUP_PLAYLIST = "playlist"
        const val GROUP_CAPTIONS = "captions"
        const val GROUP_SETTINGS = "settings"
        const val ACTION_PLAY = "play"
        const val ACTION_FORWARD = "forward"
        const val ACTION_REWIND = "rewind"

        private val PLAYLIST_COMMANDS = listOf("prev", "next", "change", "parse", "replay", "reset")
        private val CAPTION_COMMANDS = listOf("subtitle", "text", "audio", "video", "ai", "ai_language", "danmaku")
        private val SETTINGS_COMMANDS = listOf("speed", "scale", "player", "decode", "opening", "ending", "edition", "chapter")
    }
}
