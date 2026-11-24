package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.BuildConfig
import com.fongmi.android.tv.api.TmdbLibraryApi
import com.fongmi.android.tv.bean.DiscoverMediaKey
import com.fongmi.android.tv.bean.Vod
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** Bangumi's current weekly broadcast list; it does not promise a dated episode update. */
object BangumiCalendarApi {
    private val endpoint = "https://api.bgm.tv/calendar".toHttpUrl()
    private val coverHosts = setOf("lain.bgm.tv", "lain.bangumi.tv")

    @JvmStatic
    fun airingOn(date: LocalDate, page: Int, tag: Any, listener: TmdbLibraryApi.Listener<AiringCalendarPage>) {
        val today = LocalDate.now(AiringCalendarIndex.TIME_ZONE)
        if (page > 1 || !inCurrentWeek(date, today)) {
            listener.onSuccess(AiringCalendarPage(emptyList()))
            return
        }
        CalendarHttpClient.get(endpoint, tag,
            mapOf("User-Agent" to "wobuhui666/TV/${BuildConfig.VERSION_NAME} (Android) (https://github.com/wobuhui666/TV)"),
            { parse(it, date, today) }, listener)
    }

    @JvmStatic
    fun cancel(tag: Any) = CalendarHttpClient.cancel(tag)

    internal fun parse(body: String, date: LocalDate, today: LocalDate): AiringCalendarPage {
        if (!inCurrentWeek(date, today)) return AiringCalendarPage(emptyList())
        val root = JsonParser.parseString(body)
        require(root.isJsonArray) { "Invalid Bangumi calendar response" }
        val entries = linkedMapOf<Long, AiringCalendarEntry>()
        root.asJsonArray.forEach { bucket ->
            val day = bucket.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (integer(day.obj("weekday")?.get("id")) != date.dayOfWeek.value.toLong()) return@forEach
            val items = day.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
            items.forEach item@ { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@item
                val id = integer(item.get("id"))?.takeIf { it > 0 } ?: return@item
                if (integer(item.get("type")) != 2L || item.isExplicitlyAdult()) return@item
                val name = item.string("name_cn").ifBlank { item.string("name") }
                if (name.isBlank()) return@item
                val premiere = localDate(item.string("air_date"))
                if (premiere != null && premiere.isAfter(date)) return@item
                val media = parseMedia(item, id)
                entries.putIfAbsent(id, AiringCalendarEntry(media, date,
                    provider = CalendarProvider.BANGUMI, scheduleKind = CalendarScheduleKind.WEEKLY, remoteId = id))
            }
        }
        return AiringCalendarPage(entries.values.toList())
    }

    internal fun parseMedia(item: JsonObject, id: Long) = Vod().apply {
        this.id = "bangumi:subject:$id"
        name = item.string("name_cn").ifBlank { item.string("name") }
        originalName = item.string("name")
        typeName = DiscoverMediaKey.TV
        pic = item.obj("images")?.let { images ->
            listOf("large", "common", "medium", "small").asSequence()
                .map { image(images.string(it)) }.firstOrNull { it.isNotBlank() }
        }.orEmpty().ifBlank { image(item.string("image")) }
        // Collection lists carry short_summary; only the full subject has summary.
        content = item.string("summary").ifBlank { item.string("short_summary") }
        year = localDate(item.string("air_date").ifBlank { item.string("date") })?.year?.toString().orEmpty()
        remarks = rating(item.obj("rating")?.get("score") ?: item.get("score"))
    }

    private fun inCurrentWeek(date: LocalDate, today: LocalDate) =
        AiringCalendarIndex.weekStart(date) == AiringCalendarIndex.weekStart(today)

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()

    private fun JsonObject.isExplicitlyAdult(): Boolean = get("nsfw")?.let {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
    } ?: false

    private fun integer(value: JsonElement?): Long? = try {
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal?.longValueExact()
    } catch (_: RuntimeException) { null }

    private fun localDate(value: String): LocalDate? = try {
        value.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
    } catch (_: RuntimeException) { null }

    private fun rating(value: JsonElement?): String = try {
        val score = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal
        if (score != null && score > BigDecimal.ZERO && score <= BigDecimal.TEN)
            String.format(Locale.ROOT, "%.1f", score) else ""
    } catch (_: RuntimeException) { "" }

    private fun image(value: String): String {
        val url = value.toHttpUrlOrNull() ?: return ""
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return ""
        return if (url.scheme == "http" && url.host in coverHosts && url.port == 80)
            url.newBuilder().scheme("https").build().toString() else url.toString()
    }
}
