package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import java.util.List;

/** Match complete titles while allowing sources to omit year and media type. */
public final class LibrarySourceMatcher {
    public record Match(boolean matches, boolean automatic, int score) { }

    private LibrarySourceMatcher() { }

    public static Match match(Vod source, String title, List<String> aliases, String year, boolean movie, Integer season) {
        if (source == null || source.isFolder() || source.isAction()) return new Match(false, false, 0);
        MediaIdentity.Kind kind = MediaMatcher.kind(source.getTypeName());
        MediaIdentity.Kind expected = movie ? MediaIdentity.Kind.MOVIE : MediaIdentity.Kind.SERIES;
        if (kind != MediaIdentity.Kind.UNKNOWN && kind != expected) return new Match(false, false, 0);
        Integer sourceSeason = MediaMatcher.identity(source).getSeason();
        String expectedYear = movie || (season == null || season <= 1) && (sourceSeason == null || sourceSeason <= 1) ? year : "";
        SearchRelevance.Match titleMatch = SearchRelevance.evaluate(source,
                new SearchRelevance.Query(title, aliases, expectedYear, season));
        boolean matches = titleMatch.titleMatch() && (!movie || titleMatch.strict());
        boolean knownSeason = !movie && season != null && season == 1 && MediaMatcher.identity(source).getSeason() == null;
        boolean implicitFirst = knownSeason && SearchRelevance.evaluate(source,
                new SearchRelevance.Query(title, aliases, "", null)).strict();
        boolean automatic = matches && (kind == expected || hasPlayback(source)) && (titleMatch.strict() || implicitFirst);
        return new Match(matches, automatic, titleMatch.score());
    }

    public static Match match(Vod source, Vod work) {
        List<String> aliases = work.getOriginalName().isEmpty() ? List.of() : List.of(work.getOriginalName());
        return match(source, work.getName(), aliases, work.getYear(), "movie".equals(work.getTypeName()), null);
    }

    static PreparedSource prepareSource(Vod source) {
        if (source == null || source.isFolder() || source.isAction()) return new PreparedSource(false, MediaIdentity.Kind.UNKNOWN, false, null, null);
        // These two season parsers intentionally remain separate: title safety uses SearchRelevance.
        return new PreparedSource(true, MediaMatcher.kind(source.getTypeName()), hasPlayback(source), MediaMatcher.extractSeason(source.getName()),
                SearchRelevance.prepareResult(source.getName(), source.getYear()));
    }

    static boolean hasPlayback(Vod source) {
        if (source == null) return false;
        for (Flag flag : source.getFlags()) {
            for (Episode episode : flag.getEpisodes()) {
                if (!episode.getUrl().isBlank()) return true;
            }
        }
        return false;
    }

    static PreparedWork prepareWork(Vod work) {
        List<String> aliases = work.getOriginalName().isEmpty() ? List.of() : List.of(work.getOriginalName());
        return prepareWork(work.getName(), aliases, work.getYear(), "movie".equals(work.getTypeName()), null);
    }

    static PreparedWork prepareWork(String title, List<String> aliases, String year, boolean movie, Integer season) {
        SearchRelevance.PreparedQuery withYear = SearchRelevance.prepareQuery(new SearchRelevance.Query(title, aliases, year, season));
        SearchRelevance.PreparedQuery withoutYear = movie ? withYear
                : SearchRelevance.prepareQuery(new SearchRelevance.Query(title, aliases, "", season));
        SearchRelevance.PreparedQuery implicitFirst = !movie && season != null && season == 1
                ? SearchRelevance.prepareQuery(new SearchRelevance.Query(title, aliases, "", null)) : null;
        return new PreparedWork(movie, season, withYear, withoutYear, implicitFirst);
    }

    static Match match(PreparedSource source, PreparedWork work) {
        if (!source.accepted) return new Match(false, false, 0);
        MediaIdentity.Kind expected = work.movie ? MediaIdentity.Kind.MOVIE : MediaIdentity.Kind.SERIES;
        if (source.kind != MediaIdentity.Kind.UNKNOWN && source.kind != expected) return new Match(false, false, 0);
        boolean useYear = work.movie || (work.season == null || work.season <= 1) && (source.mediaSeason == null || source.mediaSeason <= 1);
        SearchRelevance.Match titleMatch = SearchRelevance.evaluatePrepared(source.title, useYear ? work.withYear : work.withoutYear);
        boolean matches = titleMatch.titleMatch() && (!work.movie || titleMatch.strict());
        boolean knownSeason = !work.movie && work.season != null && work.season == 1 && source.mediaSeason == null;
        boolean implicitFirst = knownSeason && SearchRelevance.evaluatePrepared(source.title, work.implicitFirst).strict();
        boolean automatic = matches && (source.kind == expected || source.hasPlayback) && (titleMatch.strict() || implicitFirst);
        return new Match(matches, automatic, titleMatch.score());
    }

    static final class PreparedSource {
        private final boolean accepted;
        private final MediaIdentity.Kind kind;
        private final boolean hasPlayback;
        private final Integer mediaSeason;
        private final SearchRelevance.PreparedResult title;
        private PreparedSource(boolean accepted, MediaIdentity.Kind kind, boolean hasPlayback, Integer mediaSeason, SearchRelevance.PreparedResult title) {
            this.accepted = accepted; this.kind = kind; this.hasPlayback = hasPlayback; this.mediaSeason = mediaSeason; this.title = title;
        }
    }

    static final class PreparedWork {
        private final boolean movie;
        private final Integer season;
        private final SearchRelevance.PreparedQuery withYear, withoutYear, implicitFirst;
        private PreparedWork(boolean movie, Integer season, SearchRelevance.PreparedQuery withYear,
                             SearchRelevance.PreparedQuery withoutYear, SearchRelevance.PreparedQuery implicitFirst) {
            this.movie = movie; this.season = season; this.withYear = withYear;
            this.withoutYear = withoutYear; this.implicitFirst = implicitFirst;
        }
    }
}
