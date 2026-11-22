package com.fongmi.android.tv.calendar

enum class CalendarProvider(val id: String) {
    TMDB("tmdb"),
    TRAKT("trakt"),
    BANGUMI("bangumi");

    companion object {
        @JvmStatic fun fromId(id: String?): CalendarProvider = entries.firstOrNull { it.id == id } ?: TMDB
    }
}

/** A weekly listing is a current broadcast index, rather than an exact episode date. */
enum class CalendarScheduleKind { EXACT, WEEKLY }

data class AiringCalendarPage(
    val items: List<AiringCalendarEntry>,
    val page: Int = 1,
    val totalPages: Int = 1
)
