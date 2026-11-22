package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.api.TmdbLibraryApi
import java.time.LocalDate

internal interface AccountCalendarSource {
    fun withToken(provider: CalendarProvider, listener: TmdbLibraryApi.Listener<CalendarAccountToken>)
    fun lastSync(provider: CalendarProvider): Long
    fun follows(provider: CalendarProvider): List<CalendarFollow>
    fun saveFollows(provider: CalendarProvider, follows: List<CalendarFollow>)
    fun following(provider: CalendarProvider, token: CalendarAccountToken, tag: Any,
        listener: TmdbLibraryApi.Listener<List<CalendarFollow>>)
    fun week(provider: CalendarProvider, week: LocalDate, follows: List<CalendarFollow>,
        token: CalendarAccountToken, tag: Any, listener: TmdbLibraryApi.Listener<AiringCalendarResult>)
    fun cancel(tag: Any)
}

private class RemoteAccountCalendarSource : AccountCalendarSource {
    override fun withToken(provider: CalendarProvider, listener: TmdbLibraryApi.Listener<CalendarAccountToken>) =
        CalendarAccountSession.withToken(provider, listener)
    override fun lastSync(provider: CalendarProvider) = CalendarAccountStore.lastSync(provider)
    override fun follows(provider: CalendarProvider) = CalendarAccountStore.follows(provider)
    override fun saveFollows(provider: CalendarProvider, follows: List<CalendarFollow>) = CalendarAccountStore.saveFollows(provider, follows)
    override fun following(provider: CalendarProvider, token: CalendarAccountToken, tag: Any,
        listener: TmdbLibraryApi.Listener<List<CalendarFollow>>) {
        when (provider) {
            CalendarProvider.TRAKT -> TraktAccountApi.following(CalendarAccountStore.traktClientId, token, tag, listener)
            CalendarProvider.BANGUMI -> BangumiAccountApi.following(token, tag, listener)
            CalendarProvider.TMDB -> listener.onSuccess(emptyList())
        }
    }
    override fun week(provider: CalendarProvider, week: LocalDate, follows: List<CalendarFollow>,
        token: CalendarAccountToken, tag: Any, listener: TmdbLibraryApi.Listener<AiringCalendarResult>) {
        when (provider) {
            CalendarProvider.TRAKT -> TraktAccountApi.week(week, follows, CalendarAccountStore.traktClientId, token, tag, listener)
            CalendarProvider.BANGUMI -> BangumiAccountApi.week(week, follows, token, tag, listener)
            CalendarProvider.TMDB -> listener.onSuccess(AiringCalendarResult(emptyList(), false, false))
        }
    }
    override fun cancel(tag: Any) { TraktAccountApi.cancel(tag); BangumiAccountApi.cancel(tag) }
}

/** Remote follows are separate from local favorites; only complete imports replace the snapshot. */
internal class AccountCalendarLoader internal constructor(
    private val source: AccountCalendarSource,
    private val listener: (AiringCalendarResult) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    constructor(listener: (AiringCalendarResult) -> Unit) : this(RemoteAccountCalendarSource(), listener)
    private val tag = Any()
    private var generation = 0
    private var closed = false

    fun load(provider: CalendarProvider, week: LocalDate, sync: Boolean = false) {
        cancel()
        val ticket = generation
        if (closed) return
        if (provider == CalendarProvider.TMDB) {
            listener(AiringCalendarResult(emptyList(), false, false))
            return
        }
        listener(AiringCalendarResult(emptyList(), true, false))
        source.withToken(provider, object : TmdbLibraryApi.Listener<CalendarAccountToken> {
            override fun onSuccess(value: CalendarAccountToken) {
                if (!current(ticket)) return
                val lastSync = source.lastSync(provider)
                if (!sync && lastSync > 0 && clock() - lastSync in 0 until 5 * 60_000L) {
                    schedule(provider, week, source.follows(provider), value, ticket, false)
                    return
                }
                val callback = object : TmdbLibraryApi.Listener<List<CalendarFollow>> {
                    override fun onSuccess(follows: List<CalendarFollow>) {
                        if (!current(ticket)) return
                        try { source.saveFollows(provider, follows) }
                        catch (error: Exception) { onError(error); return }
                        schedule(provider, week, follows, value, ticket, false)
                    }
                    override fun onError(error: Exception) {
                        if (current(ticket)) schedule(provider, week, source.follows(provider), value, ticket, true)
                    }
                }
                try { source.following(provider, value, tag, callback) }
                catch (error: Exception) { callback.onError(error) }
            }
            override fun onError(error: Exception) {
                if (current(ticket)) listener(AiringCalendarResult(emptyList(), false, true,
                    hasKeeps = source.follows(provider).isNotEmpty()))
            }
        })
    }

    private fun schedule(provider: CalendarProvider, week: LocalDate, follows: List<CalendarFollow>,
        token: CalendarAccountToken, ticket: Int, importFailed: Boolean) {
        if (follows.isEmpty()) {
            listener(AiringCalendarResult(emptyList(), false, importFailed))
            return
        }
        val callback = object : TmdbLibraryApi.Listener<AiringCalendarResult> {
            override fun onSuccess(value: AiringCalendarResult) {
                if (current(ticket)) listener(value.copy(hasKeeps = true, failed = value.failed || importFailed,
                    knownUpdateDates = value.items.map { it.date }.toSet()))
            }
            override fun onError(error: Exception) {
                if (current(ticket)) listener(AiringCalendarResult(emptyList(), false, true, hasKeeps = true))
            }
        }
        try { source.week(provider, week, follows, token, tag, callback) }
        catch (error: Exception) { callback.onError(error) }
    }

    private fun current(ticket: Int) = !closed && ticket == generation
    fun cancel() { generation++; source.cancel(tag) }
    fun close() { closed = true; cancel() }
}
