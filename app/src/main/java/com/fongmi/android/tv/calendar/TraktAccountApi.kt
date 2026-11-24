package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.App
import com.fongmi.android.tv.BuildConfig
import com.fongmi.android.tv.api.TmdbLibraryApi
import com.fongmi.android.tv.bean.DiscoverMediaKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate

/** Only the watchlist is synchronized; watched history and collection records are never written. */
internal object TraktAccountApi {
    private const val MAX_PAGES = 200

    fun deviceCode(clientId: String, tag: Any, listener: TmdbLibraryApi.Listener<CalendarDeviceCode>) = safely(listener) {
        CalendarAccountHttp.request(oauthUrl("device/code"), tag, headers(clientId),
            body = deviceCodeBody(clientId), method = "POST", parser = { body, _ -> parseDeviceCode(body) },
            listener = listener)
    }

    fun deviceToken(clientId: String, code: String, tag: Any,
        listener: TmdbLibraryApi.Listener<CalendarAccountToken>) = safely(listener) {
        CalendarAccountHttp.request(oauthUrl("device/token"), tag, headers(clientId),
            body = deviceTokenBody(clientId, code), method = "POST", parser = { body, _ -> parseToken(body) },
            listener = listener)
    }

    fun refresh(clientId: String, token: CalendarAccountToken, tag: Any,
        listener: TmdbLibraryApi.Listener<CalendarAccountToken>) = safely(listener) {
        CalendarAccountHttp.request(oauthUrl("token"), tag, headers(clientId),
            body = refreshBody(clientId, token), method = "POST", parser = { body, _ -> parseToken(body) },
            listener = listener)
    }

    fun following(clientId: String, token: CalendarAccountToken, tag: Any,
        listener: TmdbLibraryApi.Listener<List<CalendarFollow>>) = safely(listener) {
        val result = linkedMapOf<Long, CalendarFollow>()
        fun load(page: Int) {
            CalendarAccountHttp.request(followingUrl(page), tag, headers(clientId, token),
                parser = { body, headers -> parseFollowing(body, headers, page) },
                listener = object : TmdbLibraryApi.Listener<FollowingPage> {
                    override fun onSuccess(value: FollowingPage) {
                        value.items.forEach { result[it.remoteId] = it }
                        if (value.hasMore && page < MAX_PAGES) load(page + 1)
                        else if (value.hasMore) listener.onError(IllegalStateException("Trakt watchlist is too large"))
                        else listener.onSuccess(result.values.toList())
                    }
                    override fun onError(error: Exception) = listener.onError(error)
                })
        }
        load(1)
    }

    fun week(weekStart: LocalDate, follows: List<CalendarFollow>, clientId: String,
        token: CalendarAccountToken, tag: Any, listener: TmdbLibraryApi.Listener<AiringCalendarResult>) = safely(listener) {
        CalendarAccountHttp.request(weekUrl(weekStart), tag, headers(clientId, token),
            parser = { body, _ -> parseWeek(body, weekStart, follows) }, listener = listener)
    }

    fun setFollowing(follow: CalendarFollow, following: Boolean, clientId: String,
        token: CalendarAccountToken, tag: Any, listener: TmdbLibraryApi.Listener<Boolean>) = safely(listener) {
        val url = apiUrl(if (following) "sync/watchlist" else "sync/watchlist/remove")
        CalendarAccountHttp.request(url, tag, headers(clientId, token),
            body = followingBody(follow), method = "POST",
            parser = { body, _ -> parseMutation(body, following) }, listener = listener)
    }

    fun cancel(tag: Any) = CalendarAccountHttp.cancel(tag)

    private inline fun <T> safely(listener: TmdbLibraryApi.Listener<T>, action: () -> Unit) {
        try { action() }
        catch (error: Exception) { App.post { listener.onError(error) } }
    }

    internal fun oauthUrl(path: String): HttpUrl = HttpUrl.Builder().scheme("https")
        .host("auth.trakt.tv").addPathSegment("oauth").addPathSegments(path).build()

    private fun apiUrl(path: String): HttpUrl = HttpUrl.Builder().scheme("https")
        .host("api.trakt.tv").addPathSegments(path).build()

    internal fun headers(clientId: String, token: CalendarAccountToken? = null): Map<String, String> {
        require(clientId.isNotBlank()) { "Trakt Client ID unavailable" }
        return linkedMapOf("Content-Type" to "application/json", "User-Agent" to "TV/${BuildConfig.VERSION_NAME}",
            "trakt-api-version" to "2",
            "trakt-api-key" to clientId.trim()).apply {
            if (token != null) {
                require(token.accessToken.isNotBlank()) { "Trakt sign-in required" }
                put("Authorization", "Bearer ${token.accessToken}")
            }
        }
    }

    internal fun followingUrl(page: Int): HttpUrl = apiUrl("sync/watchlist/shows/added/asc")
        .newBuilder().addQueryParameter("extended", "full")
        .addQueryParameter("page", page.coerceAtLeast(1).toString())
        .addQueryParameter("limit", "100").build()

    internal fun weekUrl(weekStart: LocalDate): HttpUrl =
        apiUrl("calendars/my/shows/${AiringCalendarIndex.weekStart(weekStart).minusDays(1)}/8")
            .newBuilder().addQueryParameter("extended", "full").build()

    internal fun deviceCodeBody(clientId: String): String = JsonObject().apply {
        require(clientId.isNotBlank()) { "Trakt Client ID unavailable" }
        addProperty("client_id", clientId.trim())
    }.toString()

    internal fun deviceTokenBody(clientId: String, code: String): String =
        JsonParser.parseString(deviceCodeBody(clientId)).asJsonObject.apply {
            require(code.isNotBlank()) { "Trakt device code unavailable" }
            addProperty("code", code)
        }.toString()

    internal fun refreshBody(clientId: String, token: CalendarAccountToken): String =
        JsonParser.parseString(deviceCodeBody(clientId)).asJsonObject.apply {
            require(token.refreshToken.isNotBlank()) { "Trakt sign-in required" }
            addProperty("refresh_token", token.refreshToken)
            addProperty("grant_type", "refresh_token")
            addProperty("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
        }.toString()

    internal fun parseDeviceCode(body: String): CalendarDeviceCode {
        val value = objectBody(body)
        val device = value.requiredText("device_code")
        val user = value.requiredText("user_code")
        val url = value.requiredText("verification_url").toHttpUrlOrNull()
        require(url != null && url.scheme == "https" &&
            url.host in setOf("auth.trakt.tv", "trakt.tv", "app.trakt.tv") &&
            url.username.isEmpty() && url.password.isEmpty()) { "Invalid Trakt activation URL" }
        val expires = value.positiveInt("expires_in")
        val interval = value.positiveInt("interval")
        require(interval <= expires) { "Invalid Trakt device polling interval" }
        return CalendarDeviceCode(device, user, url.toString(), expires, interval)
    }

    internal fun parseToken(body: String): CalendarAccountToken {
        val value = objectBody(body)
        val access = value.requiredText("access_token")
        val refresh = value.requiredText("refresh_token")
        require(value.requiredText("token_type").equals("bearer", true)) { "Invalid Trakt token type" }
        val created = value.integer("created_at")?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid Trakt token creation time")
        val expires = value.integer("expires_in")?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid Trakt token expiry")
        val expiresAt = try { Math.multiplyExact(Math.addExact(created, expires), 1000L) }
        catch (error: ArithmeticException) { throw IllegalArgumentException("Invalid Trakt token expiry", error) }
        return CalendarAccountToken(access, refresh, expiresAt)
    }

    internal data class FollowingPage(val items: List<CalendarFollow>, val hasMore: Boolean)

    internal fun parseFollowing(body: String, headers: Headers, page: Int): FollowingPage {
        val root = JsonParser.parseString(body)
        require(root.isJsonArray) { "Invalid Trakt watchlist response" }
        val returnedPage = headers["X-Pagination-Page"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("Invalid Trakt watchlist page")
        }
        require(returnedPage == null || returnedPage == page) { "Unexpected Trakt watchlist page" }
        val items = root.asJsonArray.mapNotNull { row ->
            require(row.isJsonObject) { "Invalid Trakt watchlist item" }
            val value = row.asJsonObject
            val type = value.get("type")?.takeUnless { it.isJsonNull }
            if (type != null && (!type.isJsonPrimitive || !type.asJsonPrimitive.isString || type.asString != "show"))
                return@mapNotNull null
            val show = value.get("show")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("Invalid Trakt watchlist show")
            val ids = show.get("ids")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("Invalid Trakt watchlist identity")
            val remoteId = ids.integer("trakt")?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Invalid Trakt watchlist identity")
            val media = TraktCalendarApi.parseShow(show)
                ?: throw IllegalArgumentException("Invalid Trakt watchlist show")
            CalendarFollow(CalendarProvider.TRAKT, remoteId, media)
        }.distinctBy { it.remoteId }
        val pageCount = headers["X-Pagination-Page-Count"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("Invalid Trakt watchlist page count")
        }
        require(pageCount == null || pageCount >= 0 &&
            (root.asJsonArray.isEmpty || pageCount >= page)) { "Invalid Trakt watchlist page count" }
        // A server may clamp limit=100. A short page therefore does not establish completeness.
        val hasMore = root.asJsonArray.size() > 0 && (pageCount == null || page < pageCount)
        return FollowingPage(items, hasMore)
    }

    internal fun parseWeek(body: String, weekStart: LocalDate, follows: List<CalendarFollow>): AiringCalendarResult {
        val week = AiringCalendarIndex.weekStart(weekStart)
        val selected = follows.filter { it.provider == CalendarProvider.TRAKT }
        val ids = selected.map { it.remoteId }.filter { it > 0 }.toSet()
        val mediaIds = selected.map { it.media.id }.filter(String::isNotBlank).toSet()
        val entries = (0..6).flatMap { day -> TraktCalendarApi.parse(body, week.plusDays(day.toLong())).items }
            .filter { it.remoteId > 0 && it.remoteId in ids || it.media.id in mediaIds }
            .sortedWith(compareBy({ it.date }, { it.media.name }, { it.media.id }))
        return AiringCalendarResult(entries, false, false, hasKeeps = selected.isNotEmpty(),
            knownUpdateDates = entries.map { it.date }.toSet())
    }

    internal fun followingBody(follow: CalendarFollow): String {
        require(follow.provider == CalendarProvider.TRAKT) { "Invalid Trakt follow provider" }
        val ids = JsonObject()
        if (follow.remoteId > 0) ids.addProperty("trakt", follow.remoteId)
        else {
            val key = DiscoverMediaKey.parse(follow.media.id)?.takeUnless { it.isMovie }
                ?: throw IllegalArgumentException("Invalid Trakt show identity")
            ids.addProperty("tmdb", key.id)
        }
        return JsonObject().apply {
            add("shows", JsonArray().apply { add(JsonObject().apply { add("ids", ids) }) })
        }.toString()
    }

    internal fun parseMutation(body: String, following: Boolean): Boolean {
        val value = objectBody(body)
        val notFound = value.get("not_found")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("Invalid Trakt watchlist update")
        val missing = notFound.get("shows")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalArgumentException("Invalid Trakt watchlist update")
        require(missing.isEmpty) { "Trakt show was not found" }
        val changed = value.get(if (following) "added" else "deleted")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.integer("shows") ?: throw IllegalArgumentException("Invalid Trakt watchlist update")
        require(changed >= 0) { "Invalid Trakt watchlist update" }
        if (following) {
            val existing = value.get("existing")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.integer("shows") ?: throw IllegalArgumentException("Invalid Trakt watchlist update")
            require(existing >= 0 && (changed > 0 || existing > 0)) { "Trakt watchlist was not updated" }
        }
        // Removing an already absent show is successful when the API resolved its identity.
        return true
    }

    private fun objectBody(body: String): JsonObject = JsonParser.parseString(body).let {
        require(it.isJsonObject) { "Invalid Trakt account response" }
        it.asJsonObject
    }

    private fun JsonObject.requiredText(name: String): String = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank()
    }?.asString ?: throw IllegalArgumentException("Invalid Trakt account $name")

    private fun JsonObject.positiveInt(name: String): Int = integer(name)?.takeIf { it in 1..Int.MAX_VALUE }
        ?.toInt() ?: throw IllegalArgumentException("Invalid Trakt account $name")

    private fun JsonObject.integer(name: String): Long? = try {
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal?.longValueExact()
    } catch (_: RuntimeException) { null }
}
