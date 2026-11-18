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

    private fun metadata(id: Long, seasons: List<Int>) = TmdbLibraryApi.parseMetadata(
        DiscoverMediaKey.of("tv", id), "{\"id\":$id,\"name\":\"作品\",\"seasons\":["
                + seasons.joinToString(",") { "{\"season_number\":$it,\"episode_count\":0}" } + "]}"
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

    private sealed interface Request
    private data class PublicRequest(val date: LocalDate, val page: Int,
        val listener: TmdbLibraryApi.Listener<TmdbLibraryApi.Page>) : Request
    private data class DetailRequest(val key: DiscoverMediaKey,
        val listener: TmdbLibraryApi.Listener<LibraryMedia.Metadata>) : Request
    private data class SeasonRequest(val key: DiscoverMediaKey, val number: Int,
        val listener: TmdbLibraryApi.Listener<List<LibraryMedia.Episode>>) : Request

    private class FakeSource : AiringCalendarSource {
        val pending = mutableListOf<Request>()
        val requestedSeasons = mutableListOf<Int>()
        var publicCalls = 0
        var detailCalls = 0
        var seasonCalls = 0

        override fun airingOn(date: LocalDate, page: Int, tag: Any, listener: TmdbLibraryApi.Listener<TmdbLibraryApi.Page>) {
            publicCalls++
            pending.add(PublicRequest(date, page, listener))
        }
        override fun detail(key: DiscoverMediaKey, tag: Any, listener: TmdbLibraryApi.Listener<LibraryMedia.Metadata>) {
            detailCalls++
            pending.add(DetailRequest(key, listener))
        }
        override fun season(key: DiscoverMediaKey, number: Int, tag: Any, listener: TmdbLibraryApi.Listener<List<LibraryMedia.Episode>>) {
            seasonCalls++
            requestedSeasons.add(number)
            pending.add(SeasonRequest(key, number, listener))
        }
        override fun cancel(tag: Any) { pending.clear() }
        fun succeed(request: PublicRequest, value: TmdbLibraryApi.Page) {
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
