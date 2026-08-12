package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.ui.components.JetStreamPageScrim
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.fongmi.android.tv.ui.theme.JetStreamTheme
import com.fongmi.android.tv.ui.theme.JetStreamAnimations
import com.fongmi.android.tv.ui.theme.JetStreamShapes
import com.fongmi.android.tv.ui.theme.JetStreamBorders
import com.fongmi.android.tv.ui.theme.JetStreamSpacing
import com.fongmi.android.tv.ui.theme.JetStreamSizes

class JetStreamSettingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    interface Listener {
        fun onSettingAction(key: String)
        fun onSettingLongAction(key: String)
    }

    private data class SectionSpec(
        val key: String,
        val label: String,
        val rows: List<RowSpec>
    )

    private data class RowSpec(
        val key: String,
        val label: String,
        val actions: List<ActionSpec> = emptyList(),
        val toggle: Boolean = false
    )

    private data class ActionSpec(
        val key: String,
        val label: String,
        @param:DrawableRes val icon: Int? = null,
        val swatch: Int? = null,
        val selected: Boolean = false
    )

    private var listener: Listener? = null
    private var selectedSectionKey by mutableStateOf(SECTION_SOURCE)
    private var focusedSectionKey by mutableStateOf<String?>(null)
    private var focusedRowKey by mutableStateOf<String?>(null)
    private var initialFocusRequest by mutableIntStateOf(0)
    private var themeRefreshToken by mutableIntStateOf(0)
    private val rowValues = mutableStateMapOf<String, String>()
    private val rowVisible = mutableStateMapOf<String, Boolean>()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    @Composable
    override fun Content() {
        JetStreamTheme {
            SettingsSurface()
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setRowValue(key: String, value: CharSequence?) {
        rowValues[key] = value?.toString().orEmpty()
    }

    fun setRowVisible(key: String, visible: Boolean) {
        val restoreFocus = !visible && isRowVisible(key) && focusedRowKey == key && hasFocus()
        rowVisible[key] = visible
        val restoreSectionFocus = !visible && focusedSectionKey?.let { !isSectionVisible(it) } == true && hasFocus()
        if (restoreFocus || restoreSectionFocus) {
            focusedSectionKey = null
            focusedRowKey = null
            post {
                if (isShown && isEnabled) {
                    requestFocus()
                    initialFocusRequest += 1
                }
            }
        }
    }

    fun refreshThemeSelection() {
        themeRefreshToken += 1
    }

    fun showSection(sectionKey: String) {
        selectedSectionKey = sectionKey
    }

    fun requestInitialFocus() {
        initialFocusRequest += 1
        post {
            if (isShown && isEnabled) {
                requestFocus()
                initialFocusRequest += 1
            }
        }
    }

    private fun selectSection(key: String) {
        if (sections().any { it.key == key && it.rows.any { row -> isRowVisible(row.key) } }) selectedSectionKey = key
    }

    private fun triggerSettingAction(key: String, longClick: Boolean) {
        if (!isActionAvailable(key)) return
        if (longClick) listener?.onSettingLongAction(key) else listener?.onSettingAction(key)
    }

    private fun isActionAvailable(key: String): Boolean {
        sections().flatMap { it.rows }.filter { isRowVisible(it.key) }.forEach { row ->
            if (row.key == key || row.actions.any { action -> action.key == key }) return true
        }
        return false
    }

    @Composable
    private fun SettingsSurface() {
        val sections = sections(themeRefreshToken)
        val visibleSections = sections.filter { section -> section.rows.any { isRowVisible(it.key) } }
        val selectedSection = visibleSections.firstOrNull { it.key == selectedSectionKey } ?: visibleSections.firstOrNull()
        val selectedRows = selectedSection?.rows.orEmpty().filter { isRowVisible(it.key) }
        val firstRowFocusRequester = remember(selectedSection?.key, selectedRows.firstOrNull()?.key) { FocusRequester() }
        val listState = rememberLazyListState()

        LaunchedEffect(visibleSections.map { it.key }, selectedSectionKey) {
            if (selectedSection == null) return@LaunchedEffect
            if (selectedSection.key != selectedSectionKey) selectedSectionKey = selectedSection.key
        }
        LaunchedEffect(selectedSection?.key) {
            listState.scrollToItem(0)
        }
        LaunchedEffect(initialFocusRequest, selectedSection?.key, selectedRows.firstOrNull()?.key) {
            if (initialFocusRequest > 0 && selectedRows.isNotEmpty() && hasFocus()) runCatching { firstRowFocusRequester.requestFocus() }
        }

        JetStreamPageScrim(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                NavigationPanel(
                    sections = visibleSections,
                    selectedKey = selectedSection?.key.orEmpty(),
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                )
                ContentPanel(
                    section = selectedSection,
                    rows = selectedRows,
                    firstRowFocusRequester = firstRowFocusRequester,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    listState = listState
                )
            }
        }
    }

    @Composable
    private fun NavigationPanel(sections: List<SectionSpec>, selectedKey: String, modifier: Modifier) {
        Column(
            modifier = modifier
                .clip(JetStreamShapes.Card)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                .border(JetStreamBorders.Thin, MaterialTheme.colorScheme.outlineVariant, JetStreamShapes.Card)
                .padding(JetStreamSpacing.ButtonHorizontalPadding)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.msr_settings),
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(JetStreamSpacing.IconPadding))
                Text(
                    text = context.getString(R.string.home_setting),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(JetStreamSpacing.ExtraLarge))
            Column(verticalArrangement = Arrangement.spacedBy(JetStreamSpacing.IconPadding)) {
                sections.forEach { section ->
	                    SectionButton(
	                        section = section,
	                        selected = section.key == selectedKey,
	                        onClick = { selectSection(section.key) }
	                    )
	                }
	            }
        }
    }

    @Composable
    private fun ContentPanel(
        section: SectionSpec?,
        rows: List<RowSpec>,
        firstRowFocusRequester: FocusRequester,
        modifier: Modifier,
        contentPadding: PaddingValues,
        listState: androidx.compose.foundation.lazy.LazyListState
    ) {
        Column(
            modifier = modifier
                .clip(JetStreamShapes.Card)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
                .border(JetStreamBorders.Thin, MaterialTheme.colorScheme.outlineVariant, JetStreamShapes.Card)
                .padding(horizontal = JetStreamSpacing.CardPadding, vertical = JetStreamSpacing.ButtonHorizontalPadding)
        ) {
            Text(
                text = section?.label.orEmpty(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(JetStreamSpacing.Medium))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(JetStreamSpacing.Medium)
            ) {
                items(rows, key = { it.key }) { row ->
                    SettingRow(
                        row = row,
                        focusRequester = if (row.key == rows.firstOrNull()?.key) firstRowFocusRequester else null
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SectionButton(section: SectionSpec, selected: Boolean, onClick: () -> Unit) {
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        val scale by animateFloatAsState(
            if (focused) JetStreamAnimations.FocusScaleMedium else 1.0f,
            animationSpec = JetStreamAnimations.ScaleSpring,
            label = "sectionScale"
        )
        val background by animateColorAsState(
            targetValue = when {
                focused -> MaterialTheme.colorScheme.primaryContainer
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "sectionBackground"
        )
        val textColor by animateColorAsState(
            targetValue = when {
                focused -> MaterialTheme.colorScheme.onPrimaryContainer
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "sectionText"
        )
        LaunchedEffect(focused) {
            if (focused) focusedSectionKey = section.key
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(RoundedCornerShape(22.dp))
                .background(background)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = section.label,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SettingRow(row: RowSpec, focusRequester: FocusRequester?) {
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        val scale by animateFloatAsState(
            if (focused) JetStreamAnimations.FocusScaleSmall else 1.0f,
            animationSpec = JetStreamAnimations.ScaleSpring,
            label = "rowScale"
        )
        val background by animateColorAsState(
            targetValue = if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            animationSpec = JetStreamAnimations.ColorTween,
            label = "rowBackground"
        )
        val labelColor by animateColorAsState(
            targetValue = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            animationSpec = JetStreamAnimations.ColorTween,
            label = "rowLabel"
        )
        val valueColor by animateColorAsState(
            targetValue = if (focused) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = JetStreamAnimations.ColorTween,
            label = "rowValue"
        )
        val value = rowValues[row.key].orEmpty()
        val onText = context.getString(R.string.setting_on)
        val requesterModifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
        LaunchedEffect(focused) {
            if (focused) focusedRowKey = row.key
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(JetStreamSizes.CardMinHeight)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(JetStreamShapes.Large)
                .background(background)
                .then(requesterModifier)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { triggerSettingAction(row.key, false) },
                    onLongClick = { triggerSettingAction(row.key, true) }
                )
                .padding(horizontal = JetStreamSpacing.CardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            ) {
                Text(
                    text = row.label,
                    color = labelColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (value.isNotEmpty() && row.actions.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = value,
                        color = valueColor,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (row.toggle) {
                Switch(
                    checked = value == onText,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = if (focused) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = Color.Transparent,
                        uncheckedBorderColor = if (focused) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline
                    )
                )
            } else if (row.actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.actions.forEach { ActionChip(row.key, it) }
                }
            } else if (value.isNotEmpty()) {
                Text(
                    text = value,
                    modifier = Modifier.widthIn(max = 320.dp),
                    color = valueColor,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ActionChip(rowKey: String, action: ActionSpec) {
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        val selected = action.selected
        val scale by animateFloatAsState(
            if (focused) JetStreamAnimations.FocusScaleMedium else 1.0f,
            animationSpec = JetStreamAnimations.ScaleSpring,
            label = "chipScale"
        )
        val background by animateColorAsState(
            targetValue = when {
                focused -> MaterialTheme.colorScheme.primaryContainer
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "chipBackground"
        )
        val contentColor by animateColorAsState(
            targetValue = when {
                focused -> MaterialTheme.colorScheme.onPrimaryContainer
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = JetStreamAnimations.ColorTween,
            label = "chipContent"
        )
        val outlineColor by animateColorAsState(
            targetValue = if (focused || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            animationSpec = JetStreamAnimations.ColorTween,
            label = "chipOutline"
        )
        LaunchedEffect(focused) {
            if (focused) focusedRowKey = rowKey
        }
        Row(
            modifier = Modifier
                .height(38.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(RoundedCornerShape(19.dp))
                .background(background)
                .border(JetStreamBorders.Thin, outlineColor, RoundedCornerShape(19.dp))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { triggerSettingAction(action.key, false) },
                    onLongClick = { triggerSettingAction(action.key, true) }
                )
                .padding(start = JetStreamSpacing.IconPadding, end = JetStreamSpacing.ChipHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (action.swatch != null) {
                val swatchColor = Color(action.swatch.toLong() and 0xFFFFFFFF)
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    lightenColor(swatchColor, 0.28f),
                                    swatchColor,
                                    darkenColor(swatchColor, 0.32f)
                                ),
                                center = Offset(5.4f, 5.4f),
                                radius = 22f
                            )
                        )
                        .border(1.dp, contentColor.copy(alpha = 0.48f), CircleShape)
                )
            } else if (action.icon != null) {
                Icon(
                    painter = painterResource(id = action.icon),
                    contentDescription = action.label,
                    modifier = Modifier.size(19.dp),
                    tint = contentColor
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = action.label,
                color = contentColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = painterResource(id = R.drawable.msr_check),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = contentColor
                )
            }
        }
    }

    private fun isRowVisible(key: String): Boolean {
        return rowVisible[key] ?: true
    }

    private fun isSectionVisible(key: String): Boolean {
        return sections().firstOrNull { it.key == key }?.rows?.any { isRowVisible(it.key) } == true
    }

    private fun themeActions(): List<ActionSpec> {
        val selected = Setting.getThemeColor()
        return JetStreamPalette.presets().map { palette ->
            ActionSpec(
                key = themeColorAction(palette.value),
                label = context.getString(palette.labelRes),
                swatch = palette.swatch,
                selected = palette.value == selected
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun sections(themeRefresh: Int = themeRefreshToken): List<SectionSpec> {
        return listOf(
            SectionSpec(
                key = SECTION_SOURCE,
                label = context.getString(R.string.setting_section_source),
                rows = listOf(
                    RowSpec(
                        key = KEY_VOD,
                        label = context.getString(R.string.setting_vod),
                        actions = listOf(
                            ActionSpec(KEY_VOD_HOME, context.getString(R.string.setting_home), R.drawable.msr_home),
                            ActionSpec(KEY_VOD_HISTORY, context.getString(R.string.setting_history), R.drawable.msr_history)
                        )
                    ),
                    RowSpec(
                        key = KEY_LIVE,
                        label = context.getString(R.string.setting_live),
                        actions = listOf(
                            ActionSpec(KEY_LIVE_HOME, context.getString(R.string.setting_home), R.drawable.msr_home),
                            ActionSpec(KEY_LIVE_HISTORY, context.getString(R.string.setting_history), R.drawable.msr_history)
                        )
                    ),
                    RowSpec(
                        key = KEY_WALL,
                        label = context.getString(R.string.setting_wall),
                        actions = listOf(
                            ActionSpec(KEY_WALL_DEFAULT, context.getString(R.string.setting_default), R.drawable.msr_home),
                            ActionSpec(KEY_WALL_REFRESH, context.getString(R.string.setting_refresh), R.drawable.msr_refresh)
                        )
                    ),
                    RowSpec(
                        key = KEY_TMDB_PROXY,
                        label = context.getString(R.string.setting_tmdb_proxy)
                    )
                )
            ),
            SectionSpec(
                key = SECTION_PLAYBACK,
                label = context.getString(R.string.setting_section_playback),
                rows = listOf(
                    RowSpec(KEY_ENGINE, context.getString(R.string.player_engine)),
                    RowSpec(KEY_RENDER, context.getString(R.string.player_render)),
                    RowSpec(KEY_SCALE, context.getString(R.string.player_scale)),
                    RowSpec(KEY_SPEED, context.getString(R.string.player_speed)),
                    RowSpec(KEY_SEEK_ACCELERATE, context.getString(R.string.setting_seek_accelerate), toggle = true),
                    RowSpec(KEY_CAPTION, context.getString(R.string.player_caption)),
                    RowSpec(KEY_BACKGROUND, context.getString(R.string.player_background), toggle = true),
                    RowSpec(KEY_UA, context.getString(R.string.player_ua)),
                    RowSpec(KEY_AI_SUBTITLE, context.getString(R.string.player_ai_subtitle)),
                    RowSpec(KEY_MPV_CONF, context.getString(R.string.player_mpv_conf)),
                    RowSpec(KEY_MPV_ANIME4K, context.getString(R.string.player_mpv_anime4k)),
                    RowSpec(KEY_MPV_GPU_NEXT, context.getString(R.string.player_mpv_gpu_next), toggle = true),
                    RowSpec(KEY_MPV_VULKAN, context.getString(R.string.player_mpv_vulkan), toggle = true),
                    RowSpec(KEY_MPV_HDR, context.getString(R.string.player_mpv_hdr)),
                    RowSpec(KEY_ADBLOCK, context.getString(R.string.player_adblock), toggle = true)
                )
            ),
            SectionSpec(
                key = SECTION_DECODE,
                label = context.getString(R.string.setting_section_decode),
                rows = listOf(
                    RowSpec(KEY_TUNNEL, context.getString(R.string.player_tunnel), toggle = true),
                    RowSpec(KEY_AUDIO_PASS_THROUGH, context.getString(R.string.player_audio_pass_through), toggle = true),
                    RowSpec(KEY_AUDIO_PREFER, context.getString(R.string.player_audio_decode), toggle = true),
                    RowSpec(KEY_VIDEO_PREFER, context.getString(R.string.player_video_decode), toggle = true),
                    RowSpec(KEY_AAC, context.getString(R.string.player_aac_track), toggle = true),
                    RowSpec(KEY_AV3A, context.getString(R.string.player_av3a), toggle = true),
                    RowSpec(KEY_DOLBY, context.getString(R.string.player_mpv_dolby), toggle = true),
                    RowSpec(KEY_DV7, context.getString(R.string.player_dv7), toggle = true)
                )
            ),
            SectionSpec(
                key = SECTION_PRELOAD,
                label = context.getString(R.string.setting_section_preload),
                rows = listOf(
                    RowSpec(KEY_PRELOAD, context.getString(R.string.player_preload), toggle = true),
                    RowSpec(KEY_PRELOAD_THREADS, context.getString(R.string.player_preload_threads)),
                    RowSpec(KEY_PRELOAD_SIZE, context.getString(R.string.player_preload_size)),
                    RowSpec(KEY_PRELOAD_TIME, context.getString(R.string.player_preload_time))
                )
            ),
            SectionSpec(
                key = SECTION_DANMAKU,
                label = context.getString(R.string.setting_section_danmaku),
                rows = listOf(
                    RowSpec(KEY_DANMAKU_LOAD, context.getString(R.string.danmaku_load), toggle = true),
                    RowSpec(KEY_DANMAKU_API, context.getString(R.string.danmaku_api)),
                    RowSpec(KEY_DANMAKU_LOGVAR_API, context.getString(R.string.danmaku_logvar_api)),
                    RowSpec(KEY_DANMAKU_AUTO, context.getString(R.string.danmaku_auto_load), toggle = true),
                    RowSpec(KEY_DANMAKU_SPIDER, context.getString(R.string.danmaku_spider_first), toggle = true)
                )
            ),
            SectionSpec(
                key = SECTION_APP,
                label = context.getString(R.string.setting_section_app),
                rows = listOf(
                    RowSpec(KEY_INCOGNITO, context.getString(R.string.setting_incognito), toggle = true),
                    RowSpec(KEY_DETAIL_FILTER, context.getString(R.string.setting_detail_filter)),
                    RowSpec(KEY_FLAG_FILTER, context.getString(R.string.setting_flag_filter)),
                    RowSpec(KEY_TOAST_FILTER, context.getString(R.string.setting_toast_filter), toggle = true),
                    RowSpec(KEY_TOAST_FILTER_KEYS, context.getString(R.string.setting_toast_filter_keys)),
                    RowSpec(KEY_DOH, context.getString(R.string.setting_doh)),
                    RowSpec(KEY_THEME_COLOR, context.getString(R.string.setting_theme_color), actions = themeActions()),
                    RowSpec(KEY_SIZE, context.getString(R.string.setting_size)),
                    RowSpec(KEY_BACKUP, context.getString(R.string.setting_backup)),
                    RowSpec(KEY_RESTORE, context.getString(R.string.setting_restore)),
                    RowSpec(KEY_CACHE, context.getString(R.string.setting_cache), actions = listOf(ActionSpec(KEY_CACHE, context.getString(R.string.setting_clear), R.drawable.msr_storage))),
                    RowSpec(KEY_MPV_LOG, "MPV播放日志", actions = listOf(ActionSpec(KEY_MPV_LOG_EXPORT, "导出日志", R.drawable.msr_storage))),
                    RowSpec(KEY_QUICKJS_LOG, "JS调试日志", actions = listOf(ActionSpec(KEY_QUICKJS_LOG_EXPORT, "导出日志", R.drawable.msr_storage))),
                    RowSpec(KEY_VERSION, context.getString(R.string.setting_version))
                )
            )
        )
    }

    companion object {
        const val SECTION_SOURCE = "source"
        const val SECTION_PLAYBACK = "playback"
        const val SECTION_DECODE = "decode"
        const val SECTION_PRELOAD = "preload"
        const val SECTION_DANMAKU = "danmaku"
        const val SECTION_APP = "app"

        const val KEY_VOD = "vod"
        const val KEY_LIVE = "live"
        const val KEY_WALL = "wall"
        const val KEY_TMDB_PROXY = "tmdb_proxy"
        const val KEY_VOD_HOME = "vod_home"
        const val KEY_VOD_HISTORY = "vod_history"
        const val KEY_LIVE_HOME = "live_home"
        const val KEY_LIVE_HISTORY = "live_history"
        const val KEY_WALL_DEFAULT = "wall_default"
        const val KEY_WALL_REFRESH = "wall_refresh"

        const val KEY_ENGINE = "engine"
        const val KEY_RENDER = "render"
        const val KEY_SCALE = "scale"
        const val KEY_SPEED = "speed"
        const val KEY_SEEK_ACCELERATE = "seek_accelerate"
        const val KEY_CAPTION = "caption"
        const val KEY_BACKGROUND = "background"
        const val KEY_UA = "ua"
        const val KEY_AI_SUBTITLE = "ai_subtitle"
        const val KEY_AV3A = "av3a"
        const val KEY_DOLBY = "dolby"
        const val KEY_DV7 = "dv7_hevc"
        const val KEY_MPV_CONF = "mpv_conf"
        const val KEY_MPV_ANIME4K = "mpv_anime4k"
        const val KEY_MPV_GPU_NEXT = "mpv_gpu_next"
        const val KEY_MPV_VULKAN = "mpv_vulkan"
        const val KEY_MPV_HDR = "mpv_hdr"
        const val KEY_ADBLOCK = "adblock"

        const val KEY_TUNNEL = "tunnel"
        const val KEY_AUDIO_PASS_THROUGH = "audio_pass_through"
        const val KEY_AUDIO_PREFER = "audio_prefer"
        const val KEY_VIDEO_PREFER = "video_prefer"
        const val KEY_AAC = "aac"

        const val KEY_PRELOAD = "preload_switch"
        const val KEY_PRELOAD_THREADS = "preload_threads"
        const val KEY_PRELOAD_SIZE = "preload_size"
        const val KEY_PRELOAD_TIME = "preload_time"

        const val KEY_DANMAKU_LOAD = "danmaku_load"
        const val KEY_DANMAKU_API = "danmaku_api"
        const val KEY_DANMAKU_LOGVAR_API = "danmaku_logvar_api"
        const val KEY_DANMAKU_AUTO = "danmaku_auto"
        const val KEY_DANMAKU_SPIDER = "danmaku_spider"

        const val KEY_INCOGNITO = "incognito"
        const val KEY_DETAIL_FILTER = "detail_filter"
        const val KEY_FLAG_FILTER = "flag_filter"
        const val KEY_TOAST_FILTER = "toast_filter"
        const val KEY_TOAST_FILTER_KEYS = "toast_filter_keys"
        const val KEY_DOH = "doh"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_SIZE = "size"
        const val KEY_BACKUP = "backup"
        const val KEY_RESTORE = "restore"
        const val KEY_CACHE = "cache"
        const val KEY_MPV_LOG = "mpv_log"
        const val KEY_MPV_LOG_EXPORT = "mpv_log_export"
        const val KEY_QUICKJS_LOG = "quickjs_log"
        const val KEY_QUICKJS_LOG_EXPORT = "quickjs_log_export"
        const val KEY_VERSION = "version"
        private const val KEY_THEME_COLOR_PREFIX = "theme_color:"

        @JvmStatic
        fun themeColorAction(value: Int): String {
            return "$KEY_THEME_COLOR_PREFIX$value"
        }

        @JvmStatic
        fun parseThemeColorAction(key: String): Int? {
            if (!key.startsWith(KEY_THEME_COLOR_PREFIX)) return null
            return key.substring(KEY_THEME_COLOR_PREFIX.length).toIntOrNull()
        }
    }
}

private fun lightenColor(color: Color, fraction: Float): Color = Color(
    red = color.red + (1f - color.red) * fraction,
    green = color.green + (1f - color.green) * fraction,
    blue = color.blue + (1f - color.blue) * fraction,
    alpha = color.alpha
)

private fun darkenColor(color: Color, fraction: Float): Color = Color(
    red = color.red * (1f - fraction),
    green = color.green * (1f - fraction),
    blue = color.blue * (1f - fraction),
    alpha = color.alpha
)
