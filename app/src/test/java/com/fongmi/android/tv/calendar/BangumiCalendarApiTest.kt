package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.bean.DiscoverMediaKey
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BangumiCalendarApiTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val today = monday.plusDays(5)

    @Test fun shouldKeepNativeIdentityAndWeeklyScheduleWithoutInventingEpisodes() {
        val entry = parse(subject(418229, "\"name_cn\":\"魔法少女育成计划 restart\"," +
            "\"name\":\"魔法少女育成計画 restart\",\"air_date\":\"2026-10-05\"," +
            "\"summary\":\"番剧简介\",\"rating\":{\"score\":6.2}")).items.single()
        assertEquals("bangumi:subject:418229", entry.media.id)
        assertNull(DiscoverMediaKey.parse(entry.media.id))
        assertEquals("魔法少女育成计划 restart", entry.media.name)
        assertEquals("魔法少女育成計画 restart", entry.media.originalName)
        assertEquals("番剧简介", entry.media.content)
        assertEquals("6.2", entry.media.remarks)
        assertEquals("2026", entry.media.year)
        assertEquals(monday, entry.date)
        assertEquals(CalendarProvider.BANGUMI, entry.provider)
        assertEquals(418229L, entry.remoteId)
        assertEquals(CalendarScheduleKind.WEEKLY, entry.scheduleKind)
        assertTrue(entry.episodes.isEmpty())
        assertTrue(entry.episodeDetails.isEmpty())
        assertEquals("", entry.logo)
    }

    @Test fun shouldSelectOfficialWeekdayBucketRatherThanOriginalPremiereWeekday() {
        val body = "[{\"weekday\":{\"id\":1},\"items\":[${subject(1)}]}," +
            "{\"weekday\":{\"id\":7},\"items\":[${subject(7, "\"air_date\":\"2026-10-05\"")}]}]"
        val entry = BangumiCalendarApi.parse(body, monday.plusDays(6), today).items.single()
        assertEquals("bangumi:subject:7", entry.media.id)
        assertEquals(monday.plusDays(6), entry.date)
    }

    @Test fun shouldRejectPremieresAfterSelectedDayWithoutReusingPremiereAsEpisodeDate() {
        val result = parse(subject(1, "\"air_date\":\"2026-11-17\""),
            subject(2, "\"air_date\":\"2026-11-16\""), subject(3, "\"air_date\":\"2025-01-01\""))
        assertEquals(listOf("bangumi:subject:2", "bangumi:subject:3"), result.items.map { it.media.id })
        assertTrue(result.items.all { it.date == monday && it.episodes.isEmpty() })
    }

    @Test fun shouldRestrictCurrentWeeklyFeedToCurrentShanghaiWeekBeforeParsing() {
        assertTrue(BangumiCalendarApi.parse("invalid body", monday.minusDays(1), today).items.isEmpty())
        assertTrue(BangumiCalendarApi.parse("invalid body", monday.plusDays(7), today).items.isEmpty())
        assertEquals(1, parse(subject(1)).items.size)
    }

    @Test fun shouldUseMondayWeekBoundaryAcrossCalendarYears() {
        val newYear = LocalDate.of(2027, 1, 1)
        val previousYearMonday = LocalDate.of(2026, 12, 28)
        assertEquals(1, BangumiCalendarApi.parse(feed(subject(1)), previousYearMonday, newYear).items.size)
        assertTrue(BangumiCalendarApi.parse("invalid body", newYear.plusDays(3), newYear).items.isEmpty())
    }

    @Test fun shouldFallBackToOriginalTitleAndDeduplicateSubjects() {
        val result = parse(subject(1, "\"name_cn\":\"   \",\"name\":\"原名\""), subject(1), subject(2))
        assertEquals(listOf("bangumi:subject:1", "bangumi:subject:2"), result.items.map { it.media.id })
        assertEquals("原名", result.items.first().media.name)
        assertEquals(1, result.page)
        assertEquals(1, result.totalPages)
    }

    @Test fun shouldUpgradeOnlyKnownBangumiCoverHostsAndRejectUnsafeUrls() {
        val result = parse(
            subject(1, "\"images\":{\"large\":\"http://lain.bgm.tv/pic/cover/a.jpg\"}"),
            subject(2, "\"images\":{\"large\":\"http://lain.bangumi.tv/pic/cover/b.jpg\"}"),
            subject(3, "\"images\":{\"large\":\"http://example.org/c.jpg\"}"),
            subject(4, "\"images\":{\"large\":\"http://lain.bgm.tv.example.org/d.jpg\"}"),
            subject(5, "\"images\":{\"large\":\"javascript:alert(1)\",\"common\":\"https://lain.bgm.tv/e.jpg\"}"),
            subject(6, "\"images\":{\"large\":\"https://secret@lain.bgm.tv/f.jpg\"}")
        )
        assertEquals(listOf("https://lain.bgm.tv/pic/cover/a.jpg", "https://lain.bangumi.tv/pic/cover/b.jpg",
            "http://example.org/c.jpg", "http://lain.bgm.tv.example.org/d.jpg", "https://lain.bgm.tv/e.jpg", ""),
            result.items.map { it.media.pic })
    }

    @Test fun shouldRejectNonAnimeAdultAndUnnamedSubjects() {
        val result = parse(subject(1, "\"type\":1"), subject(2, "\"nsfw\":true"),
            subject(3, "\"name\":\"\",\"name_cn\":\"\""), subject(4, "\"nsfw\":false"))
        assertEquals(listOf("bangumi:subject:4"), result.items.map { it.media.id })
    }

    @Test fun shouldRejectInvalidIdentifiersInsteadOfTruncatingNumbers() {
        val body = "[{\"weekday\":{\"id\":1},\"items\":[" +
            "{\"id\":-1,\"type\":2,\"name\":\"bad\"}," +
            "{\"id\":1.5,\"type\":2,\"name\":\"bad\"}," +
            "{\"id\":\"2\",\"type\":2,\"name\":\"bad\"}," +
            "{\"id\":9223372036854775808,\"type\":2,\"name\":\"bad\"},${subject(8)}]}]"
        assertEquals(listOf("bangumi:subject:8"), BangumiCalendarApi.parse(body, monday, today).items.map { it.media.id })
    }

    @Test fun shouldTolerateMalformedOptionalFieldsAndSkipMalformedBuckets() {
        val body = "[null,4,{\"weekday\":null},{\"weekday\":{\"id\":1.5},\"items\":[${subject(1)}]}," +
            "{\"weekday\":{\"id\":1},\"items\":[null,[]," +
            subject(2, "\"images\":null,\"rating\":{\"score\":{}},\"air_date\":\"2026-02-31\",\"summary\":[]") + "]}]"
        val entry = BangumiCalendarApi.parse(body, monday, today).items.single()
        assertEquals("bangumi:subject:2", entry.media.id)
        assertEquals("", entry.media.pic)
        assertEquals("", entry.media.remarks)
        assertEquals("", entry.media.year)
        assertEquals("", entry.media.content)
    }

    @Test fun shouldDisplayOnlyNumericRatingsWithinBangumiScale() {
        val result = parse(subject(1, "\"rating\":{\"score\":10}"), subject(2, "\"rating\":{\"score\":0}"),
            subject(3, "\"rating\":{\"score\":10.1}"), subject(4, "\"rating\":{\"score\":-1}"),
            subject(5, "\"rating\":{\"score\":\"8.5\"}"))
        assertEquals(listOf("10.0", "", "", "", ""), result.items.map { it.media.remarks })
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldReportUnexpectedTopLevelResponseAsFailure() {
        BangumiCalendarApi.parse("{\"error\":\"unavailable\"}", monday, today)
    }

    @Test fun shouldKeepLongRunningWeeklySubjectsWithoutTreatingOldPremiereAsCurrentEpisode() {
        val body = feed(subject(1, "\"air_date\":\"1999-10-20\",\"air_weekday\":3"))
        val entry = BangumiCalendarApi.parse(body, monday, today).items.single()
        assertEquals("1999", entry.media.year)
        assertEquals(monday, entry.date)
        assertEquals(CalendarScheduleKind.WEEKLY, entry.scheduleKind)
        assertTrue(entry.episodes.isEmpty())
        assertTrue(BangumiCalendarApi.parse(body, monday.minusWeeks(1), today).items.isEmpty())
    }

    @Test fun shouldIgnoreUnusableWeekdayBucketsAndMalformedNamesWithoutCoercingValues() {
        val body = "[{\"weekday\":{\"id\":\"1\"},\"items\":[${subject(1)}]}," +
            "{\"weekday\":{\"id\":8},\"items\":[${subject(2)}]}," +
            "{\"weekday\":{\"id\":1},\"items\":[" +
            subject(3, "\"name_cn\":{},\"name\":42") + "," +
            subject(4, "\"name_cn\":null,\"name\":\"有效原名\"") + "]}]"
        val entries = BangumiCalendarApi.parse(body, monday, today).items
        assertEquals(listOf("bangumi:subject:4"), entries.map { it.media.id })
        assertEquals("有效原名", entries.single().media.name)
    }

    @Test fun shouldKeepCoverUrlPortAndQueryRatherThanUpgradeUnrecognizedHttpEndpoint() {
        val entries = parse(subject(1, "\"images\":{\"large\":\"http://lain.bgm.tv:8080/cover.jpg?version=2\"}"),
            subject(2, "\"images\":{\"large\":\"https://lain.bgm.tv/cover.jpg?version=2\"}"),
            subject(3, "\"images\":{\"large\":\"ftp://lain.bgm.tv/cover.jpg\"}")).items
        assertEquals(listOf("http://lain.bgm.tv:8080/cover.jpg?version=2", "https://lain.bgm.tv/cover.jpg?version=2", ""),
            entries.map { it.media.pic })
    }

    private fun parse(vararg subjects: String) = BangumiCalendarApi.parse(feed(*subjects), monday, today)

    private fun feed(vararg subjects: String) = "[{\"weekday\":{\"id\":1},\"items\":[${subjects.joinToString(",")}]}]"

    private fun subject(id: Int, fields: String = "") = "{\"id\":$id,\"type\":2,\"name\":\"作品$id\"" +
        (if (fields.isEmpty()) "" else ",$fields") + "}"
}
