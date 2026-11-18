package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.viewbinding.ViewBinding
import com.fongmi.android.tv.BuildConfig
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.TmdbLibraryApi
import com.fongmi.android.tv.bean.DiscoverMediaKey
import com.fongmi.android.tv.bean.Keep
import com.fongmi.android.tv.bean.LibraryMedia
import com.fongmi.android.tv.calendar.AiringCalendarEntry
import com.fongmi.android.tv.calendar.AiringCalendarRepository
import com.fongmi.android.tv.calendar.AiringCalendarResult
import com.fongmi.android.tv.databinding.ActivityAiringCalendarBinding
import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.ui.base.BaseActivity
import com.fongmi.android.tv.ui.calendar.AiringCalendarDay
import com.fongmi.android.tv.ui.calendar.AiringCalendarItem
import com.fongmi.android.tv.ui.calendar.AiringCalendarPreview
import com.fongmi.android.tv.ui.calendar.AiringCalendarState
import com.fongmi.android.tv.ui.calendar.AiringCalendarView
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Calendar browsing owns no source selection or playback requests. */
class AiringCalendarActivity : BaseActivity(), AiringCalendarView.Listener {
    companion object {
        @JvmStatic fun start(activity: Activity) {
            activity.startActivity(Intent(activity, AiringCalendarActivity::class.java))
        }

        private val WEEKDAYS = intArrayOf(R.string.calendar_monday, R.string.calendar_tuesday,
            R.string.calendar_wednesday, R.string.calendar_thursday, R.string.calendar_friday,
            R.string.calendar_saturday, R.string.calendar_sunday)
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("M/d", Locale.ROOT)
    }

    private lateinit var binding: ActivityAiringCalendarBinding
    private lateinit var repository: AiringCalendarRepository
    private var mode = "public"
    private var today = LocalDate.now(AiringCalendarRepository.TIME_ZONE)
    private var selectedDate = today
    private var weekStart = AiringCalendarRepository.weekStart(today)
    private var result = AiringCalendarResult(emptyList(), false, false)
    private var pendingPage = 1
    private var reloadOnResume = false
    private var resumed = false
    private var keepsDirty = false
    private var previewKey = ""
    private var preview = AiringCalendarPreview()
    private val artworkTag = Any()
    private var artworkGeneration = 0
    private val logos = linkedMapOf<String, List<String>>()
    private val originalLanguages = linkedMapOf<String, String>()
    private val logoFallbacks = mutableSetOf<String>()
    private val failedLogos = mutableSetOf<String>()
    private var activeFallback: String? = null
    private var artworkTask: Runnable? = null

    override fun getBinding(): ViewBinding {
        binding = ActivityAiringCalendarBinding.inflate(layoutInflater)
        return binding
    }

    override fun customWall() = false

    override fun initView(savedInstanceState: Bundle?) {
        mode = savedInstanceState?.getString("mode")?.takeIf { it == "following" } ?: "public"
        selectedDate = parseDate(savedInstanceState?.getString("date")) ?: today
        weekStart = AiringCalendarRepository.weekStart(selectedDate)
        binding.calendarRoot.setListener(this)
        repository = AiringCalendarRepository(BuildConfig.TMDB_API_KEY) { value ->
            if (!isFinishing && !isDestroyed) {
                result = value
                val entries = selectedEntries()
                val retained = entries.firstOrNull { it.stableKey == previewKey }
                if (retained == null) setPreview(entries.firstOrNull())
                else {
                    preview = preview.copy(title = retained.media.name, subtitle = episodeLabel(retained),
                        backdrop = retained.media.backdrop.ifBlank { preview.backdrop })
                    if (!value.loading && preview.logo.isEmpty() && logos.containsKey(retained.media.id)) {
                        DiscoverMediaKey.parse(retained.media.id)?.let { fallbackLogos(it, artworkGeneration) }
                    }
                }
                render()
            }
        }
        load()
        binding.calendarRoot.focusKey(savedInstanceState?.getString("focus") ?: "mode:$mode")
    }

    override fun onAction(action: String, key: String) {
        when (action) {
            "mode" -> if (key in listOf("public", "following") && key != mode) {
                mode = key
                load()
            }
            "date" -> parseDate(key)?.let { date ->
                if (date != selectedDate && AiringCalendarRepository.weekStart(date) == weekStart) {
                    selectedDate = date
                    setPreview(null)
                    if (mode == "public") load() else {
                        setPreview(selectedEntries().firstOrNull())
                        render()
                    }
                }
            }
            "week" -> {
                val shift = when (key) { "previous" -> -7L; "next" -> 7L; else -> return }
                selectedDate = selectedDate.plusDays(shift)
                weekStart = AiringCalendarRepository.weekStart(selectedDate)
                load()
            }
            "today" -> {
                today = LocalDate.now(AiringCalendarRepository.TIME_ZONE)
                selectedDate = today
                weekStart = AiringCalendarRepository.weekStart(today)
                load()
            }
            "keeps" -> KeepActivity.start(this)
            "retry" -> repository.retry()
            "loadMore" -> if (mode == "public" && !result.loading && !result.failed
                && result.page < result.totalPages) {
                pendingPage = result.page + 1
                repository.loadPublic(selectedDate, pendingPage)
            }
            "open" -> selectedEntries().firstOrNull { it.stableKey == key }?.let { entry ->
                if (entry.keep != null) LibraryNavigation.keep(this, entry.keep, null)
                else LibraryNavigation.open(this, entry.media, null)
            }
            "logoFailed" -> if (key == previewKey) nextLogo()
        }
    }

    override fun onFocused(key: String) {
        if (key.startsWith("item:")) {
            selectedEntries().firstOrNull { it.stableKey == key.removePrefix("item:") }?.let {
                if (it.stableKey != previewKey) { setPreview(it); render() }
            }
        }
    }

    private fun load() {
        if (!::repository.isInitialized) return
        setPreview(null)
        result = AiringCalendarResult(emptyList(), true, false)
        pendingPage = 1
        keepsDirty = false
        render()
        if (mode == "public") repository.loadPublic(selectedDate)
        else repository.loadPersonal(weekStart, Keep.getVodAndDiscover())
    }

    private fun selectedEntries() = result.items.filter { it.date == selectedDate }

    private fun render() {
        val end = weekStart.plusDays(6)
        val entries = selectedEntries()
        binding.calendarRoot.submit(AiringCalendarState(
            mode = mode,
            weekStart = weekStart.toString(),
            selectedDate = selectedDate.toString(),
            weekLabel = "${weekStart.year}  ${weekStart.format(DATE_FORMAT)} – " +
                (if (end.year != weekStart.year) "${end.year}/" else "") + end.format(DATE_FORMAT),
            days = (0..6).map { offset ->
                val date = weekStart.plusDays(offset.toLong())
                AiringCalendarDay(date.toString(), getString(WEEKDAYS[offset]), date == today)
            },
            items = entries.map { AiringCalendarItem(it.stableKey, it.media.name, it.media.pic, episodeLabel(it)) },
            preview = preview,
            loading = result.loading,
            failed = result.failed,
            hasKeeps = result.hasKeeps,
            hasMore = mode == "public" && result.page < result.totalPages,
            emptyMessage = getString(if (mode == "following" && !result.hasKeeps)
                R.string.calendar_empty_following else R.string.calendar_empty_day)
        ))
    }

    private fun episodeLabel(entry: AiringCalendarEntry) = entry.episodes.joinToString(" · ") {
        String.format(Locale.ROOT, "S%02dE%02d", it.season(), it.number())
    }

    private fun setPreview(entry: AiringCalendarEntry?) {
        cancelArtwork()
        previewKey = entry?.stableKey.orEmpty()
        preview = if (entry == null) AiringCalendarPreview() else AiringCalendarPreview(
            key = entry.stableKey, title = entry.media.name, backdrop = entry.media.backdrop,
            subtitle = episodeLabel(entry)
        )
        if (entry == null) return
        val mediaKey = DiscoverMediaKey.parse(entry.media.id) ?: return
        val known = logos[mediaKey.toString()]
        if (known != null) {
            preview = preview.copy(logo = known.firstOrNull().orEmpty())
            if (known.isEmpty()) fallbackLogos(mediaKey, artworkGeneration)
            return
        }
        val metadata = repository.metadataFor(mediaKey)
        if (metadata != null) {
            bindPreviewMetadata(mediaKey, metadata)
            if (metadata.logos().isEmpty()) fallbackLogos(mediaKey, artworkGeneration)
            return
        }
        val ticket = artworkGeneration
        artworkTask = Runnable {
            TmdbLibraryApi.detail(mediaKey, BuildConfig.TMDB_API_KEY, artworkTag,
                object : TmdbLibraryApi.Listener<LibraryMedia.Metadata> {
                    override fun onSuccess(value: LibraryMedia.Metadata) {
                        if (!artworkCurrent(ticket, entry.stableKey)) return
                        bindPreviewMetadata(mediaKey, value)
                        render()
                        if (value.logos().isEmpty()) fallbackLogos(mediaKey, ticket)
                    }
                    override fun onError(error: Exception) { }
                })
        }.also { binding.calendarRoot.postDelayed(it, 180) }
    }

    private fun bindPreviewMetadata(key: DiscoverMediaKey, value: LibraryMedia.Metadata) {
        logos[key.toString()] = value.logos()
        originalLanguages[key.toString()] = value.originalLanguage()
        preview = preview.copy(logo = value.logos().firstOrNull().orEmpty(),
            backdrop = value.detail().backdrop.ifBlank { preview.backdrop })
    }

    private fun nextLogo() {
        val entry = selectedEntries().firstOrNull { it.stableKey == previewKey } ?: return
        val key = DiscoverMediaKey.parse(entry.media.id) ?: return
        if (preview.logo.isNotEmpty()) failedLogos.add(preview.logo)
        val choices = logos[key.toString()].orEmpty().filter { it !in failedLogos }
        logos[key.toString()] = choices
        preview = preview.copy(logo = choices.firstOrNull().orEmpty())
        render()
        if (choices.isEmpty()) fallbackLogos(key, artworkGeneration)
    }

    private fun fallbackLogos(key: DiscoverMediaKey, ticket: Int) {
        if (mode == "following" && result.loading) return
        if (key.toString() in logoFallbacks || activeFallback == key.toString()) return
        activeFallback = key.toString()
        val selected = previewKey
        TmdbLibraryApi.logos(key, originalLanguages[key.toString()].orEmpty(), BuildConfig.TMDB_API_KEY,
            artworkTag, object : TmdbLibraryApi.Listener<List<String>> {
                override fun onSuccess(value: List<String>) {
                    if (!artworkCurrent(ticket, selected)) return
                    activeFallback = null
                    logoFallbacks.add(key.toString())
                    val fresh = value.filter { it !in failedLogos }
                    logos[key.toString()] = fresh
                    preview = preview.copy(logo = fresh.firstOrNull().orEmpty())
                    render()
                }
                override fun onError(error: Exception) {
                    if (artworkCurrent(ticket, selected)) {
                        activeFallback = null
                        logoFallbacks.add(key.toString())
                    }
                }
            })
    }

    private fun artworkCurrent(ticket: Int, key: String) =
        ticket == artworkGeneration && key == previewKey && !isFinishing && !isDestroyed

    private fun cancelArtwork() {
        artworkGeneration++
        artworkTask?.let { binding.calendarRoot.removeCallbacks(it) }
        artworkTask = null
        activeFallback = null
        TmdbLibraryApi.cancel(artworkTag)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onCalendarRefresh(event: RefreshEvent) {
        if (event.type != RefreshEvent.Type.KEEP) return
        keepsDirty = true
        if (resumed && mode == "following") load()
    }

    override fun onResume() {
        super.onResume()
        if (!::repository.isInitialized) return
        val now = LocalDate.now(AiringCalendarRepository.TIME_ZONE)
        if (now != today) {
            val browsingCurrentWeek = weekStart == AiringCalendarRepository.weekStart(today)
            if (browsingCurrentWeek && (selectedDate == today || now.dayOfWeek.value < today.dayOfWeek.value)) {
                selectedDate = now
                weekStart = AiringCalendarRepository.weekStart(now)
                pendingPage = 1
                reloadOnResume = true
            }
            today = now
        }
        resumed = true
        if (keepsDirty && mode == "following") load()
        else if (reloadOnResume) {
            if (mode == "public") repository.loadPublic(selectedDate, pendingPage)
            else repository.loadPersonal(weekStart, Keep.getVodAndDiscover())
        }
        reloadOnResume = false
        if (preview.logo.isEmpty()) {
            val current = selectedEntries().firstOrNull { it.stableKey == previewKey }
            if (current != null && (!logos.containsKey(current.media.id)
                || (logos[current.media.id].isNullOrEmpty() && current.media.id !in logoFallbacks))) {
                setPreview(current)
            }
        }
        render()
    }

    override fun onPause() {
        resumed = false
        reloadOnResume = result.loading || result.failed
        if (::repository.isInitialized) repository.cancel()
        cancelArtwork()
        super.onPause()
    }

    override fun onThemeChanged() { if (::binding.isInitialized) binding.calendarRoot.refreshTheme() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", mode)
        outState.putString("date", selectedDate.toString())
        outState.putString("focus", binding.calendarRoot.focusedKey())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::repository.isInitialized) repository.close()
        if (::binding.isInitialized) cancelArtwork()
        super.onDestroy()
    }

    private fun parseDate(value: String?): LocalDate? = try {
        value?.let(LocalDate::parse)
    } catch (_: RuntimeException) { null }
}
