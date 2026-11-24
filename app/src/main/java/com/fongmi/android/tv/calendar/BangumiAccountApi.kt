package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.api.TmdbLibraryApi
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigDecimal
import java.time.LocalDate
import java.util.ArrayDeque

/** Account collections remain private; status changes never send ratings or watched progress. */
object BangumiAccountApi {
    private val base = "https://api.bgm.tv/".toHttpUrl()
    private const val COLLECTION_LIMIT = 50
    private const val EPISODE_LIMIT = 200
    private const val MAX_CONCURRENT = 4

    internal data class Page<T>(val items: List<T>, val nextOffset: Int?)
    internal data class DatedEpisode(val id: Long, val date: LocalDate, val label: String)
    internal data class Mutation(val method: String, val body: String)

    @JvmStatic
    fun following(token: CalendarAccountToken, tag: Any, listener: TmdbLibraryApi.Listener<List<CalendarFollow>>) {
        get("v0/me", token, tag, ::parseUsername, object : TmdbLibraryApi.Listener<String> {
            override fun onSuccess(username: String) {
                val follows = linkedMapOf<Long, CalendarFollow>()
                fun load(status: Int, offset: Int) {
                    val url = collectionsUrl(username, status, offset)
                    CalendarAccountHttp.request(url, tag, headers(token), parser = { body, _ ->
                        parseCollections(body, offset)
                    }, listener = object : TmdbLibraryApi.Listener<Page<CalendarFollow>> {
                        override fun onSuccess(value: Page<CalendarFollow>) {
                            value.items.forEach { follows[it.remoteId] = it }
                            when {
                                value.nextOffset != null -> load(status, value.nextOffset)
                                status == 1 -> load(3, 0)
                                else -> listener.onSuccess(follows.values.toList())
                            }
                        }
                        override fun onError(error: Exception) = listener.onError(error)
                    })
                }
                load(1, 0)
            }
            override fun onError(error: Exception) = listener.onError(error)
        })
    }

    @JvmStatic
    fun week(weekStart: LocalDate, follows: List<CalendarFollow>, token: CalendarAccountToken,
        tag: Any, listener: TmdbLibraryApi.Listener<AiringCalendarResult>) {
        val week = AiringCalendarIndex.weekStart(weekStart)
        val selected = follows.filter { it.provider == CalendarProvider.BANGUMI && it.remoteId > 0 && it.status in listOf(0, 1, 3) }
            .distinctBy { it.remoteId }
        data class Job(val follow: CalendarFollow, val offset: Int)
        val queue = ArrayDeque<Job>().apply { selected.forEach { add(Job(it, 0)) } }
        val episodes = linkedMapOf<Long, MutableMap<Long, DatedEpisode>>()
        var active = 0
        var failed = false
        var pumping = false
        var delivered = false
        fun pump() {
            if (pumping || delivered) return
            pumping = true
            try {
                while (active < MAX_CONCURRENT && queue.isNotEmpty()) {
                    val job = queue.removeFirst()
                    active++
                    val url = episodesUrl(job.follow.remoteId, job.offset)
                    CalendarAccountHttp.request(url, tag, headers(token), parser = { body, _ ->
                        parseEpisodes(body, job.follow.remoteId, job.offset)
                    }, listener = object : TmdbLibraryApi.Listener<Page<DatedEpisode>> {
                        override fun onSuccess(value: Page<DatedEpisode>) {
                            val target = episodes.getOrPut(job.follow.remoteId) { linkedMapOf() }
                            value.items.filter { it.date >= week && it.date <= week.plusDays(6) }
                                .forEach { target.putIfAbsent(it.id, it) }
                            value.nextOffset?.let { queue.addFirst(Job(job.follow, it)) }
                            active--
                            pump()
                        }
                        override fun onError(error: Exception) {
                            failed = true
                            active--
                            pump()
                        }
                    })
                }
                if (active == 0 && queue.isEmpty()) {
                    delivered = true
                    val entries = selected.flatMap { follow ->
                        entries(follow, episodes[follow.remoteId]?.values?.toList().orEmpty(), week)
                    }.sortedBy { it.date }
                    listener.onSuccess(AiringCalendarResult(entries, false, failed,
                        hasKeeps = selected.isNotEmpty(), knownUpdateDates = entries.map { it.date }.toSet()))
                }
            } finally { pumping = false }
        }
        pump()
    }

    @JvmStatic
    fun setFollowing(follow: CalendarFollow, following: Boolean, token: CalendarAccountToken,
        tag: Any, listener: TmdbLibraryApi.Listener<Boolean>) {
        if (follow.provider != CalendarProvider.BANGUMI || follow.remoteId <= 0) {
            listener.onError(IllegalArgumentException("Invalid Bangumi subject"))
            return
        }
        get("v0/me", token, tag, ::parseUsername, object : TmdbLibraryApi.Listener<String> {
            override fun onSuccess(username: String) {
                fun update(status: Int?) {
                    val mutation = mutation(status, following)
                    if (mutation == null) {
                        listener.onSuccess(!(following && status == 2))
                        return
                    }
                    CalendarAccountHttp.request(url("v0/users/-/collections/${follow.remoteId}"), tag,
                        headers(token), body = mutation.body, method = mutation.method,
                        parser = { _, _ -> true }, listener = listener)
                }
                CalendarAccountHttp.request(collectionUrl(username, follow.remoteId), tag, headers(token),
                    parser = { body, _ -> parseCollectionStatus(body, follow.remoteId) }, listener = object : TmdbLibraryApi.Listener<Int> {
                        override fun onSuccess(value: Int) = update(value)
                        override fun onError(error: Exception) {
                            if (error is CalendarAccountHttpException && error.status == 404) update(null)
                            else listener.onError(error)
                        }
                    })
            }
            override fun onError(error: Exception) = listener.onError(error)
        })
    }

    @JvmStatic
    fun cancel(tag: Any) = CalendarAccountHttp.cancel(tag)

    internal fun parseUsername(body: String): String {
        val username = objectBody(body).string("username")
        require(username.isNotBlank()) { "Invalid Bangumi account response" }
        return username
    }

    internal fun collectionsUrl(username: String, status: Int, offset: Int): HttpUrl = base.newBuilder()
        .addPathSegments("v0/users").addPathSegment(username).addPathSegment("collections")
        .addQueryParameter("subject_type", "2").addQueryParameter("type", status.toString())
        .addQueryParameter("limit", COLLECTION_LIMIT.toString()).addQueryParameter("offset", offset.toString()).build()

    internal fun episodesUrl(subject: Long, offset: Int): HttpUrl = url("v0/episodes").newBuilder()
        .addQueryParameter("subject_id", subject.toString()).addQueryParameter("type", "0")
        .addQueryParameter("limit", EPISODE_LIMIT.toString()).addQueryParameter("offset", offset.toString()).build()

    internal fun collectionUrl(username: String, subject: Long): HttpUrl = base.newBuilder()
        .addPathSegments("v0/users").addPathSegment(username).addPathSegment("collections")
        .addPathSegment(subject.toString()).build()

    internal fun parseCollections(body: String, offset: Int): Page<CalendarFollow> {
        val root = objectBody(body)
        val data = pageData(root)
        val next = nextOffset(root, data.size(), offset, COLLECTION_LIMIT)
        val excluded = data.mapNotNull { element ->
            val item = element.objectOrNull() ?: return@mapNotNull null
            if (integer(item.get("type")) !in listOf(2L, 4L, 5L)) return@mapNotNull null
            integer(item.get("subject_id"))
        }.toSet()
        val follows = linkedMapOf<Long, CalendarFollow>()
        data.forEach { element ->
            val item = element.objectOrNull() ?: return@forEach
            val status = integer(item.get("type"))?.takeIf { it in 1L..5L }?.toInt() ?: return@forEach
            if (status != 1 && status != 3 || integer(item.get("subject_type")) != 2L) return@forEach
            val id = integer(item.get("subject_id"))?.takeIf { it > 0 } ?: return@forEach
            if (id in excluded) return@forEach
            val subject = item.get("subject")?.objectOrNull() ?: return@forEach
            if (subject.get("id") != null && integer(subject.get("id")) != id) return@forEach
            if (subject.get("nsfw")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true)
                return@forEach
            val media = BangumiCalendarApi.parseMedia(subject, id)
            if (media.name.isBlank()) return@forEach
            follows[id] = CalendarFollow(CalendarProvider.BANGUMI, id, media, status)
        }
        return Page(follows.values.toList(), next)
    }

    internal fun parseEpisodes(body: String, subjectId: Long, offset: Int): Page<DatedEpisode> {
        val root = objectBody(body)
        val data = pageData(root)
        val next = nextOffset(root, data.size(), offset, EPISODE_LIMIT)
        val episodes = linkedMapOf<Long, DatedEpisode>()
        data.forEach { element ->
            val item = element.objectOrNull() ?: return@forEach
            if (integer(item.get("subject_id")) != subjectId || integer(item.get("type")) != 0L) return@forEach
            val id = integer(item.get("id"))?.takeIf { it > 0 } ?: return@forEach
            val date = try { LocalDate.parse(item.string("airdate")) } catch (_: RuntimeException) { return@forEach }
            val number = number(item.get("sort"))?.takeIf { it > BigDecimal.ZERO }
                ?: number(item.get("ep"))?.takeIf { it > BigDecimal.ZERO }
            val label = number?.takeIf { it.precision() <= 16 && it.scale() in -6..6 }
                ?.stripTrailingZeros()?.toPlainString()?.let { "EP $it" }.orEmpty()
            episodes.putIfAbsent(id, DatedEpisode(id, date, label))
        }
        return Page(episodes.values.toList(), next)
    }

    internal fun entries(follow: CalendarFollow, episodes: List<DatedEpisode>, week: LocalDate): List<AiringCalendarEntry> =
        episodes.distinctBy { it.id }.filter { it.date >= week && it.date <= week.plusDays(6) }
            .groupBy { it.date }.toSortedMap().map { (date, day) ->
                AiringCalendarEntry(follow.media, date, provider = CalendarProvider.BANGUMI,
                    remoteId = follow.remoteId, episodeLabel = day.map { it.label }.filter { it.isNotBlank() }
                        .distinct().joinToString(" · "))
            }

    internal fun parseCollectionStatus(body: String, subjectId: Long): Int {
        val item = objectBody(body)
        require(integer(item.get("subject_id")) == subjectId) { "Unexpected Bangumi subject" }
        val type = integer(item.get("type"))?.takeIf { it in 1L..5L }?.toInt()
        require(type != null) { "Invalid Bangumi collection status" }
        return type
    }

    internal fun mutation(status: Int?, following: Boolean): Mutation? = when {
        status == 2 -> null
        following && status == null -> Mutation("POST", "{\"type\":3,\"private\":true}")
        following && status in listOf(4, 5) -> Mutation("PATCH", "{\"type\":3}")
        !following && status in listOf(1, 3) -> Mutation("PATCH", "{\"type\":4}")
        else -> null
    }

    private fun nextOffset(root: JsonObject, dataSize: Int, expected: Int, maximumLimit: Int): Int? {
        val offset = integer(root.get("offset"))
        val total = integer(root.get("total"))
        val limit = integer(root.get("limit"))
        require(offset == expected.toLong() && total != null && total in 0L..Int.MAX_VALUE.toLong() && limit != null && limit in 1L..maximumLimit.toLong()
            && dataSize <= limit) { "Invalid Bangumi pagination" }
        val next = expected.toLong() + dataSize
        require(next <= total) { "Contradictory Bangumi page size" }
        if (next >= total) return null
        require(dataSize > 0 && next <= Int.MAX_VALUE) { "Incomplete Bangumi page" }
        return next.toInt()
    }

    private fun pageData(root: JsonObject): JsonArray {
        val data = root.get("data")
        require(data != null && data.isJsonArray) { "Invalid Bangumi page" }
        return data.asJsonArray
    }

    private fun objectBody(body: String): JsonObject {
        val root = JsonParser.parseString(body)
        require(root.isJsonObject) { "Invalid Bangumi response" }
        return root.asJsonObject
    }

    private fun JsonElement.objectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(name: String): String = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString?.trim().orEmpty()
    private fun number(value: JsonElement?): BigDecimal? = try {
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal
    } catch (_: RuntimeException) { null }
    private fun integer(value: JsonElement?): Long? = try { number(value)?.longValueExact() }
    catch (_: RuntimeException) { null }

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()
    private fun headers(token: CalendarAccountToken) = mapOf("Authorization" to "Bearer ${token.accessToken}",
        "User-Agent" to "wobuhui666/TV/${com.fongmi.android.tv.BuildConfig.VERSION_NAME} (Android) (https://github.com/wobuhui666/TV)")

    private fun <T> get(path: String, token: CalendarAccountToken, tag: Any, parser: (String) -> T,
        listener: TmdbLibraryApi.Listener<T>) = CalendarAccountHttp.request(url(path), tag, headers(token),
            parser = { body, _ -> parser(body) }, listener = listener)
}
