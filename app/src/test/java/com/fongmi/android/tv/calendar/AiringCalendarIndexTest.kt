package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.api.TmdbLibraryApi
import com.fongmi.android.tv.bean.DiscoverMediaKey
import com.fongmi.android.tv.bean.Keep
import com.fongmi.android.tv.bean.LibraryMedia
import com.fongmi.android.tv.bean.Vod
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AiringCalendarIndexTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val media = Vod().apply { id = "tmdb:tv:8"; name = "作品" }
    private val keep = keep("tmdb:tv:8")

    @Test fun shouldResolveOldAndLinkedSourceFavoritesWithoutMoviesOrLiveChannels() {
        val source = keep("site@@8", Keep.TYPE_VOD, "tmdb:tv:8")
        val legacy = keep("tmdb:tv:8")
        val movie = keep("tmdb:movie:9")
        val live = keep("tmdb:tv:10", Keep.TYPE_LIVE, "tmdb:tv:10")
        val unlinked = keep("site@@11", Keep.TYPE_VOD)
        val linkedMovie = keep("tmdb:tv:12", Keep.TYPE_DISCOVER, "tmdb:movie:12")
        val result = AiringCalendarIndex.keepsByMedia(listOf(source, legacy, movie, live, unlinked, linkedMovie))
        assertEquals(setOf(DiscoverMediaKey.of("tv", 8)), result.keys)
        assertSame(source, result.values.single())
    }

    @Test fun shouldScanEveryExplicitSeasonIncludingSpecialsAndUnknownCountsWithoutFillingGaps() {
        val metadata = metadata("\"seasons\":[{\"season_number\":0,\"episode_count\":0},"
                + "{\"season_number\":1,\"episode_count\":12},{\"season_number\":3,\"episode_count\":0},"
                + "{\"season_number\":3,\"episode_count\":2}],"
                + "\"last_episode_to_air\":{\"season_number\":5,\"episode_number\":2},"
                + "\"next_episode_to_air\":{\"season_number\":6,\"episode_number\":1}")
        assertEquals(listOf(0, 1, 3, 5, 6), AiringCalendarIndex.seasonNumbers(metadata))
    }

    @Test fun shouldIncludeBothWeekBoundariesAndEverySameDayEpisodeAcrossConcurrentSeasons() {
        val metadata = metadata("\"seasons\":[{\"season_number\":0},{\"season_number\":1},{\"season_number\":3}]")
        val seasons = mapOf(
            0 to AiringCalendarIndex.episodeDates(0, listOf(episode(0, 1, monday))),
            1 to AiringCalendarIndex.episodeDates(1, listOf(episode(1, 1, monday),
                episode(1, 2, monday), episode(1, 3, monday.plusDays(6)),
                episode(1, 4, monday.minusDays(1)), episode(1, 5, monday.plusDays(7)))),
            3 to AiringCalendarIndex.episodeDates(3, listOf(episode(3, 2, monday)))
        )
        val result = AiringCalendarIndex.entries(media, keep, metadata, seasons, monday)
        assertEquals(listOf(monday, monday.plusDays(6)), result.map { it.date })
        assertEquals(listOf(0 to 1, 1 to 1, 1 to 2, 3 to 2),
            result.first().episodes.map { it.season() to it.number() })
        assertEquals("tmdb:tv:8|2026-11-16", result.first().stableKey)
        assertSame(keep, result.first().keep)
    }

    @Test fun shouldUseOnlyValidExactDatesAndKeepMissingEpisodeGaps() {
        val index = AiringCalendarIndex.episodeDates(1, listOf(
            episode(1, 1, monday), episode(1, 3, monday), episode(1, 3, monday),
            LibraryMedia.Episode(1, 4, "", "", "", "2026-02-31", 24),
            LibraryMedia.Episode(1, 5, "", "", "", "", 24), episode(2, 6, monday)
        ))
        assertEquals(listOf(1, 3), index[monday]!!.map { it.number() })
    }

    @Test fun shouldKeepDatedSummaryFallbackButPreferSuccessfulSeasonDatesForSameCoordinates() {
        val metadata = metadata("\"last_episode_to_air\":{\"season_number\":1,\"episode_number\":4,\"air_date\":\"2026-11-16\"},"
                + "\"next_episode_to_air\":{\"season_number\":1,\"episode_number\":5,\"air_date\":\"2026-11-17\"}")
        val fallback = AiringCalendarIndex.entries(media, keep, metadata, emptyMap(), monday)
        assertEquals(listOf(4, 5), fallback.flatMap { it.episodes }.map { it.number() })
        val seasons = mapOf(1 to AiringCalendarIndex.episodeDates(1,
            listOf(episode(1, 4, monday.minusDays(1)),
                LibraryMedia.Episode(1, 5, "", "", "", "", 24))))
        val result = AiringCalendarIndex.entries(media, keep, metadata, seasons, monday)
        assertEquals(listOf(5), result.flatMap { it.episodes }.map { it.number() })
        assertEquals(monday.plusDays(1), result.single().date)
    }

    @Test fun shouldUseShanghaiDateAtMidnightAndMondayWeekBoundaries() {
        val date = Instant.parse("2026-11-15T16:00:00Z").atZone(AiringCalendarIndex.TIME_ZONE).toLocalDate()
        assertEquals(monday, date)
        assertEquals(monday, AiringCalendarIndex.weekStart(monday.plusDays(6)))
        assertEquals(LocalDate.of(2026, 11, 9), AiringCalendarIndex.weekStart(monday.minusDays(1)))
    }

    @Test fun shouldRetainExactDayEpisodeStillOverviewAndRuntimeWithoutInventingMissingArtwork() {
        val episodes = listOf(
            LibraryMedia.Episode(1, 1, "重逢", "真实单集简介", "/real-still.jpg", monday.toString(), 46),
            LibraryMedia.Episode(1, 2, "归途", "", "", monday.toString(), 47),
            LibraryMedia.Episode(1, 3, "下一周", "另一天简介", "/next.jpg", monday.plusDays(7).toString(), 48)
        )
        val index = AiringCalendarIndex.episodeDates(1, episodes)
        val metadata = metadata("\"seasons\":[{\"season_number\":1}]")
        val result = AiringCalendarIndex.entries(media, keep, metadata, mapOf(1 to index), monday).single()
        assertEquals(listOf(1, 2), result.episodeDetails.map { it.number() })
        assertEquals("/real-still.jpg", result.episodeDetails.first().still())
        assertEquals("真实单集简介", result.episodeDetails.first().overview())
        assertEquals("", result.episodeDetails.last().still())
        assertEquals(listOf(46, 47), result.episodes.map { it.runtime() })
        val next = AiringCalendarIndex.entries(media, keep, metadata, mapOf(1 to index), monday.plusDays(7)).single()
        assertEquals("/next.jpg", next.episodeDetails.single().still())
    }

    private fun metadata(fields: String) = TmdbLibraryApi.parseMetadata(
        DiscoverMediaKey.of("tv", 8), "{\"id\":8,\"name\":\"作品\",$fields}"
    )

    private fun episode(season: Int, number: Int, date: LocalDate) =
        LibraryMedia.Episode(season, number, "第${number}集", "", "", date.toString(), 24)

    private fun keep(key: String, type: Int = Keep.TYPE_DISCOVER, reference: String = "") = Keep().apply {
        this.key = key
        this.type = type
        mediaRef = reference
        vodName = "作品"
        vodPic = ""
    }
}
