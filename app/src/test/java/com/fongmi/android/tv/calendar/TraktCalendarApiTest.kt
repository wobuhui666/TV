package com.fongmi.android.tv.calendar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TraktCalendarApiTest {
    private val date = LocalDate.of(2026, 11, 21)

    @Test fun shouldQueryBothUtcDatesForOneShanghaiDayWithApplicationHeaders() {
        val request = TraktCalendarApi.request(date, " client-id ")
        assertEquals("https", request.url.scheme)
        assertEquals("api.trakt.tv", request.url.host)
        assertEquals("/calendars/all/shows/2026-11-20/2", request.url.encodedPath)
        assertEquals("full", request.url.queryParameter("extended"))
        assertEquals("client-id", request.header("trakt-api-key"))
        assertEquals("2", request.header("trakt-api-version"))
        assertEquals("application/json", request.header("Content-Type"))
        assertTrue(request.header("User-Agent")!!.startsWith("TV/"))
        assertNull(request.header("Authorization"))
        assertNull(request.url.queryParameter("page"))
    }

    @Test fun shouldUsePreviousYearForTheUtcWindowAtNewYear() {
        val request = TraktCalendarApi.request(LocalDate.of(2027, 1, 1), "client-id")
        assertEquals("/calendars/all/shows/2026-12-31/2", request.url.encodedPath)
    }

    @Test fun shouldIncludeBothLocalMidnightBoundariesWithoutUsingTheUtcDate() {
        val result = parse(
            row("2026-11-20T15:59:59Z", number = 1),
            row("2026-11-20T16:00:00Z", number = 2),
            row("2026-11-21T15:59:59Z", number = 3),
            row("2026-11-21T16:00:00Z", number = 4))
        assertEquals(listOf(2, 3), result.items.single().episodes.map { it.number() })
        assertEquals(date, result.items.single().date)
        assertTrue(result.items.single().episodes.all { it.airDate() == date.toString() })
    }

    @Test fun shouldHonorExplicitTimestampOffsetsAndRejectMissingOffsets() {
        val result = parse(
            row("2026-11-21T00:00:00+08:00", number = 1),
            row("2026-11-20T11:59:59-04:00", number = 2),
            row("2026-11-21T23:59:59+08:00", number = 3),
            row("2026-11-21", number = 4),
            row("2026-11-21T10:00:00", number = 5),
            row("2026-02-31T01:00:00Z", number = 6))
        assertEquals(listOf(1, 3), result.items.single().episodes.map { it.number() })
    }

    @Test fun shouldGroupSameWorkAndDateWithoutFillingEpisodeGapsOrDuplicatingCoordinates() {
        val result = parse(row(number = 7), row(number = 4), row(number = 7),
            row(season = 0, number = 2), row(season = 3, number = 1))
        val entry = result.items.single()
        assertEquals("tmdb:tv:8", entry.media.id)
        assertEquals(listOf(0 to 2, 2 to 4, 2 to 7, 3 to 1),
            entry.episodes.map { it.season() to it.number() })
        assertEquals(entry.episodes.map { it.season() to it.number() },
            entry.episodeDetails.map { it.season() to it.number() })
        assertEquals(CalendarProvider.TRAKT, entry.provider)
        assertEquals(CalendarScheduleKind.EXACT, entry.scheduleKind)
        assertEquals(1, result.page)
        assertEquals(1, result.totalPages)
    }

    @Test fun shouldKeepNativeTraktIdentityWhenTmdbIdentityIsUnavailable() {
        val native = row().also { show(it).getAsJsonObject("ids").remove("tmdb") }
        val invalidTmdb = row(trakt = 200).also { show(it).getAsJsonObject("ids").addProperty("tmdb", 1.5) }
        val result = parse(native, invalidTmdb)
        assertEquals(listOf("trakt:show:100", "trakt:show:200"), result.items.map { it.media.id })
    }

    @Test fun shouldRejectMalformedIdentitiesAndCoordinatesWithoutTruncatingNumbers() {
        val invalid = listOf("null", "false", "-1", "1.5", "9223372036854775808", "\"8\"", "{}", "[]")
        val rows = invalid.flatMap { value ->
            listOf(row().also {
                val ids = show(it).getAsJsonObject("ids")
                ids.remove("trakt")
                ids.add("tmdb", JsonParser.parseString(value))
            }, row().also { episode(it).add("number", JsonParser.parseString(value)) })
        } + listOf(row().also { episode(it).addProperty("season", 0.5) },
            row().also { episode(it).addProperty("season", 2147483648L) },
            row().also { episode(it).addProperty("number", 2147483648L) })
        assertTrue(parse(*rows.toTypedArray()).items.isEmpty())
    }

    @Test fun shouldCarryOnlyRealArtworkAndEpisodeMetadata() {
        val row = row().also {
            show(it).addProperty("overview", "作品简介")
            show(it).addProperty("original_title", "Original Title")
            show(it).addProperty("rating", 8.25)
            show(it).add("images", JsonParser.parseString("""{
                "poster":[null,false,"javascript:bad","walter-r2.trakt.tv/poster.webp"],
                "fanart":["//walter-r2.trakt.tv/fanart.webp"],
                "logo":["https://walter-r2.trakt.tv/logo.webp"]
            }"""))
            episode(it).addProperty("title", "重逢")
            episode(it).addProperty("overview", "单集简介")
            episode(it).addProperty("runtime", 46)
            episode(it).add("images", JsonParser.parseString("""{"screenshot":["walter-r2.trakt.tv/still.webp"]}"""))
        }
        val entry = parse(row).items.single()
        assertEquals("https://walter-r2.trakt.tv/poster.webp", entry.media.pic)
        assertEquals("https://walter-r2.trakt.tv/fanart.webp", entry.media.backdrop)
        assertEquals("https://walter-r2.trakt.tv/logo.webp", entry.logo)
        assertEquals("Original Title", entry.media.originalName)
        assertEquals("作品简介", entry.media.content)
        assertEquals("8.25", entry.media.remarks)
        assertEquals("重逢", entry.episodes.single().name())
        assertEquals(46, entry.episodes.single().runtime())
        assertEquals("单集简介", entry.episodeDetails.single().overview())
        assertEquals("https://walter-r2.trakt.tv/still.webp", entry.episodeDetails.single().still())
    }

    @Test fun shouldLeaveUnavailableArtworkAndInvalidRuntimeEmpty() {
        val missing = row().also {
            show(it).add("images", JsonParser.parseString("""{
                "poster":["file:///tmp/poster","/poster.webp","https://user:pass@example.com/poster"],
                "fanart":false,"logo":[false,{}]
            }"""))
            episode(it).addProperty("runtime", 1.5)
            episode(it).add("images", JsonParser.parseString("""{"screenshot":null}"""))
        }
        val entry = parse(missing).items.single()
        assertEquals("", entry.media.pic)
        assertEquals("", entry.media.backdrop)
        assertEquals("", entry.logo)
        assertEquals("", entry.episodeDetails.single().still())
        assertEquals(0, entry.episodes.single().runtime())
    }

    @Test fun shouldIgnoreMalformedRowsWhileRetainingOtherWorks() {
        val valid = row()
        val result = TraktCalendarApi.parse("[null,false,3,{},"
            + row().also { it.add("show", JsonParser.parseString("[]")) } + ","
            + row().also { it.add("episode", JsonParser.parseString("false")) } + ","
            + valid + "]", date)
        assertEquals(1, result.items.size)
    }

    @Test fun shouldRejectNonArrayResponsesInsteadOfShowingAnEmptySuccessfulDay() {
        for (body in listOf("null", "{}", "{\"error\":\"forbidden\"}", "false")) {
            try {
                TraktCalendarApi.parse(body, date)
                fail("Non-array response must be retryable")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(TraktCalendarApi.parse("[]", date).items.isEmpty())
    }

    @Test fun shouldRejectMissingClientIdBeforeBuildingARequest() {
        try {
            TraktCalendarApi.request(date, " ")
            fail("An application Client ID is required")
        } catch (_: IllegalArgumentException) { }
    }

    private fun parse(vararg rows: JsonObject) = TraktCalendarApi.parse(rows.joinToString(",", "[", "]"), date)

    private fun row(timestamp: String = "2026-11-21T05:00:00Z", season: Int = 2,
        number: Int = 4, trakt: Long = 100): JsonObject = JsonParser.parseString("""{
        "first_aired":"$timestamp",
        "show":{"title":"作品","year":2026,"ids":{"tmdb":8,"trakt":$trakt}},
        "episode":{"season":$season,"number":$number}
    }""").asJsonObject

    private fun show(row: JsonObject) = row.getAsJsonObject("show")
    private fun episode(row: JsonObject) = row.getAsJsonObject("episode")
}
