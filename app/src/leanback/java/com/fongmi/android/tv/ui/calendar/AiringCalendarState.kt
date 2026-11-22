package com.fongmi.android.tv.ui.calendar

import androidx.compose.runtime.Immutable

@Immutable
data class AiringCalendarDay @JvmOverloads constructor(
    val date: String,
    val label: String,
    val today: Boolean = false,
    val hasUpdates: Boolean = false
)

/** One work on one date; subtitle contains all of that day's episode coordinates. */
@Immutable
data class AiringCalendarItem @JvmOverloads constructor(
    val key: String,
    val title: String,
    val poster: String = "",
    val subtitle: String = "",
    val episodeStill: String = "",
    val backdrop: String = ""
)

@Immutable
data class AiringCalendarPreview @JvmOverloads constructor(
    val title: String = "",
    val backdrop: String = "",
    val logo: String = "",
    val subtitle: String = "",
    val key: String = "",
    val overview: String = "",
    val metadata: List<String> = emptyList(),
    val airingStatus: String = ""
)

/** Display-only snapshots. The activity owns TMDB requests and follow actions. */
@Immutable
data class AiringCalendarState @JvmOverloads constructor(
    val mode: String = "public",
    val weekStart: String = "",
    val selectedDate: String = "",
    val weekLabel: String = "",
    val days: List<AiringCalendarDay> = emptyList(),
    val items: List<AiringCalendarItem> = emptyList(),
    val preview: AiringCalendarPreview = AiringCalendarPreview(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val hasKeeps: Boolean = false,
    val hasMore: Boolean = false,
    val emptyMessage: String = "",
    val todayDate: String = "",
    val provider: String = "tmdb",
    val connected: Boolean = false,
    val canFollow: Boolean = false,
    val followingTitle: Boolean = false,
    val accountBusy: Boolean = false
) {
    class Builder {
        @JvmField var mode = "public"
        @JvmField var weekStart = ""
        @JvmField var selectedDate = ""
        @JvmField var weekLabel = ""
        @JvmField var days: List<AiringCalendarDay> = emptyList()
        @JvmField var items: List<AiringCalendarItem> = emptyList()
        @JvmField var preview = AiringCalendarPreview()
        @JvmField var loading = false
        @JvmField var failed = false
        @JvmField var hasKeeps = false
        @JvmField var hasMore = false
        @JvmField var emptyMessage = ""
        @JvmField var todayDate = ""
        @JvmField var provider = "tmdb"
        @JvmField var connected = false
        @JvmField var canFollow = false
        @JvmField var followingTitle = false
        @JvmField var accountBusy = false

        fun build() = AiringCalendarState(mode, weekStart, selectedDate, weekLabel,
            days.toList(), items.toList(), preview, loading, failed, hasKeeps, hasMore, emptyMessage, todayDate,
            provider, connected, canFollow, followingTitle, accountBusy)
    }
}
