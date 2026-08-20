package com.fongmi.android.tv.source;

import java.util.Locale;
import java.util.Objects;

public final class MediaIdentity {

    public enum Kind {
        UNKNOWN,
        MOVIE,
        SERIES
    }

    private final String title;
    private final String year;
    private final Integer season;
    private final Kind kind;

    public MediaIdentity(String title, String year, Integer season, Kind kind) {
        this.title = title == null ? "" : title;
        this.year = year == null ? "" : year;
        this.season = season;
        this.kind = kind == null ? Kind.UNKNOWN : kind;
    }

    public String getTitle() {
        return title;
    }

    public String getYear() {
        return year;
    }

    public Integer getSeason() {
        return season;
    }

    public Kind getKind() {
        return kind;
    }

    public String key() {
        return title + "|" + year + "|" + (season == null ? "?" : season) + "|" + kind.name().toLowerCase(Locale.ROOT);
    }

    public boolean compatibleWith(MediaIdentity other) {
        if (other == null || !title.equals(other.title)) return false;
        if (kind != Kind.UNKNOWN && other.kind != Kind.UNKNOWN && kind != other.kind) return false;
        if (!year.isEmpty() || !other.year.isEmpty()) return year.equals(other.year);
        if (season != null && other.season != null && !season.equals(other.season)) return false;
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MediaIdentity other)) return false;
        return title.equals(other.title) && year.equals(other.year) && Objects.equals(season, other.season) && kind == other.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, year, season, kind);
    }

    @Override
    public String toString() {
        return key();
    }
}
