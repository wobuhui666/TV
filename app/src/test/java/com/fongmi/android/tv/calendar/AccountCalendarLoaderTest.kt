package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.api.TmdbLibraryApi
import com.fongmi.android.tv.bean.Vod
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class AccountCalendarLoaderTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val provider = CalendarProvider.BANGUMI
    private val source = FakeSource()
    private val results = mutableListOf<AiringCalendarResult>()
    private var now = 1_000_000L
    private val loader = AccountCalendarLoader(source, results::add) { now }

    @Test fun shouldReplaceOnlyCompletedImportsAndKeepSuccessfulDatesAfterPartialEpisodeFailure() {
        loader.load(provider, monday, true)
        source.token.onSuccess(CalendarAccountToken("fixture"))
        val follows = listOf(follow(8), follow(9))
        source.import.onSuccess(follows)
        assertEquals(follows, source.stored)
        source.schedule.onSuccess(AiringCalendarResult(listOf(entry(8)), false, true))
        assertTrue(results.last().failed)
        assertTrue(results.last().hasKeeps)
        assertEquals(setOf(monday), results.last().knownUpdateDates)
        assertEquals("bangumi:subject:8", results.last().items.single().media.id)
    }

    @Test fun shouldRetainOldFollowSnapshotAndScheduleWhenTheRemoteImportFails() {
        val old = listOf(follow(8))
        source.stored = old
        loader.load(provider, monday, true)
        source.token.onSuccess(CalendarAccountToken("fixture"))
        source.import.onError(IOException("second page failed"))
        assertEquals(0, source.saves)
        assertEquals(old, source.scheduledFollows)
        source.schedule.onSuccess(AiringCalendarResult(listOf(entry(8)), false, false))
        assertTrue(results.last().failed)
        assertEquals(old, source.stored)
        assertFalse(results.last().loading)
        assertEquals(1, results.last().items.size)
    }

    @Test fun shouldRejectLateImportsAndDatesAfterProviderOrWeekChanges() {
        loader.load(provider, monday, true)
        source.token.onSuccess(CalendarAccountToken("fixture"))
        val oldImport = source.import
        loader.load(CalendarProvider.TRAKT, monday.plusWeeks(1), true)
        val count = results.size
        oldImport.onSuccess(listOf(follow(8)))
        assertEquals(count, results.size)
        assertEquals(0, source.saves)
        source.token.onSuccess(CalendarAccountToken("fixture"))
        source.import.onSuccess(listOf(follow(9)))
        val oldSchedule = source.schedule
        loader.close()
        oldSchedule.onSuccess(AiringCalendarResult(listOf(entry(9)), false, false))
        assertEquals(0, results.last().items.size)
    }

    @Test fun shouldReuseRecentImportsButForceRefreshAfterAnUncertainWrite() {
        source.stored = listOf(follow(8))
        source.synced = now - 1000
        loader.load(provider, monday)
        source.token.onSuccess(CalendarAccountToken("fixture"))
        assertEquals(0, source.imports)
        source.schedule.onSuccess(AiringCalendarResult(listOf(entry(8)), false, false))
        source.synced = 0
        loader.load(provider, monday)
        source.token.onSuccess(CalendarAccountToken("fixture"))
        assertEquals(1, source.imports)
    }

    @Test fun shouldNotReuseAClockReversedSnapshotOrHideAuthorizationFailure() {
        source.synced = now + 1000
        source.stored = listOf(follow(8))
        loader.load(provider, monday)
        source.token.onSuccess(CalendarAccountToken("fixture"))
        assertEquals(1, source.imports)
        loader.load(provider, monday)
        source.token.onError(IOException("authorization revoked"))
        assertTrue(results.last().failed)
        assertTrue(results.last().hasKeeps)
        assertFalse(results.last().loading)
        assertEquals(0, source.saves)
    }

    @Test fun shouldClearOnlyTheAccountSnapshotAfterACompleteEmptyImport() {
        source.stored = listOf(follow(8))
        loader.load(provider, monday, true)
        source.token.onSuccess(CalendarAccountToken("fixture"))
        source.import.onSuccess(emptyList())
        assertTrue(source.stored.isEmpty())
        assertFalse(results.last().hasKeeps)
        assertFalse(results.last().failed)
        assertEquals(0, source.weeks)
    }

    private fun follow(id: Long) = CalendarFollow(provider, id, Vod().apply {
        this.id = "bangumi:subject:$id"; name = "Fixture $id"
    }, 3)
    private fun entry(id: Long) = AiringCalendarEntry(follow(id).media, monday, provider = provider,
        remoteId = id, episodeLabel = "EP 7.5")

    private class FakeSource : AccountCalendarSource {
        lateinit var token: TmdbLibraryApi.Listener<CalendarAccountToken>
        lateinit var import: TmdbLibraryApi.Listener<List<CalendarFollow>>
        lateinit var schedule: TmdbLibraryApi.Listener<AiringCalendarResult>
        var stored: List<CalendarFollow> = emptyList()
        var scheduledFollows: List<CalendarFollow> = emptyList()
        var synced = 0L
        var saves = 0
        var imports = 0
        var weeks = 0
        override fun withToken(provider: CalendarProvider, listener: TmdbLibraryApi.Listener<CalendarAccountToken>) { token = listener }
        override fun lastSync(provider: CalendarProvider) = synced
        override fun follows(provider: CalendarProvider) = stored
        override fun saveFollows(provider: CalendarProvider, follows: List<CalendarFollow>) { stored = follows; saves++ }
        override fun following(provider: CalendarProvider, token: CalendarAccountToken, tag: Any,
            listener: TmdbLibraryApi.Listener<List<CalendarFollow>>) { import = listener; imports++ }
        override fun week(provider: CalendarProvider, week: LocalDate, follows: List<CalendarFollow>, token: CalendarAccountToken,
            tag: Any, listener: TmdbLibraryApi.Listener<AiringCalendarResult>) {
            schedule = listener; scheduledFollows = follows; weeks++
        }
        override fun cancel(tag: Any) { }
    }
}
