package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.bean.Vod
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BangumiAccountApiTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val follow = CalendarFollow(CalendarProvider.BANGUMI, 8,
        Vod().apply { id = "bangumi:subject:8"; name = "作品" }, 3)

    @Test fun shouldReadOnlyWishAndDoingAnimeCollectionsIncludingPrivateFollows() {
        val value = BangumiAccountApi.parseCollections(page(listOf(collection(1, 1), collection(2, 2),
            collection(3, 3), collection(4, 4), collection(5, 5), collection(6, 3, subjectType = 1))), 0)
        assertEquals(listOf(1L, 3L), value.items.map { it.remoteId })
        assertEquals(listOf(1, 3), value.items.map { it.status })
        assertTrue(value.items.all { it.provider == CalendarProvider.BANGUMI })
        assertEquals("bangumi:subject:1", value.items.first().media.id)
        assertEquals("中文作品1", value.items.first().media.name)
        assertEquals("原名1", value.items.first().media.originalName)
        assertEquals("https://lain.bgm.tv/1.jpg", value.items.first().media.pic)
        assertNull(value.nextOffset)
    }

    @Test fun shouldAdvancePaginationUsingRawCountWhenSomeRowsAreFiltered() {
        val value = BangumiAccountApi.parseCollections(page(listOf(collection(1, 2), collection(2, 3)), total = 4), 0)
        assertEquals(listOf(2L), value.items.map { it.remoteId })
        assertEquals(2, value.nextOffset)
        val last = BangumiAccountApi.parseCollections(page(listOf(collection(3, 1), collection(4, 3)), offset = 2, total = 4), 2)
        assertNull(last.nextOffset)
    }

    @Test fun shouldUseOfficialCollectionAndEpisodeLimitsAndEncodeUsernameAsOneSegment() {
        val collections = BangumiAccountApi.collectionsUrl("name/with spaces", 3, 50)
        assertEquals("50", collections.queryParameter("limit"))
        assertEquals("2", collections.queryParameter("subject_type"))
        assertEquals("3", collections.queryParameter("type"))
        assertEquals("50", collections.queryParameter("offset"))
        assertEquals("name/with spaces", collections.pathSegments[2])
        val episodes = BangumiAccountApi.episodesUrl(8, 200)
        assertEquals("200", episodes.queryParameter("limit"))
        assertEquals("200", episodes.queryParameter("offset"))
        assertEquals("0", episodes.queryParameter("type"))
        assertEquals("8", episodes.queryParameter("subject_id"))
    }

    @Test fun shouldKeepActualEpisodeDatesAndFractionalNumbersWithoutSeasonCoordinates() {
        val parsed = BangumiAccountApi.parseEpisodes(page(listOf(episode(1, "1", monday.toString()),
            episode(2, "1.5", monday.toString()), episode(3, "2", monday.plusDays(6).toString()),
            episode(4, "3", monday.plusDays(7).toString()))), 8, 0)
        val entries = BangumiAccountApi.entries(follow, parsed.items, monday)
        assertEquals(listOf(monday, monday.plusDays(6)), entries.map { it.date })
        assertEquals("EP 1 · EP 1.5", entries.first().episodeLabel)
        assertEquals("EP 2", entries.last().episodeLabel)
        assertTrue(entries.all { it.episodes.isEmpty() && it.episodeDetails.isEmpty() })
        assertTrue(entries.all { it.scheduleKind == CalendarScheduleKind.EXACT && it.remoteId == 8L })
    }

    @Test fun shouldRejectWrongSubjectsSpecialsAndUnknownDatesWithoutInventingWeeklyRecurrence() {
        val parsed = BangumiAccountApi.parseEpisodes(page(listOf(episode(1, "1", ""),
            episode(2, "2", "2026-02-31"), episode(3, "3", monday.toString(), type = 1),
            episode(4, "4", monday.toString(), subject = 9), episode(5, "5", monday.toString()))), 8, 0)
        assertEquals(listOf(5L), parsed.items.map { it.id })
        assertEquals("EP 5", parsed.items.single().label)
    }

    @Test fun shouldDeduplicateEpisodeIdsAndKeepDatedUpdatesWithUnknownNumbers() {
        val parsed = BangumiAccountApi.parseEpisodes(page(listOf(episode(1, "1", monday.toString()),
            episode(1, "1", monday.toString()), episode(2, "null", monday.toString()))), 8, 0)
        assertEquals(listOf(1L, 2L), parsed.items.map { it.id })
        assertEquals("", parsed.items.last().label)
        assertEquals("EP 1", BangumiAccountApi.entries(follow, parsed.items, monday).single().episodeLabel)
    }

    @Test fun shouldProtectCompletedCollectionsAndPreserveOtherFieldsWhenPausing() {
        assertNull(BangumiAccountApi.mutation(2, true))
        assertNull(BangumiAccountApi.mutation(2, false))
        val pause = BangumiAccountApi.mutation(3, false)!!
        assertEquals("PATCH", pause.method)
        val body = JsonParser.parseString(pause.body).asJsonObject
        assertEquals(setOf("type"), body.keySet())
        assertEquals(4, body.get("type").asInt)
        assertNull(BangumiAccountApi.mutation(4, false))
        assertNull(BangumiAccountApi.mutation(null, false))
    }

    @Test fun shouldAddNewFollowPrivatelyAndLeaveExistingFollowsUntouched() {
        val create = BangumiAccountApi.mutation(null, true)!!
        assertEquals("POST", create.method)
        val body = JsonParser.parseString(create.body).asJsonObject
        assertEquals(setOf("type", "private"), body.keySet())
        assertEquals(3, body.get("type").asInt)
        assertTrue(body.get("private").asBoolean)
        assertNull(BangumiAccountApi.mutation(1, true))
        assertNull(BangumiAccountApi.mutation(3, true))
        assertEquals("{\"type\":3}", BangumiAccountApi.mutation(4, true)!!.body)
        assertEquals("{\"type\":3}", BangumiAccountApi.mutation(5, true)!!.body)
    }

    @Test fun shouldVerifySubjectIdentityBeforeChangingRemoteStatus() {
        assertEquals(3, BangumiAccountApi.parseCollectionStatus("{\"subject_id\":8,\"type\":3}", 8))
        assertThrowsInvalid { BangumiAccountApi.parseCollectionStatus("{\"subject_id\":9,\"type\":3}", 8) }
        assertThrowsInvalid { BangumiAccountApi.parseCollectionStatus("{\"subject_id\":8,\"type\":0}", 8) }
        assertThrowsInvalid { BangumiAccountApi.parseCollectionStatus("{\"subject_id\":8,\"type\":3.5}", 8) }
    }

    @Test fun shouldRejectEmptyOrRepeatedPagesRatherThanAcceptIncompleteAccountSnapshot() {
        assertThrowsInvalid { BangumiAccountApi.parseCollections(page(emptyList(), total = 5), 0) }
        assertThrowsInvalid { BangumiAccountApi.parseCollections(page(listOf(collection(1, 1)), offset = 0), 50) }
        assertThrowsInvalid { BangumiAccountApi.parseEpisodes("{\"data\":[],\"total\":\"20\",\"offset\":0,\"limit\":200}", 8, 0) }
        assertThrowsInvalid { BangumiAccountApi.parseCollections("{\"message\":\"failure\"}", 0) }
    }

    @Test fun shouldRejectMalformedCollectionIdentityAndAdultSubjectWithoutLosingPaginationProgress() {
        val malformed = collection(1, 3).replace("\"subject_id\":1", "\"subject_id\":1.5")
        val adult = collection(2, 3).replace("\"name\":\"原名2\"", "\"nsfw\":true,\"name\":\"原名2\"")
        val mismatch = collection(3, 3).replace("\"id\":3", "\"id\":9")
        val parsed = BangumiAccountApi.parseCollections(page(listOf(malformed, adult, mismatch, collection(4, 3)), total = 6), 0)
        assertEquals(listOf(4L), parsed.items.map { it.remoteId })
        assertEquals(4, parsed.nextOffset)
    }

    @Test fun shouldRequireTokenAccountUsernameWithoutExposingOtherProfileFields() {
        assertEquals("account_name", BangumiAccountApi.parseUsername("{\"username\":\"account_name\",\"email\":\"private@example.org\"}"))
        assertThrowsInvalid { BangumiAccountApi.parseUsername("{\"id\":8}") }
        assertThrowsInvalid { BangumiAccountApi.parseUsername("{\"username\":42}") }
    }

    @Test fun shouldDeduplicateCollectionIdentityWithoutReimportingConflictingCompletedSubject() {
        val parsed = BangumiAccountApi.parseCollections(page(listOf(collection(1, 1), collection(1, 3),
            collection(2, 1), collection(2, 3), collection(1, 2))), 0)
        assertEquals(listOf(2L), parsed.items.map { it.remoteId })
        assertEquals(listOf(3), parsed.items.map { it.status })
    }

    @Test fun shouldImportBothPublicAndPrivateFollowsWithoutImportingAuthenticatedAdultSubjects() {
        val public = collection(1, 1).replace("\"private\":true", "\"private\":false")
        val private = collection(2, 3)
        val adult = collection(3, 3).replace("\"name\":\"原名3\"", "\"name\":\"原名3\",\"nsfw\":true")
        val normal = collection(4, 3).replace("\"name\":\"原名4\"", "\"name\":\"原名4\",\"nsfw\":false")
        val parsed = BangumiAccountApi.parseCollections(page(listOf(public, private, adult, normal)), 0)
        assertEquals(listOf(1L, 2L, 4L), parsed.items.map { it.remoteId })
        val resume = JsonParser.parseString(BangumiAccountApi.mutation(4, true)!!.body).asJsonObject
        assertEquals(setOf("type"), resume.keySet())
    }

    @Test fun shouldPreserveDecimalLabelsAndAvoidFabricatedNumbersForMalformedOrExtremeValues() {
        val data = listOf(episode(1, "1.500", monday.toString()), episode(2, "1.5e1", monday.toString()),
            episode(3, "0", monday.toString()).replace("\"sort\":0", "\"sort\":0,\"ep\":7.5"),
            episode(4, "{}", monday.toString()).replace("\"sort\":{}", "\"sort\":{},\"ep\":8.5"),
            episode(5, "\"9.5\"", monday.toString()), episode(6, "-1", monday.toString()),
            episode(7, "1e10000", monday.toString()), episode(8, "0.0000001", monday.toString()))
        val parsed = BangumiAccountApi.parseEpisodes(page(data), 8, 0)
        assertEquals(listOf("EP 1.5", "EP 15", "EP 7.5", "EP 8.5", "", "", "", ""),
            parsed.items.map { it.label })
        assertEquals(8, parsed.items.size)
    }

    @Test fun shouldRejectOversizedAndContradictoryPaginationBeforeReplacingSavedFollows() {
        val oversized = page(listOf(collection(1, 3))).replace("\"limit\":50", "\"limit\":200")
        assertThrowsInvalid { BangumiAccountApi.parseCollections(oversized, 0) }
        val contradiction = page(listOf(collection(1, 3)), total = 0)
        assertThrowsInvalid { BangumiAccountApi.parseCollections(contradiction, 0) }
        assertThrowsInvalid { BangumiAccountApi.parseCollections(page(emptyList(), offset = 50, total = 30), 50) }
        val huge = page(listOf(episode(1, "1", monday.toString()))).replace("\"limit\":50", "\"limit\":201")
        assertThrowsInvalid { BangumiAccountApi.parseEpisodes(huge, 8, 0) }
    }

    private fun page(data: List<String>, offset: Int = 0, total: Int = offset + data.size) =
        "{\"data\":[${data.joinToString(",")}],\"total\":$total,\"offset\":$offset,\"limit\":50}"

    private fun collection(id: Int, status: Int, subjectType: Int = 2) =
        "{\"subject_id\":$id,\"subject_type\":$subjectType,\"type\":$status,\"private\":true,\"ep_status\":5,"
            + "\"subject\":{\"id\":$id,\"name\":\"原名$id\",\"name_cn\":\"中文作品$id\","
            + "\"images\":{\"common\":\"http://lain.bgm.tv/$id.jpg\"}}}"

    private fun episode(id: Int, sort: String, date: String, type: Int = 0, subject: Int = 8) =
        "{\"id\":$id,\"subject_id\":$subject,\"type\":$type,\"sort\":$sort,\"airdate\":\"$date\"}"

    private fun assertThrowsInvalid(action: () -> Unit) {
        try { action(); fail("Expected invalid response to fail") }
        catch (_: IllegalArgumentException) { }
    }
}
