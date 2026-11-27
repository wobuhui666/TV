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
import com.fongmi.android.tv.calendar.AccountCalendarLoader
import com.fongmi.android.tv.calendar.CalendarAccountStore
import com.fongmi.android.tv.calendar.CalendarFollow
import com.fongmi.android.tv.calendar.CalendarProvider
import com.fongmi.android.tv.calendar.CalendarScheduleKind
import com.fongmi.android.tv.databinding.ActivityAiringCalendarBinding
import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.ui.base.BaseActivity
import com.fongmi.android.tv.ui.calendar.AiringCalendarDay
import com.fongmi.android.tv.ui.calendar.AiringCalendarItem
import com.fongmi.android.tv.ui.calendar.AiringCalendarPreview
import com.fongmi.android.tv.ui.calendar.AiringCalendarState
import com.fongmi.android.tv.ui.calendar.AiringCalendarView
import com.fongmi.android.tv.ui.calendar.CalendarAccountDialogs
import com.fongmi.android.tv.ui.custom.JetStreamDialogDecor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Calendar facts describe broadcast schedules, independent of playable source availability. */
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
    private lateinit var accountLoader: AccountCalendarLoader
    private lateinit var accountDialogs: CalendarAccountDialogs
    private var provider = CalendarProvider.TMDB
    private var accountBusy = false
    private var mode = "public"
    private var today = LocalDate.now(AiringCalendarRepository.TIME_ZONE)
    private var selectedDate = today
    private var weekStart = AiringCalendarRepository.weekStart(today)
    private var result = AiringCalendarResult(emptyList(), false, false)
    private var pendingPage = 1
    private var reloadOnResume = false
    private var resumed = false
    private var keepsDirty = false
    private var indicatorsRequested: LocalDate? = null
    private var previewKey = ""
    private var enrichmentKey = ""
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
        provider = CalendarProvider.fromId(savedInstanceState?.getString("provider") ?: CalendarAccountStore.provider.id)
        mode = savedInstanceState?.getString("mode")?.takeIf { it == "following" } ?: "public"
        selectedDate = parseDate(savedInstanceState?.getString("date")) ?: today
        weekStart = AiringCalendarRepository.weekStart(selectedDate)
        binding.calendarRoot.setListener(this)
        accountDialogs = CalendarAccountDialogs(this, { sync ->
            if (sync) mode = "following"
            createRepository()
            load(sync)
        }, { busy -> accountBusy = busy; render() })
        accountLoader = AccountCalendarLoader(::acceptResult)
        createRepository()
        load()
        binding.calendarRoot.focusKey(savedInstanceState?.getString("focus") ?: "content")
    }

    private fun createRepository() {
        if (::repository.isInitialized) repository.close()
        repository = AiringCalendarRepository(BuildConfig.TMDB_API_KEY, provider, CalendarAccountStore.traktClientId) {
            if (!remoteFollowing()) acceptResult(it)
        }
    }

    private fun acceptResult(value: AiringCalendarResult) {
        if (!isFinishing && !isDestroyed) {
            result = value
            val entries = selectedEntries()
            val retained = entries.firstOrNull { it.stableKey == previewKey }
            if (retained == null) setPreview(entries.firstOrNull())
            else if (enrichmentKey != retained.stableKey) setPreview(retained)
            else preview = previewFor(retained, metadataFor(retained))
            render()
            requestIndicators()
            requestLogoFallback()
        }
    }

    override fun onAction(action: String, key: String) {
        when (action) {
            "provider" -> JetStreamDialogDecor.tintButtons(MaterialAlertDialogBuilder(this)
                .setTitle(R.string.calendar_provider).setSingleChoiceItems(arrayOf("TMDB", "Trakt", "Bangumi"), provider.ordinal) { dialog, index ->
                    dialog.dismiss()
                    val next = CalendarProvider.entries[index]
                    if (next != provider) {
                        accountDialogs.cancel()
                        provider = next
                        CalendarAccountStore.provider = provider
                        createRepository()
                        load()
                    }
                }.setNegativeButton(R.string.dialog_negative, null).show())
            "account" -> if (provider == CalendarProvider.TRAKT && CalendarAccountStore.traktClientId.isBlank()) {
                accountDialogs.configureTrakt { createRepository(); load(); accountDialogs.show(provider) }
            } else accountDialogs.show(provider)
            "follow" -> selectedEntries().firstOrNull { it.stableKey == previewKey }?.takeIf { it.remoteId > 0 && !accountBusy }?.let { entry ->
                val existing = CalendarAccountStore.follows(provider).firstOrNull { it.remoteId == entry.remoteId }
                accountDialogs.setFollowing(existing ?: CalendarFollow(provider, entry.remoteId, entry.media), existing == null)
            }
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
            "retry" -> {
                cancelArtwork()
                if (remoteFollowing()) load(true) else repository.retry()
            }
            "loadMore" -> if (mode == "public" && !result.loading && !result.failed && result.page < result.totalPages) {
                pendingPage = result.page + 1
                indicatorsRequested = null
                cancelArtwork()
                repository.loadPublic(selectedDate, pendingPage)
            }
            "open" -> selectedEntries().firstOrNull { it.stableKey == key }?.let { entry ->
                if (entry.keep != null) LibraryNavigation.keep(this, entry.keep, null)
                else if (DiscoverMediaKey.parse(entry.media.id) == null) SearchActivity.start(this, entry.media.name)
                else LibraryNavigation.open(this, entry.media, null)
            }
            "logoFailed" -> if (key == previewKey) nextLogo()
        }
    }

    override fun onFocused(key: String) {
        if (key.startsWith("item:")) selectedEntries().firstOrNull { it.stableKey == key.removePrefix("item:") }?.let {
            if (it.stableKey != previewKey || enrichmentKey != it.stableKey) {
                setPreview(it)
                render()
            }
        }
    }

    private fun remoteFollowing() = mode == "following" && provider != CalendarProvider.TMDB

    private fun load(sync: Boolean = false) {
        if (!::repository.isInitialized) return
        repository.cancel()
        accountLoader.cancel()
        setPreview(null)
        result = AiringCalendarResult(emptyList(), true, false)
        pendingPage = 1
        indicatorsRequested = null
        keepsDirty = false
        render()
        if (mode == "public" && provider == CalendarProvider.TRAKT && CalendarAccountStore.traktClientId.isBlank()) {
            result = AiringCalendarResult(emptyList(), false, false)
            render()
        } else if (remoteFollowing() && CalendarAccountStore.token(provider) == null) {
            result = AiringCalendarResult(emptyList(), false, false)
            render()
        } else if (remoteFollowing()) accountLoader.load(provider, weekStart, sync)
        else if (mode == "public") repository.loadPublic(selectedDate)
        else repository.loadPersonal(weekStart, Keep.getVodAndDiscover())
    }

    private fun selectedEntries() = result.items.filter { it.date == selectedDate }

    private fun requestIndicators() {
        if (mode != "public" || result.loading || result.failed || indicatorsRequested == weekStart) return
        indicatorsRequested = weekStart
        repository.loadWeekIndicators(weekStart)
    }

    private fun render() {
        if (!::binding.isInitialized) return
        val entries = selectedEntries()
        val selected = entries.firstOrNull { it.stableKey == previewKey }
        val connected = provider != CalendarProvider.TMDB && CalendarAccountStore.token(provider) != null
        val end = weekStart.plusDays(6)
        val range = if (weekStart.year == end.year) {
            "${weekStart.format(DATE_FORMAT)} – ${end.format(DATE_FORMAT)}"
        } else {
            "${weekStart.year}/${weekStart.format(DATE_FORMAT)} – ${end.year}/${end.format(DATE_FORMAT)}"
        }
        binding.calendarRoot.submit(AiringCalendarState(
            mode = mode, weekStart = weekStart.toString(), selectedDate = selectedDate.toString(),
            weekLabel = range,
            days = (0..6).map { offset ->
                val date = weekStart.plusDays(offset.toLong())
                AiringCalendarDay(date.toString(), getString(WEEKDAYS[offset]), date == today,
                    date in result.knownUpdateDates)
            },
            items = entries.map {
                AiringCalendarItem(it.stableKey, it.media.name, it.media.pic, episodeLabel(it),
                    episodeStill(it), actualBackdrop(it))
            },
            // A rejected token is shown as a reconnect prompt; retrying the same token cannot help.
            preview = preview, loading = result.loading, failed = result.failed && !result.unauthorized,
            hasKeeps = result.hasKeeps, hasMore = mode == "public" && result.page < result.totalPages,
            emptyMessage = getString(when {
                remoteFollowing() && !connected -> R.string.calendar_connected_required
                remoteFollowing() && result.unauthorized -> R.string.calendar_auth_failed
                mode == "public" && provider == CalendarProvider.TRAKT && CalendarAccountStore.traktClientId.isBlank() -> R.string.calendar_client_id_required
                mode == "public" && provider == CalendarProvider.BANGUMI && weekStart != AiringCalendarRepository.weekStart(today) -> R.string.calendar_weekly_scope
                remoteFollowing() && !result.hasKeeps -> R.string.calendar_account_follow_empty
                mode == "following" && !result.hasKeeps -> R.string.calendar_empty_following
                else -> R.string.calendar_empty_day
            }),
            todayDate = today.toString(), provider = provider.id,
            connected = connected, canFollow = connected && (selected?.remoteId ?: 0) > 0,
            followingTitle = selected?.takeIf { connected }?.let { entry ->
                CalendarAccountStore.follows(provider).any { it.remoteId == entry.remoteId }
            } == true, accountBusy = accountBusy
        ))
    }

    private fun episodeLabel(entry: AiringCalendarEntry) = entry.episodeLabel.ifBlank { entry.episodes.joinToString(" · ") {
        String.format(Locale.ROOT, "S%02dE%02d", it.season(), it.number())
    } }

    private fun episodeStill(entry: AiringCalendarEntry) =
        entry.episodeDetails.firstOrNull { it.still().isNotBlank() }?.still().orEmpty()

    private fun actualBackdrop(entry: AiringCalendarEntry) =
        entry.media.backdrop.takeUnless { it == entry.media.pic }.orEmpty()

    private fun metadataFor(entry: AiringCalendarEntry) =
        DiscoverMediaKey.parse(entry.media.id)?.let(repository::metadataFor)

    private fun previewFor(entry: AiringCalendarEntry, metadata: LibraryMedia.Metadata?): AiringCalendarPreview {
        val detail = metadata?.detail()
        val runtime = entry.episodes.map { it.runtime() }.filter { it > 0 }.distinct()
            .ifEmpty { detail?.runtimeMinutes?.takeIf { it > 0 }?.let { listOf(it) } ?: emptyList() }
        val facts = buildList {
            (detail?.rating?.ifBlank { entry.media.remarks } ?: entry.media.remarks).takeIf { it.isNotBlank() }?.let(::add)
            episodeLabel(entry).takeIf { it.isNotBlank() }?.let(::add)
            if (runtime.isNotEmpty()) add(runtime.joinToString(" / ") { getString(R.string.calendar_runtime_minutes, it) })
            metadata?.networkNames()?.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let(::add)
            if (entry.scheduleKind == CalendarScheduleKind.EXACT) add(entry.date.toString())
        }
        return AiringCalendarPreview(
            key = entry.stableKey, title = detail?.title?.ifBlank { entry.media.name } ?: entry.media.name,
            backdrop = episodeStill(entry).ifBlank { detail?.backdrop.orEmpty() }
                .ifBlank { actualBackdrop(entry) }.ifBlank { entry.media.pic },
            logo = logos[entry.media.id]?.firstOrNull() ?: entry.logo.takeUnless { it in failedLogos }.orEmpty(),
            subtitle = entry.episodeDetails.map { it.name() }.filter { it.isNotBlank() }.distinct().joinToString(" · "),
            overview = entry.episodeDetails.firstOrNull { it.overview().isNotBlank() }?.overview()
                ?: detail?.overview?.ifBlank { entry.media.content } ?: entry.media.content,
            metadata = facts,
            airingStatus = when {
                entry.scheduleKind == CalendarScheduleKind.WEEKLY -> "weekly"
                entry.date.isBefore(today) -> "aired"
                entry.date.isAfter(today) -> "upcoming"
                else -> "today"
            }
        )
    }

    private fun setPreview(entry: AiringCalendarEntry?) {
        cancelArtwork()
        previewKey = entry?.stableKey.orEmpty()
        preview = if (entry == null) AiringCalendarPreview() else previewFor(entry, metadataFor(entry))
        if (entry == null) return
        enrichmentKey = entry.stableKey
        val ticket = artworkGeneration
        artworkTask = Runnable {
            repository.focus(entry) { focused ->
                if (!artworkCurrent(ticket, entry.stableKey)) return@focus
                focused.metadata?.let { value ->
                    val id = focused.entry.media.id
                    originalLanguages[id] = value.originalLanguage()
                    if (!logos.containsKey(id)) logos[id] = value.logos().filter { it !in failedLogos }
                }
                preview = previewFor(focused.entry, focused.metadata)
                render()
                requestLogoFallback()
            }
        }.also { binding.calendarRoot.postDelayed(it, 180) }
    }

    private fun nextLogo() {
        val entry = selectedEntries().firstOrNull { it.stableKey == previewKey } ?: return
        if (preview.logo.isNotEmpty()) failedLogos.add(preview.logo)
        logos[entry.media.id] = logos[entry.media.id].orEmpty().filter { it !in failedLogos }
        preview = preview.copy(logo = logos[entry.media.id]?.firstOrNull().orEmpty())
        render()
        requestLogoFallback()
    }

    private fun requestLogoFallback() {
        if (preview.logo.isNotEmpty() || activeFallback != null || repository.hasPendingRequests()) return
        val entry = selectedEntries().firstOrNull { it.stableKey == previewKey } ?: return
        val key = DiscoverMediaKey.parse(entry.media.id) ?: return
        if (!logos.containsKey(key.toString()) || key.toString() in logoFallbacks) return
        activeFallback = key.toString()
        val ticket = artworkGeneration
        val selected = previewKey
        TmdbLibraryApi.logos(key, originalLanguages[key.toString()].orEmpty(), BuildConfig.TMDB_API_KEY,
            artworkTag, object : TmdbLibraryApi.Listener<List<String>> {
                override fun onSuccess(value: List<String>) {
                    if (!artworkCurrent(ticket, selected)) return
                    activeFallback = null
                    logoFallbacks.add(key.toString())
                    logos[key.toString()] = value.filter { it !in failedLogos }
                    preview = preview.copy(logo = logos[key.toString()]?.firstOrNull().orEmpty())
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
        enrichmentKey = ""
        if (::repository.isInitialized) repository.cancelFocus()
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
        binding.calendarRoot.refreshTheme()
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
        if (remoteFollowing() && CalendarAccountStore.token(provider) != null &&
            System.currentTimeMillis() - CalendarAccountStore.lastSync(provider) >= 5 * 60_000) load()
        else if (keepsDirty && mode == "following") load()
        else if (reloadOnResume) {
            if (remoteFollowing()) accountLoader.load(provider, weekStart)
            else if (mode == "public") repository.loadPublic(selectedDate, pendingPage)
            else repository.loadPersonal(weekStart, Keep.getVodAndDiscover())
        }
        reloadOnResume = false
        val current = selectedEntries().firstOrNull { it.stableKey == previewKey } ?: selectedEntries().firstOrNull()
        if (current != null && enrichmentKey != current.stableKey) setPreview(current)
        requestIndicators()
        render()
    }

    override fun onPause() {
        resumed = false
        reloadOnResume = result.loading || result.failed
        indicatorsRequested = null
        if (::repository.isInitialized) repository.cancel()
        if (::accountLoader.isInitialized) accountLoader.cancel()
        if (::accountDialogs.isInitialized) accountDialogs.cancel()
        cancelArtwork()
        super.onPause()
    }

    override fun onThemeChanged() { if (::binding.isInitialized) binding.calendarRoot.refreshTheme() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", mode)
        outState.putString("provider", provider.id)
        outState.putString("date", selectedDate.toString())
        outState.putString("focus", binding.calendarRoot.focusedKey())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::repository.isInitialized) repository.close()
        if (::accountLoader.isInitialized) accountLoader.close()
        if (::accountDialogs.isInitialized) accountDialogs.close()
        if (::binding.isInitialized) cancelArtwork()
        super.onDestroy()
    }

    private fun parseDate(value: String?): LocalDate? = try {
        value?.let(LocalDate::parse)
    } catch (_: RuntimeException) { null }
}
