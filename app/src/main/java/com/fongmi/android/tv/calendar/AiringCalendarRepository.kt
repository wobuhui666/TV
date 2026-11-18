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
    fun airingOn(date: LocalDate, page: Int, tag: Any, listener: TmdbLibraryApi.Listener<TmdbLibraryApi.Page>)
    fun detail(key: DiscoverMediaKey, tag: Any, listener: TmdbLibraryApi.Listener<LibraryMedia.Metadata>)
    fun season(key: DiscoverMediaKey, number: Int, tag: Any, listener: TmdbLibraryApi.Listener<List<LibraryMedia.Episode>>)
    fun cancel(tag: Any)
}

private class TmdbCalendarSource(private val apiKey: String) : AiringCalendarSource {
    override fun airingOn(date: LocalDate, page: Int, tag: Any, listener: TmdbLibraryApi.Listener<TmdbLibraryApi.Page>) =
        TmdbLibraryApi.airingOn(date, page, apiKey, tag, listener)
    override fun detail(key: DiscoverMediaKey, tag: Any, listener: TmdbLibraryApi.Listener<LibraryMedia.Metadata>) =
        TmdbLibraryApi.detail(key, apiKey, tag, listener)
    override fun season(key: DiscoverMediaKey, number: Int, tag: Any, listener: TmdbLibraryApi.Listener<List<LibraryMedia.Episode>>) =
        TmdbLibraryApi.season(key, number, apiKey, tag, listener)
    override fun cancel(tag: Any) = TmdbLibraryApi.cancel(tag)
}

/** Page-scoped loader. Call on the main thread, matching TMDB's callback delivery. */
class AiringCalendarRepository internal constructor(
    private val source: AiringCalendarSource,
    private val listener: (AiringCalendarResult) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    constructor(apiKey: String, listener: (AiringCalendarResult) -> Unit) :
        this(TmdbCalendarSource(apiKey), listener)

    companion object {
        @JvmField val TIME_ZONE: ZoneId = AiringCalendarIndex.TIME_ZONE
        @JvmStatic fun weekStart(date: LocalDate): LocalDate = AiringCalendarIndex.weekStart(date)
        private const val MAX_CONCURRENT = 4
        private const val CACHE_TTL = 30 * 60_000L
    }

    private data class Cached<T>(val time: Long, val value: T)
    private data class Job(val key: String, val generation: Int, val run: () -> Unit)
    private data class Work(
        val key: DiscoverMediaKey,
        val keep: Keep,
        val media: Vod,
        var metadata: LibraryMedia.Metadata? = null,
        val seasons: MutableMap<Int, Map<LocalDate, List<LibraryMedia.AiringEpisode>>> = linkedMapOf()
    )

    private val tag = Any()
    private val queue = ArrayDeque<Job>()
    private val active = linkedMapOf<String, Job>()
    private val failures = linkedMapOf<String, Job>()
    private val publicItems = linkedMapOf<String, AiringCalendarEntry>()
    private val personal = linkedMapOf<DiscoverMediaKey, Work>()
    private val metadataCache = cache<DiscoverMediaKey, LibraryMedia.Metadata>(64)
    private val seasonCache = cache<String, Map<LocalDate, List<LibraryMedia.AiringEpisode>>>(256)
    private val publicCache = cache<String, TmdbLibraryApi.Page>(64)
    private var generation = 0
    private var pumping = false
    private var closed = false
    private var publicDate: LocalDate? = null
    private var personalWeek: LocalDate? = null
    private var page = 1
    private var totalPages = 1
    private var hasKeeps = false

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
            publish()
            return
        }
        val ticket = generation
        enqueue(Job("public|$cacheKey", ticket) {
            source.airingOn(date, requestedPage, tag, object : TmdbLibraryApi.Listener<TmdbLibraryApi.Page> {
                override fun onSuccess(value: TmdbLibraryApi.Page) {
                    if (!current(ticket)) return
                    publicCache[cacheKey] = Cached(clock(), value)
                    addPublic(date, value)
                    finish("public|$cacheKey", ticket, true)
                }
                override fun onError(error: Exception) = finish("public|$cacheKey", ticket, false)
            })
        })
        publish()
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
        publish()
        pump()
    }

    /** Retry only failed requests; completed work and its exact dates remain visible. */
    fun retry() {
        if (closed || failures.isEmpty()) return
        val retry = failures.values.toList()
        failures.clear()
        retry.forEach(::enqueue)
        publish()
        pump()
    }

    /** Reuse loaded artwork metadata without adding a request to the personal scan. */
    fun metadataFor(key: DiscoverMediaKey): LibraryMedia.Metadata? =
        personal[key]?.metadata ?: value(metadataCache, key)

    fun cancel() {
        generation++
        queue.clear()
        active.clear()
        failures.clear()
        source.cancel(tag)
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
    }

    private fun enqueueMetadata(work: Work) {
        val ticket = generation
        val jobKey = "detail|${work.key}"
        enqueue(Job(jobKey, ticket) {
            source.detail(work.key, tag, object : TmdbLibraryApi.Listener<LibraryMedia.Metadata> {
                override fun onSuccess(value: LibraryMedia.Metadata) {
                    if (!current(ticket)) return
                    metadataCache[work.key] = Cached(clock(), value)
                    applyMetadata(work, value)
                    finish(jobKey, ticket, true)
                }
                override fun onError(error: Exception) = finish(jobKey, ticket, false)
            })
        })
    }

    private fun applyMetadata(work: Work, metadata: LibraryMedia.Metadata) {
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
        AiringCalendarIndex.seasonNumbers(metadata).forEach { number ->
            val cached = value(seasonCache, "${work.key}|$number")
            if (cached != null) work.seasons[number] = cached
            else enqueueSeason(work, number)
        }
    }

    private fun enqueueSeason(work: Work, number: Int) {
        val ticket = generation
        val cacheKey = "${work.key}|$number"
        val jobKey = "season|$cacheKey"
        enqueue(Job(jobKey, ticket) {
            source.season(work.key, number, tag, object : TmdbLibraryApi.Listener<List<LibraryMedia.Episode>> {
                override fun onSuccess(value: List<LibraryMedia.Episode>) {
                    if (!current(ticket)) return
                    val dates = AiringCalendarIndex.episodeDates(number, value)
                    seasonCache[cacheKey] = Cached(clock(), dates)
                    work.seasons[number] = dates
                    finish(jobKey, ticket, true)
                }
                override fun onError(error: Exception) = finish(jobKey, ticket, false)
            })
        })
    }

    private fun addPublic(date: LocalDate, result: TmdbLibraryApi.Page) {
        result.items().forEach { media ->
            val key = DiscoverMediaKey.parse(media.id)
            if (key != null && !key.isMovie) {
                val entry = AiringCalendarEntry(media, date)
                publicItems.putIfAbsent(entry.stableKey, entry)
            }
        }
        page = result.page()
        totalPages = result.totalPages()
    }

    private fun enqueue(job: Job) {
        if (job.generation == generation && !active.containsKey(job.key)
            && queue.none { it.key == job.key }) queue.addLast(job)
    }

    private fun pump() {
        if (pumping || closed) return
        pumping = true
        try {
            while (active.size < MAX_CONCURRENT && queue.isNotEmpty() && !closed) {
                val job = queue.removeFirst()
                if (job.generation != generation) continue
                active[job.key] = job
                try { job.run() }
                catch (_: Exception) { finish(job.key, job.generation, false) }
            }
        } finally { pumping = false }
    }

    private fun finish(key: String, ticket: Int, success: Boolean) {
        if (!current(ticket)) return
        val job = active.remove(key) ?: return
        if (!success) failures[key] = job
        publish()
        pump()
    }

    private fun current(ticket: Int) = !closed && ticket == generation

    private fun publish() {
        if (closed) return
        val week = personalWeek
        val items = if (week == null) publicItems.values.toList() else personal.values.flatMap { work ->
            work.metadata?.let { AiringCalendarIndex.entries(work.media, work.keep, it, work.seasons, week) }
                ?: emptyList()
        }.sortedWith(compareBy({ it.date }, { it.media.name }, { it.media.id }))
        listener(AiringCalendarResult(items, queue.isNotEmpty() || active.isNotEmpty(),
            failures.isNotEmpty(), page, totalPages, hasKeeps))
    }

    private fun <K, V> value(cache: Map<K, Cached<V>>, key: K): V? = cache[key]?.takeIf {
        clock() - it.time < CACHE_TTL
    }?.value

    private fun <K, V> cache(limit: Int): MutableMap<K, Cached<V>> =
        object : LinkedHashMap<K, Cached<V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Cached<V>>?): Boolean = size > limit
        }
}
