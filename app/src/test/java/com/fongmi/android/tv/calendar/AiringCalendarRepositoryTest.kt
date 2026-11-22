package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.api.TmdbLibraryApi
import com.fongmi.android.tv.bean.DiscoverMediaKey
import com.fongmi.android.tv.bean.Keep
import com.fongmi.android.tv.bean.LibraryMedia
import com.fongmi.android.tv.bean.Vod
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class AiringCalendarRepositoryTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val source = FakeSource()
    private val results = mutableListOf<AiringCalendarResult>()
    private var now = 1_000L
    private val repository = AiringCalendarRepository(source, { results.add(it) }, { now })

    @Test fun shouldPageAndDeduplicatePublicSchedulesWithoutRequestingShowDetails() {
        repository.loadPublic(monday)
        assertTrue(results.last().loading)
        source.succeed(source.pending.single() as PublicRequest,
            TmdbLibraryApi.Page(listOf(media(8), media(9, "movie")), 1, 3))
        repository.loadPublic(monday, 2)
        source.succeed(source.pending.single() as PublicRequest,
            TmdbLibraryApi.Page(listOf(media(8), media(10)), 2, 3))
        val result = results.last()
        assertEquals(listOf("tmdb:tv:8", "tmdb:tv:10"), result.items.map { it.media.id })
        assertTrue(result.items.all { it.date == monday && it.episodes.isEmpty() })
        assertEquals(2, result.page)
        assertEquals(3, result.totalPages)
        assertFalse(result.loading)
        assertEquals(0, source.detailCalls)
        assertEquals(0, source.seasonCalls)
    }

    @Test fun shouldScanAllSeasonsWithFourConcurrentRequestsAndProgressiveWholeWeekResults() {
        repository.loadPersonal(monday, listOf(keep(8), keep(8)))
        val detail = source.pending.single() as DetailRequest
        source.succeed(detail, metadata(8, (0..8).toList()))
        assertEquals(4, source.pending.size)
        val first = source.pending.first() as SeasonRequest
        source.succeed(first, listOf(episode(first.number, 1, monday)))
        assertEquals(1, results.last().items.size)
        assertTrue(results.last().loading)
        while (source.pending.isNotEmpty()) {
            val request = source.pending.first() as SeasonRequest
            source.succeed(request, listOf(episode(request.number, 1, monday.plusDays(6))))
            assertTrue(source.pending.size <= 4)
        }
        assertEquals((0..8).toList(), source.requestedSeasons.sorted())
        assertEquals(1, source.detailCalls)
        assertEquals(9, results.last().items.sumOf { it.episodes.size })
        assertFalse(results.last().loading)
        assertFalse(results.last().failed)
    }

    @Test fun shouldKeepSuccessfulEntriesAndRetryOnlyFailedSeasons() {
        repository.loadPersonal(monday, listOf(keep(8)))
        source.succeed(source.pending.single() as DetailRequest, metadata(8, listOf(0, 3)))
        val specials = source.pending.filterIsInstance<SeasonRequest>().single { it.number == 0 }
        val third = source.pending.filterIsInstance<SeasonRequest>().single { it.number == 3 }
        source.succeed(specials, listOf(episode(0, 1, monday)))
        source.fail(third)
        assertTrue(results.last().failed)
        assertFalse(results.last().loading)
        assertEquals(1, results.last().items.single().episodes.size)
        repository.retry()
        assertEquals(3, (source.pending.single() as SeasonRequest).number)
        assertEquals(1, results.last().items.single().episodes.size)
        source.succeed(source.pending.single() as SeasonRequest, listOf(episode(3, 2, monday)))
        assertEquals(2, results.last().items.single().episodes.size)
        assertFalse(results.last().failed)
        assertEquals(listOf(0, 3, 3), source.requestedSeasons)
    }

    @Test fun shouldIgnoreLateWeekAndTabResponsesAndCancelQueuedFanOut() {
        repository.loadPersonal(monday, listOf(keep(8)))
        source.succeed(source.pending.single() as DetailRequest, metadata(8, (0..8).toList()))
        val oldSeason = source.pending.first() as SeasonRequest
        repository.loadPublic(monday.plusDays(7))
        val size = results.size
        source.succeed(oldSeason, listOf(episode(oldSeason.number, 1, monday)))
        assertEquals(size, results.size)
        assertEquals(1, source.pending.size)
        assertEquals(4, source.seasonCalls)
        source.succeed(source.pending.single() as PublicRequest,
            TmdbLibraryApi.Page(listOf(media(11)), 1, 1))
        assertEquals("tmdb:tv:11", results.last().items.single().media.id)
        assertEquals(monday.plusDays(7), results.last().items.single().date)
    }

    @Test fun shouldReuseSeasonDateIndexesForAdjacentWeeksAndExpireSessionCaches() {
        repository.loadPersonal(monday, listOf(keep(8)))
        source.succeed(source.pending.single() as DetailRequest, metadata(8, listOf(1)))
        source.succeed(source.pending.single() as SeasonRequest,
            listOf(episode(1, 1, monday), episode(1, 2, monday.plusDays(7))))
        repository.loadPersonal(monday.plusDays(7), listOf(keep(8)))
        assertTrue(source.pending.isEmpty())
        assertEquals(2, results.last().items.single().episodes.single().number())
        assertEquals(1, source.detailCalls)
        assertEquals(1, source.seasonCalls)
        now += 30 * 60_000L
        repository.loadPersonal(monday, listOf(keep(8)))
        assertEquals(1, source.pending.size)
        assertEquals(2, source.detailCalls)
    }

    @Test fun shouldReusePublicPagesButDiscardLateCallbacksAfterClose() {
        repository.loadPublic(monday)
        source.succeed(source.pending.single() as PublicRequest,
            TmdbLibraryApi.Page(listOf(media(8)), 1, 1))
        repository.loadPublic(monday)
        assertTrue(source.pending.isEmpty())
        assertEquals(1, source.publicCalls)
        repository.loadPublic(monday.plusDays(1))
        val request = source.pending.single() as PublicRequest
        repository.close()
        val count = results.size
        source.succeed(request, TmdbLibraryApi.Page(listOf(media(9)), 1, 1))
        repository.loadPublic(monday)
        assertEquals(count, results.size)
        assertTrue(source.pending.isEmpty())
    }

    @Test fun shouldDistinguishNoFavoritesFromFavoritesWithoutAnyDatedEpisodes() {
        repository.loadPersonal(monday, emptyList())
        assertFalse(results.last().hasKeeps)
        repository.loadPersonal(monday, listOf(keep(9).apply { key = "tmdb:movie:9" }))
        assertFalse(results.last().hasKeeps)
        assertTrue(source.pending.isEmpty())
        repository.loadPersonal(monday, listOf(keep(8)))
        source.succeed(source.pending.single() as DetailRequest, metadata(8, emptyList()))
        assertTrue(results.last().hasKeeps)
        assertTrue(results.last().items.isEmpty())
        assertFalse(results.last().loading)
        assertFalse(results.last().failed)
    }

    @Test fun shouldReuseLoadedArtworkAfterTabChangesWithoutExposingExpiredCachedMetadata() {
        val key = DiscoverMediaKey.of("tv", 8)
        repository.loadPersonal(monday, listOf(keep(8)))
        val metadata = metadata(8, emptyList())
        source.succeed(source.pending.single() as DetailRequest, metadata)
        assertSame(metadata, repository.metadataFor(key))
        repository.loadPublic(monday)
        assertSame(metadata, repository.metadataFor(key))
        assertEquals(1, source.detailCalls)
        now += 30 * 60_000L
        assertNull(repository.metadataFor(key))
    }

    @Test fun shouldEnrichOnlyFocusedPublicWorkWithExactDayEpisodesAndStill() {
        repository.loadPublic(monday)
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(8), media(10)), 1, 1))
        val focused = mutableListOf<AiringCalendarFocusResult>()
        repository.focus(results.last().items.first(), focused::add)
        source.succeed(source.pending.single() as DetailRequest,
            metadata(8, listOf(0, 1, 3), "\"vote_average\":8.7,\"overview\":\"剧集简介\",\"networks\":[{\"name\":\"NHK\"}]"))
        assertFalse(results.last().loading)
        assertTrue(focused.last().loading)
        while (source.pending.isNotEmpty()) {
            val request = source.pending.first() as SeasonRequest
            source.succeed(request, when (request.number) {
                1 -> listOf(LibraryMedia.Episode(1, 1, "重逢", "单集简介", "/still.jpg", monday.toString(), 46),
                    episode(1, 2, monday))
                3 -> listOf(episode(3, 1, monday.plusDays(1)))
                else -> emptyList()
            })
        }
        val entry = focused.last().entry
        assertEquals(listOf(1, 2), entry.episodes.map { it.number() })
        assertEquals("/still.jpg", entry.episodeDetails.first().still())
        assertEquals("单集简介", entry.episodeDetails.first().overview())
        assertEquals(listOf("NHK"), focused.last().metadata!!.networkNames())
        assertEquals(1, source.detailCalls)
        assertTrue(results.last().items.last().episodeDetails.isEmpty())
        assertFalse(focused.last().loading)
    }

    @Test fun shouldShareFourSlotsWithIndicatorsAndRejectLateSameKeyFocusCallbacks() {
        repository.loadPublic(monday)
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(8)), 1, 1))
        repository.loadWeekIndicators(monday)
        val focus = mutableListOf<AiringCalendarFocusResult>()
        repository.focus(results.last().items.single(), focus::add)
        assertEquals(4, source.pending.size)
        source.succeed(source.pending.first() as PublicRequest, TmdbLibraryApi.Page(emptyList(), 1, 1))
        source.succeed(source.pending.filterIsInstance<DetailRequest>().single(), metadata(8, (0..8).toList()))
        val old = source.pending.filterIsInstance<SeasonRequest>().first()
        repository.focus(results.last().items.single(), focus::add)
        val count = focus.size
        source.fail(old)
        assertEquals(count, focus.size)
        assertFalse(focus.last().failed)
        assertTrue(source.pending.size <= 4)
        assertTrue(source.pending.filterIsInstance<PublicRequest>().isNotEmpty())
        repository.cancel()
        assertTrue(source.pending.isEmpty())
    }

    @Test fun shouldReuseFullFocusedSeasonCacheAcrossDifferentWeeks() {
        repository.loadPublic(monday)
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(8)), 1, 1))
        val focus = mutableListOf<AiringCalendarFocusResult>()
        repository.focus(results.last().items.single(), focus::add)
        source.succeed(source.pending.single() as DetailRequest, metadata(8, listOf(1)))
        source.succeed(source.pending.single() as SeasonRequest, listOf(
            LibraryMedia.Episode(1, 1, "本周", "本周简介", "/first.jpg", monday.toString(), 45),
            LibraryMedia.Episode(1, 2, "下周", "下周简介", "/second.jpg", monday.plusDays(7).toString(), 46)))
        repository.loadPublic(monday.plusDays(7))
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(8)), 1, 1))
        repository.focus(results.last().items.single(), focus::add)
        assertTrue(source.pending.isEmpty())
        assertEquals("/second.jpg", focus.last().entry.episodeDetails.single().still())
        assertEquals("下周简介", focus.last().entry.episodeDetails.single().overview())
        assertEquals(1, source.detailCalls)
        assertEquals(1, source.seasonCalls)
    }

    @Test fun shouldReuseOngoingPersonalSeasonRequestsWhenFocusingAnEntry() {
        repository.loadPersonal(monday, listOf(keep(8)))
        source.succeed(source.pending.single() as DetailRequest, metadata(8, listOf(0, 1, 3)))
        source.succeed(source.pending.filterIsInstance<SeasonRequest>().single { it.number == 0 },
            listOf(episode(0, 1, monday)))
        val focus = mutableListOf<AiringCalendarFocusResult>()
        repository.focus(results.last().items.single(), focus::add)
        while (source.pending.isNotEmpty()) {
            val request = source.pending.first() as SeasonRequest
            source.succeed(request, listOf(LibraryMedia.Episode(request.number, 1, "单集", "实有简介", "/real.jpg", monday.toString(), 24)))
        }
        assertEquals(1, source.detailCalls)
        assertEquals(3, source.seasonCalls)
        assertEquals(3, focus.last().entry.episodeDetails.size)
        assertFalse(focus.last().loading)
    }

    @Test fun shouldLoadExactWeekIndicatorsWithoutReplacingSelectedItemsAndReuseTheirPages() {
        repository.loadPublic(monday)
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(8)), 1, 1))
        repository.loadWeekIndicators(monday)
        while (source.pending.isNotEmpty()) {
            assertTrue(source.pending.size <= 4)
            assertFalse(results.last().loading)
            val request = source.pending.first() as PublicRequest
            source.succeed(request, TmdbLibraryApi.Page(if (request.date == monday.plusDays(3)) listOf(media(10)) else emptyList(), 1, 1))
        }
        assertEquals(setOf(monday, monday.plusDays(3)), results.last().knownUpdateDates)
        assertEquals("tmdb:tv:8", results.last().items.single().media.id)
        assertEquals(0, source.detailCalls)
        assertEquals(0, source.seasonCalls)
        repository.loadPublic(monday.plusDays(3))
        assertTrue(source.pending.isEmpty())
        assertEquals(7, source.publicCalls)
        assertEquals("tmdb:tv:10", results.last().items.single().media.id)
    }

    @Test fun shouldIgnoreOldWeekIndicatorsAfterChangingTheSelectedWeek() {
        repository.loadPublic(monday)
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(8)), 1, 1))
        repository.loadWeekIndicators(monday)
        val old = source.pending.first() as PublicRequest
        repository.loadPublic(monday.plusDays(7))
        val count = results.size
        source.succeed(old, TmdbLibraryApi.Page(listOf(media(9)), 1, 1))
        assertEquals(count, results.size)
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(10)), 1, 1))
        assertEquals(setOf(monday.plusDays(7)), results.last().knownUpdateDates)
    }

    @Test fun shouldNotExposeExpiredIndicatorDatesAsConfirmedUpdates() {
        repository.loadPublic(monday)
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(8)), 1, 1))
        repository.loadWeekIndicators(monday)
        while (source.pending.isNotEmpty()) {
            val request = source.pending.first() as PublicRequest
            source.succeed(request, TmdbLibraryApi.Page(listOf(media(9)), 1, 1))
        }
        assertEquals(7, results.last().knownUpdateDates.size)
        now += 30 * 60_000L
        repository.loadPublic(monday)
        assertTrue(results.last().knownUpdateDates.isEmpty())
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(emptyList(), 1, 1))
        assertTrue(results.last().knownUpdateDates.isEmpty())
    }

    @Test fun shouldRestartSameWorkEnrichmentAfterAppendingAnotherPublicPage() {
        repository.loadPublic(monday)
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(8)), 1, 2))
        val focus = mutableListOf<AiringCalendarFocusResult>()
        repository.focus(results.last().items.single(), focus::add)
        source.succeed(source.pending.single() as DetailRequest, metadata(8, (0..6).toList()))
        val old = source.pending.first() as SeasonRequest
        repository.loadPublic(monday, 2)
        source.succeed(source.pending.single() as PublicRequest, TmdbLibraryApi.Page(listOf(media(10)), 2, 2))
        repository.focus(results.last().items.first(), focus::add)
        val count = focus.size
        source.succeed(old, listOf(episode(old.number, 1, monday)))
        assertEquals(count, focus.size)
        while (source.pending.isNotEmpty()) {
            val request = source.pending.first() as SeasonRequest
            source.succeed(request, listOf(episode(request.number, 1, monday)))
            assertTrue(source.pending.size <= 4)
        }
        assertEquals(7, focus.last().entry.episodes.size)
        assertEquals(1, source.detailCalls)
        assertEquals(2, results.last().items.size)
    }

    @Test fun shouldPreserveTraktEpisodeCoordinatesAndLocalDateWhenTmdbUsesAnotherAirDate() {
        val date = monday
        val entry = AiringCalendarEntry(media(8), date,
            episodes = listOf(LibraryMedia.AiringEpisode(2, 7, "Trakt episode", date.toString(), 51)),
            episodeDetails = listOf(LibraryMedia.Episode(2, 7, "Trakt episode", "Trakt overview", "/trakt.jpg", date.toString(), 51)),
            provider = CalendarProvider.TRAKT, logo = "https://images.trakt.tv/logo.png", remoteId = 900)
        repository.loadPublic(date)
        source.succeed(source.pending.single() as PublicRequest, AiringCalendarPage(listOf(entry)))
        val focused = mutableListOf<AiringCalendarFocusResult>()
        repository.focus(results.last().items.single(), focused::add)
        source.succeed(source.pending.single() as DetailRequest, metadata(8, listOf(1, 2, 3)))
        assertEquals(listOf(2), source.requestedSeasons)
        source.succeed(source.pending.single() as SeasonRequest, listOf(
            LibraryMedia.Episode(2, 7, "中文单集", "TMDB overview", "/tmdb.jpg", date.minusDays(1).toString(), 45),
            LibraryMedia.Episode(2, 8, "Different episode", "", "/other.jpg", date.toString(), 45)))
        val result = focused.last().entry
        assertEquals(date, result.date)
        assertEquals(entry.episodes, result.episodes)
        assertEquals(listOf(7), result.episodeDetails.map { it.number() })
        assertEquals(date.toString(), result.episodeDetails.single().airDate())
        assertEquals("中文单集", result.episodeDetails.single().name())
        assertEquals("/tmdb.jpg", result.episodeDetails.single().still())
        assertEquals(51, result.episodeDetails.single().runtime())
        assertEquals(entry.logo, result.logo)
        assertEquals(900L, result.remoteId)
        assertEquals(CalendarProvider.TRAKT, result.provider)
        assertEquals(result, results.last().items.single())
        assertFalse(focused.last().loading)
    }

    @Test fun shouldKeepNativeCalendarIdentitiesAndFocusWithoutRequestingTmdb() {
        val bangumi = AiringCalendarEntry(Vod().apply { id = "bangumi:subject:77"; name = "动漫" }, monday,
            provider = CalendarProvider.BANGUMI, scheduleKind = CalendarScheduleKind.WEEKLY)
        val trakt = AiringCalendarEntry(Vod().apply { id = "trakt:show:99"; name = "Series" }, monday,
            provider = CalendarProvider.TRAKT)
        val invalid = bangumi.copy(media = Vod().apply { id = "bangumi:subject:0" })
        repository.loadPublic(monday)
        source.succeed(source.pending.single() as PublicRequest, AiringCalendarPage(listOf(
            bangumi, trakt, invalid, bangumi.copy(date = monday.plusDays(1)),
            AiringCalendarEntry(media(9, "movie"), monday, provider = CalendarProvider.TRAKT))))
        assertEquals(listOf(bangumi, trakt), results.last().items)
        assertEquals(setOf(monday), results.last().knownUpdateDates)
        val focused = mutableListOf<AiringCalendarFocusResult>()
        repository.focus(bangumi, focused::add)
        assertEquals(bangumi, focused.single().entry)
        assertNull(focused.single().metadata)
        assertFalse(focused.single().loading)
        assertEquals(0, source.detailCalls)
        assertEquals(0, source.seasonCalls)
    }

    @Test fun shouldKeepProviderFocusKeysIndependentWhilePreservingTmdbKeys() {
        val entry = AiringCalendarEntry(media(8), monday)
        assertEquals("tmdb:tv:8|$monday", entry.stableKey)
        assertEquals(3, CalendarProvider.entries.map { entry.copy(provider = it).stableKey }.toSet().size)
    }

    private fun metadata(id: Long, seasons: List<Int>, extra: String = "") = TmdbLibraryApi.parseMetadata(
        DiscoverMediaKey.of("tv", id), "{\"id\":$id,\"name\":\"作品\",\"seasons\":["
                + seasons.joinToString(",") { "{\"season_number\":$it,\"episode_count\":0}" }
                + "]" + if (extra.isBlank()) "}" else ",$extra}"
    )

    private fun keep(id: Long) = Keep().apply {
        key = "tmdb:tv:$id"
        type = Keep.TYPE_DISCOVER
        vodName = "作品"
        vodPic = ""
    }

    private fun media(id: Long, type: String = "tv") = Vod().apply {
        this.id = "tmdb:$type:$id"
        name = "作品"
        typeName = type
    }

    private fun episode(season: Int, number: Int, date: LocalDate) =
        LibraryMedia.Episode(season, number, "", "", "", date.toString(), 24)

    private sealed interface Request { val tag: Any }
    private data class PublicRequest(val date: LocalDate, val page: Int,
        val listener: TmdbLibraryApi.Listener<AiringCalendarPage>, override val tag: Any) : Request
    private data class DetailRequest(val key: DiscoverMediaKey,
        val listener: TmdbLibraryApi.Listener<LibraryMedia.Metadata>, override val tag: Any) : Request
    private data class SeasonRequest(val key: DiscoverMediaKey, val number: Int,
        val listener: TmdbLibraryApi.Listener<List<LibraryMedia.Episode>>, override val tag: Any) : Request

    private class FakeSource : AiringCalendarSource {
        val pending = mutableListOf<Request>()
        val requestedSeasons = mutableListOf<Int>()
        var publicCalls = 0
        var detailCalls = 0
        var seasonCalls = 0

        override fun airingOn(date: LocalDate, page: Int, tag: Any, listener: TmdbLibraryApi.Listener<AiringCalendarPage>) {
            publicCalls++
            pending.add(PublicRequest(date, page, listener, tag))
        }
        override fun detail(key: DiscoverMediaKey, tag: Any, listener: TmdbLibraryApi.Listener<LibraryMedia.Metadata>) {
            detailCalls++
            pending.add(DetailRequest(key, listener, tag))
        }
        override fun season(key: DiscoverMediaKey, number: Int, tag: Any, listener: TmdbLibraryApi.Listener<List<LibraryMedia.Episode>>) {
            seasonCalls++
            requestedSeasons.add(number)
            pending.add(SeasonRequest(key, number, listener, tag))
        }
        override fun cancel(tag: Any) { pending.removeAll { it.tag == tag } }
        fun succeed(request: PublicRequest, value: TmdbLibraryApi.Page) {
            succeed(request, AiringCalendarPage(value.items().map { AiringCalendarEntry(it, request.date) }, value.page(), value.totalPages()))
        }
        fun succeed(request: PublicRequest, value: AiringCalendarPage) {
            pending.remove(request)
            request.listener.onSuccess(value)
        }
        fun succeed(request: DetailRequest, value: LibraryMedia.Metadata) {
            pending.remove(request)
            request.listener.onSuccess(value)
        }
        fun succeed(request: SeasonRequest, value: List<LibraryMedia.Episode>) {
            pending.remove(request)
            request.listener.onSuccess(value)
        }
        fun fail(request: SeasonRequest) {
            pending.remove(request)
            request.listener.onError(IOException("offline"))
        }
    }
}
