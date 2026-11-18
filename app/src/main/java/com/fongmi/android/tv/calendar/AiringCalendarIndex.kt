package com.fongmi.android.tv.calendar

import com.fongmi.android.tv.bean.DiscoverMediaKey
import com.fongmi.android.tv.bean.Keep
import com.fongmi.android.tv.bean.LibraryMedia
import com.fongmi.android.tv.bean.Vod
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class AiringCalendarEntry(
    val media: Vod,
    val date: LocalDate,
    val episodes: List<LibraryMedia.AiringEpisode> = emptyList(),
    val keep: Keep? = null
) {
    val stableKey: String get() = "${media.id}|$date"
}

data class AiringCalendarResult(
    val items: List<AiringCalendarEntry>,
    val loading: Boolean,
    val failed: Boolean,
    val page: Int = 1,
    val totalPages: Int = 1,
    val hasKeeps: Boolean = false
)

/** Exact dates and coordinates from TMDB; never estimates a weekly recurrence. */
object AiringCalendarIndex {
    @JvmField val TIME_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    @JvmStatic
    fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    @JvmStatic
    fun mediaKey(keep: Keep): DiscoverMediaKey? {
        if (keep.type != Keep.TYPE_VOD && keep.type != Keep.TYPE_DISCOVER) return null
        val key = DiscoverMediaKey.parse(keep.mediaRef)
            ?: if (keep.type == Keep.TYPE_DISCOVER) DiscoverMediaKey.parse(keep.key) else null
        return key?.takeUnless { it.isMovie }
    }

    @JvmStatic
    fun keepsByMedia(keeps: List<Keep>): Map<DiscoverMediaKey, Keep> {
        val result = linkedMapOf<DiscoverMediaKey, Keep>()
        keeps.forEach { keep -> mediaKey(keep)?.let { result.putIfAbsent(it, keep) } }
        return result
    }

    @JvmStatic
    fun seasonNumbers(metadata: LibraryMedia.Metadata): List<Int> = buildSet {
        metadata.seasons().forEach { if (it.number() >= 0) add(it.number()) }
        metadata.lastAired()?.let { if (it.season() >= 0) add(it.season()) }
        metadata.nextAiring()?.let { if (it.season() >= 0) add(it.season()) }
    }.sorted()

    @JvmStatic
    fun episodeDates(
        season: Int,
        episodes: List<LibraryMedia.Episode>
    ): Map<LocalDate, List<LibraryMedia.AiringEpisode>> {
        val dated = linkedMapOf<Pair<Int, Int>, LibraryMedia.AiringEpisode>()
        episodes.forEach { episode ->
            if (season < 0 || episode.season() != season || episode.number() <= 0) return@forEach
            val date = date(episode.airDate()) ?: return@forEach
            dated.putIfAbsent(season to episode.number(), LibraryMedia.AiringEpisode(
                season, episode.number(), episode.name(), date.toString(), episode.runtime()
            ))
        }
        return dated.values.groupBy { LocalDate.parse(it.airDate()) }
    }

    @JvmStatic
    fun entries(
        media: Vod,
        keep: Keep,
        metadata: LibraryMedia.Metadata,
        seasons: Map<Int, Map<LocalDate, List<LibraryMedia.AiringEpisode>>>,
        weekStart: LocalDate
    ): List<AiringCalendarEntry> {
        val coordinates = linkedMapOf<Pair<Int, Int>, LibraryMedia.AiringEpisode>()
        listOfNotNull(metadata.lastAired(), metadata.nextAiring()).forEach { episode ->
            if (episode.season() >= 0 && episode.number() > 0 && date(episode.airDate()) != null) {
                val detailed = seasons[episode.season()]?.values?.asSequence()?.flatten()
                    ?.firstOrNull { it.number() == episode.number() }
                coordinates[episode.season() to episode.number()] = detailed ?: episode
            }
        }
        // Per-season detail supersedes last/next summaries for the same coordinate.
        // Scan seven indexed dates, rather than every historical episode on each delivery.
        seasons.toSortedMap().values.forEach { days ->
            repeat(7) { offset ->
                days[weekStart.plusDays(offset.toLong())].orEmpty().forEach {
                    coordinates[it.season() to it.number()] = it
                }
            }
        }
        val weekEnd = weekStart.plusDays(6)
        return coordinates.values.groupBy { LocalDate.parse(it.airDate()) }
            .filterKeys { !it.isBefore(weekStart) && !it.isAfter(weekEnd) }
            .toSortedMap()
            .map { (day, episodes) -> AiringCalendarEntry(media, day,
                episodes.sortedWith(compareBy({ it.season() }, { it.number() })), keep) }
    }

    private fun date(value: String?): LocalDate? = try {
        value?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
    } catch (_: RuntimeException) { null }
}
