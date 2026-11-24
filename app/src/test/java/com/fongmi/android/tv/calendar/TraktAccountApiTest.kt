package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.bean.Vod
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Headers
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TraktAccountApiTest {
    private val token = CalendarAccountToken("access-token", "refresh-token", 1000000)
    private val monday = LocalDate.of(2026, 11, 16)

    @Test fun shouldUseTheDedicatedAuthHostAndNativeDeviceGrantWithoutClientSecrets() {
        assertEquals("https://auth.trakt.tv/oauth/device/code", TraktAccountApi.oauthUrl("device/code").toString())
        assertEquals("https://auth.trakt.tv/oauth/device/token", TraktAccountApi.oauthUrl("device/token").toString())
        assertEquals("https://auth.trakt.tv/oauth/token", TraktAccountApi.oauthUrl("token").toString())
        val code = objectBody(TraktAccountApi.deviceCodeBody(" client-id "))
        assertEquals(setOf("client_id"), code.keySet())
        assertEquals("client-id", code.get("client_id").asString)
        val poll = objectBody(TraktAccountApi.deviceTokenBody("client-id", "device-code"))
        assertEquals(setOf("client_id", "code"), poll.keySet())
        assertEquals("device-code", poll.get("code").asString)
        assertFalse(poll.has("client_secret"))
    }

    @Test fun shouldRefreshWithTheCurrentSingleUseRefreshTokenWithoutSendingTheAccessToken() {
        val body = objectBody(TraktAccountApi.refreshBody("client-id", token))
        assertEquals("refresh-token", body.get("refresh_token").asString)
        assertEquals("refresh_token", body.get("grant_type").asString)
        assertEquals("urn:ietf:wg:oauth:2.0:oob", body.get("redirect_uri").asString)
        assertFalse(body.has("access_token"))
        assertFalse(body.has("client_secret"))
    }

    @Test fun shouldParseTheDeviceCodeAndRespectItsExpiryAndPollingInterval() {
        val code = TraktAccountApi.parseDeviceCode("""{
            "device_code":"secret-device-code","user_code":"ABCD1234",
            "verification_url":"https://auth.trakt.tv/activate","expires_in":600,"interval":5
        }""")
        assertEquals("secret-device-code", code.deviceCode)
        assertEquals("ABCD1234", code.userCode)
        assertEquals("https://auth.trakt.tv/activate", code.verificationUrl)
        assertEquals(600, code.expiresIn)
        assertEquals(5, code.interval)
    }

    @Test fun shouldRejectUnusableDeviceResponsesAndUnsafeActivationUrls() {
        val base = objectBody("""{
            "device_code":"code","user_code":"USER1234","verification_url":"https://trakt.tv/activate",
            "expires_in":600,"interval":5
        }""")
        for ((field, value) in listOf("device_code" to "\"\"", "user_code" to "false",
            "expires_in" to "0", "expires_in" to "1.5", "interval" to "0", "interval" to "700",
            "verification_url" to "\"javascript:alert(1)\"", "verification_url" to "\"https://example.com/activate\"",
            "verification_url" to "\"http://trakt.tv/activate\"")) {
            val body = base.deepCopy().apply { add(field, JsonParser.parseString(value)) }.toString()
            assertInvalid { TraktAccountApi.parseDeviceCode(body) }
        }
    }

    @Test fun shouldReplaceBothTokensAndCalculateExpiryFromTheProviderCreationTime() {
        val result = TraktAccountApi.parseToken("""{
            "access_token":"new-access","refresh_token":"new-refresh","token_type":"bearer",
            "created_at":1795000000,"expires_in":604800
        }""")
        assertEquals("new-access", result.accessToken)
        assertEquals("new-refresh", result.refreshToken)
        assertEquals(1795604800000L, result.expiresAt)
    }

    @Test fun shouldRejectInvalidTokenRepliesRatherThanLosingTheExistingRefreshToken() {
        val base = objectBody("""{
            "access_token":"new-access","refresh_token":"new-refresh","token_type":"bearer",
            "created_at":1795000000,"expires_in":604800
        }""")
        for ((field, value) in listOf("access_token" to "null", "refresh_token" to "\"\"",
            "token_type" to "\"basic\"", "created_at" to "false", "expires_in" to "1.5",
            "expires_in" to "9223372036854775807")) {
            val body = base.deepCopy().apply { add(field, JsonParser.parseString(value)) }.toString()
            assertInvalid { TraktAccountApi.parseToken(body) }
        }
    }

    @Test fun shouldReadOnlyShowWatchlistItemsAndKeepTheirNativeRemoteIdentity() {
        val show = watchlistShow(100, 8)
        val native = watchlistShow(200, null)
        val movie = """{"type":"movie","movie":{"title":"电影","ids":{"trakt":300,"tmdb":9}}}"""
        val result = TraktAccountApi.parseFollowing("[$show,$native,$show,$movie]", pageHeaders(1, 1), 1)
        assertEquals(listOf(100L, 200L), result.items.map { it.remoteId })
        assertEquals(listOf("tmdb:tv:8", "trakt:show:200"), result.items.map { it.media.id })
        assertTrue(result.items.all { it.provider == CalendarProvider.TRAKT })
        assertFalse(result.hasMore)
    }

    @Test fun shouldRequestExplicitWatchlistPaginationAndUseReturnedPageCountsAfterLimitClamping() {
        val url = TraktAccountApi.followingUrl(2)
        assertEquals("/sync/watchlist/shows/added/asc", url.encodedPath)
        assertEquals("2", url.queryParameter("page"))
        assertEquals("100", url.queryParameter("limit"))
        assertEquals("full", url.queryParameter("extended"))
        val shortPage = "[${watchlistShow(100, 8)}]"
        assertTrue(TraktAccountApi.parseFollowing(shortPage, pageHeaders(1, 3), 1).hasMore)
        assertFalse(TraktAccountApi.parseFollowing(shortPage, pageHeaders(3, 3), 3).hasMore)
        assertEquals("Bearer access-token", TraktAccountApi.headers("client-id", token)["Authorization"])
        assertFalse(TraktAccountApi.headers("client-id").containsKey("Authorization"))
    }

    @Test fun shouldContinueUntilAnEmptyPageWhenPaginationHeadersAreAbsent() {
        val headers = Headers.Builder().build()
        assertTrue(TraktAccountApi.parseFollowing("[${watchlistShow(100, 8)}]", headers, 1).hasMore)
        assertFalse(TraktAccountApi.parseFollowing("[]", headers, 2).hasMore)
    }

    @Test fun shouldRejectMismatchedPagesAndMalformedWatchlistRepliesBeforeReportingCompleteSync() {
        val show = "[${watchlistShow(100, 8)}]"
        assertInvalid { TraktAccountApi.parseFollowing(show, pageHeaders(2, 3), 1) }
        assertInvalid { TraktAccountApi.parseFollowing(show, pageHeaders(1, 0), 1) }
        assertInvalid { TraktAccountApi.parseFollowing(show,
            Headers.Builder().add("X-Pagination-Page-Count", "invalid").build(), 1) }
        assertInvalid { TraktAccountApi.parseFollowing("[null]", pageHeaders(1, 1), 1) }
        assertInvalid { TraktAccountApi.parseFollowing("[{\"type\":\"show\",\"show\":{}}]", pageHeaders(1, 1), 1) }
        assertInvalid { TraktAccountApi.parseFollowing("{}", pageHeaders(1, 1), 1) }
    }

    @Test fun shouldRequestEightUtcDaysAndKeepOnlyWatchlistedShowsWithinTheShanghaiWeek() {
        val url = TraktAccountApi.weekUrl(monday.plusDays(2))
        assertEquals("/calendars/my/shows/2026-11-15/8", url.encodedPath)
        val follows = listOf(follow(100, "tmdb:tv:8"), follow(200, "trakt:show:200"))
        val body = listOf(
            calendarRow("2026-11-15T15:59:59Z", 100, 8, 1),
            calendarRow("2026-11-15T16:00:00Z", 100, 8, 2),
            calendarRow("2026-11-22T15:59:59Z", 200, null, 3),
            calendarRow("2026-11-22T16:00:00Z", 100, 8, 4),
            calendarRow("2026-11-17T05:00:00Z", 300, 9, 5)
        ).joinToString(",", "[", "]")
        val result = TraktAccountApi.parseWeek(body, monday, follows)
        assertEquals(listOf(monday, monday.plusDays(6)), result.items.map { it.date })
        assertEquals(listOf(2, 3), result.items.flatMap { it.episodes }.map { it.number() })
        assertEquals(setOf(monday, monday.plusDays(6)), result.knownUpdateDates)
        assertTrue(result.hasKeeps)
        assertFalse(result.loading)
        assertFalse(result.failed)
    }

    @Test fun shouldAvoidIncludingWatchedHistoryWhenTheFollowListIsEmptyOrBelongsToAnotherProvider() {
        val body = "[${calendarRow("2026-11-16T05:00:00Z", 100, 8, 1)}]"
        val empty = TraktAccountApi.parseWeek(body, monday, emptyList())
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasKeeps)
        val other = TraktAccountApi.parseWeek(body, monday, listOf(
            CalendarFollow(CalendarProvider.BANGUMI, 100, Vod().apply { id = "tmdb:tv:8" })))
        assertTrue(other.items.isEmpty())
        assertFalse(other.hasKeeps)
    }

    @Test fun shouldWriteOnlyTheRequestedShowWatchlistIdentity() {
        val native = objectBody(TraktAccountApi.followingBody(follow(100, "tmdb:tv:8")))
        assertEquals(setOf("shows"), native.keySet())
        val show = native.getAsJsonArray("shows").single().asJsonObject
        assertEquals(setOf("ids"), show.keySet())
        assertEquals(100L, show.getAsJsonObject("ids").get("trakt").asLong)
        assertFalse(show.has("watched_at"))
        assertFalse(show.has("seasons"))
        val mapped = objectBody(TraktAccountApi.followingBody(follow(0, "tmdb:tv:8")))
        assertEquals(8L, mapped.getAsJsonArray("shows").single().asJsonObject
            .getAsJsonObject("ids").get("tmdb").asLong)
        assertInvalid { TraktAccountApi.followingBody(follow(0, "tmdb:movie:8")) }
    }

    @Test fun shouldReportWatchlistUpdatesOnlyWhenTheProviderResolvesTheShow() {
        assertTrue(TraktAccountApi.parseMutation("""{
            "added":{"shows":1},"existing":{"shows":0},"not_found":{"shows":[]}
        }""", true))
        assertTrue(TraktAccountApi.parseMutation("""{
            "added":{"shows":0},"existing":{"shows":1},"not_found":{"shows":[]}
        }""", true))
        assertTrue(TraktAccountApi.parseMutation("""{
            "deleted":{"shows":0},"not_found":{"shows":[]}
        }""", false))
        assertInvalid { TraktAccountApi.parseMutation("""{
            "added":{"shows":0},"existing":{"shows":0},"not_found":{"shows":[]}
        }""", true) }
        assertInvalid { TraktAccountApi.parseMutation("""{
            "deleted":{"shows":0},"not_found":{"shows":[{"ids":{"trakt":100}}]}
        }""", false) }
        assertInvalid { TraktAccountApi.parseMutation("{}", true) }
        assertInvalid { TraktAccountApi.parseMutation("{}", false) }
    }

    private fun follow(remoteId: Long, id: String) =
        CalendarFollow(CalendarProvider.TRAKT, remoteId, Vod().apply { this.id = id; name = "作品" })

    private fun pageHeaders(page: Int, count: Int) = Headers.Builder()
        .add("X-Pagination-Page", page.toString()).add("X-Pagination-Page-Count", count.toString()).build()

    private fun watchlistShow(trakt: Long, tmdb: Long?): String = """{
        "type":"show","show":{"title":"作品","ids":{"trakt":$trakt,"tmdb":$tmdb}}
    }"""

    private fun calendarRow(timestamp: String, trakt: Long, tmdb: Long?, number: Int): String = """{
        "first_aired":"$timestamp","episode":{"season":1,"number":$number},
        "show":{"title":"作品","ids":{"trakt":$trakt,"tmdb":$tmdb}}
    }"""

    private fun objectBody(body: String): JsonObject = JsonParser.parseString(body).asJsonObject

    private fun assertInvalid(action: () -> Unit) {
        try { action(); fail("Malformed response must not report success") }
        catch (_: IllegalArgumentException) { }
    }
}
