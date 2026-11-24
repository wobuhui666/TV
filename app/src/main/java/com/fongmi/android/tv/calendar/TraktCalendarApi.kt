package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.App
import com.fongmi.android.tv.BuildConfig
import com.fongmi.android.tv.api.TmdbLibraryApi
import com.fongmi.android.tv.bean.DiscoverMediaKey
import com.fongmi.android.tv.bean.LibraryMedia
import com.fongmi.android.tv.bean.Vod
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/** Public episode schedules use Trakt's UTC timestamps, independent of TMDB's dates. */
internal object TraktCalendarApi {
    fun airingOn(date: LocalDate, page: Int, clientId: String, tag: Any,
        listener: TmdbLibraryApi.Listener<AiringCalendarPage>) {
        if (clientId.isBlank() || page > 1) {
            App.post { listener.onError(IOException(if (clientId.isBlank())
                "Trakt Client ID unavailable" else "Trakt calendar has no additional pages")) }
            return
        }
        val request = try { request(date, clientId) }
        catch (error: IllegalArgumentException) {
            App.post { listener.onError(IOException("Invalid Trakt Client ID", error)) }
            return
        }
        CalendarHttpClient.get(request.url, tag, request.headers.toMap(), { parse(it, date) }, listener)
    }

    internal fun request(date: LocalDate, clientId: String): Request {
        require(clientId.isNotBlank()) { "Trakt Client ID unavailable" }
        val url = HttpUrl.Builder().scheme("https").host("api.trakt.tv")
            .addPathSegments("calendars/all/shows")
            .addPathSegment(date.minusDays(1).toString()).addPathSegment("2")
            .addQueryParameter("extended", "full").build()
        return Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", "TV/${BuildConfig.VERSION_NAME}")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId.trim()).build()
    }

    internal fun parse(body: String, date: LocalDate): AiringCalendarPage {
        val root = JsonParser.parseString(body)
        require(root.isJsonArray) { "Invalid Trakt calendar response" }
        val works = linkedMapOf<String, Work>()
        root.asJsonArray.forEach { row ->
            val item = row.objectOrNull() ?: return@forEach
            val aired = try { OffsetDateTime.parse(item.text("first_aired"))
                .atZoneSameInstant(AiringCalendarIndex.TIME_ZONE).toLocalDate() }
            catch (_: RuntimeException) { return@forEach }
            if (aired != date) return@forEach
            val show = item.get("show").objectOrNull() ?: return@forEach
            val ids = show.get("ids").objectOrNull() ?: return@forEach
            val tmdb = ids.integer("tmdb")?.takeIf { it > 0 }
            val trakt = ids.integer("trakt")?.takeIf { it > 0 }
            val id = when {
                tmdb != null -> DiscoverMediaKey.of(DiscoverMediaKey.TV, tmdb).toString()
                trakt != null -> "trakt:show:$trakt"
                else -> return@forEach
            }
            val title = show.text("title").takeIf { it.isNotBlank() } ?: return@forEach
            val episode = item.get("episode").objectOrNull() ?: return@forEach
            val season = episode.integer("season")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
                ?: return@forEach
            val number = episode.integer("number")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
                ?: return@forEach
            val runtime = episode.integer("runtime")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: 0
            val images = show.get("images").objectOrNull()
            val work = works.getOrPut(id) { Work(media(show, id, title), images.image("logo"), trakt ?: 0) }
            work.episodes.putIfAbsent(season to number, LibraryMedia.Episode(season, number,
                episode.text("title"), episode.text("overview"),
                episode.get("images").objectOrNull().image("screenshot"), date.toString(), runtime))
        }
        return AiringCalendarPage(works.values.map { work ->
            val episodes = work.episodes.values.sortedWith(compareBy({ it.season() }, { it.number() }))
            AiringCalendarEntry(work.media, date, episodes.map {
                LibraryMedia.AiringEpisode(it.season(), it.number(), it.name(), it.airDate(), it.runtime())
            }, episodeDetails = episodes, provider = CalendarProvider.TRAKT,
                scheduleKind = CalendarScheduleKind.EXACT, logo = work.logo, remoteId = work.remoteId)
        })
    }

    internal fun parseShow(show: JsonObject): Vod? {
        val ids = show.get("ids").objectOrNull() ?: return null
        val tmdb = ids.integer("tmdb")?.takeIf { it > 0 }
        val trakt = ids.integer("trakt")?.takeIf { it > 0 }
        val id = when {
            tmdb != null -> DiscoverMediaKey.of(DiscoverMediaKey.TV, tmdb).toString()
            trakt != null -> "trakt:show:$trakt"
            else -> return null
        }
        val title = show.text("title").takeIf { it.isNotBlank() } ?: return null
        return media(show, id, title)
    }

    private fun media(show: JsonObject, id: String, title: String) = Vod().apply {
        this.id = id
        name = title
        originalName = show.text("original_title")
        typeName = DiscoverMediaKey.TV
        year = show.integer("year")?.takeIf { it in 1..9999 }?.toString().orEmpty()
        val images = show.get("images").objectOrNull()
        pic = images.image("poster")
        backdrop = images.image("fanart")
        content = show.text("overview")
        remarks = try { show.get("rating")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asBigDecimal?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.TEN }
            ?.stripTrailingZeros()?.toPlainString().orEmpty() }
        catch (_: RuntimeException) { "" }
        genreText = show.get("genres")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.stringOrNull()?.takeIf(String::isNotBlank) }
            ?.distinct()?.joinToString(" · ").orEmpty()
    }

    private class Work(val media: Vod, val logo: String, val remoteId: Long,
        val episodes: MutableMap<Pair<Int, Int>, LibraryMedia.Episode> = linkedMapOf())

    private fun JsonElement?.objectOrNull(): JsonObject? =
        this?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement?.stringOrNull(): String? =
        this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()

    private fun JsonObject.text(name: String): String = get(name).stringOrNull().orEmpty()

    private fun JsonObject.integer(name: String): Long? = try {
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asBigDecimal?.longValueExact()
    } catch (_: RuntimeException) { null }

    private fun JsonObject?.image(name: String): String =
        this?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.firstNotNullOfOrNull { element ->
            val value = element.stringOrNull()?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) }
                ?: return@firstNotNullOfOrNull null
            val candidate = when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("https://") || value.startsWith("http://") -> value
                value.contains(":") || value.startsWith("/") -> return@firstNotNullOfOrNull null
                else -> "https://$value"
            }
            candidate.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
        }.orEmpty()
}
