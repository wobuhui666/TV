package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.api.TmdbLibraryApi
import com.fongmi.android.tv.bean.DiscoverMediaKey
import com.fongmi.android.tv.bean.Keep
import com.fongmi.android.tv.bean.LibraryMedia
import com.fongmi.android.tv.bean.Vod
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque

internal interface AiringCalendarSource {
    fun airingOn(date: LocalDate, page: Int, tag: Any, listener: TmdbLibraryApi.Listener<AiringCalendarPage>)
    fun detail(key: DiscoverMediaKey, tag: Any, listener: TmdbLibraryApi.Listener<LibraryMedia.Metadata>)
    fun season(key: DiscoverMediaKey, number: Int, tag: Any, listener: TmdbLibraryApi.Listener<List<LibraryMedia.Episode>>)
    fun cancel(tag: Any)
}

private class ProviderCalendarSource(
    private val apiKey: String,
    private val provider: CalendarProvider = CalendarProvider.TMDB,
    private val traktClientId: String = ""
) : AiringCalendarSource {
    override fun airingOn(date: LocalDate, page: Int, tag: Any, listener: TmdbLibraryApi.Listener<AiringCalendarPage>) {
        when (provider) {
            CalendarProvider.TMDB -> TmdbLibraryApi.airingOn(date, page, apiKey, tag,
                object : TmdbLibraryApi.Listener<TmdbLibraryApi.Page> {
                    override fun onSuccess(value: TmdbLibraryApi.Page) = listener.onSuccess(AiringCalendarPage(
                        value.items().filter { DiscoverMediaKey.parse(it.id)?.isMovie == false }
                            .map { AiringCalendarEntry(it, date) }, value.page(), value.totalPages()))
                    override fun onError(error: Exception) = listener.onError(error)
                })
            CalendarProvider.TRAKT -> TraktCalendarApi.airingOn(date, page, traktClientId, tag, listener)
            CalendarProvider.BANGUMI -> BangumiCalendarApi.airingOn(date, page, tag, listener)
        }
    }
    override fun detail(key: DiscoverMediaKey, tag: Any, listener: TmdbLibraryApi.Listener<LibraryMedia.Metadata>) =
        TmdbLibraryApi.detail(key, apiKey, tag, listener)
    override fun season(key: DiscoverMediaKey, number: Int, tag: Any, listener: TmdbLibraryApi.Listener<List<LibraryMedia.Episode>>) =
        TmdbLibraryApi.season(key, number, apiKey, tag, listener)
    override fun cancel(tag: Any) {
        TmdbLibraryApi.cancel(tag)
        CalendarHttpClient.cancel(tag)
    }
}

/** Page-scoped loader. Call on the main thread, matching TMDB's callback delivery. */
class AiringCalendarRepository internal constructor(
    private val source: AiringCalendarSource,
    private val listener: (AiringCalendarResult) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    constructor(apiKey: String, listener: (AiringCalendarResult) -> Unit) :
        this(ProviderCalendarSource(apiKey), listener)

    constructor(apiKey: String, provider: CalendarProvider, traktClientId: String,
        listener: (AiringCalendarResult) -> Unit) :
        this(ProviderCalendarSource(apiKey, provider, traktClientId), listener)

    companion object {
        @JvmField val TIME_ZONE: ZoneId = AiringCalendarIndex.TIME_ZONE
        @JvmStatic fun weekStart(date: LocalDate): LocalDate = AiringCalendarIndex.weekStart(date)
        private const val MAX_CONCURRENT = 4
        private const val CACHE_TTL = 30 * 60_000L
    }

    private data class Cached<T>(val time: Long, val value: T)
    private enum class Scope { LOAD, FOCUS, INDICATOR }
    private data class Job(val key: String, val generation: Int, val scope: Scope = Scope.LOAD,
        val focusToken: Int? = null, val run: () -> Unit)
    private data class Work(
        val key: DiscoverMediaKey,
        val keep: Keep?,
        val media: Vod,
        var metadata: LibraryMedia.Metadata? = null,
        val seasons: MutableMap<Int, Map<LocalDate, List<LibraryMedia.Episode>>> = linkedMapOf()
    )
    private data class FocusWork(val base: AiringCalendarEntry, val work: Work, val token: Int,
        val listener: (AiringCalendarFocusResult) -> Unit)

    private val tag = Any()
    private val focusTag = Any()
    private val indicatorTag = Any()
    private val queue = ArrayDeque<Job>()
    private val active = linkedMapOf<String, Job>()
    private val failures = linkedMapOf<String, Job>()
    private val auxiliaryFailures = linkedMapOf<String, Job>()
    private val updateDates = mutableMapOf<LocalDate, Cached<Boolean>>()
    private val publicItems = linkedMapOf<String, AiringCalendarEntry>()
    private val personal = linkedMapOf<DiscoverMediaKey, Work>()
    private val metadataCache = cache<DiscoverMediaKey, LibraryMedia.Metadata>(64)
    private val seasonCache = cache<String, Map<LocalDate, List<LibraryMedia.Episode>>>(256)
    private val publicCache = cache<String, AiringCalendarPage>(64)
    private var generation = 0
    private var pumping = false
    private var closed = false
    private var publicDate: LocalDate? = null
    private var personalWeek: LocalDate? = null
    private var page = 1
    private var totalPages = 1
    private var hasKeeps = false
    private var focusToken = 0
    private var focused: FocusWork? = null

    fun loadPublic(date: LocalDate, page: Int = 1) {
        if (closed) return
        val requestedPage = page.coerceAtLeast(1)
        val append = personalWeek == null && publicDate == date && requestedPage > 1
        cancel()
        personal.clear()
        personalWeek = null
        publicDate = date
        hasKeeps = false
        if (!append) {
            publicItems.clear()
            this.page = 1
            totalPages = 1
        }
        val cacheKey = "$date|$requestedPage"
        val cached = value(publicCache, cacheKey)
        if (cached != null) {
            addPublic(date, cached)
            notifyState()
            return
        }
        val ticket = generation
        enqueue(Job("public|$cacheKey", ticket) {
            source.airingOn(date, requestedPage, tag, object : TmdbLibraryApi.Listener<AiringCalendarPage> {
                override fun onSuccess(value: AiringCalendarPage) {
                    if (!current(ticket)) return
                    publicCache[cacheKey] = Cached(clock(), value)
                    addPublic(date, value)
                    finish("public|$cacheKey", ticket, true)
                }
                override fun onError(error: Exception) = finish("public|$cacheKey", ticket, false)
            })
        })
        notifyState()
        pump()
    }

    fun loadPersonal(weekStart: LocalDate, keeps: List<Keep>) {
        if (closed) return
        cancel()
        publicDate = null
        publicItems.clear()
        personalWeek = AiringCalendarIndex.weekStart(weekStart)
        personal.clear()
        page = 1
        totalPages = 1
        val followed = AiringCalendarIndex.keepsByMedia(keeps)
        hasKeeps = followed.isNotEmpty()
        followed.forEach { (key, keep) ->
            val media = Vod().apply {
                id = key.toString()
                name = keep.vodName.orEmpty()
                pic = keep.vodPic.orEmpty()
                typeName = DiscoverMediaKey.TV
            }
            val work = Work(key, keep, media)
            personal[key] = work
            val cached = value(metadataCache, key)
            if (cached != null) applyMetadata(work, cached)
            else enqueueMetadata(work)
        }
        notifyState()
        pump()
    }

    /** Six other dated discovery pages, sharing the existing bounded queue and page cache. */
    fun loadWeekIndicators(weekStart: LocalDate) {
        val selected = publicDate ?: return
        val week = AiringCalendarIndex.weekStart(weekStart)
        if (closed || personalWeek != null || AiringCalendarIndex.weekStart(selected) != week) return
        val ticket = generation
        repeat(7) { offset ->
            val date = week.plusDays(offset.toLong())
            if (date == selected) return@repeat
            val cacheKey = "$date|1"
            val cached = value(publicCache, cacheKey)
            if (cached != null) rememberUpdateDate(date, cached)
            else {
                val jobKey = "indicator|$date"
                enqueue(Job(jobKey, ticket, Scope.INDICATOR) {
                    source.airingOn(date, 1, indicatorTag, object : TmdbLibraryApi.Listener<AiringCalendarPage> {
                        override fun onSuccess(value: AiringCalendarPage) {
                            if (!current(ticket)) return
                            publicCache[cacheKey] = Cached(clock(), value)
                            rememberUpdateDate(date, value)
                            finish(jobKey, ticket, true)
                        }
                        override fun onError(error: Exception) = finish(jobKey, ticket, false)
                    })
                })
            }
        }
        notifyState()
        pump()
    }

    /** Enrich only the focused public work; followed works reuse their ongoing full scan. */
    fun focus(entry: AiringCalendarEntry, listener: (AiringCalendarFocusResult) -> Unit) {
        if (closed) return
        clearFocus()
        val key = DiscoverMediaKey.parse(entry.media.id)?.takeUnless { it.isMovie }
        if (key == null) {
            listener(AiringCalendarFocusResult(entry, null, false, false))
            pump()
            return
        }
        val personalWork = personal[key]
        val work = personalWork ?: Work(key, entry.keep, entry.media)
        focused = FocusWork(entry, work, focusToken, listener)
        if (personalWork == null) {
            val metadata = metadataFor(key)
            if (metadata != null) applyMetadata(work, metadata, focusToken)
            else enqueueMetadata(work, focusToken)
        }
        notifyState()
        pump()
    }

    fun cancelFocus() {
        clearFocus()
        pump()
    }

    private fun clearFocus() {
        focusToken++
        focused = null
        queue.removeIf { it.scope == Scope.FOCUS }
        active.entries.removeIf { it.value.scope == Scope.FOCUS }
        auxiliaryFailures.entries.removeIf { it.value.scope == Scope.FOCUS }
        source.cancel(focusTag)
    }

    /** Retry only failed requests; completed work and its exact dates remain visible. */
    fun retry() {
        if (closed || failures.isEmpty() && auxiliaryFailures.isEmpty()) return
        val retry = failures.values.toList() + auxiliaryFailures.values.toList()
        failures.clear()
        auxiliaryFailures.clear()
        retry.forEach(::enqueue)
        notifyState()
        pump()
    }

    /** Reuse loaded artwork metadata without adding a request to the personal scan. */
    fun metadataFor(key: DiscoverMediaKey): LibraryMedia.Metadata? =
        personal[key]?.metadata ?: value(metadataCache, key)

    fun hasPendingRequests(): Boolean = queue.isNotEmpty() || active.isNotEmpty()

    fun cancel() {
        clearFocus()
        generation++
        queue.clear()
        active.clear()
        failures.clear()
        auxiliaryFailures.clear()
        source.cancel(tag)
        source.cancel(indicatorTag)
    }

    fun close() {
        if (closed) return
        closed = true
        cancel()
        metadataCache.clear()
        seasonCache.clear()
        publicCache.clear()
        personal.clear()
        publicItems.clear()
        updateDates.clear()
    }

    private fun enqueueMetadata(work: Work, focus: Int? = null) {
        val ticket = generation
        val jobKey = (if (focus == null) "detail|" else "focus-detail|") + work.key
        val scope = if (focus == null) Scope.LOAD else Scope.FOCUS
        enqueue(Job(jobKey, ticket, scope, focus) {
            source.detail(work.key, if (focus == null) tag else focusTag, object : TmdbLibraryApi.Listener<LibraryMedia.Metadata> {
                override fun onSuccess(value: LibraryMedia.Metadata) {
                    if (!current(ticket, focus)) return
                    metadataCache[work.key] = Cached(clock(), value)
                    applyMetadata(work, value, focus)
                    finish(jobKey, ticket, true, focus)
                }
                override fun onError(error: Exception) = finish(jobKey, ticket, false, focus)
            })
        })
    }

    private fun applyMetadata(work: Work, metadata: LibraryMedia.Metadata, focus: Int? = null) {
        work.metadata = metadata
        val detail = metadata.detail()
        work.media.apply {
            if (detail.title.isNotBlank()) name = detail.title
            originalName = detail.originalTitle
            if (detail.poster.isNotBlank()) pic = detail.poster
            backdrop = detail.backdrop
            content = detail.overview
            year = detail.year
            remarks = detail.rating
            genreText = detail.genres
        }
        val base = focused?.takeIf { it.token == focus && it.work === work }?.base
        val numbers = if (base != null && base.provider != CalendarProvider.TMDB) {
            base.episodes.map { it.season() }.filter { it >= 0 }.distinct().sorted()
        } else AiringCalendarIndex.seasonNumbers(metadata)
        val currentSeasons = listOfNotNull(metadata.lastAired()?.season(), metadata.nextAiring()?.season()).toSet()
        // Focus jobs enter the front of the queue, so enqueue current seasons last.
        val ordered = if (focus == null) numbers else numbers.filterNot { it in currentSeasons } +
            numbers.filter { it in currentSeasons }
        ordered.forEach { number ->
            val cached = value(seasonCache, "${work.key}|$number")
            if (cached != null) work.seasons[number] = cached
            else enqueueSeason(work, number, focus)
        }
    }

    private fun enqueueSeason(work: Work, number: Int, focus: Int? = null) {
        val ticket = generation
        val cacheKey = "${work.key}|$number"
        val jobKey = (if (focus == null) "season|" else "focus-season|") + cacheKey
        val scope = if (focus == null) Scope.LOAD else Scope.FOCUS
        enqueue(Job(jobKey, ticket, scope, focus) {
            source.season(work.key, number, if (focus == null) tag else focusTag, object : TmdbLibraryApi.Listener<List<LibraryMedia.Episode>> {
                override fun onSuccess(value: List<LibraryMedia.Episode>) {
                    if (!current(ticket, focus)) return
                    val dates = AiringCalendarIndex.episodeDates(number, value)
                    seasonCache[cacheKey] = Cached(clock(), dates)
                    work.seasons[number] = dates
                    finish(jobKey, ticket, true, focus)
                }
                override fun onError(error: Exception) = finish(jobKey, ticket, false, focus)
            })
        })
    }

    private fun addPublic(date: LocalDate, result: AiringCalendarPage) {
        result.items.filter { validPublicEntry(it, date) }.forEach { entry ->
            publicItems.putIfAbsent(entry.stableKey, entry)
        }
        page = result.page.coerceAtLeast(1)
        totalPages = result.totalPages.coerceAtLeast(page)
        rememberUpdateDate(date, result)
    }

    private fun validPublicEntry(entry: AiringCalendarEntry, date: LocalDate): Boolean {
        if (entry.date != date) return false
        val key = DiscoverMediaKey.parse(entry.media.id)
        if (key != null) return !key.isMovie
        val prefix = when (entry.provider) {
            CalendarProvider.BANGUMI -> "bangumi:subject:"
            CalendarProvider.TRAKT -> "trakt:show:"
            CalendarProvider.TMDB -> return false
        }
        return entry.media.id.startsWith(prefix) && entry.media.id.removePrefix(prefix).toLongOrNull()?.let { it > 0 } == true
    }

    private fun rememberUpdateDate(date: LocalDate, result: AiringCalendarPage) {
        val hasItems = result.items.any { validPublicEntry(it, date) }
        if (hasItems || result.page == 1) {
            val time = publicCache["$date|${result.page}"]?.time ?: clock()
            updateDates[date] = Cached(time, hasItems)
        }
    }

    private fun enqueue(job: Job) {
        if (job.generation == generation && !active.containsKey(job.key)
            && queue.none { it.key == job.key }) {
            if (job.scope == Scope.FOCUS) queue.addFirst(job) else queue.addLast(job)
        }
    }

    private fun pump() {
        if (pumping || closed) return
        pumping = true
        try {
            while (active.size < MAX_CONCURRENT && queue.isNotEmpty() && !closed) {
                val job = queue.removeFirst()
                if (!current(job.generation, job.focusToken)) continue
                active[job.key] = job
                try { job.run() }
                catch (_: Exception) { finish(job.key, job.generation, false, job.focusToken) }
            }
        } finally { pumping = false }
    }

    private fun finish(key: String, ticket: Int, success: Boolean, focus: Int? = null) {
        if (!current(ticket, focus)) return
        val job = active.remove(key) ?: return
        val failed = if (job.scope == Scope.LOAD) failures else auxiliaryFailures
        failed.remove(key)
        if (!success) failed[key] = job
        notifyState()
        pump()
    }

    private fun current(ticket: Int, focus: Int? = null) =
        !closed && ticket == generation && (focus == null || focus == focusToken)

    private fun notifyState() {
        if (closed) return
        val focus = focused
        val focusResult = focus?.let { focusResult(it) }
        if (personalWeek == null && focusResult != null && publicDate == focusResult.entry.date
            && publicItems.containsKey(focusResult.entry.stableKey)) {
            publicItems[focusResult.entry.stableKey] = focusResult.entry
        }
        val week = personalWeek
        val items = if (week == null) publicItems.values.toList() else personal.values.flatMap { work ->
            work.metadata?.let { AiringCalendarIndex.entries(work.media, work.keep, it, work.seasons, week) }
                ?: emptyList()
        }.sortedWith(compareBy({ it.date }, { it.media.name }, { it.media.id }))
        val knownDates = if (week == null) {
            publicDate?.let { selected -> updateDates.filter { (date, cached) ->
                cached.value && clock() - cached.time < CACHE_TTL &&
                    AiringCalendarIndex.weekStart(date) == AiringCalendarIndex.weekStart(selected)
            }.keys.toSet() }
                ?: emptySet()
        } else items.map { it.date }.toSet()
        listener(AiringCalendarResult(items, queue.any { it.scope == Scope.LOAD } || active.values.any { it.scope == Scope.LOAD },
            failures.isNotEmpty(), page, totalPages, hasKeeps, knownDates))
        if (focus != null && focused === focus && focusResult != null) focus.listener(focusResult)
    }

    private fun focusResult(focus: FocusWork): AiringCalendarFocusResult {
        val work = focus.work
        val metadata = work.metadata
        val entry = if (focus.base.provider != CalendarProvider.TMDB) enrichedProviderEntry(focus) else metadata?.let {
            AiringCalendarIndex.entries(work.media, work.keep, it, work.seasons,
                AiringCalendarIndex.weekStart(focus.base.date)).firstOrNull { entry -> entry.date == focus.base.date }
        } ?: if (metadata == null) focus.base else focus.base.copy(episodes = emptyList(), episodeDetails = emptyList())
        fun relevant(job: Job): Boolean = job.scope == Scope.FOCUS && job.focusToken == focus.token ||
            job.scope == Scope.LOAD && (job.key == "detail|${work.key}" || job.key.startsWith("season|${work.key}|"))
        return AiringCalendarFocusResult(entry, metadata,
            queue.any(::relevant) || active.values.any(::relevant),
            failures.values.any(::relevant) || auxiliaryFailures.values.any(::relevant))
    }

    /** External calendar dates and episode coordinates remain authoritative during TMDB enrichment. */
    private fun enrichedProviderEntry(focus: FocusWork): AiringCalendarEntry {
        val base = focus.base
        val indexed = focus.work.seasons.values.flatMap { it.values.flatten() }
            .associateBy { it.season() to it.number() }
        val original = base.episodeDetails.associateBy { it.season() to it.number() }
        val details = base.episodes.map { episode ->
            val coordinate = episode.season() to episode.number()
            val fallback = original[coordinate]
            val tmdb = indexed[coordinate]
            LibraryMedia.Episode(episode.season(), episode.number(),
                tmdb?.name()?.ifBlank { fallback?.name().orEmpty().ifBlank { episode.name() } }
                    ?: fallback?.name().orEmpty().ifBlank { episode.name() },
                tmdb?.overview()?.ifBlank { fallback?.overview().orEmpty() } ?: fallback?.overview().orEmpty(),
                tmdb?.still()?.ifBlank { fallback?.still().orEmpty() } ?: fallback?.still().orEmpty(),
                base.date.toString(), episode.runtime().takeIf { it > 0 } ?: tmdb?.runtime() ?: fallback?.runtime() ?: 0)
        }
        return base.copy(media = focus.work.media, episodeDetails = details)
    }

    private fun <K, V> value(cache: Map<K, Cached<V>>, key: K): V? = cache[key]?.takeIf {
        clock() - it.time < CACHE_TTL
    }?.value

    private fun <K, V> cache(limit: Int): MutableMap<K, Cached<V>> =
        object : LinkedHashMap<K, Cached<V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Cached<V>>?): Boolean = size > limit
        }
}
