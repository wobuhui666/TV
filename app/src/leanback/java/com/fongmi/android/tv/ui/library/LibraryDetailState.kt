package com.fongmi.android.tv.ui.library

import androidx.compose.runtime.Immutable

/** Display data only; source and playback decisions stay in the existing activity. */
@Immutable
data class LibraryDetailItem @JvmOverloads constructor(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val overview: String = "",
    val image: String = "",
    val badge: String = "",
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val progress: Float = 0f,
    val airDate: String = ""
)

@Immutable
data class LibraryDetailChoice @JvmOverloads constructor(
    val key: String,
    val label: String,
    val selected: Boolean = false
)

@Immutable
data class LibraryDetailBadge(val label: String, val value: String)

@Immutable
data class LibraryDetailState(
    val identity: String = "",
    val title: String = "",
    val backdrop: String = "",
    val logo: String = "",
    val metadata: String = "",
    val overview: String = "",
    val seriesStatus: String = "",
    val ratings: List<LibraryDetailBadge> = emptyList(),
    val formats: List<String> = emptyList(),
    val playLabel: String = "",
    val playHint: String = "",
    val playEnabled: Boolean = true,
    val selecting: Boolean = false,
    val kept: Boolean = false,
    val movie: Boolean = false,
    val simple: Boolean = false,
    val seasonLabel: String = "",
    val episodeStatus: String = "",
    val episodesLoading: Boolean = false,
    val episodes: List<LibraryDetailItem> = emptyList(),
    val ranges: List<LibraryDetailChoice> = emptyList(),
    val resources: List<LibraryDetailItem> = emptyList(),
    val resourceStatus: String = "",
    val resourcesLoading: Boolean = false,
    val resourceFilters: List<LibraryDetailChoice> = emptyList(),
    val cast: List<LibraryDetailItem> = emptyList(),
    val genres: List<LibraryDetailChoice> = emptyList(),
    val trailers: List<LibraryDetailItem> = emptyList(),
    val stills: List<LibraryDetailItem> = emptyList(),
    val similar: List<LibraryDetailItem> = emptyList(),
    val mediaInformation: String = "",
    val productionLogos: List<String> = emptyList(),
    val showMoreAction: Boolean = true
) {
    /** Java fills a builder; submit() takes an immutable snapshot once per update. */
    class Builder {
        @JvmField var identity = ""
        @JvmField var title = ""
        @JvmField var backdrop = ""
        @JvmField var logo = ""
        @JvmField var metadata = ""
        @JvmField var overview = ""
        @JvmField var seriesStatus = ""
        @JvmField var ratings: List<LibraryDetailBadge> = emptyList()
        @JvmField var formats: List<String> = emptyList()
        @JvmField var playLabel = ""
        @JvmField var playHint = ""
        @JvmField var playEnabled = true
        @JvmField var selecting = false
        @JvmField var kept = false
        @JvmField var movie = false
        @JvmField var simple = false
        @JvmField var seasonLabel = ""
        @JvmField var episodeStatus = ""
        @JvmField var episodesLoading = false
        @JvmField var episodes: List<LibraryDetailItem> = emptyList()
        @JvmField var ranges: List<LibraryDetailChoice> = emptyList()
        @JvmField var resources: List<LibraryDetailItem> = emptyList()
        @JvmField var resourceStatus = ""
        @JvmField var resourcesLoading = false
        @JvmField var resourceFilters: List<LibraryDetailChoice> = emptyList()
        @JvmField var cast: List<LibraryDetailItem> = emptyList()
        @JvmField var genres: List<LibraryDetailChoice> = emptyList()
        @JvmField var trailers: List<LibraryDetailItem> = emptyList()
        @JvmField var stills: List<LibraryDetailItem> = emptyList()
        @JvmField var similar: List<LibraryDetailItem> = emptyList()
        @JvmField var mediaInformation = ""
        @JvmField var productionLogos: List<String> = emptyList()
        @JvmField var showMoreAction = true

        fun build() = LibraryDetailState(identity, title, backdrop, logo, metadata, overview,
            seriesStatus, ratings.toList(), formats.toList(), playLabel, playHint,
            playEnabled, selecting, kept, movie, simple, seasonLabel, episodeStatus,
            episodesLoading, episodes.toList(), ranges.toList(), resources.toList(),
            resourceStatus, resourcesLoading, resourceFilters.toList(), cast.toList(),
            genres.toList(), trailers.toList(), stills.toList(), similar.toList(),
            mediaInformation, productionLogos.toList(), showMoreAction)
    }
}
