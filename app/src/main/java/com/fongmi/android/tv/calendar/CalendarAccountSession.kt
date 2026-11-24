package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.api.TmdbLibraryApi
import java.io.IOException

/** Main-thread single flight: rotated refresh credentials are saved before any next request. */
internal object CalendarAccountSession {
    private var refreshing = false
    private val waiting = mutableListOf<TmdbLibraryApi.Listener<CalendarAccountToken>>()

    fun withToken(provider: CalendarProvider, listener: TmdbLibraryApi.Listener<CalendarAccountToken>) {
        val token = CalendarAccountStore.token(provider)
        if (token == null) { listener.onError(IOException("Account disconnected")); return }
        if (provider != CalendarProvider.TRAKT || token.expiresAt > System.currentTimeMillis() + 60_000) {
            listener.onSuccess(token)
            return
        }
        if (token.refreshToken.isBlank()) { listener.onError(IOException("Account authorization expired")); return }
        waiting.add(listener)
        if (refreshing) return
        refreshing = true
        // Do not cancel a rotating-token exchange when a calendar screen leaves the foreground.
        TraktAccountApi.refresh(CalendarAccountStore.traktClientId, token, Any(), object : TmdbLibraryApi.Listener<CalendarAccountToken> {
            override fun onSuccess(value: CalendarAccountToken) {
                try {
                    if (CalendarAccountStore.token(provider)?.accessToken != token.accessToken) {
                        finish(null, IOException("Account changed"))
                        return
                    }
                    CalendarAccountStore.saveToken(provider, value)
                    finish(value, null)
                } catch (e: Exception) { finish(null, e) }
            }
            override fun onError(error: Exception) { finish(null, error) }
        })
    }

    private fun finish(token: CalendarAccountToken?, error: Exception?) {
        val listeners = waiting.toList()
        waiting.clear()
        refreshing = false
        listeners.forEach { if (token != null) it.onSuccess(token) else it.onError(error ?: IOException("Authorization failed")) }
    }
}
